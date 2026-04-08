package com.spotifytrueshuffle.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spotifytrueshuffle.MainActivity
import com.spotifytrueshuffle.api.SpotifyRepository
import com.spotifytrueshuffle.auth.SpotifyAuthManager
import com.spotifytrueshuffle.auth.TokenStorage
import com.spotifytrueshuffle.cache.AppSettingsStorage
import com.spotifytrueshuffle.cache.ArtistTrackCache
import com.spotifytrueshuffle.cache.ShuffleHistoryStorage
import com.spotifytrueshuffle.cache.TrackPoolCache
import com.spotifytrueshuffle.network.buildApiService
import com.spotifytrueshuffle.shuffle.TrueShuffleEngine

private const val TAG = "PlaylistRebuildWorker"
private const val CHANNEL_ID   = "playlist_rebuild"
private const val NOTIFICATION_ID = 1001

/**
 * WorkManager [CoroutineWorker] that runs in the background (even when the app is closed)
 * to rebuild the True Shuffle playlist on a user-configured interval.
 *
 * All dependencies are created fresh from [applicationContext] — this is intentional since
 * the app doesn't use a DI framework and the worker may run when no Activity is alive.
 *
 * On success: posts a notification "Playlist updated ✓"
 * On soft failure (not logged in / no client ID): returns [Result.success] silently
 * On transient failure (network / API error): returns [Result.retry] — WorkManager retries
 */
class PlaylistRebuildWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Background playlist rebuild starting (attempt ${runAttemptCount + 1})")

        val appSettings   = AppSettingsStorage(applicationContext)
        val tokenStorage  = TokenStorage(applicationContext)
        val authManager   = SpotifyAuthManager(tokenStorage) { appSettings.load().clientId }
        val apiService    = buildApiService(tokenStorage)
        val repository    = SpotifyRepository(apiService, authManager, tokenStorage)
        val buildService  = PlaylistBuildService(
            repository     = repository,
            artistCache    = ArtistTrackCache(applicationContext),
            trackPoolCache = TrackPoolCache(applicationContext),
            shuffleEngine  = TrueShuffleEngine(),
            historyStorage = ShuffleHistoryStorage(applicationContext),
            appSettings    = appSettings,
            tokenStorage   = tokenStorage
        )

        return when (val result = buildService.build(forceArtistRescan = true)) {
            is PlaylistBuildService.BuildResult.Success -> {
                Log.d(TAG, "Background build succeeded — ${result.trackCount} tracks")
                postNotification(result)
                Result.success()
            }
            is PlaylistBuildService.BuildResult.NotLoggedIn -> {
                Log.w(TAG, "Background build skipped — user not logged in")
                Result.success()   // not an error; user just hasn't authenticated yet
            }
            is PlaylistBuildService.BuildResult.NoClientId -> {
                Log.w(TAG, "Background build skipped — no Client ID configured")
                Result.success()   // setup not complete; skip silently
            }
            is PlaylistBuildService.BuildResult.Failure -> {
                Log.e(TAG, "Background build failed at ${result.stepName}: ${result.message}")
                // Retry on transient failures; WorkManager uses exponential back-off
                if (runAttemptCount < 3) Result.retry() else Result.success()
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun postNotification(result: PlaylistBuildService.BuildResult.Success) {
        ensureNotificationChannel()

        // Check permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = applicationContext.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted — skipping notification")
                return
            }
        }

        val tapIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("True Shuffle — Playlist Updated ✓")
            .setContentText("${result.trackCount} tracks · ${result.durationMinutes} min · ${result.artistCount} artists")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "${result.trackCount} tracks · ${result.durationMinutes} min\n" +
                        buildTierLine(result)
                    )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not post notification (permission denied): ${e.message}")
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playlist Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notified when True Shuffle automatically rebuilds your playlist"
            }
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildTierLine(r: PlaylistBuildService.BuildResult.Success): String {
        val parts = buildList {
            if (r.tierCCount > 0) add("${r.tierCCount} discovery")
            if (r.tierBCount > 0) add("${r.tierBCount} familiar")
            if (r.tierACount > 0) add("${r.tierACount} top")
        }
        return if (parts.isEmpty()) "" else parts.joinToString(" · ")
    }
}
