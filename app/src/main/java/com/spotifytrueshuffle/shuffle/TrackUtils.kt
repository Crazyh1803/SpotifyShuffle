package com.spotifytrueshuffle.shuffle

/**
 * Utilities for normalizing track titles and filtering tracks.
 *
 *  1. [songKey] — produces a deduplication key that treats different versions of the
 *     same song (live, remastered, acoustic, demo, etc.) as identical for the
 *     purpose of the repeat-cooldown check.
 *
 *  2. [isSkit] — detects skit tracks that should be excluded from shuffle playlists.
 */
object TrackUtils {

    /** Regex patterns that strip version/annotation suffixes from track titles. */
    private val VERSION_PATTERNS = listOf(
        // Remaster suffixes: "- Remastered", "- 2011 Remaster", "- Remastered 2011"
        Regex(
            """\s*[-–]\s*(remaster(?:ed)?(?:\s+\d{4})?|\d{4}\s+remaster(?:ed)?).*""",
            RegexOption.IGNORE_CASE
        ),
        // Parenthesised annotations: (Live), (Live at Wembley), (Acoustic Version), (Demo), …
        Regex(
            """\s*\((live(?:\s+(?:version|at\s+[^)]+))?|acoustic(?:\s+version)?""" +
            """|demo(?:\s+version)?|radio\s+edit|single\s+version|album\s+version""" +
            """|deluxe(?:\s+edition)?|bonus\s+track|extended(?:\s+version)?""" +
            """|(?:[^)]*\s+)?(?:remix|mix)|instrumental|unplugged|stripped|mono|stereo)\)""",
            RegexOption.IGNORE_CASE
        ),
        // Square-bracket annotations: [Live], [Acoustic], [Remix], [Remaster 2018]
        Regex(
            """\s*\[(live|acoustic|demo|radio\s+edit|instrumental|(?:[^\]]*\s+)?(?:remix|mix)|remaster[^\]]*)\]""",
            RegexOption.IGNORE_CASE
        ),
        // Dash-prefixed suffix at end of title: "- Live", "- Acoustic", "- Demo Version"
        Regex(
            """\s*[-–]\s*(live|acoustic|demo\s+version|radio\s+edit|instrumental)\s*$""",
            RegexOption.IGNORE_CASE
        ),
        // Featured-artist annotations: (feat. X) or [ft. X]
        Regex(
            """\s*[\(\[](feat\.?|ft\.?)\s+[^\)\]]*[\)\]]""",
            RegexOption.IGNORE_CASE
        ),
    )

    /**
     * Strips version/annotation suffixes from a track title and lowercases it so that
     * "Jane Says", "Jane Says (Live)", and "Jane Says - Remastered 2014" all normalize
     * to the same string: "jane says".
     */
    fun normalizeTitle(title: String): String {
        var result = title
        VERSION_PATTERNS.forEach { result = it.replace(result, "") }
        return result.trim().lowercase()
    }

    /**
     * A deduplication key that identifies different versions of the same song.
     * Format: "<normalized_title>||<primary_artist_id_lowercase>"
     *
     * Stored in the cooldown history instead of raw Spotify track IDs so that
     * e.g. "Jane Says" and "Jane Says (Live)" are treated as the same track
     * and won't appear in back-to-back playlists.
     */
    fun songKey(trackName: String, primaryArtistId: String): String =
        "${normalizeTitle(trackName)}||${primaryArtistId.lowercase()}"

    /**
     * Returns true if the track title contains the word "skit" (case-insensitive).
     * Skits are excluded from all shuffle playlists as they disrupt flow outside
     * the context of their own album.
     */
    fun isSkit(trackName: String): Boolean =
        trackName.contains("skit", ignoreCase = true)
}
