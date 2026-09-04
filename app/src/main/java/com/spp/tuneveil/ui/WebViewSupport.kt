package com.spp.tuneveil.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

private const val WEBVIEW_DEBUG_TAG = "TuneveilWV"
private val INTERNAL_WEBVIEW_HOSTS = setOf("spotify.com", "scdn.co")

@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.configureTuneveilWebSettings() {
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureTuneveilWebSettings, true)
        flush()
    }

    settings.apply {
        //noinspection SetJavaScriptEnabled
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        javaScriptCanOpenWindowsAutomatically = true
        userAgentString = buildTuneveilDesktopUserAgent(userAgentString)
        useWideViewPort = true
        loadWithOverviewMode = true
        loadsImagesAutomatically = true
        cacheMode = WebSettings.LOAD_DEFAULT
        allowContentAccess = true
        setSupportMultipleWindows(true)
    }

    isFocusable = true
    isFocusableInTouchMode = true
    isVerticalScrollBarEnabled = true
    isHorizontalScrollBarEnabled = false

    // Inject the ad-blocker script at document-start so our fetch/WebSocket
    // hooks are in place before Spotify's own JavaScript runs.
    // This mirrors what the "Blockify" Chrome extension does via chrome.scripting.
    installAdBlockerAtDocumentStart()
}

/**
 * Installs the ad-blocker JavaScript at document-start using
 * [WebViewCompat.addDocumentStartJavaScript] (requires WebView 69+, API 24+).
 * Scoped to open.spotify.com only so it has no effect on any other page.
 * Falls back silently on older WebView versions.
 */
@SuppressLint("RequiresFeature") // guarded by isFeatureSupported check inside
private fun WebView.installAdBlockerAtDocumentStart() {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        try {
            val script = context.resources
                .openRawResource(com.spp.tuneveil.R.raw.tuneveil_ad_blocker)
                .bufferedReader()
                .readText()
            WebViewCompat.addDocumentStartJavaScript(
                this,
                script,
                setOf("https://open.spotify.com"),
            )
            Log.i(WEBVIEW_DEBUG_TAG, "ad-blocker document-start script installed")
        } catch (e: Exception) {
            Log.e(WEBVIEW_DEBUG_TAG, "failed to install ad-blocker script: $e")
        }
    } else {
        Log.w(WEBVIEW_DEBUG_TAG, "addDocumentStartJavaScript not supported on this WebView version")
    }
}

internal fun createLoggingWebChromeClient(): WebChromeClient {
    return object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) {
                super.onPermissionRequest(null)
                return
            }

            val requestedResources = request.resources?.toList().orEmpty()
            Log.i(WEBVIEW_DEBUG_TAG, "permission request origin=${request.origin} resources=$requestedResources")

            val grantableResources = buildList {
                if (requestedResources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                    add(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
                }
            }

            if (grantableResources.isNotEmpty()) {
                request.grant(grantableResources.toTypedArray())
                return
            }

            request.deny()
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            val parentWebView = view ?: return false
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            var popupWebView: WebView? = null

            fun handOffNavigation(targetUrl: String?) {
                val url = targetUrl?.takeIf { it.isNotBlank() } ?: return
                val uri = url.toUri()

                Log.i(WEBVIEW_DEBUG_TAG, "popup navigation: $uri")

                if (shouldOpenExternally(uri)) {
                    openUrlOutsideWebView(parentWebView.context, url)
                } else {
                    parentWebView.post {
                        parentWebView.loadUrl(url)
                    }
                }

                popupWebView?.post {
                    popupWebView?.stopLoading()
                    popupWebView?.destroy()
                    popupWebView = null
                }
            }

            popupWebView = WebView(parentWebView.context).apply {
                configureTuneveilWebSettings()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        handOffNavigation(request?.url?.toString())
                        return true
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        handOffNavigation(url)
                    }
                }
            }

            transport.webView = popupWebView
            resultMsg.sendToTarget()
            return true
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            val msg = consoleMessage ?: return false
            Log.d(
                WEBVIEW_DEBUG_TAG,
                "console [${msg.messageLevel()}] ${msg.sourceId()}:${msg.lineNumber()} ${msg.message()}",
            )
            return super.onConsoleMessage(msg)
        }
    }
}

internal fun createLoggingWebViewClient(): WebViewClient {
    return object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url
            if (shouldOpenExternally(uri)) {
                Log.i(WEBVIEW_DEBUG_TAG, "opening outside WebView: $uri")
                view?.context?.let { context ->
                    openUrlOutsideWebView(context, uri.toString())
                }
                return true
            }

            return super.shouldOverrideUrlLoading(view, request)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            Log.d(WEBVIEW_DEBUG_TAG, "page started: $url")
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            Log.d(WEBVIEW_DEBUG_TAG, "page finished: $url")
            view?.let {
                it.requestFocus()
                installMediaActivationScript(it)
            }
            super.onPageFinished(view, url)
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse? {
            val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
            // Block known Spotify ad-audio and ad-tracking endpoints at network level.
            // This is a second-layer defence; the primary layer is the JS state-machine hook.
            if (isAdNetworkUrl(url)) {
                Log.i(WEBVIEW_DEBUG_TAG, "ad-network request blocked: $url")
                return emptyResponse()
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            Log.e(
                WEBVIEW_DEBUG_TAG,
                "load error url=${request?.url} code=${error?.errorCode} description=${error?.description}",
            )
            super.onReceivedError(view, request, error)
        }
    }
}

private fun installMediaActivationScript(webView: WebView) {
    webView.evaluateJavascript(
        """
        (function() {
          if (window.__spotifyAndroidMediaPatchInstalled) {
            return 'already-installed';
          }

          window.__spotifyAndroidMediaPatchInstalled = true;

          // Run once on first user gesture to unblock autoplay policy.
          // We intentionally do NOT hook MutationObserver here because Spotify's
          // React renderer causes hundreds of mutations per second; calling play()
          // that frequently breaks the audio pipeline on some devices.
          function activateMedia() {
            const elements = Array.from(document.querySelectorAll('audio,video'));
            elements.forEach((element) => {
              try {
                element.muted = false;
                if (element.volume === 0) element.volume = 1;
                const p = element.play && element.play();
                if (p && typeof p.catch === 'function') p.catch(() => {});
              } catch (_) {}
            });
            // Remove listeners after the first successful activation
            ['click', 'touchend', 'keydown'].forEach((n) => {
              document.removeEventListener(n, activateMedia, true);
            });
          }

          ['click', 'touchend', 'keydown'].forEach((eventName) => {
            document.addEventListener(eventName, activateMedia, true);
          });

          return 'installed';
        })();
        """.trimIndent(),
    ) { result ->
        Log.d(WEBVIEW_DEBUG_TAG, "media activation script result=$result")
    }
}

/** Pauses all HTML media elements — used when the app loses audio focus. */
internal fun WebView.pauseAllMedia() {
    evaluateJavascript(
        """(function(){document.querySelectorAll('audio,video').forEach(function(e){try{e.pause();}catch(_){}});})();""",
        null,
    )
}


// ---------------------------------------------------------------------------
// Ad-skip bridge helper
// ---------------------------------------------------------------------------

/**
 * Dumps the current now-playing bar's data-testid elements and the first
 * audio element's state to Logcat under the [WEBVIEW_DEBUG_TAG] tag.
 * Call this once when an ad is suspected to discover which selectors are live.
 */
internal fun WebView.dumpNowPlayingState() {
    evaluateJavascript(
        """
        (function(){
          var info = {};
          // Collect every data-testid value visible in the now-playing bar
          var bar = document.querySelector('[data-testid="now-playing-bar"]') ||
                    document.querySelector('[data-testid="now-playing-widget"]') ||
                    document.querySelector('footer') || document.body;
          var testIds = Array.from(bar.querySelectorAll('[data-testid]'))
            .map(function(el){ return el.dataset.testid + '=' + el.textContent.trim().substring(0,40); });
          info.testIds = testIds.slice(0,30);
          // All visible leaf text nodes in bar (to see "Advertisement" / "Spotify" labels)
          info.barLeafTexts = Array.from(bar.querySelectorAll('a,span,div'))
            .filter(function(el){ return el.children.length===0 && el.offsetParent!==null && el.textContent.trim().length>0; })
            .map(function(el){ return el.textContent.trim().substring(0,50); })
            .slice(0,20);
          // All audio elements state
          var audioEls = Array.from(document.querySelectorAll('audio'));
          info.audioElements = audioEls.map(function(a){
            return {
              src: (a.currentSrc||a.src||'').substring(0,100),
              paused: a.paused, duration: a.duration, currentTime: a.currentTime,
              muted: a.muted, volume: a.volume, readyState: a.readyState,
              networkState: a.networkState
            };
          });
          return JSON.stringify(info);
        })();
        """.trimIndent(),
    ) { result ->
        Log.i(WEBVIEW_DEBUG_TAG, "now-playing-state: $result")
    }
}

/**
 * Attempts to skip the current Spotify ad using a layered strategy:
 *
 * 1. Click a visible skip/close button (skippable video / display ads).
 * 2. Detect an audio ad via title text, explicit ad-testid elements, or audio
 *    source URL patterns, then fast-forward the playing audio element to its end.
 * 3. If seeking is blocked (DRM), mute all audio elements as a fallback and
 *    record the muted state in `window.__spotifyAdMuted`.
 * 4. On subsequent calls with no ad detected, restore audio if it was muted.
 *
 * [callback] receives a status string logged under tag `TuneveilWV`.
 */
internal fun WebView.skipAdIfPresent(callback: ((String) -> Unit)? = null) {
    evaluateJavascript(
        """
        (function(){
          // ── 1. Skip/close button (testid + aria-label + text-content scan) ─
          var skipBtn = document.querySelector(
            '[data-testid="skip-ad-button"],' +
            '[data-testid="ad-skip-button"],' +
            '[data-testid="skip-button"],' +
            '[class*="skip-ad"],' +
            '[aria-label*="skip" i]'
          );
          // Broaden: scan visible buttons/spans for "Skip" text
          if (!skipBtn || skipBtn.offsetParent === null) {
            var allBtns = document.querySelectorAll('button,span[role="button"]');
            for (var b = 0; b < allBtns.length; b++) {
              var bt = allBtns[b];
              if (bt.offsetParent !== null &&
                  /^skip/i.test((bt.textContent || '').trim())) {
                skipBtn = bt; break;
              }
            }
          }
          if (skipBtn && skipBtn.offsetParent !== null) {
            skipBtn.click();
            window.__spotifyAdMuted = false;
            window.__spotifyAdCount = 0;
            return 'skipped-via-button';
          }

          // ── 2. Ad detection via multiple DOM signals ──────────────────────
          var detected = null;

          // 2a. Explicit ad-related testid elements
          var explicitAd = document.querySelector(
            '[data-testid="ad-label"],' +
            '[data-testid="advertisement"],' +
            '[data-testid="ad-indicator"],' +
            '[data-testid="ads-label"],' +
            '[data-testid="ad-countdown"],' +
            '[data-testid="advertisement-banner"]'
          );
          if (explicitAd) detected = 'testid:' + (explicitAd.dataset.testid || 'found');

          // 2b. Now-playing bar shows "Advertisement" as track name and "Spotify" as artist.
          // Scan ALL anchor/span elements inside the now-playing footer for those strings.
          if (!detected) {
            var bar = document.querySelector('[data-testid="now-playing-bar"]') ||
                      document.querySelector('[data-testid="now-playing-widget"]') ||
                      document.querySelector('footer') || document.body;
            var barTexts = Array.from(bar.querySelectorAll('a,span,div'))
              .filter(function(el){ return el.children.length === 0 && el.offsetParent !== null; })
              .map(function(el){ return el.textContent.trim().toLowerCase(); });
            var hasAdvert = barTexts.some(function(t){ return t === 'advertisement'; });
            var hasSpotify = barTexts.some(function(t){ return t === 'spotify'; });
            if (hasAdvert) detected = 'bar-text:advertisement' + (hasSpotify ? '+spotify' : '');
          }

          // 2c. Any visible testid title element with ad text
          if (!detected) {
            var titleEl = document.querySelector(
              '[data-testid="context-item-info-title"] a,' +
              '[data-testid="context-item-info-title"],' +
              '[data-testid="now-playing-bar-title"],' +
              '[data-testid="track-info-name"]'
            );
            if (titleEl) {
              var t = titleEl.textContent.trim().toLowerCase();
              if (t === 'advertisement' || t === 'ad') detected = 'title:' + t;
            }
          }

          // 2d. Any visible leaf element whose text is literally "Advertisement"
          if (!detected) {
            var all = document.querySelectorAll('span,div,p');
            for (var i = 0; i < all.length; i++) {
              var el = all[i];
              if (el.children.length === 0 &&
                  el.textContent.trim().toLowerCase() === 'advertisement' &&
                  el.offsetParent !== null) {
                detected = 'text-node:advertisement';
                break;
              }
            }
          }

          // 2e. Audio source URL contains known ad-network patterns
          if (!detected) {
            var audios = document.querySelectorAll('audio');
            for (var j = 0; j < audios.length; j++) {
              var src = audios[j].currentSrc || audios[j].src || '';
              if (src && (/adswizz|audio-ads|ad-audio|adeventtracker|tritondigital/.test(src))) {
                detected = 'audio-src';
                break;
              }
            }
          }

          // ── No ad signal — restore mute if needed and exit ────────────────
          if (!detected) {
            window.__spotifyAdCount = 0;
            if (window.__spotifyAdMuted) {
              document.querySelectorAll('audio,video').forEach(function(m) {
                try { m.muted = false; if (m.volume < 0.05) m.volume = 1; } catch(_) {}
              });
              window.__spotifyAdMuted = false;
              return 'unmuted-after-ad';
            }
            return 'no-ad';
          }

          // Increment consecutive-detection counter
          window.__spotifyAdCount = (window.__spotifyAdCount || 0) + 1;

          // ── 3. Fast-forward ALL media (paused or not) ─────────────────────
          var seeked = false;
          document.querySelectorAll('audio,video').forEach(function(m) {
            if (m.duration && isFinite(m.duration) && m.duration > 0) {
              try {
                m.currentTime = Math.max(0, m.duration - 0.1);
                // Resume if it was paused so the player advances to next track
                if (m.paused) { try { m.play(); } catch(_) {} }
                seeked = true;
              } catch (_) {}
            }
          });
          if (seeked) {
            window.__spotifyAdMuted = false;
            window.__spotifyAdCount = 0;
            return 'fast-forwarded:' + detected;
          }

          // ── 4. Mute fallback — mute ALL media (paused or playing) ─────────
          // Previous version only muted !paused elements; ads are often paused
          // momentarily while buffering, causing the "detected-no-action" loop.
          var mutedCount = 0;
          document.querySelectorAll('audio,video').forEach(function(m) {
            try { m.muted = true; m.volume = 0; mutedCount++; } catch(_) {}
          });
          var didMute = mutedCount > 0;
          window.__spotifyAdMuted = didMute;
          return didMute
            ? ('muted:' + detected + ':count=' + window.__spotifyAdCount)
            : ('detected-no-media:' + detected + ':count=' + window.__spotifyAdCount);
        })();
        """.trimIndent(),
    ) { result ->
        val status = result?.trim('"') ?: "no-ad"
        if (status != "no-ad") {
            Log.i(WEBVIEW_DEBUG_TAG, "ad-skip: $status")
        }
        callback?.invoke(status)
    }
}

// ---------------------------------------------------------------------------
// JavaScript bridge helpers — called from the native playback overlay
// ---------------------------------------------------------------------------

/** Clicks a Tuneveil web-player control identified by its CSS [selector]. */
internal fun WebView.clickPlayerButton(selector: String) {
    evaluateJavascript(
        """(function(){var b=document.querySelector('$selector');if(b){b.click();}})();""",
        null,
    )
}

/**
 * Queries whether the Spotify player is currently playing by reading the
 * aria-label of the play/pause button, then invokes [callback] on the
 * main thread with the result.
 */
internal fun WebView.queryIsPlaying(callback: (Boolean) -> Unit) {
    evaluateJavascript(
        """
        (function(){
          var b=document.querySelector('[data-testid=control-button-playpause]');
          if(!b){return 'unknown';}
          var a=(b.getAttribute('aria-label')||'').toLowerCase();
          return a.indexOf('pause')>=0?'playing':'paused';
        })();
        """.trimIndent(),
    ) { result -> callback(result?.contains("playing") == true) }
}

/**
 * Queries whether the currently playing track is saved to the user's
 * Liked Songs by inspecting the add-button's aria state.
 *
 * Spotify's add-button uses:
 *   - `aria-checked="true"`  when the track is already liked
 *   - `aria-label` containing "Remove" when liked, "Save" when not
 *
 * [callback] is called with `true` if liked, `false` if not liked,
 * `null` if the button is not present (no track playing yet).
 */
internal fun WebView.queryIsLiked(callback: (Boolean?) -> Unit) {
    evaluateJavascript(
        """
        (function(){
          var btn = document.querySelector('[data-testid="add-button"]');
          if (!btn) return 'absent';
          var checked = btn.getAttribute('aria-checked');
          if (checked === 'true')  return 'liked';
          if (checked === 'false') return 'not-liked';
          // Fall back to aria-label text
          var label = (btn.getAttribute('aria-label') || '').toLowerCase();
          if (label.indexOf('remove') >= 0) return 'liked';
          if (label.indexOf('save')   >= 0) return 'not-liked';
          return 'unknown';
        })();
        """.trimIndent(),
    ) { result ->
        val r = result?.trim('"') ?: "absent"
        callback(
            when (r) {
                "liked"     -> true
                "not-liked" -> false
                else        -> null   // absent or unknown — don't change current state
            }
        )
    }
}

/**
 * Queries title, artist, current playback position (ms) and track duration (ms)
 * from the Tuneveil web player's now-playing bar and the page's <audio> element.
 * Position and duration are -1 when unavailable.
 */
internal fun WebView.queryTrackInfo(
    callback: (title: String, artist: String, positionMs: Long, durationMs: Long) -> Unit,
) {
    evaluateJavascript(
        """
        (function(){
          try {
            var t = document.querySelector('[data-testid="context-item-info-title"]');
            var a = document.querySelector('[data-testid="context-item-info-subtitles"]');
            var audio = document.querySelector('audio');
            var pos = (audio && isFinite(audio.currentTime) && audio.currentTime >= 0)
                      ? Math.round(audio.currentTime * 1000) : -1;
            var dur = (audio && isFinite(audio.duration) && audio.duration > 0)
                      ? Math.round(audio.duration * 1000) : -1;
            return [(t ? t.textContent.trim() : ''), (a ? a.textContent.trim() : ''), pos, dur];
          } catch(_) { return ['', '', -1, -1]; }
        })();
        """.trimIndent(),
    ) { result ->
        try {
            val arr = org.json.JSONArray(result ?: "[]")
            callback(arr.optString(0), arr.optString(1), arr.optLong(2, -1L), arr.optLong(3, -1L))
        } catch (_: Exception) {
            callback("", "", -1L, -1L)
        }
    }
}

// Known Spotify / ad-network URL patterns to block at the network layer.
// These URLs are only ever used to serve ad audio or ad tracking pixels.
private val AD_NETWORK_URL_PATTERNS = listOf(
    "adstudio-assets.scdn.co/mp3/",
    "adstudio-assets.scdn.co/mp3-ad/",
    "2mdn.net",
    "amillionads.com",
    "audio-ads.spotify.com",
    "adswizz.com",
    "adeventtracker.spotify.com",
    "tritondigital.com",
    "audio-fa.scdn.co/ads/",          // Spotify ad audio CDN path
    "pagead2.googlesyndication.com",
    "doubleclick.net",
    "omnivore.spotify.com/1/e/",       // Spotify ad impression tracking
)

private fun isAdNetworkUrl(url: String): Boolean =
    AD_NETWORK_URL_PATTERNS.any { url.contains(it, ignoreCase = true) }

/** Returns a 200 OK response with an empty body — used to silently block requests. */
private fun emptyResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", 200, "OK", emptyMap(), "".byteInputStream())

private fun buildTuneveilDesktopUserAgent(defaultUserAgent: String): String {
    val chromeVersion =
        Regex("""Chrome/[\d.]+""").find(defaultUserAgent)?.value ?: "Chrome/126.0.0.0"

    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chromeVersion Safari/537.36"
}

private fun shouldOpenExternally(uri: Uri?): Boolean {
    val scheme = uri?.scheme?.lowercase().orEmpty()
    if (scheme.isBlank()) {
        return false
    }

    if (scheme != "http" && scheme != "https") {
        return true
    }

    val host = uri?.host?.lowercase().orEmpty()
    if (host.isBlank()) {
        return false
    }

    return INTERNAL_WEBVIEW_HOSTS.none { host == it || host.endsWith(".$it") }
}

private fun openUrlOutsideWebView(context: android.content.Context, url: String) {
    val uri = url.toUri()
    val customTabsIntent = androidx.browser.customtabs.CustomTabsIntent.Builder().build()

    try {
        customTabsIntent.launchUrl(context, uri)
    } catch (_: Throwable) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        context.startActivity(intent)
    }
}
