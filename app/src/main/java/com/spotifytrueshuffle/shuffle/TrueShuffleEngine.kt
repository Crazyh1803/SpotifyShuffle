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
 *             (somewhat familiar — appears occasionally)
 *   Tier C — "discovery" artists: followed artists whose tracks came ONLY from
 *             the gap-fill source (never liked/saved — pure discovery)
 *             (given the most slots)
 *
 * Ratios:
 *   With Tier C:    C:B:A = 3:1:1 per cycle → 60 % discovery, 20 % familiar, 20 % top
 *   Without Tier C: B:A   = 4:1   per cycle → 80 % non-top   (up from the old 67 %)
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

        // Build the tier-interleaved order for fresh artists, then append cooldown as fallback.
        val orderedArtists = buildOrderedArtists(freshArtists, topArtistIds, discoveryArtistIds) +
            cooldownFallbackArtists.shuffled()

        // Pick one track per artist, stopping when we reach the target duration
        val playlist = mutableListOf<Track>()
        var totalMs = 0L

        for (artist in orderedArtists) {
            if (totalMs >= targetDurationMs) break
            val tracks = tracksByArtist[artist.id] ?: continue
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
     * Splits [artists] into up to three tiers and interleaves them so discovery
     * artists appear most often, top artists least often.
     */
    private fun buildOrderedArtists(
        artists: List<Artist>,
        topArtistIds: Set<String>,
        discoveryArtistIds: Set<String>
    ): List<Artist> {
        val tierA = artists.filter { it.id in topArtistIds }.shuffled()
        val tierC = artists.filter { it.id in discoveryArtistIds }.shuffled()
        val tierB = artists.filter { it.id !in topArtistIds && it.id !in discoveryArtistIds }.shuffled()

        return if (tierC.isNotEmpty()) {
            // C, C, C, B, A, C, C, C, B, A, … — heavy discovery bias
            interleave3(tierC, tierB, tierA, cPerCycle = 3, bPerCycle = 1, aPerCycle = 1)
        } else {
            // B, B, B, B, A, B, B, B, B, A, … — strongly non-top
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
        cooldownTrackIds: Set<String> = emptySet()
    ): Track {
        if (!isRareArtist) return tracks.random()

        // Three-level fallback so we always return something.
        val pool = tracks
            .filter { it.id !in cooldownTrackIds && it.id !in likedTrackIds }
            .ifEmpty  { tracks.filter { it.id !in cooldownTrackIds } }
            .ifEmpty  { tracks }

        if (pool.size == 1) return pool[0]

        val sorted = pool.sortedBy { it.popularity }
        // x² biases toward 0; coerce to valid index range
        val rawIdx = (Random.nextDouble().pow(2.0) * sorted.size).toInt()
        val idx = rawIdx.coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
