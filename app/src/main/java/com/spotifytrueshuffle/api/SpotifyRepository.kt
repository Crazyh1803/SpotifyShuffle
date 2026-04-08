package com.spotifytrueshuffle.api

import android.util.Log
import com.spotifytrueshuffle.auth.SpotifyAuthManager
import com.spotifytrueshuffle.auth.TokenStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SpotifyRepository"

/** Top-artist gap slots — top artists with no saved music get tracks added but remain in Tier A only.
 *  Capped separately from discovery so top artists can't crowd out true discovery candidates. */
private const val MAX_TOP_GAP_ARTISTS = 25

/** Discovery-artist gap slots — followed artists that are NOT top artists and have no saved music.
 *  These are the true Tier C candidates; 75 gives plenty of variety for any playlist length. */
private const val MAX_DISCOVERY_GAP_ARTISTS = 75

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
 * Output of [SpotifyRepository.buildTrackPool].
 *
 * @param tracksByArtist      artistId → de-duplicated list of their tracks
 * @param discoveryArtistIds  artists whose tracks came ONLY from the gap-fill
 *                            source (GET /artists/{id}/top-tracks). These are
 *                            artists the user follows but has never liked or saved
 *                            anything from — pure discovery candidates for the
 *                            shuffle engine's Tier C.
 * @param likedTrackIds       track IDs that came from the user's liked-songs list
 *                            (GET /me/tracks, source 2). Used by the shuffle engine
 *                            to prefer non-liked tracks when selecting songs for
 *                            Tier B and Tier C artists.
 */
data class TrackPool(
    val tracksByArtist: Map<String, List<Track>>,
    val discoveryArtistIds: Set<String>,
    val likedTrackIds: Set<String>
)

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
     * Builds a map of artistId → their tracks.
     *
     * Sources, in order:
     *   1. GET /me/top/tracks × 3 time ranges (up to 150 tracks) — user-top-read
     *   2. GET /me/tracks     paginated liked songs (up to 500)   — user-library-read
     *   3. GET /me/albums     first 50 liked albums (~500+ tracks) — user-library-read
     *   4. GET /artists/{id}/top-tracks for "gap" artists — followed artists with
     *      zero tracks after sources 1-3. Capped at 50 artists; bails out early if
     *      the endpoint returns 3 consecutive 403s (Spotify dev-mode restriction).
     *      If the endpoint IS available, this surfaces songs the user has never liked.
     *
     * Tracks are de-duplicated per artist. artists[0].id is the grouping key.
     *
     * @param followedArtistIds IDs of all followed artists; used to identify gap
     *                          artists for source 4. Pass empty to skip source 4.
     * @param topArtistIds      IDs of the user's top artists (Tier A). Used in
     *                          source 4b to decide which non-top artists to supplement
     *                          with fresh top-tracks. Pass empty to skip source 4b.
     */
    suspend fun buildTrackPool(
        followedArtists: List<Artist> = emptyList(),
        topArtistIds: Set<String> = emptySet(),
        market: String? = null
    ): TrackPool {
        val followedArtistIds = followedArtists.map { it.id }
        val artistNameById     = followedArtists.associate { it.id to it.name }
        ensureValidToken()
        val trackMap = mutableMapOf<String, MutableList<Track>>()
        val likedTrackIds = mutableSetOf<String>()

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

        // 2. Saved ("liked") tracks — fully paginated (no cap).
        //    Track IDs collected here go into likedTrackIds so the shuffle engine
        //    can de-prioritise them for non-top artists in favour of fresh tracks.
        var offset = 0
        while (true) {
            try {
                val page = api.getSavedTracks(limit = 50, offset = offset)
                page.items.forEach {
                    addTrack(it.track)
                    likedTrackIds.add(it.track.id)
                }
                offset += page.items.size
                if (page.next == null || page.items.isEmpty()) break
            } catch (e: retrofit2.HttpException) {
                Log.w(TAG, "getSavedTracks(offset=$offset) failed: ${e.code()}")
                break
            }
        }
        Log.d(TAG, "Liked songs: $offset tracks → ${trackMap.size} artists so far")

        // 3. Saved ("liked") albums — fully paginated through ALL saved albums.
        //    Albums often cover followed artists who have no individually liked songs, which is
        //    exactly the gap we need to fill so the shuffle can reach more of the artist library.
        //    SimplifiedTrack has no popularity field, so we default to 0 (treated as a deep cut
        //    by the shuffle engine — a reasonable default for less-explored music).
        try {
            var albumOffset = 0
            var albumTrackCount = 0
            var albumCount = 0
            while (true) {
                val albumPage = api.getSavedAlbums(limit = 50, offset = albumOffset)
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
                albumCount += albumPage.items.size
                albumOffset += albumPage.items.size
                if (albumPage.next == null || albumPage.items.isEmpty()) break
            }
            Log.d(TAG, "Saved albums: $albumTrackCount tracks from $albumCount albums → ${trackMap.size} artists so far")
        } catch (e: retrofit2.HttpException) {
            Log.w(TAG, "getSavedAlbums failed: ${e.code()}")
        }

        // 4. Gap artists — followed artists with zero track coverage after sources 1–3.
        //    Split into two groups to preserve correct tier semantics:
        //      • topGapIds:       top artists with no saved music — get tracks but stay in Tier A only.
        //      • discoveryGapIds: non-top artists with no saved music — true Tier C candidates.
        //    Running both groups together in one coroutineScope + Semaphore(5) keeps the scan
        //    fast (~10-20 s for 100 artists). On 429 we skip immediately — no waits.
        val discoveryArtistIds = mutableSetOf<String>()
        if (followedArtistIds.isNotEmpty()) {
            val allGapIds = followedArtistIds.filter { it !in trackMap.keys }

            // Top artists that landed in gap fill: user streams them but hasn't saved music.
            // Shuffled so no fixed ordering bias from the API cursor. These stay Tier A.
            val topGapIds = allGapIds.filter { it in topArtistIds }.shuffled().take(MAX_TOP_GAP_ARTISTS)

            // True discovery candidates: followed, not top, no saved music → Tier C.
            val discoveryGapIds = allGapIds.filter { it !in topArtistIds }.shuffled().take(MAX_DISCOVERY_GAP_ARTISTS)

            val allFetchIds = topGapIds + discoveryGapIds
            Log.d(TAG, "Gap artists: ${allGapIds.size} total → " +
                "fetching ${topGapIds.size} top-gap + ${discoveryGapIds.size} discovery-gap")

            val topTracksBlocked = AtomicBoolean(false)
            val semaphore = Semaphore(5)

            val gapResults: List<List<Track>> = coroutineScope {
                allFetchIds.map { artistId ->
                    async {
                        semaphore.withPermit {
                            val found = mutableListOf<Track>()
                            // Try top-tracks unless blocked by a 403; skip immediately on 429
                            if (!topTracksBlocked.get()) {
                                try {
                                    found.addAll(api.getArtistTopTracks(artistId).tracks)
                                } catch (e: retrofit2.HttpException) {
                                    when (e.code()) {
                                        429  -> Log.w(TAG, "Rate limited on $artistId — skipping")
                                        403  -> topTracksBlocked.set(true)
                                        else -> Log.w(TAG, "getArtistTopTracks($artistId) ${e.code()}")
                                    }
                                }
                            }
                            // Search fallback when top-tracks returned nothing; skip on 429
                            if (found.isEmpty() && market != null) {
                                val name = artistNameById[artistId]
                                if (name != null) {
                                    try {
                                        found.addAll(
                                            api.searchTracks(
                                                query = "artist:\"$name\"",
                                                market = market, limit = 10
                                            ).tracks.items
                                        )
                                    } catch (e: retrofit2.HttpException) {
                                        Log.w(TAG, "searchTracks($artistId) ${e.code()} — skipping")
                                    }
                                }
                            }
                            found
                        }
                    }
                }.awaitAll()
            }

            val fetchedByArtist = allFetchIds.zip(gapResults).toMap()

            // Top-gap merge: add tracks to pool only.
            // These artists are already Tier A via topArtistIds — do NOT add to discoveryArtistIds
            // or they'll be double-assigned to both Tier A and Tier C, inflating their frequency.
            topGapIds.forEach { artistId ->
                fetchedByArtist[artistId]?.forEach { addTrack(it) }
            }

            // Discovery-gap merge: add tracks to pool AND register as Tier C.
            discoveryGapIds.forEach { artistId ->
                fetchedByArtist[artistId]?.forEach { addTrack(it) }
                if (trackMap.containsKey(artistId)) discoveryArtistIds.add(artistId)
            }

            Log.d(TAG, "Gap fill complete: ${discoveryArtistIds.size} Tier C artists, " +
                "pool now ${trackMap.size} artists")
        }

        val result = trackMap.mapValues { (_, tracks) -> tracks.distinctBy { it.id } }
        Log.d(TAG, "Track pool: ${result.values.sumOf { it.size }} tracks for ${result.size} artists")
        return TrackPool(
            tracksByArtist = result,
            discoveryArtistIds = discoveryArtistIds,
            likedTrackIds = likedTrackIds
        )
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
