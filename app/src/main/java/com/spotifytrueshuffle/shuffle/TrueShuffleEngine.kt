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
     * @param cooldownSongKeys   Song keys (from [TrackUtils.songKey]) that appeared in the
     *                           last N playlists. Versions of the same song share a key, so
     *                           "Jane Says" and "Jane Says (Live)" are both suppressed when
     *                           either has appeared recently.
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
        cooldownSongKeys: Set<String> = emptySet(),
        discoveryBias: Int = 60,
        targetDurationMs: Long = 2L * 60 * 60 * 1000
    ): List<Track> {
        // Only keep artists for whom we actually have tracks
        val artistsWithTracks = followedArtists.filter {
            tracksByArtist[it.id]?.isNotEmpty() == true
        }
        if (artistsWithTracks.isEmpty()) return emptyList()

        // Partition: artists on cooldown are placed after all fresh artists so they
        // only fill in if the playlist would otherwise fall short of targetDurationMs.
        val freshArtists = artistsWithTracks.filter { it.id !in cooldownArtistIds }
        val cooldownFallbackArtists = artistsWithTracks.filter { it.id in cooldownArtistIds }

        // Build the tier-interleaved order for fresh artists, then append cooldown fallbacks.
        // Fallback artists also go through buildOrderedArtists so the discovery bias and
        // tier weighting still apply even when most of the library is on cooldown.
        val orderedArtists = buildOrderedArtists(
            freshArtists, topArtistIds, discoveryArtistIds, discoveryBias
        ) + buildOrderedArtists(
            cooldownFallbackArtists, topArtistIds, discoveryArtistIds, discoveryBias
        )

        // Pick one track per artist, stopping when we reach the target duration
        val playlist = mutableListOf<Track>()
        var totalMs = 0L

        for (artist in orderedArtists) {
            if (totalMs >= targetDurationMs) break
            val tracks = tracksByArtist[artist.id] ?: continue
            // If every track this artist has is on song cooldown, skip them in the first
            // pass — they'll be picked up in the second pass only if needed to hit the
            // target duration. This prevents single-track artists from forcing the same
            // song into back-to-back playlists.
            if (tracks.all { t ->
                    TrackUtils.songKey(t.name, t.artists.firstOrNull()?.id ?: "") in cooldownSongKeys
                }) continue
            val track = selectTrack(
                tracks,
                isRareArtist = artist.id !in topArtistIds,
                likedTrackIds = likedTrackIds,
                cooldownSongKeys = cooldownSongKeys
            )
            playlist.add(track)
            totalMs += track.durationMs
        }

        // Second pass if we're still short: allow repeat artists, avoid exact same track
        if (totalMs < targetDurationMs) {
            val usedTrackIds = playlist.map { it.id }.toMutableSet()
            for (artist in orderedArtists.shuffled()) {
                if (totalMs >= targetDurationMs) break
                val remaining = tracksByArtist[artist.id]?.filter { it.id !in usedTrackIds }
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
     * Top artists (isRareArtist = false) skip the liked/cooldown filtering and just
     * pick uniformly at random — they're already deprioritised by tier ordering.
     *
     * For non-top artists the chosen pool is then sorted by ascending popularity and
     * sampled with an x² distribution so lower-popularity (deep-cut) tracks are
     * more likely — index 0 (least popular) is most probable.
     */
    private fun selectTrack(
        tracks: List<Track>,
        isRareArtist: Boolean,
        likedTrackIds: Set<String> = emptySet(),
        cooldownSongKeys: Set<String> = emptySet()
    ): Track {
        if (!isRareArtist) return tracks.random()

        // Three-level fallback so we always return something.
        // Cooldown is checked via song key so live/remastered versions count as the same song.
        val pool = tracks
            .filter { t -> TrackUtils.songKey(t.name, t.artists.firstOrNull()?.id ?: "") !in cooldownSongKeys
                        && t.id !in likedTrackIds }
            .ifEmpty { tracks.filter { t -> TrackUtils.songKey(t.name, t.artists.firstOrNull()?.id ?: "") !in cooldownSongKeys } }
            .ifEmpty { tracks }

        if (pool.size == 1) return pool[0]

        val sorted = pool.sortedBy { it.popularity }
        // x² biases toward 0; coerce to valid index range
        val rawIdx = (Random.nextDouble().pow(2.0) * sorted.size).toInt()
        val idx = rawIdx.coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
