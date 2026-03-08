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
     * Builds a map of artistId → their tracks using only /me/ endpoints.
     *
     * Sources (all require no per-artist calls, no rate-limit issues):
     *   • GET /me/top/tracks   × 3 time ranges (up to 150 tracks) — user-top-read
     *   • GET /me/tracks       paginated up to 500 saved songs     — user-library-read
     *
     * Tracks are de-duplicated per artist. The primary artist of each track
     * (artists[0]) is used as the grouping key.
     */
    suspend fun buildTrackPool(): Map<String, List<Track>> {
        ensureValidToken()
        val trackMap = mutableMapOf<String, MutableList<Track>>()

        fun addTrack(track: Track) {
            val artistId = track.artists.firstOrNull()?.id ?: return
            trackMap.getOrPut(artistId) { mutableListOf() }.add(track)
        }

        // 1. Top tracks — long / medium / short term
        for (range in listOf("long_term", "medium_term", "short_term")) {
            try {
                api.getTopTracks(timeRange = range, limit = 50).items.forEach { addTrack(it) }
            } catch (e: retrofit2.HttpException) {
                Log.w(TAG, "getTopTracks($range) failed: ${e.code()}")
            }
        }

        // 2. Saved ("liked") tracks — paginated, cap at 500
        var offset = 0
        while (offset < 500) {
            try {
                val page = api.getSavedTracks(limit = 50, offset = offset)
                page.items.forEach { addTrack(it.track) }
                offset += page.items.size
                if (page.next == null || page.items.isEmpty()) break
            } catch (e: retrofit2.HttpException) {
                Log.w(TAG, "getSavedTracks(offset=$offset) failed: ${e.code()}")
                break
            }
        }

        val result = trackMap.mapValues { (_, tracks) -> tracks.distinctBy { it.id } }
        Log.d(TAG, "Track pool: ${result.values.sumOf { it.size }} tracks for ${result.size} artists")
        return result
    }

    // ── Playlist ──────────────────────────────────────────────────────────────

    suspend fun createPlaylist(userId: String, description: String): Playlist {
        ensureValidToken()
        val request = CreatePlaylistRequest(
            name = com.spotifytrueshuffle.SpotifyConfig.PLAYLIST_NAME,
            description = description,
            isPublic = true   // public — requires only playlist-modify-public scope
        )

        // Prefer POST /me/playlists — it uses the token identity directly and avoids
        // the 403 that POST /users/{id}/playlists returns for non-/me/ paths in dev mode.
        return try {
            val playlist = api.createPlaylistForMe(request)
            Log.d(TAG, "createPlaylistForMe succeeded: ${playlist.id}")
            playlist
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 404) {
                // /me/playlists endpoint not available — fall back to user_id path
                Log.w(TAG, "POST me/playlists → 404, falling back to users/{id}/playlists")
                api.createPlaylist(userId = userId, request = request)
            } else {
                throw e
            }
        }
    }

    /**
     * Replaces the tracks in a playlist via PUT /playlists/{id}/tracks.
     * Returns false for 404 (deleted) or 403 (stale/forbidden) so the caller can react.
     * Throws for any other HTTP error.
     */
    suspend fun replacePlaylistTracks(playlistId: String, trackUris: List<String>): Boolean {
        ensureValidToken()
        Log.d(TAG, "replacePlaylistTracks: playlistId=$playlistId uris=${trackUris.size}")
        val response = api.replacePlaylistTracks(
            playlistId = playlistId,
            body = TracksBody(trackUris.take(100))
        )
        if (!response.isSuccessful) {
            val body = try { response.errorBody()?.string() ?: "(no body)" } catch (_: Exception) { "(unreadable)" }
            Log.w(TAG, "replacePlaylistTracks failed: ${response.code()} — $body")
            if (response.code() == 404 || response.code() == 403) return false
            throw retrofit2.HttpException(response)
        }
        Log.d(TAG, "replacePlaylistTracks succeeded for $playlistId")
        return true
    }

    /**
     * Appends tracks to a playlist via POST /playlists/{id}/tracks.
     * Used as a fallback when PUT (replacePlaylistTracks) is blocked (403).
     * Returns true on success, throws on error.
     */
    suspend fun addTracksToPlaylist(playlistId: String, trackUris: List<String>): Boolean {
        ensureValidToken()
        Log.d(TAG, "addTracksToPlaylist: playlistId=$playlistId uris=${trackUris.size}")
        val response = api.addTracksToPlaylist(
            playlistId = playlistId,
            body = TracksBody(trackUris.take(100))
        )
        if (!response.isSuccessful) {
            val body = try { response.errorBody()?.string() ?: "(no body)" } catch (_: Exception) { "(unreadable)" }
            Log.w(TAG, "addTracksToPlaylist failed: ${response.code()} — $body")
            throw retrofit2.HttpException(response)
        }
        Log.d(TAG, "addTracksToPlaylist succeeded for $playlistId")
        return true
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private suspend fun ensureValidToken() {
        val ok = authManager.refreshTokenIfNeeded()
        if (!ok) throw IllegalStateException("Spotify session expired — please log in again")
    }
}
