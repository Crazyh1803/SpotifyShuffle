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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "MainViewModel"

/** How many artists to sample tracks for on each playlist build. */
private const val TIER_A_SAMPLE = 6   // top/frequent artists
private const val TIER_B_SAMPLE = 9   // followed but not top
private const val TRACK_FETCH_DELAY_MS = 1_000L
private const val RATE_LIMIT_MAX_WAIT_SEC = 60L  // skip artist if Retry-After > this

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
     * 1. Get user profile (market for catalog lookups).
     * 2. If artist library is not cached yet, fetch it first (fast — just artist names/IDs).
     * 3. Pick a random sample of ~15 artists from the library.
     * 4. Fetch tracks for only those ~15 artists (~15s with 1s delays).
     * 5. Build and save the playlist.
     *
     * Because we pick a fresh random sample on every build, each playlist is different
     * even though the artist library stays cached between runs.
     */
    fun buildPlaylist() {
        viewModelScope.launch {
            var stepName = "initializing"
            try {
                // Step 1 — user profile
                stepName = "getUserProfile"
                progress("Getting your Spotify profile…", 1, 4)
                val user = repository.getUserProfile()
                val market = user.country ?: "US"

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

                // Step 3 — fetch tracks for a random sample of artists
                stepName = "fetchTracks"
                val topArtistIds = library.topArtistIds.toSet()
                val tierA = library.followedArtists.filter { it.id in topArtistIds }
                    .shuffled().take(TIER_A_SAMPLE)
                val tierB = library.followedArtists.filter { it.id !in topArtistIds }
                    .shuffled().take(TIER_B_SAMPLE)
                val sample = (tierA + tierB).shuffled()
                Log.d(TAG, "Sampling ${sample.size} artists (${tierA.size} tier-A, ${tierB.size} tier-B)")

                val (tracksByArtist, fetchDiag) = fetchTracksForArtists(sample, market, progressStep = 3, progressTotal = 4)
                if (tracksByArtist.isEmpty()) {
                    val hint = when {
                        fetchDiag.http403 > 0 -> "Tap \"Log Out & Re-authorize\" to refresh permissions."
                        fetchDiag.rateLimitSkips > 0 -> "Wait 60 seconds and tap Try Again."
                        fetchDiag.empty > 0 -> "Search returned no tracks. Try tapping Build again."
                        else -> "Wait 60 seconds and tap Try Again."
                    }
                    _uiState.value = UiState.Error(
                        "No tracks loaded from ${sample.size} artists.\n" +
                        "[${fetchDiag.summary()}]\n\n$hint"
                    )
                    return@launch
                }

                // Step 4 — shuffle + save to Spotify
                stepName = "savePlaylist"
                progress("Assembling your true shuffle playlist…", 4, 4)
                val tracks = shuffleEngine.buildPlaylist(
                    followedArtists = library.followedArtists,
                    topArtistIds = topArtistIds,
                    tracksByArtist = tracksByArtist,
                    targetDurationMs = SpotifyConfig.TARGET_DURATION_MS
                )

                if (tracks.isEmpty()) {
                    _uiState.value = UiState.Error(
                        "Not enough tracks to build a playlist.\nTry following more artists."
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
     * Fetches top tracks for [artists] one at a time with [TRACK_FETCH_DELAY_MS] between calls.
     *
     * Rate-limit (429) strategy:
     * - Read Retry-After header
     * - If ≤ [RATE_LIMIT_MAX_WAIT_SEC]: wait and retry once
     * - If > [RATE_LIMIT_MAX_WAIT_SEC]: skip immediately and move to the next artist
     *   (we have enough artists in the sample to absorb skips)
     */
    private data class FetchDiagnostics(
        val success: Int, val empty: Int, val http403: Int, val rateLimitSkips: Int, val other: Int
    ) {
        fun summary() = "ok=$success empty=$empty 403=$http403 429-skipped=$rateLimitSkips err=$other"
    }

    private suspend fun fetchTracksForArtists(
        artists: List<Artist>,
        market: String,
        progressStep: Int,
        progressTotal: Int
    ): Pair<Map<String, List<Track>>, FetchDiagnostics> {
        val result = mutableMapOf<String, List<Track>>()
        var success = 0; var empty = 0; var http403 = 0; var rateLimitSkips = 0; var other = 0

        for ((index, artist) in artists.withIndex()) {
            progress(
                "Fetching tracks ${index + 1}/${artists.size} — ${artist.name}…",
                progressStep,
                progressTotal
            )
            try {
                val tracks = repository.getArtistTopTracks(artist.id, artist.name, market)
                if (tracks.isNotEmpty()) { result[artist.id] = tracks; success++ } else empty++
            } catch (e: retrofit2.HttpException) {
                when (e.code()) {
                    404 -> { empty++; Log.d(TAG, "Artist ${artist.name} not found, skipping") }
                    403 -> { http403++; Log.w(TAG, "HTTP 403 for ${artist.name}, skipping") }
                    429 -> {
                        // Default 30s when Spotify omits Retry-After.
                        val retryAfterSec = e.response()
                            ?.headers()?.get("Retry-After")?.toLongOrNull() ?: 30L
                        if (retryAfterSec <= RATE_LIMIT_MAX_WAIT_SEC) {
                            Log.w(TAG, "Rate limited on ${artist.name} — waiting ${retryAfterSec}s, retrying")
                            progress(
                                "Spotify rate limit — resuming in ${retryAfterSec}s…",
                                progressStep, progressTotal
                            )
                            delay(retryAfterSec * 1_000L)
                            try {
                                val tracks = repository.getArtistTopTracks(artist.id, artist.name, market)
                                if (tracks.isNotEmpty()) { result[artist.id] = tracks; success++ } else empty++
                            } catch (e2: retrofit2.HttpException) {
                                rateLimitSkips++
                                Log.w(TAG, "Retry failed for ${artist.name}: HTTP ${e2.code()}, skipping")
                            } catch (e2: Exception) {
                                other++
                                Log.w(TAG, "Retry failed for ${artist.name}: ${e2.message}, skipping")
                            }
                        } else {
                            rateLimitSkips++
                            Log.w(TAG, "Rate limited on ${artist.name} (Retry-After=${retryAfterSec}s > ${RATE_LIMIT_MAX_WAIT_SEC}s) — skipping")
                        }
                    }
                    else -> { other++; Log.w(TAG, "HTTP ${e.code()} for ${artist.name}, skipping") }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                other++
                Log.w(TAG, "Exception for ${artist.name}: ${e.message}, skipping")
            }

            delay(TRACK_FETCH_DELAY_MS)
        }

        val diag = FetchDiagnostics(success, empty, http403, rateLimitSkips, other)
        Log.d(TAG, "Track fetch complete: ${diag.summary()}")
        return Pair(result, diag)
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

        val existingId = tokenStorage.playlistId
        if (existingId != null) {
            val updated = repository.replacePlaylistTracks(existingId, uris)
            if (updated) {
                Log.d(TAG, "Playlist $existingId updated")
                return "https://open.spotify.com/playlist/$existingId"
            }
            Log.w(TAG, "Stored playlist $existingId no longer accessible, creating new one")
            tokenStorage.playlistId = null
        }

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
        repository.replacePlaylistTracks(playlist.id, uris)
        Log.d(TAG, "New playlist created: ${playlist.id}")
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
