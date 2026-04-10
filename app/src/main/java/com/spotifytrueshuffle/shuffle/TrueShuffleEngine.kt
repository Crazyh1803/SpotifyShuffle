package com.spotifytrueshuffle.shuffle

import com.spotifytrueshuffle.api.Artist
import com.spotifytrueshuffle.api.Track
import kotlin.math.pow
import kotlin.random.Random

/**
 * True Shuffle Algorithm
 * ──────────────────────
 * The problem with Spotify's default shuffle: it heavily weights songs you've
 * already listened to a lot, so you hear the same artists over and over.
 *
 * This engine mimics the old iPod "Shuffle All" behavior:
 *   • Every followed artist has a chance to appear
 *   • Frequently heard artists are NOT over-represented
 *   • Rarely heard / undiscovered artists are surfaced more often
 *
 * Tier system (three tiers when discovery data is available):
 *   Tier A — user's top artists (frequently heard — included, but deprioritized)
 *   Tier B — followed artists with library tracks but NOT in top artists
 *             (somewhat familiar — appears often; 4× more slots than A)
 *   Tier C — "discovery" artists: followed artists whose tracks came ONLY from
 *             the gap-fill source (never liked/saved — pure discovery)
 *             (count controlled by discoveryBias slider, 0–100)
 *
 * The B:A ratio is always fixed at 4:1. Only the C count varies with the slider.
 *
 * Discovery bias → tier ratios (cPerCycle : bPerCycle : aPerCycle):
 *   0–15 %   →  0 : 4 : 1  (80 % B, 20 % A — no discovery)
 *   16–35 %  →  1 : 4 : 1  (17 % C, 67 % B, 17 % A)
 *   36–55 %  →  2 : 4 : 1  (29 % C, 57 % B, 14 % A)
 *   56–75 %  →  3 : 4 : 1  (38 % C, 50 % B, 13 % A)  ← default (bias = 60)
 *   76–90 %  →  5 : 4 : 1  (50 % C, 40 % B, 10 % A)
 *   91–100 % →  9 : 4 : 1  (64 % C, 29 % B,  7 % A)
 */
class TrueShuffleEngine {

    /**
     * Builds the playlist.
     *
     * @param followedArtists    Full list of artists the user follows
     * @param topArtistIds       IDs of the user's top artists (Tier A)
     * @param tracksByArtist     Map of artistId → list of their tracks
     * @param discoveryArtistIds Artists whose tracks came ONLY from gap-fill (Tier C).
     *                           Pass an empty set when not available.
     * @param likedTrackIds      Track IDs from the user's liked-songs list. For non-top
     *                           artists, selectTrack prefers tracks NOT in this set, so
     *                           the playlist surfaces unfamiliar songs where possible.
     * @param cooldownArtistIds  Artist IDs that appeared in the last N playlists. These
     *                           artists are excluded from the normal ordering and appended
     *                           at the end so they only appear if the playlist runs short.
     * @param cooldownTrackIds   Track IDs that appeared in the last N playlists. Even if
     *                           a cooldown artist is used as a fallback, their cooldown
     *                           tracks are de-prioritised in selectTrack.
     * @param discoveryBias      0–100 slider value controlling Tier C weight. Default 60.
     * @param targetDurationMs   Total duration to aim for (default 2 hours)
     * @return Ordered list of tracks for the playlist
     */
    fun buildPlaylist(
        followedArtists: List<Artist>,
        topArtistIds: Set<String>,
        tracksByArtist: Map<String, List<Track>>,
        discoveryArtistIds: Set<String> = emptySet(),
        likedTrackIds: Set<String> = emptySet(),
        cooldownArtistIds: Set<String> = emptySet(),
        cooldownTrackIds: Set<String> = emptySet(),
        discoveryBias: Int = 60,
        targetDurationMs: Long = 2L * 60 * 60 * 1000
    ): List<Track> {
        // Filter out non-music tracks (skits, interludes, etc.) from every artist's pool
        // before any selection logic runs. Falls back to the unfiltered list for artists
        // where filtering would leave them with zero tracks.
        val filteredTracksByArtist = tracksByArtist.mapValues { (_, tracks) ->
            val filtered = tracks.filter { !isNonMusicTrack(it.name) }
            filtered.ifEmpty { tracks }  // never leave an artist empty due to filtering
        }

        // Only keep artists for whom we actually have tracks
        val artistsWithTracks = followedArtists.filter {
            filteredTracksByArtist[it.id]?.isNotEmpty() == true
        }
        if (artistsWithTracks.isEmpty()) return emptyList()

        // Partition: artists on cooldown are placed after all fresh artists so they
        // only fill in if the playlist would otherwise fall short of targetDurationMs.
        val freshArtists = artistsWithTracks.filter { it.id !in cooldownArtistIds }
        val cooldownFallbackArtists = artistsWithTracks.filter { it.id in cooldownArtistIds }

        // Build the tier-interleaved order for fresh artists, then append cooldown as fallback.
        val orderedArtists = buildOrderedArtists(
            freshArtists, topArtistIds, discoveryArtistIds, discoveryBias
        ) + cooldownFallbackArtists.shuffled()

        // Pick one track per artist, stopping when we reach the target duration
        val playlist = mutableListOf<Track>()
        var totalMs = 0L

        for (artist in orderedArtists) {
            if (totalMs >= targetDurationMs) break
            val tracks = filteredTracksByArtist[artist.id] ?: continue
            val track = selectTrack(
                tracks,
                isRareArtist = artist.id !in topArtistIds,
                likedTrackIds = likedTrackIds,
                cooldownTrackIds = cooldownTrackIds
            )
            playlist.add(track)
            totalMs += track.durationMs
        }

        // Second pass if we're still short: allow repeat artists, avoid exact same track
        if (totalMs < targetDurationMs) {
            val usedTrackIds = playlist.map { it.id }.toMutableSet()
            for (artist in orderedArtists.shuffled()) {
                if (totalMs >= targetDurationMs) break
                val remaining = filteredTracksByArtist[artist.id]?.filter { it.id !in usedTrackIds }
                if (remaining.isNullOrEmpty()) continue
                val track = remaining.random()
                playlist.add(track)
                usedTrackIds.add(track.id)
                totalMs += track.durationMs
            }
        }

        return playlist
    }

    /**
     * Maps a 0–100 discovery bias value to tier cycle counts (cPerCycle, bPerCycle, aPerCycle).
     * bPerCycle and aPerCycle are always fixed at 4 and 1 to maintain the 4:1 B:A preference.
     * Only cPerCycle increases as the slider moves right.
     *
     * @return Triple(cPerCycle, bPerCycle, aPerCycle)
     */
    fun computeTierWeights(bias: Int): Triple<Int, Int, Int> {
        val cPerCycle = when {
            bias <= 15  -> 0
            bias <= 35  -> 1
            bias <= 55  -> 2
            bias <= 75  -> 3
            bias <= 90  -> 5
            else        -> 9
        }
        return Triple(cPerCycle, 4, 1)
    }

    /**
     * Splits [artists] into up to three tiers and interleaves them so discovery
     * artists appear most often, top artists least often.
     */
    private fun buildOrderedArtists(
        artists: List<Artist>,
        topArtistIds: Set<String>,
        discoveryArtistIds: Set<String>,
        discoveryBias: Int = 60
    ): List<Artist> {
        val tierA = artists.filter { it.id in topArtistIds }.shuffled()
        val tierC = artists.filter { it.id in discoveryArtistIds }.shuffled()
        val tierB = artists.filter { it.id !in topArtistIds && it.id !in discoveryArtistIds }.shuffled()

        return if (tierC.isNotEmpty()) {
            val (cPerCycle, bPerCycle, aPerCycle) = computeTierWeights(discoveryBias)
            if (cPerCycle == 0) {
                // Bias is so low that C is disabled — treat same as no-discovery path
                interleave(tierA, tierB, aPerCycle = aPerCycle, bPerCycle = bPerCycle)
            } else {
                interleave3(tierC, tierB, tierA, cPerCycle = cPerCycle, bPerCycle = bPerCycle, aPerCycle = aPerCycle)
            }
        } else {
            // No discovery artists — use B:A = 4:1 interleave
            interleave(tierA, tierB, aPerCycle = 1, bPerCycle = 4)
        }
    }

    /**
     * Interleaves two lists in repeating cycles:
     * [bPerCycle] items from [b] then [aPerCycle] items from [a], until both exhausted.
     */
    private fun <T> interleave(
        a: List<T>,
        b: List<T>,
        aPerCycle: Int,
        bPerCycle: Int
    ): List<T> {
        val result = mutableListOf<T>()
        var ai = 0
        var bi = 0
        while (ai < a.size || bi < b.size) {
            repeat(bPerCycle) { if (bi < b.size) result.add(b[bi++]) }
            repeat(aPerCycle) { if (ai < a.size) result.add(a[ai++]) }
        }
        return result
    }

    /**
     * Interleaves three lists in repeating cycles:
     * [cPerCycle] items from [c], then [bPerCycle] from [b], then [aPerCycle] from [a],
     * until all three lists are exhausted.
     */
    private fun <T> interleave3(
        c: List<T>, b: List<T>, a: List<T>,
        cPerCycle: Int, bPerCycle: Int, aPerCycle: Int
    ): List<T> {
        val result = mutableListOf<T>()
        var ci = 0; var bi = 0; var ai = 0
        while (ci < c.size || bi < b.size || ai < a.size) {
            repeat(cPerCycle) { if (ci < c.size) result.add(c[ci++]) }
            repeat(bPerCycle) { if (bi < b.size) result.add(b[bi++]) }
            repeat(aPerCycle) { if (ai < a.size) result.add(a[ai++]) }
        }
        return result
    }

    /**
     * Selects one track from the artist's track list using a three-level preference:
     *
     *   1. Fresh + non-liked  (not in cooldown AND not an explicitly liked song)
     *   2. Fresh only         (not in cooldown, but may be liked)      [fallback]
     *   3. All tracks         (everything, including cooldown tracks)  [last resort]
     *
     * Pool selection differs by tier:
     *   Tier B/C (isRareArtist = true)  — prefers non-liked tracks first (surfaces album
     *       deep cuts the user hasn't explicitly liked), then falls back to all non-cooldown,
     *       then all tracks.
     *   Tier A  (isRareArtist = false)  — skips the liked-track filter (top artists are
     *       expected favourites; filtering out liked tracks would leave very little), but
     *       still respects cooldown and applies the same depth-cut bias.
     *
     * Both tiers then apply an x² distribution over tracks sorted by ascending popularity
     * so lower-popularity (deep-cut) songs are more likely than chart hits.
     *
     * Edge case: album-sourced tracks all have popularity = 0 (the simplified-track API
     * response has no popularity field). When all tracks share the same popularity score
     * the sort order is arbitrary and the x² bias would cluster at the first few entries.
     * In that case we fall back to uniform random so every track is equally likely.
     */
    private fun selectTrack(
        tracks: List<Track>,
        isRareArtist: Boolean,
        likedTrackIds: Set<String> = emptySet(),
        cooldownTrackIds: Set<String> = emptySet()
    ): Track {
        // Build candidate pool based on tier.
        val pool = if (isRareArtist) {
            // Tier B / C: prefer non-liked tracks to surface album deep cuts
            tracks
                .filter { it.id !in cooldownTrackIds && it.id !in likedTrackIds }
                .ifEmpty { tracks.filter { it.id !in cooldownTrackIds } }
                .ifEmpty { tracks }
        } else {
            // Tier A: include liked tracks (all top-artist songs are fair game),
            // still exclude recent cooldown tracks
            tracks.filter { it.id !in cooldownTrackIds }
                .ifEmpty { tracks }
        }

        if (pool.size == 1) return pool[0]

        val sorted = pool.sortedBy { it.popularity }

        // If every track has the same popularity (common for album-sourced gap-artist tracks
        // where the API returns no score and we default to 0), the x² index would skew
        // heavily toward the first entry. Use uniform random instead.
        if (sorted.first().popularity == sorted.last().popularity) {
            return pool.random()
        }

        // x² biases toward index 0 (least popular = deepest cut).
        val rawIdx = (Random.nextDouble().pow(2.0) * sorted.size).toInt()
        return sorted[rawIdx.coerceIn(0, sorted.size - 1)]
    }

    /**
     * Returns true if the track name looks like a non-music filler track that should
     * be excluded from playlists. Matches on whole words so "Interlude" doesn't catch
     * a song legitimately titled e.g. "Prelude to a Kiss".
     *
     * Matched terms (case-insensitive): skit, interlude, intro, outro, reprise,
     * spoken word, spoken, transition, commentary.
     */
    private fun isNonMusicTrack(name: String): Boolean {
        val lower = name.lowercase()
        val noiseWords = listOf(
            "skit", "interlude", "intro", "outro", "reprise",
            "spoken word", "spoken", "transition", "commentary"
        )
        return noiseWords.any { word ->
            // Match as a whole word or surrounded by punctuation/parens
            Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(lower)
        }
    }
}
