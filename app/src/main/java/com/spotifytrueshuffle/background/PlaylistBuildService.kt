package com.spotifytrueshuffle.background

import android.util.Log
import com.spotifytrueshuffle.api.SpotifyApiError
import com.spotifytrueshuffle.api.SpotifyRepository
import com.spotifytrueshuffle.api.Track
import com.spotifytrueshuffle.auth.TokenStorage
import com.spotifytrueshuffle.cache.AppSettingsStorage
import com.spotifytrueshuffle.cache.ArtistLibrary
import com.spotifytrueshuffle.cache.ArtistTrackCache
import com.spotifytrueshuffle.cache.ShuffleHistoryStorage
import com.spotifytrueshuffle.cache.TrackPoolCache
import com.spotifytrueshuffle.shuffle.TrackUtils
import com.spotifytrueshuffle.shuffle.TrueShuffleEngine
import java.time.LocalDate

private const val TAG = "PlaylistBuildService"

/**
 * Core playlist-build logic, decoupled from the UI layer.
 *
 * Used by:
 *  - [com.spotifytrueshuffle.ui.MainViewModel] (interactive foreground builds — caller updates UiState)
 *  - [PlaylistRebuildWorker] (background WorkManager builds — caller posts a notification)
 *
 * @param onProgress Optional callback for progress updates (step label, current step, total steps).
 *                   The ViewModel passes a lambda that updates UiState.Building.
 *                   The Worker passes `null` (progress is silently logged instead).
 */
class PlaylistBuildService(
    private val repository: SpotifyRepository,
    private val artistCache: ArtistTrackCache,
    private val trackPoolCache: TrackPoolCache,
    private val shuffleEngine: TrueShuffleEngine,
    private val historyStorage: ShuffleHistoryStorage,
    private val appSettings: AppSettingsStorage,
    private val tokenStorage: TokenStorage
) {

    /**
     * Invalidates the track pool cache so the next build triggers a full rescan.
     * Call this after a successful artist library refresh.
     */
    fun invalidateTrackPool() = trackPoolCache.invalidate()

    /** Result returned by [build]. Caller decides how to surface it (UI state vs notification). */
    sealed class BuildResult {
        data class Success(
            val playlistUrl: String,
            val trackCount: Int,
            val durationMinutes: Int,
            val artistCount: Int,
            val tierCCount: Int,
            val tierBCount: Int,
            val tierACount: Int
        ) : BuildResult()

        data class Failure(val message: String, val stepName: String) : BuildResult()
        /** User's Spotify session has expired or they've never logged in. */
        object NotLoggedIn : BuildResult()
        /** Client ID hasn't been configured yet (first-time setup incomplete). */
        object NoClientId : BuildResult()
    }

    /**
     * Runs the full playlist build:
     * 1. Pre-flight checks (logged in, client ID set)
     * 2. Fetch user profile
     * 3. Load or refresh artist library (always rescans for background builds)
     * 4. Build track pool
     * 5. Run shuffle engine
     * 6. Save/update playlist on Spotify
     * 7. Record cooldown history
     *
     * @param forceArtistRescan If true, always re-fetches artist library from Spotify API
     *                          (used by background scheduled builds). If false, uses cached
     *                          library when available (used by interactive builds).
     * @param onProgress        Optional progress callback: (message, currentStep, totalSteps)
     */
    suspend fun build(
        forceArtistRescan: Boolean = false,
        onProgress: ((String, Int, Int) -> Unit)? = null
    ): BuildResult {
        val settings = appSettings.load()

        if (settings.clientId.isEmpty()) return BuildResult.NoClientId
        if (!tokenStorage.isLoggedIn())  return BuildResult.NotLoggedIn

        var stepName = "initializing"
        return try {
            // Step 1 — user profile
            stepName = "getUserProfile"
            progress(onProgress, "Getting your Spotify profile…", 1, 4)
            val user = repository.getUserProfile()

            // Step 2 — artist library
            stepName = "artistLibrary"
            val library: ArtistLibrary = if (forceArtistRescan || artistCache.load().followedArtists.isEmpty()) {
                progress(onProgress, "Loading your artist library…", 2, 4)
                val followed = repository.getAllFollowedArtists()
                val topIds   = repository.getTopArtists().map { it.id }
                ArtistLibrary(
                    lastRefreshedMs = System.currentTimeMillis(),
                    followedArtists = followed,
                    topArtistIds    = topIds
                ).also { artistCache.save(it) }
            } else {
                val cached = artistCache.load()
                progress(onProgress, "Loaded ${cached.followedArtists.size} artists from library…", 2, 4)
                cached
            }

            if (library.followedArtists.isEmpty()) {
                return BuildResult.Failure(
                    "You're not following any artists on Spotify yet.\nFollow some artists and try again!",
                    stepName
                )
            }

            // Step 3 — track pool (cache-first: rebuild only on force rescan or cache miss/expiry)
            stepName = "buildTrackPool"
            val topArtistIds = library.topArtistIds.toSet()
            val trackPool = if (!forceArtistRescan) {
                val cached = trackPoolCache.load()
                if (cached != null) {
                    progress(onProgress,
                        "Loaded ${cached.tracksByArtist.size} artists from cache…", 3, 4)
                    cached
                } else {
                    progress(onProgress, "Scanning your Spotify library for tracks…", 3, 4)
                    repository.buildTrackPool(
                        followedArtists = library.followedArtists,
                        topArtistIds    = topArtistIds,
                        market          = user.country
                    ).also { trackPoolCache.save(it) }
                }
            } else {
                // Force rescan: always rebuild and refresh the cache
                trackPoolCache.invalidate()
                progress(onProgress, "Scanning your Spotify library for tracks…", 3, 4)
                repository.buildTrackPool(
                    followedArtists = library.followedArtists,
                    topArtistIds    = topArtistIds,
                    market          = user.country
                ).also { trackPoolCache.save(it) }
            }
            val tracksByArtist = trackPool.tracksByArtist
            Log.d(TAG, "Track pool: ${tracksByArtist.size} artists " +
                "(${trackPool.discoveryArtistIds.size} discovery)")

            if (tracksByArtist.isEmpty()) {
                return BuildResult.Failure(
                    "No tracks found in your Spotify library.\n\nSave some songs in Spotify, then try again.",
                    stepName
                )
            }

            // Step 4 — shuffle + save
            stepName = "buildAndSave"
            progress(onProgress, "Assembling your true shuffle playlist…", 4, 4)

            // Filter out skit tracks (case-insensitive) before any shuffle logic
            val filteredTracksByArtist = tracksByArtist
                .mapValues { (_, ts) -> ts.filter { !TrackUtils.isSkit(it.name) } }
                .filter { it.value.isNotEmpty() }
            val skitCount = tracksByArtist.values.sumOf { it.size } - filteredTracksByArtist.values.sumOf { it.size }
            if (skitCount > 0) Log.d(TAG, "Filtered $skitCount skit track(s)")

            val history = historyStorage.load()
            // Track cooldown and artist cooldown use independent depth windows
            val trackCooldownN  = history.cooldownPlaylists
            val artistCooldownN = settings.artistCooldownPlaylists
            val cooldownSongKeys  = historyStorage.getCooldownSets(trackCooldownN,  history).first
            val cooldownArtistIds = historyStorage.getCooldownSets(artistCooldownN, history).second
            Log.d(TAG, "Cooldown — songs: last $trackCooldownN playlists (${cooldownSongKeys.size} keys), " +
                "artists: last $artistCooldownN playlists (${cooldownArtistIds.size} suppressed)")

            val tracks = shuffleEngine.buildPlaylist(
                followedArtists    = library.followedArtists,
                topArtistIds       = topArtistIds,
                tracksByArtist     = filteredTracksByArtist,
                discoveryArtistIds = trackPool.discoveryArtistIds,
                likedTrackIds      = trackPool.likedTrackIds,
                cooldownSongKeys   = cooldownSongKeys,
                cooldownArtistIds  = cooldownArtistIds,
                discoveryBias      = settings.discoveryBias,
                targetDurationMs   = settings.playlistDurationMs
            )

            if (tracks.isEmpty()) {
                return BuildResult.Failure(
                    "None of your followed artists had tracks in your library.\n\n" +
                    "Save songs from artists you follow, then try again.",
                    stepName
                )
            }

            // Save / update the Spotify playlist
            val playlistUrl = savePlaylist(user.id, tracks)

            // Record this build — store song keys (not raw IDs) so version deduplication works
            historyStorage.recordPlaylist(
                trackIds  = tracks.map { TrackUtils.songKey(it.name, it.artists.firstOrNull()?.id ?: "") },
                artistIds = tracks.mapNotNull { it.artists.firstOrNull()?.id }.distinct(),
                cooldownCount = history.cooldownPlaylists
            )

            // Tier breakdown
            val tierCCount = tracks.count { it.artists.firstOrNull()?.id in trackPool.discoveryArtistIds }
            val tierACount = tracks.count { it.artists.firstOrNull()?.id in topArtistIds }
            val tierBCount = tracks.size - tierCCount - tierACount
            Log.d(TAG, "Build complete — C=$tierCCount B=$tierBCount A=$tierACount")

            BuildResult.Success(
                playlistUrl     = playlistUrl,
                trackCount      = tracks.size,
                durationMinutes = (tracks.sumOf { it.durationMs.toLong() } / 60_000).toInt(),
                artistCount     = tracks.flatMap { it.artists }.map { it.id }.toSet().size,
                tierCCount      = tierCCount,
                tierBCount      = tierBCount,
                tierACount      = tierACount
            )

        } catch (e: Exception) {
            Log.e(TAG, "Build failed at $stepName", e)
            BuildResult.Failure(buildErrorMessage(e, stepName), stepName)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun savePlaylist(userId: String, tracks: List<Track>): String {
        @Suppress("SENSELESS_COMPARISON")
        val uris = tracks.map { it.uri }.filter { it != null && it.startsWith("spotify:track:") }
        Log.d(TAG, "savePlaylist: ${tracks.size} tracks → ${uris.size} valid URIs")
        if (uris.isEmpty()) throw Exception("No valid track URIs found. Try building again.")

        val description = "True shuffle of ${tracks.flatMap { it.artists }.map { it.id }.toSet().size}" +
            " artists — generated ${LocalDate.now()}"

        // Try to update the existing playlist first
        val existingId = tokenStorage.playlistId
        if (existingId != null) {
            val updated = repository.replacePlaylistTracks(existingId, uris)
            if (updated) {
                Log.d(TAG, "Playlist $existingId updated in-place")
                return "https://open.spotify.com/playlist/$existingId"
            }
            Log.w(TAG, "Stored playlist $existingId returned 404/403 — creating a new one")
            tokenStorage.playlistId = null
        }

        // Create a fresh playlist
        val playlist = try {
            repository.createPlaylist(userId, description)
        } catch (e: retrofit2.HttpException) {
            val body   = try { e.response()?.errorBody()?.string() ?: "(no body)" } catch (_: Exception) { "(unreadable)" }
            val scopes = tokenStorage.grantedScopes ?: "(not stored)"
            Log.e(TAG, "createPlaylist ${e.code()}: userId=$userId scopes=$scopes body=$body")
            throw Exception(
                "Spotify ${e.code()} on createPlaylist.\nuserId=$userId\n" +
                "playlist-modify-public: ${scopes.contains("playlist-modify-public")}\nDiag: $body"
            )
        }
        tokenStorage.playlistId = playlist.id

        // Populate — try PUT first, fall back to POST
        try {
            val replaced = repository.replacePlaylistTracks(playlist.id, uris)
            if (!replaced) {
                Log.w(TAG, "PUT tracks returned 403 — falling back to POST")
                repository.addTracksToPlaylist(playlist.id, uris)
            }
        } catch (e: SpotifyApiError) {
            val scopes = tokenStorage.grantedScopes ?: "(not stored)"
            throw Exception(
                "Spotify ${e.httpCode} — tracks couldn't be added.\n" +
                "modify-public=${scopes.contains("playlist-modify-public")}  " +
                "modify-private=${scopes.contains("playlist-modify-private")}\n" +
                "Body: ${e.responseBody}"
            )
        }

        return playlist.externalUrls?.spotify ?: "https://open.spotify.com/playlist/${playlist.id}"
    }

    private fun buildErrorMessage(e: Exception, stepName: String): String {
        if (e is retrofit2.HttpException) {
            val body = try { e.response()?.errorBody()?.string() ?: "(no body)" } catch (_: Exception) { "(unreadable)" }
            return when (e.code()) {
                429 -> "Spotify rate limit hit.\n\nWait 60 seconds then try again."
                401 -> "Session expired. Please log out and re-authorize."
                403 -> "Spotify 403 at $stepName.\n\nDiag: $body"
                else -> "HTTP ${e.code()} at step: $stepName.\n\nDiag: $body"
            }
        }
        return e.message ?: "Something went wrong. Please try again."
    }

    private fun progress(cb: ((String, Int, Int) -> Unit)?, message: String, step: Int, total: Int) {
        cb?.invoke(message, step, total) ?: Log.d(TAG, "[$step/$total] $message")
    }
}
