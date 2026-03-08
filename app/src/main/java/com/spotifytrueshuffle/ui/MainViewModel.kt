package com.spotifytrueshuffle.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spotifytrueshuffle.SpotifyConfig
import com.spotifytrueshuffle.api.Artist
import com.spotifytrueshuffle.api.SpotifyRepository
import com.spotifytrueshuffle.api.Track
import com.spotifytrueshuffle.auth.SpotifyAuthManager
import com.spotifytrueshuffle.auth.TokenStorage
import com.spotifytrueshuffle.cache.ArtistLibrary
import com.spotifytrueshuffle.cache.ArtistTrackCache
import com.spotifytrueshuffle.shuffle.TrueShuffleEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "MainViewModel"

class MainViewModel(
    private val authManager: SpotifyAuthManager,
    private val repository: SpotifyRepository,
    private val tokenStorage: TokenStorage,
    private val shuffleEngine: TrueShuffleEngine,
    private val artistCache: ArtistTrackCache
) : ViewModel() {

    sealed class UiState {
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
            val artistCount: Int
        ) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.NotLoggedIn)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        _uiState.value = if (authManager.isLoggedIn()) loggedInState() else UiState.NotLoggedIn
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

                // Step 3 — build track pool from /me/ endpoints (no per-artist calls)
                stepName = "buildTrackPool"
                progress("Scanning your Spotify library for tracks…", 3, 4)
                val tracksByArtist = repository.buildTrackPool()
                Log.d(TAG, "Track pool covers ${tracksByArtist.size} artists")

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
                val topArtistIds = library.topArtistIds.toSet()
                val tracks = shuffleEngine.buildPlaylist(
                    followedArtists = library.followedArtists,
                    topArtistIds = topArtistIds,
                    tracksByArtist = tracksByArtist,
                    targetDurationMs = SpotifyConfig.TARGET_DURATION_MS
                )

                if (tracks.isEmpty()) {
                    _uiState.value = UiState.Error(
                        "None of your followed artists had tracks in your library.\n\n" +
                        "Save songs from artists you follow, then tap Try Again."
                    )
                    return@launch
                }

                val playlistUrl = savePlaylist(user.id, tracks)
                val totalDurationMs = tracks.sumOf { it.durationMs.toLong() }
                val artistsRepresented = tracks.flatMap { it.artists }.map { it.id }.toSet().size

                _uiState.value = UiState.Success(
                    playlistUrl = playlistUrl,
                    trackCount = tracks.size,
                    durationMinutes = (totalDurationMs / 60_000).toInt(),
                    artistCount = artistsRepresented
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
                "Tap \"Log Out & Re-authorize\" below, then Build again.\n\n" +
                "modify-public=$hasModPub  modify-private=$hasModPriv\n" +
                "Body: ${e.responseBody}\n" +
                "Headers: ${e.responseHeaders}"
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
    private val trackCache: ArtistTrackCache
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MainViewModel(authManager, repository, tokenStorage, shuffleEngine, trackCache) as T
}
