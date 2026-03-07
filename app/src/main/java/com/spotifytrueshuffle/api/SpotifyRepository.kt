package com.spotifytrueshuffle.api

import android.util.Log
import com.spotifytrueshuffle.auth.SpotifyAuthManager
import com.spotifytrueshuffle.auth.TokenStorage

private const val TAG = "SpotifyRepository"

/**
 * Single source of truth for all Spotify data.
 * Handles pagination, token refresh, and error recovery.
 */
class SpotifyRepository(
    private val api: SpotifyApiService,
    private val authManager: SpotifyAuthManager,
    private val tokenStorage: TokenStorage
) {

    // ── User ─────────────────────────────────────────────────────────────────

    suspend fun getUserProfile(): UserProfile {
        ensureValidToken()
        return api.getUserProfile()
    }

    // ── Artists ───────────────────────────────────────────────────────────────

    /**
     * Fetches ALL artists the user follows, handling cursor-based pagination.
     * Spotify returns max 50 per page.
     */
    suspend fun getAllFollowedArtists(): List<Artist> {
        ensureValidToken()
        val artists = mutableListOf<Artist>()
        var after: String? = null

        while (true) {
            val response = api.getFollowedArtists(after = after)
            artists.addAll(response.artists.items)
            Log.d(TAG, "Followed artists fetched: ${artists.size} / ${response.artists.total}")

            after = response.artists.cursors?.after
            if (after == null) break
        }

        return artists
    }

    /**
     * Returns the union of the user's long-term and medium-term top artists.
     * These are the "frequently heard" tier for the shuffle algorithm.
     */
    suspend fun getTopArtists(): List<Artist> {
        ensureValidToken()
        val topArtists = mutableListOf<Artist>()

        val longTerm = api.getTopArtists(timeRange = "long_term", limit = 50)
        topArtists.addAll(longTerm.items)

        val existingIds = topArtists.map { it.id }.toSet()
        val mediumTerm = api.getTopArtists(timeRange = "medium_term", limit = 50)
        topArtists.addAll(mediumTerm.items.filter { it.id !in existingIds })

        Log.d(TAG, "Top artists (long + medium term): ${topArtists.size}")
        return topArtists
    }

    // Latched to false the first time top-tracks returns 403 so all subsequent
    // artists skip straight to the recommendations fallback (avoids 130 wasted calls).
    private var topTracksAvailable = true

    /**
     * Returns up to 10 tracks for the artist.
     *
     * Primary: GET /artists/{id}/top-tracks
     * Fallback: GET /recommendations?seed_artists={id}  (used when top-tracks 403s —
     *           a known restriction on some Spotify dev-mode apps)
     *
     * Once top-tracks is found to be unavailable the fallback is used for ALL
     * subsequent artists in the same session (no wasted retry calls).
     */
    suspend fun getArtistTopTracks(artistId: String, market: String): List<Track> {
        ensureValidToken()
        if (topTracksAvailable) {
            try {
                return api.getArtistTopTracks(artistId, market).tracks
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 403) {
                    Log.w(TAG, "top-tracks returned 403 — switching all artists to recommendations fallback")
                    topTracksAvailable = false
                    // fall through to recommendations below
                } else throw e
            }
        }
        // Fallback: recommendations seeded from this artist
        return api.getRecommendations(seedArtistId = artistId, market = market).tracks
    }

    // ── Playlist ──────────────────────────────────────────────────────────────

    suspend fun createPlaylist(userId: String, description: String): Playlist {
        ensureValidToken()
        return api.createPlaylist(
            userId = userId,
            request = CreatePlaylistRequest(
                name = com.spotifytrueshuffle.SpotifyConfig.PLAYLIST_NAME,
                description = description,
                isPublic = false
            )
        )
    }

    /**
     * Replaces the tracks in an existing playlist.
     * Returns false if the playlist was deleted (404), so the caller can create a new one.
     */
    suspend fun replacePlaylistTracks(playlistId: String, trackUris: List<String>): Boolean {
        ensureValidToken()
        // Spotify accepts max 100 URIs per PUT call; our 2-hour playlist is ~30 tracks
        val response = api.replacePlaylistTracks(
            playlistId = playlistId,
            body = TracksBody(trackUris.take(100))
        )
        if (!response.isSuccessful) {
            Log.w(TAG, "replacePlaylistTracks failed: ${response.code()}")
        }
        return response.isSuccessful
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private suspend fun ensureValidToken() {
        val ok = authManager.refreshTokenIfNeeded()
        if (!ok) throw IllegalStateException("Spotify session expired — please log in again")
    }
}
