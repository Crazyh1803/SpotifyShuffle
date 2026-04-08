package com.spotifytrueshuffle.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG           = "PlaylistBuildSvc"
private const val CHANNEL_ID    = "playlist_build_progress"
private const val NOTIFICATION_ID = 1002

/**
 * Foreground service that runs the playlist build while the app is in the background
 * or the screen is off.
 *
 * Why a foreground service?
 *   Android treats processes with an active foreground service as high-priority and will not
 *   kill them to reclaim memory. A PARTIAL_WAKE_LOCK alone prevents the CPU from sleeping
 *   but does NOT stop Android from terminating the process when the app is backgrounded.
 *
 * Lifecycle:
 *   1. [MainViewModel.buildPlaylist] starts this service via [startForegroundService].
 *   2. [onStartCommand] immediately calls [startForeground] (required within 5 s on API 26+)
 *      then launches the build coroutine.
 *   3. Build progress is pushed to [BuildEventBus.progress]; the ViewModel collects it to
 *      update the UI.
 *   4. On completion, the result is pushed to [BuildEventBus.results] and [stopSelf] is called.
 *   5. [onDestroy] cancels the coroutine scope so no work leaks if the service is stopped early.
 */
class PlaylistBuildForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Foreground build service starting")
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))

        val appSettings  = AppSettingsStorage(applicationContext)
        val tokenStorage = TokenStorage(applicationContext)
        val authManager  = SpotifyAuthManager(tokenStorage) { appSettings.load().clientId }
        val apiService   = buildApiService(tokenStorage)
        val repository   = SpotifyRepository(apiService, authManager, tokenStorage)
        val buildService = PlaylistBuildService(
            repository     = repository,
            artistCache    = ArtistTrackCache(applicationContext),
            trackPoolCache = TrackPoolCache(applicationContext),
            shuffleEngine  = TrueShuffleEngine(),
            historyStorage = ShuffleHistoryStorage(applicationContext),
            appSettings    = appSettings,
            tokenStorage   = tokenStorage
        )

        serviceScope.launch {
            val result = buildService.build(
                forceArtistRescan = false,
                onProgress = { msg, step, total ->
                    // tryEmit is non-suspending — safe inside a plain lambda
                    BuildEventBus.tryEmitProgress(msg, step, total)
                    updateNotification(msg)
                }
            )
            BuildEventBus.emitResult(result)
            Log.d(TAG, "Build finished — stopping foreground service")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /** This service is not designed to be bound. */
    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun buildNotification(message: String) = run {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("True Shuffle — Building Playlist")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playlist Building",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while True Shuffle is building your playlist"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
