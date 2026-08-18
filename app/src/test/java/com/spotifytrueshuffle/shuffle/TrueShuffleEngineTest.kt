package com.spotifytrueshuffle.shuffle

import com.spotifytrueshuffle.api.AlbumSimple
import com.spotifytrueshuffle.api.Artist
import com.spotifytrueshuffle.api.ArtistSimple
import com.spotifytrueshuffle.api.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the two defects the August 2026 playlist logs exposed.
 *
 * Both are simulation-style: they drive [TrueShuffleEngine.buildPlaylist] over many consecutive
 * builds, feeding each build's output back as cooldown history the way the real callers do. That
 * matters because neither defect is visible in a single build — they only appear once history
 * accumulates.
 */
class TrueShuffleEngineTest {

    private val durationMs = 2L * 60 * 60 * 1000   // 2 hours
    private val album = AlbumSimple("al", "Album", "2020", null)

    private fun track(artistId: String, n: Int, extraArtist: String? = null) = Track(
        id = "$artistId-t$n",
        name = "$artistId track $n",
        durationMs = 225_000,
        popularity = 0,          // Spotify no longer returns this; mirrors production
        uri = "spotify:track:$artistId-t$n",
        artists = listOfNotNull(
            ArtistSimple(artistId, artistId),
            extraArtist?.let { ArtistSimple(it, it) }
        ),
        album = album,
        previewUrl = null
    )

    private fun artist(id: String) = Artist(id, id, 0, null, null)

    /** Runs [builds] consecutive builds, threading history through exactly as the app does. */
    private fun simulate(
        artistCount: Int,
        tracksPerArtist: (Int) -> Int,
        builds: Int,
        songCooldown: Int,
        artistCooldown: Int,
        topCount: Int = 55,
        discoveryFrom: Int = 61,
        overlapCount: Int = 0,
        collabPairs: Int = 0,
        onBuild: (index: Int, result: ShuffleResult, cooldownIds: Set<String>) -> Unit
    ) {
        val engine = TrueShuffleEngine()
        val artists = (0 until artistCount).map { artist("a$it") }
        val tracksByArtist = artists.associate { a ->
            val idx = a.id.removePrefix("a").toInt()
            a.id to (0 until tracksPerArtist(idx)).map { track(a.id, it) }.toMutableList()
        }.toMutableMap()

        // Collaboration tracks: ONE track object filed under BOTH artists, exactly as the
        // repository's addTrack does. Either artist's slot can select it.
        repeat(collabPairs) { k ->
            val a1 = "a$k"; val a2 = "a${k + 1}"
            val collab = track(a1, 900 + k, extraArtist = a2)
            tracksByArtist[a1]?.add(collab)
            tracksByArtist[a2]?.add(collab)
        }

        val topIds = (0 until topCount).map { "a$it" }.toSet()
        // Overlap: artists that are BOTH top and discovery — a top artist credited only as a
        // feature has no primary coverage, so the repository classifies them as a gap artist too.
        val discoveryIds = ((discoveryFrom until artistCount).map { "a$it" } +
            (0 until overlapCount).map { "a$it" }).toSet()

        val history = ArrayDeque<Pair<List<String>, Set<String>>>()   // trackIds, artistIds
        repeat(builds) { i ->
            val cooldownTrackIds = history.take(songCooldown).flatMap { it.first }.toSet()
            val recentArtistSets = history.take(artistCooldown).map { it.second }

            val result = engine.buildPlaylist(
                followedArtists = artists,
                topArtistIds = topIds,
                tracksByArtist = tracksByArtist,
                discoveryArtistIds = discoveryIds,
                likedTrackIds = emptySet(),
                recentArtistSets = recentArtistSets,
                cooldownTrackIds = cooldownTrackIds,
                discoveryBias = 90,
                targetDurationMs = durationMs
            )
            onBuild(i, result, cooldownTrackIds)

            history.addFirst(
                result.tracks.map { it.id } to
                    result.tracks.flatMap { t -> t.artists.map { it.id } }.toSet()
            )
            while (history.size > 100) history.removeLast()
        }
    }

    /**
     * The headline defect: `selectTrack` used to end each branch with `.ifEmpty { tracks }`, so a
     * one-track artist whose only song was inside the cooldown window had it served anyway.
     *
     * The real logs showed 16 repeats inside a 40-playlist window, 15 of them from artists with a
     * single cached track — hence the deliberately shallow catalogues here.
     */
    @Test
    fun `song cooldown is never violated even with one-track artists`() {
        var violations = 0
        simulate(
            artistCount = 293,
            // Every third artist has exactly one track — the shape that broke in production.
            tracksPerArtist = { if (it % 3 == 0) 1 else 4 },
            builds = 40,
            songCooldown = 40,
            artistCooldown = 7
        ) { _, result, cooldownIds ->
            violations += result.tracks.count { it.id in cooldownIds }
        }
        assertEquals("a track inside the cooldown window was reused", 0, violations)
    }

    /** A build must never contain the same track twice, from either duplicate cause. */
    @Test
    fun `no duplicate tracks within a single build`() {
        var duplicateBuilds = 0
        simulate(
            artistCount = 293,
            tracksPerArtist = { 12 },
            builds = 60,
            songCooldown = 39,
            artistCooldown = 7,
            overlapCount = 40,   // cause A: artists in both topIds and discoveryIds
            collabPairs = 30     // cause B: one track filed under two artists
        ) { _, result, _ ->
            val distinct = result.tracks.map { it.id }.toSet().size
            if (distinct != result.tracks.size) duplicateBuilds++
        }
        assertEquals("a build contained the same track twice", 0, duplicateBuilds)
    }

    /** Shallow pools exercise pass 2, where duplicates were most likely. */
    @Test
    fun `no duplicates when a shallow pool forces the second pass`() {
        var duplicateBuilds = 0
        simulate(
            artistCount = 120,
            tracksPerArtist = { 3 },
            builds = 60,
            songCooldown = 39,
            artistCooldown = 7,
            discoveryFrom = 40,
            overlapCount = 20,
            collabPairs = 20
        ) { _, result, _ ->
            val distinct = result.tracks.map { it.id }.toSet().size
            if (distinct != result.tracks.size) duplicateBuilds++
        }
        assertEquals("a build contained the same track twice", 0, duplicateBuilds)
    }

    /**
     * A hard cooldown means running out is a real outcome — it must be reported, not hidden,
     * otherwise the user just sees a mysteriously short playlist.
     */
    @Test
    fun `exhausted pool reports shortOfTarget instead of repeating`() {
        var sawShort = false
        var violations = 0
        simulate(
            artistCount = 40,
            tracksPerArtist = { 1 },     // 40 distinct tracks total, ~32 needed per build
            builds = 12,
            songCooldown = 40,
            artistCooldown = 3
        ) { _, result, cooldownIds ->
            if (result.shortOfTarget) sawShort = true
            violations += result.tracks.count { it.id in cooldownIds }
        }
        assertTrue("a pool this small must eventually come up short", sawShort)
        assertEquals("came up short AND repeated — cooldown leaked", 0, violations)
    }

    /** The ceiling formula should be expressed in builds, not per-artist catalogue depth. */
    @Test
    fun `maxSustainableSongCooldown counts builds worth of distinct tracks`() {
        // 2 hours / 240s = 30 tracks per build.
        assertEquals(10, TrueShuffleEngine.maxSustainableSongCooldown(300, durationMs))
        assertEquals(0, TrueShuffleEngine.maxSustainableSongCooldown(0, durationMs))
        assertEquals(100, TrueShuffleEngine.maxSustainableSongCooldown(3000, durationMs))
    }
}
