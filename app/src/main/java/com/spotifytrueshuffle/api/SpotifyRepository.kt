package com.spotifytrueshuffle.api

import android.util.Log
import com.spotifytrueshuffle.auth.SpotifyAuthManager
import com.spotifytrueshuffle.auth.TokenStorage
import com.spotifytrueshuffle.cache.GapArtistEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SpotifyRepository"

/** Maximum gap artists to scan per build. Keeps each run well under 1 minute. */
private const val BATCH_SIZE = 100

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
 * Return type for [SpotifyRepository.buildTrackPool] in incremental-cache mode.
 *
 * @param pool            The full track pool ready for the shuffle engine.
 * @param newlyScanned    Entries fetched during this build (caller merges + saves to disk).
 * @param totalGapArtists Total number of gap artists (covered by cache + newly scanned + skipped).
 * @param totalCached     Gap artists already in the cache before this build started.
 */
data class TrackPoolBuildResult(
    val pool: TrackPool,
    val newlyScanned: Map<String, GapArtistEntry>,
    val totalGapArtists: Int,
    val totalCached: Int
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
    /**
     * Builds the full track pool from four sources with incremental gap-artist caching.
     *
     *   1. User's top tracks (3 time ranges) — always re-fetched
     *   2. Liked songs — fully paginated, always re-fetched
     *   3. Saved albums — fully paginated, always re-fetched
     *   4. Gap fill — followed artists with zero coverage after 1-3:
     *        a. Load cached entries instantly (no API calls) → adds their tracks to the pool
     *        b. Scan up to [batchSize] unscanned/stale artists → results returned for caller to cache
     *
     *    Gap artists split into two groups to preserve correct tier semantics:
     *      • Top-gap     : in topArtistIds, no saved music → tracks added, stay Tier A only
     *      • Discovery   : not a top artist, no saved music → tracks added, marked Tier C
     *
     *    Uses /artists/{id}/top-tracks first; falls back to /search on 403.
     *    Skips on 429 immediately — skipped artists remain unscanned and picked up next build.
     *
     * @param cachedEntries       Per-artist cache loaded by caller before invocation.
     * @param batchSize           Max new artists to scan this build (default [BATCH_SIZE]).
     * @param rescanThresholdMs   Entries older than this (ms) are treated as stale and re-scanned
     *                            after all completely-unscanned artists. Long.MAX_VALUE = manual only.
     */
    suspend fun buildTrackPool(
        followedArtists: List<Artist> = emptyList(),
        topArtistIds: Set<String> = emptySet(),
        market: String? = null,
        cachedEntries: Map<String, GapArtistEntry> = emptyMap(),
        batchSize: Int = BATCH_SIZE,
        rescanThresholdMs: Long = Long.MAX_VALUE,
        /** Called each time a new artist scan starts — artist name, artists scanned so far, total to scan. */
        onScanProgress: ((artistName: String, scanned: Int, total: Int) -> Unit)? = null
    ): TrackPoolBuildResult {
        ensureValidToken()
        val followedArtistIds = followedArtists.map { it.id }
        val artistNameById = followedArtists.associate { it.id to it.name }

        val trackMap = mutableMapOf<String, MutableList<Track>>()
        val likedTrackIds = mutableSetOf<String>()

        // Buckets a track under its primary artist (sources 1-3).
        fun addTrack(track: Track) {
            val id = track.artists.firstOrNull()?.id ?: return
            trackMap.getOrPut(id) { mutableListOf() }.add(track)
        }

        // Buckets a track under a specific artist ID regardless of track.artists ordering.
        // Used in gap fill so search results land under the artist we searched for.
        fun addTrackForArtist(track: Track, artistId: String) {
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
        Log.d(TAG, "After top tracks: ${trackMap.size} artists in pool")

        // 2. Liked songs — fully paginated (no cap).
        var likedOffset = 0
        while (true) {
            try {
                val page = api.getSavedTracks(limit = 50, offset = likedOffset)
                page.items.forEach {
                    addTrack(it.track)
                    likedTrackIds.add(it.track.id)
                }
                likedOffset += page.items.size
                if (page.next == null || page.items.isEmpty()) break
            } catch (e: retrofit2.HttpException) {
                Log.w(TAG, "getSavedTracks(offset=$likedOffset) failed: ${e.code()}")
                break
            }
        }
        Log.d(TAG, "After liked songs ($likedOffset tracks): ${trackMap.size} artists in pool")

        // 3. Saved albums — fully paginated.
        try {
            var albumOffset = 0
            var albumTrackCount = 0
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
                albumOffset += albumPage.items.size
                if (albumPage.next == null || albumPage.items.isEmpty()) break
            }
            Log.d(TAG, "After saved albums ($albumTrackCount tracks, $albumOffset albums): ${trackMap.size} artists")
        } catch (e: retrofit2.HttpException) {
            Log.w(TAG, "getSavedAlbums failed: ${e.code()}")
        }

        // 4. Gap fill — followed artists with zero coverage after sources 1-3.
        //
        //    Step 4a: Load cached entries instantly. For each gap artist already in the cache,
        //    add their stored tracks to the pool immediately — no API calls needed.
        //    Step 4b: Identify unscanned (not in cache or scannedAtMs==0) and stale (older than
        //    rescanThresholdMs) artists. Scan the next batch, prioritising unscanned first.
        val discoveryArtistIds = mutableSetOf<String>()
        val newlyScanned = mutableMapOf<String, GapArtistEntry>()

        if (followedArtistIds.isNotEmpty()) {
            // All gap artists = followed artists not covered by sources 1-3
            val allGapIds = followedArtistIds.filter { it !in trackMap.keys }
            val totalGapArtists = allGapIds.size

            // 4a: Load cached entries — instant, no API calls.
            // Only entries with scannedAtMs > 0 AND non-empty tracks are considered "loaded".
            // Entries with empty tracks (e.g. from a previous market-filter bug) are skipped
            // here so the unscannedIds filter can pick them up for retry in 4b.
            val nowMs = System.currentTimeMillis()
            var loadedFromCache = 0
            for (artistId in allGapIds) {
                val entry = cachedEntries[artistId]
                if (entry != null && entry.scannedAtMs > 0L && entry.tracks.isNotEmpty()) {
                    entry.tracks.forEach { addTrackForArtist(it, artistId) }
                    // Recalculate tier from current topArtistIds (top artist list changes over time)
                    if (artistId !in topArtistIds) {
                        discoveryArtistIds.add(artistId)
                    }
                    loadedFromCache++
                }
            }
            Log.d(TAG, "Gap fill 4a: $loadedFromCache/${totalGapArtists} loaded from cache instantly")

            // 4b: Build the scan batch.
            // Unscanned: gap artists not in cache at all, scannedAtMs==0, OR cached but empty.
            // The "cached but empty" case catches artists that were scanned when the album
            // strategy had a market filter bug — those scans all came back empty and were saved
            // with a valid timestamp, but should be retried now that the bug is fixed.
            val unscannedIds = allGapIds.filter { artistId ->
                val entry = cachedEntries[artistId]
                entry == null || entry.scannedAtMs == 0L || entry.tracks.isEmpty()
            }.shuffled()

            // Stale: in cache with a real timestamp, but older than rescanThresholdMs
            val staleIds = allGapIds.filter { artistId ->
                val entry = cachedEntries[artistId]
                entry != null && entry.scannedAtMs > 0L && entry.scannedAtMs < nowMs - rescanThresholdMs
            }.shuffled()

            // Take up to batchSize, unscanned first
            val toScan = (unscannedIds + staleIds).take(batchSize)
            Log.d(TAG, "Gap fill 4b: ${unscannedIds.size} unscanned + ${staleIds.size} stale → " +
                "scanning ${toScan.size} this build")

            if (toScan.isNotEmpty()) {
                val topTracksBlocked = AtomicBoolean(false)
                val semaphore = Semaphore(2)   // 2 concurrent — keeps burst rate well under Spotify's limit

                val scanResults: List<List<Track>> = coroutineScope {
                    toScan.mapIndexed { index, artistId ->
                        async {
                            // Stagger launch: spread starts 100ms apart so we don't slam the API
                            // with 100 requests simultaneously. Effective rate ≈ 2 req/sec.
                            delay(index * 100L)
                            semaphore.withPermit {
                                val artistName = artistNameById[artistId] ?: artistId
                                onScanProgress?.invoke(artistName, index + 1, toScan.size)
                                val found = mutableListOf<Track>()

                                // ── Strategy 1: album-based (preferred) ─────────────────────
                                // Picks 2 random albums/singles from the artist's discography
                                // and fetches their full track lists. This surfaces genuine
                                // variety — not just the same 10 popularity-ranked hits every time.
                                // On each rescan cycle different albums are selected, so the
                                // cache rotates naturally.
                                //
                                // NOTE: getArtistAlbums is called WITHOUT a market filter so we
                                // see all of the artist's releases globally. Passing market here
                                // filters to albums licensed in that country — many niche artists
                                // have zero albums available in some markets, making every scan
                                // return empty. Market is still passed to getAlbumTracks below
                                // so only tracks that are actually playable are added to the pool.
                                try {
                                    val albumsPage = api.getArtistAlbums(
                                        artistId = artistId,
                                        includeGroups = "album,single",
                                        limit = 20
                                        // no market — fetch all global releases
                                    )
                                    if (albumsPage.items.isNotEmpty()) {
                                        // Shuffle so we pick different albums each rescan
                                        val selectedAlbums = albumsPage.items.shuffled().take(2)
                                        for (album in selectedAlbums) {
                                            try {
                                                val tracksPage = api.getAlbumTracks(
                                                    albumId = album.id,
                                                    limit = 50,
                                                    market = market  // playback filter — only tracks listenable in user's market
                                                )
                                                tracksPage.items
                                                    .filter { !it.isLocal && it.uri.startsWith("spotify:track:") }
                                                    .forEach { st ->
                                                        found.add(Track(
                                                            id = st.id,
                                                            name = st.name,
                                                            durationMs = st.durationMs,
                                                            popularity = 0, // simplified track — no score
                                                            uri = st.uri,
                                                            artists = st.artists,
                                                            album = AlbumSimple(
                                                                id = album.id,
                                                                name = album.name,
                                                                releaseDate = album.releaseDate,
                                                                images = album.images
                                                            ),
                                                            previewUrl = st.previewUrl
                                                        ))
                                                    }
                                            } catch (e: retrofit2.HttpException) {
                                                Log.w(TAG, "getAlbumTracks(${album.id}) ${e.code()} — skipping album")
                                            }
                                        }
                                    }
                                } catch (e: retrofit2.HttpException) {
                                    when (e.code()) {
                                        429  -> return@withPermit found  // rate limit — bail immediately
                                        else -> Log.w(TAG, "getArtistAlbums($artistId) ${e.code()} — falling back")
                                    }
                                }

                                // ── Strategy 2: top-tracks fallback ─────────────────────────
                                // Fires when album fetch returned nothing (e.g. market unavailable,
                                // artist has no albums/singles, or API error). Top-tracks are
                                // popularity-ranked hits, so this is the less-preferred path.
                                if (found.isEmpty() && !topTracksBlocked.get()) {
                                    try {
                                        found.addAll(api.getArtistTopTracks(artistId, market = market).tracks)
                                    } catch (e: retrofit2.HttpException) {
                                        when (e.code()) {
                                            429  -> return@withPermit found
                                            403  -> topTracksBlocked.set(true)
                                            else -> Log.w(TAG, "getArtistTopTracks($artistId) ${e.code()}")
                                        }
                                    }
                                }

                                // ── Strategy 3: search fallback ─────────────────────────────
                                // Last resort — fires when top-tracks is blocked (403) and
                                // album fetch gave nothing.
                                if (found.isEmpty() && market != null) {
                                    val name = artistNameById[artistId]
                                    if (name != null) {
                                        try {
                                            val results = api.searchTracks(
                                                query = "artist:\"$name\"",
                                                market = market,
                                                limit = 10
                                            ).tracks.items
                                            found.addAll(results.filter { track ->
                                                track.artists.any { it.id == artistId }
                                            })
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

                // Merge scan results into pool and build newlyScanned map.
                // Shuffle + take(40) so we store a randomised sample rather than the first N
                // tracks from whatever album happened to come back first.
                toScan.zip(scanResults).forEach { (artistId, tracks) ->
                    val isDiscovery = artistId !in topArtistIds
                    // Always mark as scanned (nowMs) even when tracks is empty.
                    // Empty means either no accessible tracks or a 429 skip — either way,
                    // retrying every single build wastes API calls and freezes the progress
                    // counter. These artists will be re-tried on the next explicit rescan
                    // (user taps "Scan for new tracks") or when the auto-rescan interval fires.
                    val scannedAtMs = nowMs
                    val dedupedTracks = tracks.distinctBy { it.id }.shuffled().take(40)
                    newlyScanned[artistId] = GapArtistEntry(
                        tracks = dedupedTracks,
                        isDiscovery = isDiscovery,
                        scannedAtMs = scannedAtMs
                    )
                    if (dedupedTracks.isNotEmpty()) {
                        dedupedTracks.forEach { addTrackForArtist(it, artistId) }
                        if (isDiscovery) discoveryArtistIds.add(artistId)
                    }
                }
            }

            Log.d(TAG, "Gap fill complete: ${newlyScanned.size} newly scanned, " +
                "${discoveryArtistIds.size} Tier C artists, pool now ${trackMap.size} artists")

            val result = trackMap.mapValues { (_, tracks) -> tracks.distinctBy { it.id } }
            Log.d(TAG, "Track pool: ${result.values.sumOf { it.size }} tracks for ${result.size} artists")
            return TrackPoolBuildResult(
                pool = TrackPool(
                    tracksByArtist = result,
                    discoveryArtistIds = discoveryArtistIds,
                    likedTrackIds = likedTrackIds
                ),
                newlyScanned = newlyScanned,
                totalGapArtists = totalGapArtists,
                totalCached = loadedFromCache
            )
        }

        // No followed artists — return pool built from sources 1-3 only
        val result = trackMap.mapValues { (_, tracks) -> tracks.distinctBy { it.id } }
        Log.d(TAG, "Track pool (no followed artists): ${result.values.sumOf { it.size }} tracks")
        return TrackPoolBuildResult(
            pool = TrackPool(
                tracksByArtist = result,
                discoveryArtistIds = emptySet(),
                likedTrackIds = likedTrackIds
            ),
            newlyScanned = emptyMap(),
            totalGapArtists = 0,
            totalCached = 0
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
