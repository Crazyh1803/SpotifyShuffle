package com.spotifytrueshuffle.api

import android.util.Log
import com.spotifytrueshuffle.auth.SpotifyAuthManager
import com.spotifytrueshuffle.auth.TokenStorage

private const val TAG = "SpotifyRepository"

/**
 * Carries an HTTP error code, the response body, and selected response headers
 * all the way up to the UI layer without double-reading the OkHttp buffer.
 *
 * Background: OkHttp's ResponseBody.string() can only be called once. If we read
 * it inside the repository (to log it) and then throw retrofit2.HttpException, the
 * caller can't read the body again — it comes back empty. This class sidesteps that
 * by embedding the already-read body in the exception itself.
 */
class SpotifyApiError(
    val httpCode: Int,
    val responseBody: String,
    val responseHeaders: String
) : Exception("Spotify $httpCode: $responseBody")

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

        // 3. Saved ("liked") albums — fetch first 50 albums, each includes its track listing.
        //    Albums often cover followed artists who have no individually liked songs, which is
        //    exactly the gap we need to fill so the shuffle can reach more of the artist library.
        //    SimplifiedTrack has no popularity field, so we default to 0 (treated as a deep cut
        //    by the shuffle engine — a reasonable default for less-explored music).
        try {
            val albumPage = api.getSavedAlbums(limit = 50, offset = 0)
            var albumTrackCount = 0
            for (savedAlbum in albumPage.items) {
                val alb = savedAlbum.album
                val albumSimple = AlbumSimple(
                    id = alb.id, name = alb.name,
                    releaseDate = alb.releaseDate, images = alb.images
                )
                alb.tracks.items
                    .filter { !it.isLocal && it.uri.startsWith("spotify:track:") }
                    .forEach { st ->
                        addTrack(Track(
                            id = st.id, name = st.name, durationMs = st.durationMs,
                            popularity = 0, uri = st.uri, artists = st.artists,
                            album = albumSimple, previewUrl = st.previewUrl
                        ))
                        albumTrackCount++
                    }
            }
            Log.d(TAG, "Saved albums: $albumTrackCount tracks from ${albumPage.items.size} albums")
        } catch (e: retrofit2.HttpException) {
            Log.w(TAG, "getSavedAlbums failed: ${e.code()}")
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
            isPublic = false  // private — uses playlist-modify-private; avoids 403s on public playlists in dev mode
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
            val code = response.code()
            val body = try { response.errorBody()?.string() ?: "(no body)" } catch (_: Exception) { "(unreadable)" }
            val headers = captureKeyHeaders(response.headers())
            Log.w(TAG, "replacePlaylistTracks $code — body=$body headers=$headers")
            if (code == 404 || code == 403) return false
            throw SpotifyApiError(code, body, headers)
        }
        Log.d(TAG, "replacePlaylistTracks succeeded for $playlistId")
        return true
    }

    /**
     * Appends tracks to a playlist via POST /playlists/{id}/tracks.
     * Used as a fallback when PUT (replacePlaylistTracks) is blocked (403).
     * Returns true on success, throws SpotifyApiError on error.
     */
    suspend fun addTracksToPlaylist(playlistId: String, trackUris: List<String>): Boolean {
        ensureValidToken()
        Log.d(TAG, "addTracksToPlaylist: playlistId=$playlistId uris=${trackUris.size}")
        val response = api.addTracksToPlaylist(
            playlistId = playlistId,
            body = TracksBody(trackUris.take(100))
        )
        if (!response.isSuccessful) {
            val code = response.code()
            val body = try { response.errorBody()?.string() ?: "(no body)" } catch (_: Exception) { "(unreadable)" }
            val headers = captureKeyHeaders(response.headers())
            Log.w(TAG, "addTracksToPlaylist $code — body=$body headers=$headers")
            throw SpotifyApiError(code, body, headers)
        }
        Log.d(TAG, "addTracksToPlaylist succeeded for $playlistId")
        return true
    }

    /** Extracts the most diagnostic response headers into a compact string for error display. */
    private fun captureKeyHeaders(headers: okhttp3.Headers): String {
        // These headers reveal whether the rejection came from Spotify's API servers,
        // a CDN (Cloudflare), or a proxy (WAF/load balancer).
        val interesting = listOf(
            "www-authenticate",   // bearer realm + error — reveals token validation failure
            "content-type",       // application/json from Spotify; text/html from a proxy
            "cf-ray",             // Cloudflare ray ID — present if Cloudflare rejected
            "x-request-id",       // Spotify request ID — present if Spotify's API handled it
            "retry-after",        // rate limit disguised as 403
            "x-content-type-options"
        )
        return headers.toMultimap()
            .filterKeys { it.lowercase() in interesting }
            .entries
            .joinToString(" | ") { (k, v) -> "$k: ${v.joinToString()}" }
            .ifEmpty { "(no key headers)" }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private suspend fun ensureValidToken() {
        val ok = authManager.refreshTokenIfNeeded()
        if (!ok) throw IllegalStateException("Spotify session expired — please log in again")
    }
}
