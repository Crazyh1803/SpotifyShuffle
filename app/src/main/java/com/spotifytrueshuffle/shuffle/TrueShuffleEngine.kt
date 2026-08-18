package com.spotifytrueshuffle.shuffle

import com.spotifytrueshuffle.api.Artist
import com.spotifytrueshuffle.api.Track

/**
 * The outcome of a build.
 *
 * [shortOfTarget] matters because the song cooldown is a hard constraint: when every remaining
 * track is inside the user's no-repeat window the build legitimately comes up short rather than
 * repeating a song. Callers surface that instead of quietly handing back a short playlist.
 */
data class ShuffleResult(
    val tracks: List<Track>,
    val shortOfTarget: Boolean,
    val durationMs: Long,
)

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

    companion object {
        /** Tracks shorter than this are treated as fragments/interstitials and filtered out. */
        const val MIN_TRACK_MS = 60_000L

        /**
         * Tracks longer than this are treated as outliers (silence/joke tracks, sprawling live
         * jams) and filtered out — a single 30-minute track would blow the duration budget.
         * Set generously (20 min) so legitimate prog / post-rock epics still qualify.
         */
        const val MAX_TRACK_MS = 20L * 60 * 1000

        /** Rough average track length, used to estimate how many artists a build needs. */
        private const val AVG_TRACK_MS = 240_000L

        /** Keep at least neededArtists × this many fresh artists before applying cooldown. */
        private const val FRESH_HEADROOM = 2

        /**
         * Estimates the largest artist-cooldown value (in playlists) the given pool can fully
         * sustain before [adaptiveArtistCooldown]'s starvation floor starts relaxing it early.
         * Mirrors the same math the engine actually uses, so it's precise, not a guess.
         *
         * Used by Settings to warn the user when their chosen cooldown value exceeds what their
         * library can support — the engine itself already degrades safely, this just makes that
         * degradation visible instead of silent.
         */
        fun maxSustainableCooldown(poolArtistCount: Int, targetDurationMs: Long): Int {
            val neededArtists = (targetDurationMs / AVG_TRACK_MS).toInt().coerceAtLeast(1)
            val floor = neededArtists * FRESH_HEADROOM
            return ((poolArtistCount - floor) / neededArtists).coerceAtLeast(0)
        }

        /**
         * How many consecutive playlists the pool can fill with NO song repeating, given the
         * target duration. The song cooldown is a hard rule, so this is simply how many builds'
         * worth of distinct tracks exist: past it, builds start coming up short rather than
         * repeating.
         *
         * An upper bound — artist cooldown and tier interleaving mean not every track is
         * reachable in every build — but it is the honest ceiling, and unlike a per-artist
         * average it is expressed in the same unit as the setting itself.
         */
        fun maxSustainableSongCooldown(totalTrackCount: Int, targetDurationMs: Long): Int {
            val tracksPerBuild = (targetDurationMs / AVG_TRACK_MS).toInt().coerceAtLeast(1)
            return (totalTrackCount / tracksPerBuild).coerceAtLeast(0)
        }

        /**
         * Classifies a track into a playlist tier ("A", "B", or "C") using the same
         * priority the success-screen breakdown uses: C > A > B. We check ALL of a
         * track's artists (not just the primary) because gap-fill tracks fetched from
         * albums sometimes list a featured artist first, which would otherwise
         * misclassify a discovery track as Tier B.
         *
         *   C — any artist is a pure-discovery (gap-fill-only) artist
         *   A — otherwise, any artist is a top artist
         *   B — everything else (familiar, non-top)
         */
        fun tierOf(
            track: Track,
            discoveryArtistIds: Set<String>,
            topArtistIds: Set<String>
        ): String = when {
            track.artists.any { it.id in discoveryArtistIds } -> "C"
            track.artists.any { it.id in topArtistIds }       -> "A"
            else                                              -> "B"
        }
    }

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
     * @param recentArtistSets   Primary-artist IDs from recent playlists, MOST-RECENT FIRST
     *                           (one set per past playlist). Used to compute an adaptive
     *                           artist cooldown that never starves the fresh pool and never
     *                           suppresses scarce Tier C artists — see [adaptiveArtistCooldown].
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
        recentArtistSets: List<Set<String>> = emptyList(),
        cooldownTrackIds: Set<String> = emptySet(),
        discoveryBias: Int = 60,
        targetDurationMs: Long = 2L * 60 * 60 * 1000
    ): ShuffleResult {
        // Filter out non-music tracks (skits, interludes, etc.) AND duration outliers — very
        // short fragments (interstitials, joke tracks) and absurdly long ones (silence tracks,
        // sprawling live jams that would dominate the playlist). Falls back progressively so an
        // artist is never left empty purely because of filtering.
        val filteredTracksByArtist = tracksByArtist.mapValues { (_, tracks) ->
            tracks.filter {
                !isNonMusicTrack(it.name) &&
                    it.durationMs >= MIN_TRACK_MS && it.durationMs <= MAX_TRACK_MS
            }
                .ifEmpty { tracks.filter { !isNonMusicTrack(it.name) } }
                .ifEmpty { tracks }
        }

        // Only keep artists for whom we actually have tracks
        val artistsWithTracks = followedArtists.filter {
            filteredTracksByArtist[it.id]?.isNotEmpty() == true
        }
        if (artistsWithTracks.isEmpty()) {
            return ShuffleResult(emptyList(), shortOfTarget = true, durationMs = 0L)
        }

        // Adaptive artist cooldown: suppress artists from the most-recent playlists first
        // (all tiers), but stop before the fresh pool would starve. Self-relaxing, so a small
        // library never gets stuck unable to build a playlist.
        val poolArtistIds = artistsWithTracks.map { it.id }.toSet()
        val neededArtists = (targetDurationMs / AVG_TRACK_MS).toInt().coerceAtLeast(1)
        val cooldownArtistIds = adaptiveArtistCooldown(
            recentArtistSets = recentArtistSets,
            poolArtistIds = poolArtistIds,
            neededArtists = neededArtists
        )

        // Partition: artists on cooldown are placed after all fresh artists so they
        // only fill in if the playlist would otherwise fall short of targetDurationMs.
        val freshArtists = artistsWithTracks.filter { it.id !in cooldownArtistIds }
        val cooldownFallbackArtists = artistsWithTracks.filter { it.id in cooldownArtistIds }

        // Build the tier-interleaved order for fresh artists, then append cooldown as fallback.
        val orderedArtists = buildOrderedArtists(
            freshArtists, topArtistIds, discoveryArtistIds, discoveryBias
        ) + cooldownFallbackArtists.shuffled()

        // Pick one track per artist, stopping when we reach the target duration.
        //
        // A track is filed under EVERY artist credited on it, so a collaboration sits in two or
        // more artists' pools and can be selected twice by different slots. usedTrackIds is
        // hoisted above pass 1 (it used to be created for pass 2 only) so a build can never
        // contain the same track twice.
        val playlist = mutableListOf<Track>()
        val usedTrackIds = mutableSetOf<String>()
        var totalMs = 0L

        for (artist in orderedArtists) {
            if (totalMs >= targetDurationMs) break
            val tracks = filteredTracksByArtist[artist.id] ?: continue
            val available = tracks.filter { it.id !in usedTrackIds }
            if (available.isEmpty()) continue
            val track = selectTrack(
                available,
                isRareArtist = artist.id !in topArtistIds,
                likedTrackIds = likedTrackIds,
                cooldownTrackIds = cooldownTrackIds
            ) ?: continue   // every track this artist has is inside the cooldown window
            playlist.add(track)
            usedTrackIds.add(track.id)
            totalMs += track.durationMs
        }

        // Second pass if we're still short: allow repeat artists, but keep honouring the
        // cooldown/liked/popularity preferences via selectTrack instead of a blind random
        // pick — so we don't re-surface recently-played tracks just to fill time.
        if (totalMs < targetDurationMs) {
            for (artist in orderedArtists.shuffled()) {
                if (totalMs >= targetDurationMs) break
                val remaining = filteredTracksByArtist[artist.id]?.filter { it.id !in usedTrackIds }
                if (remaining.isNullOrEmpty()) continue
                val track = selectTrack(
                    remaining,
                    isRareArtist = artist.id !in topArtistIds,
                    likedTrackIds = likedTrackIds,
                    cooldownTrackIds = cooldownTrackIds
                ) ?: continue
                playlist.add(track)
                usedTrackIds.add(track.id)
                totalMs += track.durationMs
            }
        }

        // A hard song cooldown can legitimately leave the build short: every remaining track is
        // inside the user's no-repeat window. Report it rather than quietly returning a short list.
        return ShuffleResult(
            tracks = playlist,
            shortOfTarget = totalMs < targetDurationMs,
            durationMs = totalMs
        )
    }

    /**
     * Decides which artists to place on cooldown for this build.
     *
     * Walks [recentArtistSets] most-recent-first, adding each past playlist's artists to the
     * suppressed set — but stops before the remaining fresh pool would drop below
     * [neededArtists] × [FRESH_HEADROOM]. Applies to all tiers, including Tier C (discovery):
     * earlier versions exempted discovery artists entirely because the discovery pool used to be
     * too small to survive any cooldown at all, which caused the bias to collapse to 0% after a
     * few refreshes. Now that the same starvation-safe floor already protects A/B, it protects C
     * too — the floor check is tier-agnostic pool-size math, so there's no new collapse risk as
     * long as the discovery pool is large enough (which is what this floor is checking for).
     *
     * With a large library this behaves like the old fixed-N cooldown; with a small one it
     * relaxes automatically so the playlist can still be built (at the cost of some repeats,
     * which is unavoidable when the pool is smaller than what the target duration needs).
     */
    private fun adaptiveArtistCooldown(
        recentArtistSets: List<Set<String>>,
        poolArtistIds: Set<String>,
        neededArtists: Int
    ): Set<String> {
        val floor = neededArtists * FRESH_HEADROOM
        val suppressed = mutableSetOf<String>()
        for (set in recentArtistSets) {
            val additions = set.filter {
                it in poolArtistIds && it !in suppressed
            }
            // Stop at playlist granularity once suppressing this one would starve the pool.
            if (poolArtistIds.size - (suppressed.size + additions.size) < floor) break
            suppressed.addAll(additions)
        }
        return suppressed
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
        // The three tiers MUST be mutually exclusive: an artist appearing in two of them lands
        // twice in the ordered list and gets two slots in pass 1. Top-artist and discovery
        // membership do overlap in practice — a top artist credited only as a feature has no
        // primary coverage from Sources 1-3, so they are classified as a gap artist as well.
        // Resolve the overlap the same way tierOf() does (C > A > B) so ordering and tier
        // counting agree.
        val tierC = artists.filter { it.id in discoveryArtistIds }.shuffled()
        val tierA = artists.filter { it.id in topArtistIds && it.id !in discoveryArtistIds }.shuffled()
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
     * The song cooldown is a HARD constraint: a track inside the cooldown window is never
     * returned, even if that means this artist contributes nothing to the build. "Cooldown 40"
     * means a song cannot reappear within 40 playlists, full stop — so the caller skips the
     * artist rather than reaching for a track the user has explicitly excluded.
     *
     * This used to end each branch with `.ifEmpty { tracks }`, which silently served a
     * cooled-down track whenever an artist had nothing fresh. Real logs showed the cost: 16
     * repeats inside a 40-playlist window, 15 of them from artists with a single cached track.
     *
     * Selection within the surviving pool is UNIFORM RANDOM. There used to be an x² bias over
     * ascending popularity to favour deep cuts, but Spotify no longer returns the `popularity`
     * field on /me/tracks or /me/top/tracks — it is absent, not zero — so every track scored
     * NEUTRAL_POPULARITY, the equal-popularity branch always won, and the bias had already
     * degenerated to uniform random on every call. Removing it changed no behaviour; it only
     * stopped the code implying a preference it could not express. The non-liked filter below
     * is now what surfaces deep cuts.
     *
     * @return the chosen track, or null if every track this artist has is inside the cooldown
     *         window — the caller skips the artist.
     */
    private fun selectTrack(
        tracks: List<Track>,
        isRareArtist: Boolean,
        likedTrackIds: Set<String> = emptySet(),
        cooldownTrackIds: Set<String> = emptySet()
    ): Track? {
        // Hard gate first — nothing below may reintroduce a cooled-down track.
        val fresh = tracks.filter { it.id !in cooldownTrackIds }
        if (fresh.isEmpty()) return null

        // Tier B / C prefer non-liked tracks so album deep cuts surface, but fall back to liked
        // ones rather than dropping the artist. Tier A skips the filter — top artists are
        // expected favourites. This preference stays SOFT; only the cooldown is hard.
        val pool = if (isRareArtist) fresh.filter { it.id !in likedTrackIds }.ifEmpty { fresh }
                   else fresh

        if (pool.size == 1) return pool[0]
        return pool.random()
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
