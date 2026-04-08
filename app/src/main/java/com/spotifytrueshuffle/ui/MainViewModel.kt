package com.spotifytrueshuffle.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spotifytrueshuffle.api.Artist
import com.spotifytrueshuffle.api.SpotifyRepository
import com.spotifytrueshuffle.api.Track
import com.spotifytrueshuffle.auth.SpotifyAuthManager
import com.spotifytrueshuffle.auth.TokenStorage
import com.spotifytrueshuffle.cache.AppSettingsStorage
import com.spotifytrueshuffle.cache.ArtistLibrary
import com.spotifytrueshuffle.cache.ArtistTrackCache
import com.spotifytrueshuffle.cache.GapArtistCache
import com.spotifytrueshuffle.cache.ShuffleHistoryStorage
import com.spotifytrueshuffle.shuffle.TrueShuffleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "MainViewModel"

/**
 * Tracks incremental gap-artist scan progress across builds.
 * [isComplete] is true once all followed artists have been scanned at least once.
 */
data class ScanProgress(val scanned: Int, val total: Int) {
    val isComplete: Boolean get() = scanned >= total
}

class MainViewModel(
    private val authManager: SpotifyAuthManager,
    private val repository: SpotifyRepository,
    private val tokenStorage: TokenStorage,
    private val shuffleEngine: TrueShuffleEngine,
    private val artistCache: ArtistTrackCache,
    private val historyStorage: ShuffleHistoryStorage,
    private val appSettings: AppSettingsStorage,
    private val gapArtistCache: GapArtistCache
) : ViewModel() {

    sealed class UiState {
        /** First-time launch: user hasn't entered their Spotify Developer Client ID yet. */
        object Setup : UiState()
        object NotLoggedIn : UiState()
        /** Logged in and ready. Shows artist library size if available. */
        data class LoggedIn(
            val cachedArtistCount: Int = 0,
            val lastRefreshed: String? = null
        ) : UiState()
        data class Building(val progress: String, val step: Int, val totalSteps: Int) : UiState()
        data class Success(
            val playlistUrl: String,
            val trackCount: Int,
            val durationMinutes: Int,
            val artistCount: Int,
            /** Number of tracks from Tier C (pure discovery artists). */
            val tierCCount: Int = 0,
            /** Number of tracks from Tier B (familiar non-top artists). */
            val tierBCount: Int = 0,
            /** Number of tracks from Tier A (top artists). */
            val tierACount: Int = 0
        ) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.NotLoggedIn)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** How many past playlists an artist/track must skip before being eligible again (1–10). */
    private val _cooldownCount = MutableStateFlow(historyStorage.load().cooldownPlaylists)
    val cooldownCount: StateFlow<Int> = _cooldownCount.asStateFlow()

    /** 0–100 discovery bias slider (maps to Tier C weight in the engine). */
    private val _discoveryBias = MutableStateFlow(appSettings.load().discoveryBias)
    val discoveryBias: StateFlow<Int> = _discoveryBias.asStateFlow()

    /** Target playlist duration in milliseconds. */
    private val _playlistDurationMs = MutableStateFlow(appSettings.load().playlistDurationMs)
    val playlistDurationMs: StateFlow<Long> = _playlistDurationMs.asStateFlow()

    /** Whether the Settings bottom sheet is currently visible. */
    private val _settingsVisible = MutableStateFlow(false)
    val settingsVisible: StateFlow<Boolean> = _settingsVisible.asStateFlow()

    /** Incremental scan progress — null until the first build completes. */
    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()

    /** How often gap-artist tracks are automatically re-scanned (0 = manual only). */
    private val _trackRescanIntervalDays = MutableStateFlow(appSettings.load().trackRescanIntervalDays)
    val trackRescanIntervalDays: StateFlow<Int> = _trackRescanIntervalDays.asStateFlow()

    init {
        val settings = appSettings.load()
        _uiState.value = when {
            settings.clientId.isEmpty() -> UiState.Setup
            authManager.isLoggedIn()    -> loggedInState()
            else                        -> UiState.NotLoggedIn
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun openSettings() { _settingsVisible.value = true }
    fun closeSettings() { _settingsVisible.value = false }

    /**
     * Called when the user finishes entering their Client ID on the Setup screen.
     * Saves the ID and transitions to the normal NotLoggedIn home screen.
     */
    fun completeSetup(clientId: String) {
        appSettings.saveClientId(clientId.trim())
        _uiState.value = UiState.NotLoggedIn
    }

    /**
     * Returns the user to the Setup screen so they can change their Client ID.
     * Closes the settings sheet first if it's open.
     */
    fun changeClientId() {
        _settingsVisible.value = false
        _uiState.value = UiState.Setup
    }

    /** Updates the cooldown setting and persists it immediately. */
    fun setCooldownCount(n: Int) {
        val clamped = n.coerceIn(1, 10)
        _cooldownCount.value = clamped
        historyStorage.saveCooldownCount(clamped)
    }

    /** Updates the discovery bias (0–100) and persists it. */
    fun setDiscoveryBias(bias: Int) {
        val clamped = bias.coerceIn(0, 100)
        _discoveryBias.value = clamped
        appSettings.saveDiscoveryBias(clamped)
    }

    /** Updates the target playlist duration (ms) and persists it. */
    fun setPlaylistDuration(ms: Long) {
        _playlistDurationMs.value = ms
        appSettings.savePlaylistDuration(ms)
    }

    /** Clears cooldown history (resets artist/track suppression) without touching the cooldown count setting. */
    fun clearCooldownHistory() {
        historyStorage.clearHistory()
        Log.d(TAG, "Cooldown history cleared by user")
    }

    /** Updates the auto-rescan interval (0 = manual only, 1–365 = days) and persists it. */
    fun setTrackRescanIntervalDays(days: Int) {
        val clamped = days.coerceIn(0, 365)
        _trackRescanIntervalDays.value = clamped
        appSettings.saveTrackRescanIntervalDays(clamped)
    }

    /**
     * Marks all cached gap-artist entries as needing rescan (sets scannedAtMs=0).
     * The next [buildPlaylist] call will pick up the first batch of artists to re-fetch.
     */
    fun rescanAllTracks() {
        gapArtistCache.clearTimestamps()
        _scanProgress.value = null  // Reset progress display so user sees it refresh
        buildPlaylist()
    }

    /**
     * Exports the followed artist list as a CSV file to the device's Downloads folder.
     * Returns the file name on success, or null if the library is empty or write fails.
     */
    suspend fun exportArtistList(context: Context): String? = withContext(Dispatchers.IO) {
        val names = artistCache.load().followedArtists
            .map { it.name }
            .distinct()
            .sorted()
        if (names.isEmpty()) return@withContext null

        val date = java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val fileName = "shuffle_all_artists_$date.csv"
        val csv = names.joinToString("\n")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return@withContext null
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, fileName).writeText(csv)
            }
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "Artist export failed", e)
            null
        }
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    fun handleAuthCallback(code: String) {
        viewModelScope.launch {
            val success = authManager.handleCallback(code)
            _uiState.value = if (success) loggedInState()
                             else UiState.Error("Login failed — please try again")
        }
    }

    fun logout() {
        tokenStorage.clearAll()
        _uiState.value = UiState.NotLoggedIn
    }

    fun dismissError() {
        _uiState.value = if (authManager.isLoggedIn()) loggedInState() else UiState.NotLoggedIn
    }

    /** Resets the UI to the LoggedIn state without starting a build. */
    fun backToHome() {
        _uiState.value = loggedInState()
    }

    // ── Build Playlist ───────────────────────────────────────────────────────

    /**
     * Full playlist build flow:
     *
     * 1. Get user profile (needed for playlist creation).
     * 2. Load artist library from cache (or fetch if not cached yet).
     * 3. Build a track pool from /me/top/tracks + /me/tracks (saved songs).
     *    All /me/ endpoints — no per-artist calls, no rate-limit risk.
     * 4. Run the shuffle engine over all artists that have tracks in the pool,
     *    then save the resulting playlist to Spotify.
     */
    fun buildPlaylist() {
        viewModelScope.launch {
            var stepName = "initializing"
            try {
                // Step 1 — user profile
                stepName = "getUserProfile"
                progress("Getting your Spotify profile…", 1, 4)
                val user = repository.getUserProfile()

                // Step 2 — artist library (load from cache or fetch fresh)
                stepName = "artistLibrary"
                var library = artistCache.load()
                if (library.followedArtists.isEmpty()) {
                    library = buildArtistLibrary(progressStep = 2, progressTotal = 4)
                } else {
                    progress("Loaded ${library.followedArtists.size} artists from library…", 2, 4)
                    Log.d(TAG, "Using cached library: ${library.followedArtists.size} artists")
                }

                if (library.followedArtists.isEmpty()) {
                    _uiState.value = UiState.Error(
                        "You're not following any artists on Spotify yet.\n" +
                        "Follow some artists and try again!"
                    )
                    return@launch
                }

                // Step 3 — build track pool (library sources + incremental gap-artist cache)
                stepName = "buildTrackPool"
                progress("Scanning your Spotify library for tracks…", 3, 4)
                val topArtistIds = library.topArtistIds.toSet()

                val cachedEntries = gapArtistCache.load()
                val rescanIntervalDays = _trackRescanIntervalDays.value
                val rescanThresholdMs = if (rescanIntervalDays == 0) Long.MAX_VALUE
                                        else rescanIntervalDays * 86_400_000L

                val buildResult = repository.buildTrackPool(
                    followedArtists = library.followedArtists,
                    topArtistIds = topArtistIds,
                    market = user.country,
                    cachedEntries = cachedEntries,
                    rescanThresholdMs = rescanThresholdMs
                )

                // Persist newly scanned entries (merged with existing cache)
                if (buildResult.newlyScanned.isNotEmpty()) {
                    gapArtistCache.save(cachedEntries + buildResult.newlyScanned)
                }

                // Update scan progress for the success screen
                _scanProgress.value = ScanProgress(
                    scanned = buildResult.totalCached + buildResult.newlyScanned.size,
                    total = buildResult.totalGapArtists
                )

                val trackPool = buildResult.pool
                val tracksByArtist = trackPool.tracksByArtist
                Log.d(TAG, "Track pool covers ${tracksByArtist.size} artists " +
                    "(${trackPool.discoveryArtistIds.size} discovery, " +
                    "${trackPool.likedTrackIds.size} liked track IDs, " +
                    "${buildResult.newlyScanned.size} newly scanned)")

                if (tracksByArtist.isEmpty()) {
                    _uiState.value = UiState.Error(
                        "No tracks found in your Spotify library.\n\n" +
                        "Save some songs in Spotify, then tap Try Again."
                    )
                    return@launch
                }

                // Step 4 — shuffle + save to Spotify
                stepName = "savePlaylist"
                progress("Assembling your true shuffle playlist…", 4, 4)

                // Load cooldown sets: track/artist IDs from the last N playlists are
                // suppressed so the same songs/artists don't repeat every build.
                val history = historyStorage.load()
                val cooldown = historyStorage.getCooldownSets(history.cooldownPlaylists, history)
                Log.d(TAG, "Cooldown N=${history.cooldownPlaylists}: " +
                    "${cooldown.first.size} tracks, ${cooldown.second.size} artists suppressed")

                val currentBias = _discoveryBias.value
                val currentDurationMs = _playlistDurationMs.value
                Log.d(TAG, "Building with discoveryBias=$currentBias, durationMs=$currentDurationMs")

                val tracks = shuffleEngine.buildPlaylist(
                    followedArtists = library.followedArtists,
                    topArtistIds = topArtistIds,
                    tracksByArtist = tracksByArtist,
                    discoveryArtistIds = trackPool.discoveryArtistIds,
                    likedTrackIds = trackPool.likedTrackIds,
                    cooldownTrackIds = cooldown.first,
                    cooldownArtistIds = cooldown.second,
                    discoveryBias = currentBias,
                    targetDurationMs = currentDurationMs
                )

                if (tracks.isEmpty()) {
                    _uiState.value = UiState.Error(
                        "None of your followed artists had tracks in your library.\n\n" +
                        "Save songs from artists you follow, then tap Try Again."
                    )
                    return@launch
                }

                val playlistUrl = savePlaylist(user.id, tracks)

                // Record this playlist so future builds avoid repeating its songs/artists.
                val playlistTrackIds = tracks.map { it.id }
                val playlistArtistIds = tracks.flatMap { it.artists }.map { it.id }.distinct()
                historyStorage.recordPlaylist(playlistTrackIds, playlistArtistIds, history.cooldownPlaylists)

                // Count tier membership for the success screen breakdown.
                // Each track is attributed to its primary artist (first in list).
                val tierCCount = tracks.count { track ->
                    track.artists.firstOrNull()?.id in trackPool.discoveryArtistIds
                }
                val tierACount = tracks.count { track ->
                    track.artists.firstOrNull()?.id in topArtistIds
                }
                val tierBCount = tracks.size - tierCCount - tierACount

                val totalDurationMs = tracks.sumOf { it.durationMs.toLong() }
                val artistsRepresented = tracks.flatMap { it.artists }.map { it.id }.toSet().size

                Log.d(TAG, "Tier breakdown: C=$tierCCount, B=$tierBCount, A=$tierACount")

                _uiState.value = UiState.Success(
                    playlistUrl = playlistUrl,
                    trackCount = tracks.size,
                    durationMinutes = (totalDurationMs / 60_000).toInt(),
                    artistCount = artistsRepresented,
                    tierCCount = tierCCount,
                    tierBCount = tierBCount,
                    tierACount = tierACount
                )

            } catch (e: Exception) {
                Log.e(TAG, "Build failed at $stepName", e)
                _uiState.value = UiState.Error(buildErrorMessage(e, stepName))
            }
        }
    }

    /**
     * Re-fetches the full followed-artist list and top-artist IDs, then saves to cache.
     * This is a fast operation (a handful of paginated API calls — no track fetching).
     * Called when the user taps "Refresh Artists".
     */
    fun refreshArtists() {
        viewModelScope.launch {
            try {
                buildArtistLibrary(progressStep = 1, progressTotal = 2)
                _uiState.value = loggedInState()
            } catch (e: Exception) {
                Log.e(TAG, "Refresh artists failed", e)
                _uiState.value = UiState.Error(e.message ?: "Failed to refresh artists. Please try again.")
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Fetches ALL followed artists + top-artist IDs, saves to cache, and returns the library.
     * Typically 3–6 API calls total regardless of how many artists the user follows.
     */
    private suspend fun buildArtistLibrary(progressStep: Int, progressTotal: Int): ArtistLibrary {
        progress("Loading your artist library…", progressStep, progressTotal)
        val followed = repository.getAllFollowedArtists()
        val topIds = repository.getTopArtists().map { it.id }
        val library = ArtistLibrary(
            lastRefreshedMs = System.currentTimeMillis(),
            followedArtists = followed,
            topArtistIds = topIds
        )
        artistCache.save(library)
        Log.d(TAG, "Artist library built: ${followed.size} artists, ${topIds.size} top")
        return library
    }

    /**
     * Persists the playlist to Spotify.
     * Re-uses the same playlist ID so each build updates the existing playlist
     * rather than creating a new one.
     */
    private suspend fun savePlaylist(userId: String, tracks: List<Track>): String {
        @Suppress("SENSELESS_COMPARISON")
        val uris = tracks.map { it.uri }.filter { it != null && it.startsWith("spotify:track:") }
        Log.d(TAG, "savePlaylist: ${tracks.size} tracks → ${uris.size} valid URIs")
        if (uris.isEmpty()) {
            throw Exception("No valid track URIs found. Try tapping Build again.")
        }

        val description = "True shuffle of ${tracks.flatMap { it.artists }.map { it.id }.toSet().size}" +
            " artists — generated ${LocalDate.now()}"

        // ── Try to update the existing playlist ──────────────────────────────
        val existingId = tokenStorage.playlistId
        if (existingId != null) {
            val updated = repository.replacePlaylistTracks(existingId, uris)
            if (updated) {
                Log.d(TAG, "Playlist $existingId updated")
                return "https://open.spotify.com/playlist/$existingId"
            }
            Log.w(TAG, "Stored playlist $existingId returned 404/403 — creating a new one")
            tokenStorage.playlistId = null
        }

        // ── Create a fresh playlist ───────────────────────────────────────────
        val playlist = try {
            repository.createPlaylist(userId, description)
        } catch (e: retrofit2.HttpException) {
            val body = try { e.response()?.errorBody()?.string() ?: "(no body)" } catch (_: Exception) { "(unreadable)" }
            val scopes = tokenStorage.grantedScopes ?: "(not stored)"
            Log.e(TAG, "createPlaylist ${e.code()}: userId=$userId scopes=$scopes body=$body")
            throw Exception(
                "Spotify ${e.code()} on createPlaylist.\n" +
                "userId: $userId\n" +
                "playlist-modify-public: ${scopes.contains("playlist-modify-public")}\n" +
                "Diag: $body"
            )
        }
        tokenStorage.playlistId = playlist.id
        Log.d(TAG, "New playlist created: ${playlist.id}")

        // ── Populate it — try PUT first, fall back to POST ────────────────────
        try {
            val replaced = repository.replacePlaylistTracks(playlist.id, uris)
            if (!replaced) {
                Log.w(TAG, "PUT /playlists/${playlist.id}/tracks returned 403 — trying POST fallback")
                repository.addTracksToPlaylist(playlist.id, uris)
            }
        } catch (e: com.spotifytrueshuffle.api.SpotifyApiError) {
            val scopes = tokenStorage.grantedScopes ?: "(not stored)"
            val hasModPub  = scopes.contains("playlist-modify-public")
            val hasModPriv = scopes.contains("playlist-modify-private")
            Log.e(TAG, "addTracks ${e.httpCode}: playlistId=${playlist.id} scopes=$scopes body=${e.responseBody} headers=${e.responseHeaders}")
            throw Exception(
                "Spotify ${e.httpCode} — tracks couldn't be added to playlist.\n\n" +
                "modify-public=$hasModPub  modify-private=$hasModPriv\n" +
                "Body: ${e.responseBody}"
            )
        }

        return playlist.externalUrls?.spotify ?: "https://open.spotify.com/playlist/${playlist.id}"
    }

    private fun loggedInState(): UiState.LoggedIn {
        val lib = artistCache.load()
        return UiState.LoggedIn(
            cachedArtistCount = lib.followedArtists.size,
            lastRefreshed = formatDate(lib.lastRefreshedMs)
        )
    }

    private fun formatDate(ms: Long): String? {
        if (ms == 0L) return null
        val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        return date.format(DateTimeFormatter.ofPattern("MMM d"))
    }

    private fun buildErrorMessage(e: Exception, stepName: String): String {
        if (e is retrofit2.HttpException) {
            val body = try { e.response()?.errorBody()?.string() ?: "(no body)" } catch (_: Exception) { "(unreadable)" }
            Log.e(TAG, "HTTP ${e.code()} body at $stepName: $body")
            return when (e.code()) {
                429 -> "Spotify rate limit hit.\n\nWait 60 seconds then tap Try Again."
                401 -> "Session expired. Tap Log Out & Re-authorize."
                403 -> "Spotify 403 at $stepName.\n\nDiag: $body"
                else -> "HTTP ${e.code()} at step: $stepName.\n\nDiag: $body"
            }
        }
        return e.message ?: "Something went wrong. Please try again."
    }

    private fun progress(message: String, step: Int, totalSteps: Int) {
        _uiState.value = UiState.Building(message, step, totalSteps)
    }
}

// ── Factory ──────────────────────────────────────────────────────────────────

class MainViewModelFactory(
    private val authManager: SpotifyAuthManager,
    private val repository: SpotifyRepository,
    private val tokenStorage: TokenStorage,
    private val shuffleEngine: TrueShuffleEngine,
    private val trackCache: ArtistTrackCache,
    private val historyStorage: ShuffleHistoryStorage,
    private val appSettings: AppSettingsStorage,
    private val gapArtistCache: GapArtistCache
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MainViewModel(authManager, repository, tokenStorage, shuffleEngine, trackCache, historyStorage, appSettings, gapArtistCache) as T
}
