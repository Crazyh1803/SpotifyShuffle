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

    /**
     * Returns the artist's top 10 tracks.
     * [market] should be the user's ISO 3166-1 alpha-2 country code (from getUserProfile).
     * Passing it explicitly avoids a 403 on accounts where Spotify cannot infer the market
     * from the token alone (e.g. some developer-mode apps).
     * Throws on network/API errors so the caller can decide how to handle each failure.
     */
    suspend fun getArtistTopTracks(artistId: String, market: String): List<Track> {
        ensureValidToken()
        return api.getArtistTopTracks(artistId, market).tracks
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
