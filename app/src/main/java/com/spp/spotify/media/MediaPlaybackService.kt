package com.spp.spotify.media

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.spp.spotify.MainActivity
import com.spp.spotify.R

/**
 * Foreground service that owns the [MediaSessionCompat] and posts a
 * media-style notification.  This is what makes lock-screen controls,
 * Bluetooth headset buttons, and the Android media panel all work.
 */
class MediaPlaybackService : Service() {

    // ------------------------------------------------------------------
    // Binder
    // ------------------------------------------------------------------

    inner class LocalBinder : Binder() {
        val service: MediaPlaybackService get() = this@MediaPlaybackService
    }

    private val binder = LocalBinder()

    // ------------------------------------------------------------------
    // Callbacks — set by WebPlayerScreen after binding
    // ------------------------------------------------------------------

    var onPlayPause: (() -> Unit)? = null
    var onSkipNext: (() -> Unit)? = null
    var onSkipPrevious: (() -> Unit)? = null

    // ------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------

    private lateinit var mediaSession: MediaSessionCompat

    // ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK was added in API 29.
    // ServiceCompat.startForeground() guards the call internally for older devices,
    // so inlining the constant here is safe.
    @SuppressLint("InlinedApi")
    private val foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, SESSION_TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay()           { onPlayPause?.invoke() }
                override fun onPause()          { onPlayPause?.invoke() }
                override fun onStop()           { onPlayPause?.invoke() }
                override fun onSkipToNext()     { onSkipNext?.invoke() }
                override fun onSkipToPrevious() { onSkipPrevious?.invoke() }
            })
            setPlaybackState(buildState(isPlaying = false))
            isActive = true
        }

        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(false, "Spotify", ""),
            foregroundServiceType,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Routes hardware media-button intents dispatched by the system
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swipe-away from recents: stop the foreground service so the
        // notification disappears when the user explicitly closes the app.
        stopSelf()
    }

    override fun onDestroy() {
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Public API called from WebPlayerScreen
    // ------------------------------------------------------------------

    fun update(isPlaying: Boolean, title: String, artist: String,
               positionMs: Long = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
               durationMs: Long = -1L) {
        mediaSession.setPlaybackState(buildState(isPlaying, positionMs))
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  title.ifBlank  { "Spotify" })
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .apply { if (durationMs > 0) putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs) }
                .build(),
        )
        // Update via ServiceCompat.startForeground with explicit type — required
        // on Android 14+ and exempt from POST_NOTIFICATIONS runtime permission.
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(isPlaying, title, artist),
            foregroundServiceType,
        )
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun buildState(
        isPlaying: Boolean,
        positionMs: Long = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
    ): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP,
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                positionMs,
                if (isPlaying) 1f else 0f,
            )
            .build()

    private fun buildNotification(isPlaying: Boolean, title: String, artist: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            flags,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "Spotify" })
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setSilent(true)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    IconCompat.createWithResource(this, R.drawable.ic_media_previous),
                    "Previous",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
                    ),
                ).build(),
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    IconCompat.createWithResource(
                        this,
                        if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                    ),
                    if (isPlaying) "Pause" else "Play",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_PLAY_PAUSE,
                    ),
                ).build(),
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    IconCompat.createWithResource(this, R.drawable.ic_media_next),
                    "Next",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
                    ),
                ).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Media Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Spotify web player controls"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------
    companion object {
        private const val SESSION_TAG = "SpotifyWebPlayer"
        private const val CHANNEL_ID  = "spotify_webview_playback"
        const val NOTIF_ID = 1001
    }
}
