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
 *   • Rarely heard artists are surfaced more often
 *   • The final order is randomized so it doesn't cluster by artist
 *
 * Tier system:
 *   Tier A — artists in the user's long-term OR medium-term top 50
 *             (frequently heard — included, but deprioritized)
 *   Tier B — followed artists NOT in the user's top artists
 *             (rarely heard — given more slots in the playlist)
 *
 * Ratio: for every 1 Tier-A track we include ~2 Tier-B tracks.
 */
class TrueShuffleEngine {

    /**
     * Builds the playlist.
     *
     * @param followedArtists  Full list of artists the user follows
     * @param topArtistIds     IDs of the user's top artists (Tier A)
     * @param tracksByArtist   Map of artistId → list of their top tracks
     * @param targetDurationMs Total duration to aim for (default 2 hours)
     * @return Ordered list of tracks for the playlist
     */
    fun buildPlaylist(
        followedArtists: List<Artist>,
        topArtistIds: Set<String>,
        tracksByArtist: Map<String, List<Track>>,
        targetDurationMs: Long = 2L * 60 * 60 * 1000
    ): List<Track> {
        // Only keep artists for whom we actually fetched tracks
        val artistsWithTracks = followedArtists.filter {
            tracksByArtist[it.id]?.isNotEmpty() == true
        }
        if (artistsWithTracks.isEmpty()) return emptyList()

        // Split into tiers and independently shuffle each so neither tier
        // is always front-loaded
        val tierA = artistsWithTracks.filter { it.id in topArtistIds }.shuffled()
        val tierB = artistsWithTracks.filter { it.id !in topArtistIds }.shuffled()

        // Interleave: B, B, A, B, B, A, … (2:1 rare-to-frequent ratio)
        val orderedArtists = interleave(tierA, tierB, aPerCycle = 1, bPerCycle = 2)

        // Pick one track per artist, stopping when we reach the target duration
        val playlist = mutableListOf<Track>()
        var totalMs = 0L

        for (artist in orderedArtists) {
            if (totalMs >= targetDurationMs) break
            val tracks = tracksByArtist[artist.id] ?: continue
            val track = selectTrack(tracks, isRareArtist = artist.id !in topArtistIds)
            playlist.add(track)
            totalMs += track.durationMs
        }

        // Second pass if we're still short (user follows very few artists):
        // allow repeat artists but not the exact same track
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
     * Selects one track from the artist's track list.
     *
     * Rarely-heard artists: bias toward lower-popularity tracks (deep cuts).
     *   Uses x² distribution so index 0 (least popular) is most likely.
     *
     * Frequently-heard artists: uniform random selection across all top tracks.
     */
    private fun selectTrack(tracks: List<Track>, isRareArtist: Boolean): Track {
        if (tracks.size == 1 || !isRareArtist) return tracks.random()

        val sorted = tracks.sortedBy { it.popularity }
        // x² biases toward 0; coerce to valid index range
        val rawIdx = (Random.nextDouble().pow(2.0) * sorted.size).toInt()
        val idx = rawIdx.coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
