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
import com.spotifytrueshuffle.shuffle.TrueShuffleEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val TAG = "MainViewModel"

class MainViewModel(
    private val authManager: SpotifyAuthManager,
    private val repository: SpotifyRepository,
    private val tokenStorage: TokenStorage,
    private val shuffleEngine: TrueShuffleEngine
) : ViewModel() {

    sealed class UiState {
        object NotLoggedIn : UiState()
        object LoggedIn : UiState()
        data class Building(val progress: String, val step: Int, val totalSteps: Int = 5) : UiState()
        data class Success(
            val playlistUrl: String,
            val trackCount: Int,
            val durationMinutes: Int,
            val artistCount: Int
        ) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(
        if (authManager.isLoggedIn()) UiState.LoggedIn else UiState.NotLoggedIn
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ── Auth ─────────────────────────────────────────────────────────────────

    fun handleAuthCallback(code: String) {
        viewModelScope.launch {
            val success = authManager.handleCallback(code)
            _uiState.value = if (success) {
                UiState.LoggedIn
            } else {
                UiState.Error("Login failed — please try again")
            }
        }
    }

    fun logout() {
        tokenStorage.clearAll()
        _uiState.value = UiState.NotLoggedIn
    }

    fun dismissError() {
        _uiState.value = if (authManager.isLoggedIn()) UiState.LoggedIn else UiState.NotLoggedIn
    }

    // ── Playlist Build ───────────────────────────────────────────────────────

    fun buildPlaylist() {
        viewModelScope.launch {
            var stepName = "initializing"
            try {
                // Step 1: User profile
                stepName = "getUserProfile"
                progress("Getting your Spotify profile…", 1)
                val user = repository.getUserProfile()

                // Step 2: Followed artists
                stepName = "getAllFollowedArtists"
                progress("Loading your followed artists…", 2)
                val followedArtists = repository.getAllFollowedArtists()
                Log.d(TAG, "Followed artists: ${followedArtists.size}")

                if (followedArtists.isEmpty()) {
                    _uiState.value = UiState.Error(
                        "You're not following any artists on Spotify yet.\n" +
                        "Follow some artists and try again!"
                    )
                    return@launch
                }

                // Step 3: Top artists (to identify tiers)
                stepName = "getTopArtists"
                progress("Identifying your listening tiers…", 3)
                val topArtists = repository.getTopArtists()
                val topArtistIds = topArtists.map { it.id }.toSet()

                // Step 4: Fetch tracks (biggest step — rate-limit aware)
                // Always pass a non-null market so Spotify can resolve the correct regional
                // catalog. Fall back to "US" if the profile didn't return a country (which
                // can happen when the token is missing user-read-private scope).
                val market = user.country ?: "US"
                Log.d(TAG, "User country: ${user.country}, using market: $market")
                stepName = "fetchTracksForSample"
                val tracksByArtist = fetchTracksForSample(followedArtists, topArtistIds, market)

                // Step 5: Build playlist & save to Spotify
                stepName = "savePlaylist"
                progress("Assembling your true shuffle playlist…", 5)
                val tracks = shuffleEngine.buildPlaylist(
                    followedArtists = followedArtists,
                    topArtistIds = topArtistIds,
                    tracksByArtist = tracksByArtist,
                    targetDurationMs = SpotifyConfig.TARGET_DURATION_MS
                )

                if (tracks.isEmpty()) {
                    _uiState.value = UiState.Error("Not enough tracks to build a playlist. Try following more artists.")
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
                Log.e(TAG, "Playlist build failed at $stepName", e)
                val msg = if (e is retrofit2.HttpException) {
                    "HTTP ${e.code()} at step: $stepName\n\n" +
                    "Tap Log Out & Re-authorize, make sure to tap Agree on the Spotify screen."
                } else {
                    e.message ?: "Something went wrong. Please try again."
                }
                _uiState.value = UiState.Error(msg)
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Fetches top tracks for a representative sample of the user's followed artists.
     *
     * Rate-limit strategy:
     * - Up to 50 Tier-A + 80 Tier-B artists sampled (130 requests max)
     * - 1-second pause every 25 requests
     * - Per-artist failures are skipped, but the real error is captured and thrown
     *   if ALL artists fail so we surface the root cause instead of a vague message.
     */
    private suspend fun fetchTracksForSample(
        followedArtists: List<Artist>,
        topArtistIds: Set<String>,
        market: String
    ): Map<String, List<Track>> {
        val tierA = followedArtists.filter { it.id in topArtistIds }.shuffled().take(50)
        val tierB = followedArtists.filter { it.id !in topArtistIds }.shuffled().take(80)
        val sample = (tierA + tierB).shuffled()

        val result = mutableMapOf<String, List<Track>>()
        var firstError: Exception? = null
        var skipCount = 0

        for ((index, artist) in sample.withIndex()) {
            progress(
                "Sampling tracks… ${index + 1} / ${sample.size} artists",
                step = 4,
                totalSteps = 5
            )
            try {
                val tracks = repository.getArtistTopTracks(artist.id, artist.name, market)
                if (tracks.isNotEmpty()) result[artist.id] = tracks
            } catch (e: retrofit2.HttpException) {
                when (e.code()) {
                    404 -> Log.d(TAG, "Artist ${artist.name} not found, skipping")
                    429 -> {
                        // Rate-limited — back off 2 s and retry once
                        Log.w(TAG, "Rate limited on ${artist.name}, backing off…")
                        delay(2_000)
                        try {
                            val tracks = repository.getArtistTopTracks(artist.id, artist.name, market)
                            if (tracks.isNotEmpty()) result[artist.id] = tracks
                        } catch (e2: Exception) {
                            if (firstError == null) firstError = e2
                            skipCount++
                        }
                    }
                    else -> {
                        Log.w(TAG, "HTTP ${e.code()} for artist ${artist.name}: ${e.message()}")
                        if (firstError == null) firstError = e
                        skipCount++
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // Always rethrow — lets coroutine cancellation work properly
            } catch (e: Exception) {
                Log.w(TAG, "Exception for artist ${artist.name}: ${e.javaClass.simpleName}: ${e.message}")
                if (firstError == null) firstError = e
                skipCount++
            }

            // Brief pause every 25 requests
            if ((index + 1) % 25 == 0) delay(1_000)
        }

        Log.d(TAG, "Tracks fetched for ${result.size} / ${sample.size} artists ($skipCount skipped)")

        if (result.isEmpty()) {
            // Surface the actual root-cause error instead of a generic message
            val cause = when (val e = firstError) {
                is retrofit2.HttpException -> {
                    // Read the Spotify error body so we can see the exact reason
                    val body = try {
                        e.response()?.errorBody()?.string() ?: "(no body)"
                    } catch (_: Exception) { "(unreadable)" }
                    Log.e(TAG, "Spotify 403 body: $body")
                    when (e.code()) {
                        403 -> "Spotify API 403 Forbidden on both top-tracks and recommendations.\n\nDiag: market=$market\n\nPlease tap Log Out, go to spotify.com/account/apps, remove this app, then reconnect and tap Agree."
                        401 -> "Spotify session expired. Please log out and log back in."
                        429 -> "Spotify rate limit hit. Wait a minute and try again."
                        else -> "Spotify API error ${e.code()}: $body"
                    }
                }
                is java.io.IOException ->
                    "Network error: ${e.message}\nCheck your internet connection."
                null ->
                    "All ${sample.size} artists returned empty track lists. " +
                    "Make sure you follow some artists on Spotify."
                else ->
                    "${e.javaClass.simpleName}: ${e.message}"
            }
            throw Exception(cause)
        }

        return result
    }

    /**
     * Persists the playlist to Spotify.
     * Re-uses the same playlist ID stored in TokenStorage so refreshing
     * the playlist doesn't create a new one each time.
     */
    private suspend fun savePlaylist(userId: String, tracks: List<Track>): String {
        val uris = tracks.map { it.uri }
        val description = "True shuffle of ${tracks.flatMap { it.artists }.map { it.id }.toSet().size}" +
            " artists — generated ${LocalDate.now()}"

        val existingId = tokenStorage.playlistId
        if (existingId != null) {
            val updated = repository.replacePlaylistTracks(existingId, uris)
            if (updated) {
                Log.d(TAG, "Playlist $existingId updated")
                return "https://open.spotify.com/playlist/$existingId"
            }
            // 404 — playlist was deleted; fall through to create a new one
            Log.w(TAG, "Stored playlist $existingId not found, creating new one")
            tokenStorage.playlistId = null
        }

        val playlist = repository.createPlaylist(userId, description)
        tokenStorage.playlistId = playlist.id
        repository.replacePlaylistTracks(playlist.id, uris)
        Log.d(TAG, "New playlist created: ${playlist.id}")
        return playlist.externalUrls?.spotify ?: "https://open.spotify.com/playlist/${playlist.id}"
    }

    private fun progress(message: String, step: Int, totalSteps: Int = 5) {
        _uiState.value = UiState.Building(message, step, totalSteps)
    }
}

// ── Factory ──────────────────────────────────────────────────────────────────

class MainViewModelFactory(
    private val authManager: SpotifyAuthManager,
    private val repository: SpotifyRepository,
    private val tokenStorage: TokenStorage,
    private val shuffleEngine: TrueShuffleEngine
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MainViewModel(authManager, repository, tokenStorage, shuffleEngine) as T
}
