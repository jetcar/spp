package com.spp.tuneveil.ui

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.view.WindowManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt
import com.spp.tuneveil.media.MediaPlaybackService
import com.spp.tuneveil.CrashRecoveryHandler

private const val WEB_PLAYER_URL = "https://open.spotify.com/"

// Tuneveil web-player data-testid selectors (unquoted attribute values work
// for simple identifiers in all modern WebView / Chromium builds)
private const val SEL_PLAY_PAUSE   = "[data-testid=control-button-playpause]"
private const val SEL_SKIP_BACK    = "[data-testid=control-button-skip-back]"
private const val SEL_SKIP_FORWARD = "[data-testid=control-button-skip-forward]"
private const val SEL_ADD_TO_LIKED = "[data-testid=add-button]"

@Composable
fun WebPlayerScreen() {
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val isPlaying  = remember { mutableStateOf(false) }
    val isLiked    = remember { mutableStateOf<Boolean?>(null) }
    val serviceRef = remember { mutableStateOf<MediaPlaybackService?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val view = LocalView.current

    // Keep the screen on while this screen is shown
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Crash-recovery: detect if we're restarting after a crash and whether playback was active.
    val isCrashRecovery   = remember { CrashRecoveryHandler.isCrashRecovery(context) }
    val wasPlayingOnCrash = remember { CrashRecoveryHandler.wasPlayingBeforeCrash(context) }
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val maxMusicVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    val musicVolumeFraction = remember { mutableStateOf(0f) }
    val uptimeSeconds = remember { mutableStateOf(0L) }

    // Tick uptime counter every second
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1_000)
            uptimeSeconds.value++
        }
    }

    // Crash-recovery auto-play: if the app crashed while playing, resume after Spotify loads.
    LaunchedEffect(isCrashRecovery) {
        if (isCrashRecovery) {
            // Wait for WebView to be created
            while (webViewRef.value == null) delay(300)
            // Give Spotify ~5 s to fully load and be interactive
            delay(5_000)
            if (wasPlayingOnCrash) {
                webViewRef.value?.clickPlayerButton(SEL_PLAY_PAUSE)
                android.util.Log.i("CrashRecovery", "Auto-play triggered after crash restart")
            }
        }
        // Always clear the flag so the next normal launch is unaffected.
        CrashRecoveryHandler.clearRecoveryFlag(context)
    }

    fun formatUptime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }

    fun syncVolumeFromSystem() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        musicVolumeFraction.value = current.toFloat() / maxMusicVolume.toFloat()
    }

    BackHandler(enabled = webViewRef.value?.canGoBack() == true) {
        webViewRef.value?.goBack()
    }

    LaunchedEffect(audioManager, maxMusicVolume) {
        syncVolumeFromSystem()
        while (isActive) {
            delay(500)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val fraction = current.toFloat() / maxMusicVolume.toFloat()
            if (abs(fraction - musicVolumeFraction.value) >= 0.01f) {
                musicVolumeFraction.value = fraction
            }
        }
    }

    // ── Audio focus ──────────────────────────────────────────────────────────
    // Managed here (not in MainActivity) so we have direct access to webViewRef
    // and can actually pause/resume audio when focus changes.
    DisposableEffect(Unit) {
        val focusAudioManager = context.getSystemService(AudioManager::class.java)

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        // Another app (or a second instance) took audio focus — pause media
                        webViewRef.value?.pauseAllMedia()
                    }
                    // AUDIOFOCUS_GAIN: audio focus returned; let the user resume manually
                    // to avoid unexpected auto-play after e.g. a phone call.
                }
            }
            .build()

        focusAudioManager.requestAudioFocus(focusRequest)

        onDispose {
            focusAudioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    // Keep the play/pause icon and liked state in sync with the actual player state; also
    // push current track metadata to the media notification.
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(2_000)
            val wv = webViewRef.value ?: continue
            wv.queryIsPlaying { playing ->
                isPlaying.value = playing
                // Persist playing state so crash handler can auto-resume on restart.
                CrashRecoveryHandler.savePlaybackState(context, playing)
                wv.queryTrackInfo { title, artist, positionMs, durationMs ->
                    serviceRef.value?.update(playing, title, artist, positionMs, durationMs)
                }
            }
            wv.queryIsLiked { liked ->
                if (liked != null) isLiked.value = liked
            }
        }
    }

    // ── MediaPlaybackService binding ─────────────────────────────────────────
    // Starts the foreground service and wires media-button callbacks so that
    // lock-screen controls, Bluetooth buttons, and the notification actions
    // all drive the WebView JS bridge.
    DisposableEffect(context) {
        val mainHandler = Handler(Looper.getMainLooper())
        var bound = false

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                (binder as? MediaPlaybackService.LocalBinder)?.service?.also { svc ->
                    serviceRef.value = svc
                    // WebView.evaluateJavascript() MUST be called on the main thread.
                    // MediaSessionCompat.Callback fires on a background handler thread, so
                    // we must always dispatch back to main before touching the WebView.
                    svc.onPlayPause    = { mainHandler.post { webViewRef.value?.clickPlayerButton(SEL_PLAY_PAUSE) } }
                    svc.onSkipNext     = { mainHandler.post { webViewRef.value?.clickPlayerButton(SEL_SKIP_FORWARD) } }
                    svc.onSkipPrevious = { mainHandler.post { webViewRef.value?.clickPlayerButton(SEL_SKIP_BACK) } }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                serviceRef.value = null
            }
        }
        val intent = Intent(context, MediaPlaybackService::class.java)
        context.startForegroundService(intent)
        bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)

        onDispose {
            serviceRef.value?.apply {
                onPlayPause    = null
                onSkipNext     = null
                onSkipPrevious = null
            }
            // Only call unbindService if bindService() actually succeeded; calling it
            // when not bound throws IllegalArgumentException: Service not registered.
            if (bound) {
                try {
                    context.unbindService(conn)
                } catch (e: IllegalArgumentException) {
                    android.util.Log.w("TuneveilWV", "unbindService failed (service was not registered): ${e.message}")
                }
            }
        }
    }

    // Periodically check for and skip Spotify ads.
    // Dump DOM state on first detection so selectors can be verified via Logcat (tag TuneveilWV).
    // Also re-dump whenever a new "detected-no-media" case appears (audio element missing).
    LaunchedEffect(Unit) {
        var dumped = false
        while (isActive) {
            delay(1_500)
            webViewRef.value?.skipAdIfPresent { status ->
                val isAdEvent = status != "no-ad" && status != "unmuted-after-ad"
                if (isAdEvent) {
                    android.util.Log.i("TuneveilWV", "ad-skip-poll: $status")
                    if (!dumped) {
                        dumped = true
                        webViewRef.value?.dumpNowPlayingState()
                    }
                }
                // Re-dump on first no-media case so we can see the DOM when audio is absent
                if (status.startsWith("detected-no-media") && dumped) {
                    webViewRef.value?.dumpNowPlayingState()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            factory = { context ->
                WebView(context).apply {
                    webViewRef.value = this
                    configureTuneveilWebSettings()
                    webViewClient = createLoggingWebViewClient()
                    webChromeClient = createLoggingWebChromeClient()
                    onResume()
                    resumeTimers()
                    loadUrl(WEB_PLAYER_URL)
                }
            },
            update = { webView -> webViewRef.value = webView },
        )

        // Top-right corner: refresh button + uptime badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 12.dp, top = 2.dp),
        ) {
            IconButton(
                onClick = { webViewRef.value?.loadUrl(WEB_PLAYER_URL) },
                modifier = Modifier
                    .background(Color(0x66000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Reload Tuneveil",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = formatUptime(uptimeSeconds.value),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(Color(0x66000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        // Transparent playback controls pinned to the bottom
        PlaybackOverlay(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            isPlaying = isPlaying.value,
            isLiked = isLiked.value,
            volumeFraction = musicVolumeFraction.value,
            onVolumeDown = {
                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val next = (current - 1).coerceAtLeast(0)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                musicVolumeFraction.value = next.toFloat() / maxMusicVolume.toFloat()
            },
            onVolumeUp = {
                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val next = (current + 1).coerceAtMost(maxMusicVolume)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                musicVolumeFraction.value = next.toFloat() / maxMusicVolume.toFloat()
            },
            onVolumeChange = { fraction ->
                val clamped = fraction.coerceIn(0f, 1f)
                musicVolumeFraction.value = clamped
                val streamVolume = (clamped * maxMusicVolume.toFloat()).roundToInt()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, streamVolume, 0)
            },
            onPrevious = {
                webViewRef.value?.clickPlayerButton(SEL_SKIP_BACK)
            },
            onPlayPause = {
                webViewRef.value?.let { wv ->
                    wv.clickPlayerButton(SEL_PLAY_PAUSE)
                    // Re-query state shortly after the click so the icon reflects reality
                    wv.postDelayed({ wv.queryIsPlaying { playing -> isPlaying.value = playing } }, 400)
                }
            },
            onNext = {
                webViewRef.value?.clickPlayerButton(SEL_SKIP_FORWARD)
            },
            onAddToFavorites = {
                webViewRef.value?.clickPlayerButton(SEL_ADD_TO_LIKED)
                // Re-query liked state shortly after the click so the icon updates
                webViewRef.value?.postDelayed({
                    webViewRef.value?.queryIsLiked { liked -> if (liked != null) isLiked.value = liked }
                }, 400)
            },
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    webViewRef.value?.onResume()
                    webViewRef.value?.resumeTimers()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // Do NOT call pauseTimers() — it is a global call that stops ALL
                    // JavaScript timers in every WebView, including Spotify's keep-alive
                    // and state-machine heartbeats, causing audio to stop within seconds.
                    //
                    // Only call onPause() when music is not playing so Chromium can
                    // throttle rendering/GPU without touching the JS audio pipeline.
                    if (!isPlaying.value) {
                        webViewRef.value?.onPause()
                    }
                }
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webViewRef.value?.apply {
                onPause()
                pauseTimers()   // safe here — WebView is about to be destroyed
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webViewRef.value = null
        }
    }
}

// ---------------------------------------------------------------------------
// Playback overlay composable
// ---------------------------------------------------------------------------

@Composable
private fun PlaybackOverlay(
    modifier: Modifier = Modifier,
    isPlaying: Boolean,
    isLiked: Boolean?,
    volumeFraction: Float,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onAddToFavorites: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0xCC000000)),
                ),
            )
            .navigationBarsPadding()
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onVolumeDown) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeDown,
                        contentDescription = "Volume down",
                        tint = Color.White,
                    )
                }

                Slider(
                    value = volumeFraction,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )

                IconButton(onClick = onVolumeUp) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = "Volume up",
                        tint = Color.White,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }

                IconButton(
                    onClick  = onPlayPause,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0x40FFFFFF)),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }

                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }

                IconButton(
                    onClick = onAddToFavorites,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (isLiked == true) Icons.Rounded.Favorite
                                      else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (isLiked == true) "Remove from favorites"
                                             else "Add to favorites",
                        tint = if (isLiked == true) Color(0xFF1DB954) // Tuneveil green
                               else Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
