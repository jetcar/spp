package com.spp.spotify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log

/**
 * Catches uncaught exceptions (including native WebView crashes that bubble up),
 * persists current playback state, schedules an automatic restart via [AlarmManager],
 * then kills the process cleanly.
 *
 * Usage: call [install] once from [SpotifyApp.onCreate].
 */
class CrashRecoveryHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Log.e(TAG, "Uncaught exception on thread '${thread.name}' – scheduling restart", throwable)

        try {
            // The was_playing flag was continuously updated by WebPlayerScreen while running.
            // We do NOT overwrite it here – just add the recovery flag on top.
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CRASH_RECOVERY, true)
                .apply()

            // Schedule a restart 1.5 s from now. AlarmManager survives process death.
            val restartIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val pi = PendingIntent.getActivity(
                context, REQUEST_CODE, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .set(AlarmManager.RTC, System.currentTimeMillis() + RESTART_DELAY_MS, pi)

            Log.i(TAG, "Restart scheduled in ${RESTART_DELAY_MS}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Could not schedule restart", e)
        }

        // Kill our process so Android cleans up all resources.
        Process.killProcess(Process.myPid())
    }

    // ── Companion helpers called from WebPlayerScreen ─────────────────────────

    companion object {
        private const val TAG               = "CrashRecovery"
        private const val RESTART_DELAY_MS  = 1500L
        private const val REQUEST_CODE      = 9001

        const val PREFS_NAME            = "crash_recovery"
        const val KEY_CRASH_RECOVERY    = "crash_recovery_pending"
        const val KEY_WAS_PLAYING       = "was_playing"

        /** Install the global crash handler. Call once from [SpotifyApp.onCreate]. */
        fun install(context: Context) {
            val default = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(
                CrashRecoveryHandler(context.applicationContext, default),
            )
            Log.i(TAG, "Crash-recovery handler installed")
        }

        /** True if the app is launching after an unhandled crash. */
        fun isCrashRecovery(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_CRASH_RECOVERY, false)

        /** True if playback was active when the previous crash occurred. */
        fun wasPlayingBeforeCrash(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_WAS_PLAYING, false)

        /** Call this once the recovery sequence is complete so we don't re-trigger. */
        fun clearRecoveryFlag(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_CRASH_RECOVERY).apply()
        }

        /** Called on every playback state poll to keep the persisted state fresh. */
        fun savePlaybackState(context: Context, isPlaying: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_WAS_PLAYING, isPlaying).apply()
        }
    }
}

