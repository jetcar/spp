package com.spp.spotify.ui

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

private const val WEBVIEW_DEBUG_TAG = "SpotifyWV"
private val INTERNAL_WEBVIEW_HOSTS = setOf("spotify.com", "scdn.co")

@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.configureSpotifyWebSettings() {
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureSpotifyWebSettings, true)
        flush()
    }

    settings.apply {
        //noinspection SetJavaScriptEnabled
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        javaScriptCanOpenWindowsAutomatically = true
        userAgentString = buildSpotifyDesktopUserAgent(userAgentString)
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
                configureSpotifyWebSettings()
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
            val url = request?.url?.toString().orEmpty()
            if (url.isNotBlank()) {
                Log.d(WEBVIEW_DEBUG_TAG, "request ${request?.method ?: "GET"} $url")
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
 * Attempts to skip the current Spotify ad.
 *
 * Strategy (in order):
 * 1. Click `[data-testid="skip-ad-button"]` if it exists (skippable ad).
 * 2. If an ad label is detected but there's no skip button, fast-forward all
 *    audio/video elements to their end — this ends un-skippable audio ads.
 *
 * [callback] receives one of: "skipped-via-button", "fast-forwarded", "no-ad".
 */
internal fun WebView.skipAdIfPresent(callback: ((String) -> Unit)? = null) {
    evaluateJavascript(
        """
        (function(){
          var skipBtn = document.querySelector('[data-testid="skip-ad-button"]');
          if (skipBtn) { skipBtn.click(); return 'skipped-via-button'; }
          var adLabel = document.querySelector(
            '[data-testid="ad-label"],[data-testid="advertisement"],[aria-label="Advertisement"]'
          );
          if (!adLabel) {
            var nowPlaying = document.querySelector('[data-testid="context-item-info-subtitles"]');
            if (!nowPlaying || nowPlaying.textContent.toLowerCase().indexOf('advertisement') < 0) {
              return 'no-ad';
            }
          }
          var skipped = false;
          document.querySelectorAll('audio,video').forEach(function(m) {
            if (m.duration && isFinite(m.duration) && !m.paused) {
              try { m.currentTime = m.duration; skipped = true; } catch(_) {}
            }
          });
          return skipped ? 'fast-forwarded' : 'no-ad';
        })();
        """.trimIndent(),
    ) { result ->
        Log.d(WEBVIEW_DEBUG_TAG, "ad-skip result=$result")
        callback?.invoke(result?.trim('"') ?: "no-ad")
    }
}

// ---------------------------------------------------------------------------
// JavaScript bridge helpers — called from the native playback overlay
// ---------------------------------------------------------------------------

/** Clicks a Spotify web-player control identified by its CSS [selector]. */
internal fun WebView.clickSpotifyButton(selector: String) {
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

private fun buildSpotifyDesktopUserAgent(defaultUserAgent: String): String {
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
