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
import com.spotifytrueshuffle.cache.ArtistLibrary
import com.spotifytrueshuffle.cache.ArtistTrackCache
import com.spotifytrueshuffle.cache.GapArtistCache
import com.spotifytrueshuffle.cache.ShuffleHistoryStorage
import com.spotifytrueshuffle.network.buildApiService
import com.spotifytrueshuffle.shuffle.TrueShuffleEngine

private const val TAG            = "PlaylistRebuildWorker"
private const val CHANNEL_ID     = "playlist_rebuild"
private const val NOTIFICATION_ID = 1001

/**
 * WorkManager [CoroutineWorker] that auto-rebuilds the True Shuffle playlist in the background
 * on a user-configured interval (set in Settings → Auto-rebuild).
 *
 * All dependencies are created fresh — intentional since the worker may run when no Activity
 * is alive and the app doesn't use a DI framework.
 *
 * Success  → posts a "Playlist Updated ✓" notification
 * Not set up (no login / no Client ID) → returns [Result.success] silently
 * Transient failure (network / API) → [Result.retry] up to 3 attempts, then [Result.success]
 */
class PlaylistRebuildWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Background playlist rebuild starting (attempt ${runAttemptCount + 1})")

        val appSettings  = AppSettingsStorage(applicationContext)
        val settings     = appSettings.load()

        // Skip silently if setup is incomplete
        if (settings.clientId.isEmpty()) {
            Log.w(TAG, "No Client ID — skipping"); return Result.success()
        }

        val tokenStorage = TokenStorage(applicationContext)
        if (!tokenStorage.isLoggedIn()) {
            Log.w(TAG, "Not logged in — skipping"); return Result.success()
        }

        val authManager   = SpotifyAuthManager(tokenStorage) { settings.clientId }
        val repository    = SpotifyRepository(buildApiService(tokenStorage), authManager, tokenStorage)
        val artistCache   = ArtistTrackCache(applicationContext)
        val gapArtistCache = GapArtistCache(applicationContext)
        val historyStorage = ShuffleHistoryStorage(applicationContext)
        val shuffleEngine  = TrueShuffleEngine()

        return try {
            // Load or refresh artist library
            var library = artistCache.load()
            if (library.followedArtists.isEmpty()) {
                val followed = repository.getAllFollowedArtists()
                val topIds   = repository.getTopArtists().map { it.id }
                library = ArtistLibrary(System.currentTimeMillis(), followed, topIds)
                artistCache.save(library)
            }

            // Build track pool (incremental — uses cached gap-artist data)
            val user              = repository.getUserProfile()
            val topArtistIds      = library.topArtistIds.toSet()
            val cachedEntries     = gapArtistCache.load()
            val rescanThresholdMs = if (settings.trackRescanIntervalDays == 0) Long.MAX_VALUE
                                    else settings.trackRescanIntervalDays * 86_400_000L
            val buildResult = repository.buildTrackPool(
                followedArtists   = library.followedArtists,
                topArtistIds      = topArtistIds,
                market            = user.country,
                cachedEntries     = cachedEntries,
                rescanThresholdMs = rescanThresholdMs
            )
            if (buildResult.newlyScanned.isNotEmpty()) {
                gapArtistCache.save(cachedEntries + buildResult.newlyScanned)
            }
            if (buildResult.pool.tracksByArtist.isEmpty()) {
                Log.w(TAG, "Empty track pool — skipping"); return Result.success()
            }

            // Shuffle
            val history  = historyStorage.load()
            val cooldown = historyStorage.getCooldownSets(history.cooldownPlaylists, history)
            val tracks   = shuffleEngine.buildPlaylist(
                followedArtists   = library.followedArtists,
                topArtistIds      = topArtistIds,
                tracksByArtist    = buildResult.pool.tracksByArtist,
                discoveryArtistIds = buildResult.pool.discoveryArtistIds,
                likedTrackIds     = buildResult.pool.likedTrackIds,
                cooldownTrackIds  = cooldown.first,
                cooldownArtistIds = cooldown.second,
                discoveryBias     = settings.discoveryBias,
                targetDurationMs  = settings.playlistDurationMs
            )
            if (tracks.isEmpty()) {
                Log.w(TAG, "Shuffle produced no tracks — skipping"); return Result.success()
            }

            // Save to Spotify
            val uris        = tracks.map { it.uri }.filter { it.startsWith("spotify:track:") }
            val description = "Auto-rebuilt by True Shuffle"
            val existingId  = tokenStorage.playlistId
            if (existingId != null) {
                if (!repository.replacePlaylistTracks(existingId, uris)) {
                    val pl = repository.createPlaylist(user.id, description)
                    tokenStorage.playlistId = pl.id
                    repository.replacePlaylistTracks(pl.id, uris)
                }
            } else {
                val pl = repository.createPlaylist(user.id, description)
                tokenStorage.playlistId = pl.id
                repository.replacePlaylistTracks(pl.id, uris)
            }

            // Record history so cooldown works on the next build
            historyStorage.recordPlaylist(
                tracks.map { it.id },
                tracks.flatMap { it.artists }.map { it.id }.distinct(),
                history.cooldownPlaylists
            )

            val durationMin = tracks.sumOf { it.durationMs.toLong() } / 60_000
            val artistCount = tracks.flatMap { it.artists }.map { it.id }.toSet().size
            val tierCCount  = tracks.count { t -> t.artists.firstOrNull()?.id in buildResult.pool.discoveryArtistIds }
            val tierACount  = tracks.count { t -> t.artists.firstOrNull()?.id in topArtistIds }
            val tierBCount  = tracks.size - tierCCount - tierACount
            Log.d(TAG, "Background build done: ${tracks.size} tracks, $durationMin min")

            postNotification(tracks.size, durationMin.toInt(), artistCount, tierCCount, tierBCount, tierACount)
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Background build failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun postNotification(
        trackCount: Int,
        durationMin: Int,
        artistCount: Int,
        tierCCount: Int,
        tierBCount: Int,
        tierACount: Int
    ) {
        ensureChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = applicationContext.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) { Log.w(TAG, "POST_NOTIFICATIONS not granted"); return }
        }

        val tap = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val tierParts = buildList {
            if (tierCCount > 0) add("$tierCCount discovery")
            if (tierBCount > 0) add("$tierBCount familiar")
            if (tierACount > 0) add("$tierACount top")
        }.joinToString(" · ")

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("True Shuffle — Playlist Updated ✓")
            .setContentText("$trackCount tracks · $durationMin min · $artistCount artists")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$trackCount tracks · $durationMin min\n$tierParts"))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not post notification: ${e.message}")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playlist Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notified when True Shuffle automatically rebuilds your playlist"
            }
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
