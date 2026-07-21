package com.spotifytrueshuffle.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.spotifytrueshuffle.api.Track
import com.spotifytrueshuffle.shuffle.TrueShuffleEngine
import java.io.File
import java.time.Instant

private const val TAG = "PlaylistLog"
private const val LOG_FILE = "playlist_log.json"

/** Maximum number of past playlists to retain in the analysis log. */
const val MAX_LOGGED = 50

/**
 * One track as it appeared in a generated playlist, captured for later analysis.
 * Carries human-readable metadata (names, album, popularity, duration) plus the
 * tier it was selected from and whether it was one of the user's liked songs.
 */
data class PlaylistTrackLog(
    val trackId: String = "",
    val trackName: String = "",
    val artistId: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val releaseDate: String? = null,
    val popularity: Int = 0,
    val durationMs: Int = 0,
    /** "A" (top), "B" (familiar non-top), or "C" (pure discovery). */
    val tier: String = "",
    val liked: Boolean = false
)

/**
 * A full snapshot of one generated playlist: the settings in effect for the build
 * plus every track it contained. Unlike [ShuffleHistory]'s ID-only snapshots (used
 * for cooldown), this is the analysis-oriented record surfaced by the export.
 */
data class PlaylistLogEntry(
    val timestampMs: Long = 0L,
    val timestampIso: String = "",
    /** "manual" (user tapped Build) or "auto" (background rebuild worker). */
    val source: String = "manual",
    val discoveryBias: Int = 0,
    val targetDurationMs: Long = 0L,
    val cooldownPlaylists: Int = 0,
    val trackCount: Int = 0,
    val artistCount: Int = 0,
    val tierACount: Int = 0,
    val tierBCount: Int = 0,
    val tierCCount: Int = 0,
    val tracks: List<PlaylistTrackLog> = emptyList()
)

/** Persisted playlist log: most-recent build first, capped at [MAX_LOGGED]. */
data class PlaylistLog(
    val entries: List<PlaylistLogEntry> = emptyList()
)

/**
 * Builds a [PlaylistLogEntry] from a freshly generated playlist. Shared by the
 * foreground build (MainViewModel) and the background rebuild worker so both log the
 * same shape and classify tiers identically (via [TrueShuffleEngine.tierOf]).
 */
fun buildPlaylistLogEntry(
    tracks: List<Track>,
    discoveryArtistIds: Set<String>,
    topArtistIds: Set<String>,
    likedTrackIds: Set<String>,
    source: String,
    discoveryBias: Int,
    targetDurationMs: Long,
    cooldownPlaylists: Int,
    nowMs: Long = System.currentTimeMillis()
): PlaylistLogEntry {
    val trackLogs = tracks.map { track ->
        val primary = track.artists.firstOrNull()
        PlaylistTrackLog(
            trackId = track.id,
            trackName = track.name,
            artistId = primary?.id ?: "",
            artistName = primary?.name ?: "",
            albumName = track.album.name,
            releaseDate = track.album.releaseDate,
            popularity = track.popularity,
            durationMs = track.durationMs,
            tier = TrueShuffleEngine.tierOf(track, discoveryArtistIds, topArtistIds),
            liked = track.id in likedTrackIds
        )
    }
    return PlaylistLogEntry(
        timestampMs = nowMs,
        timestampIso = Instant.ofEpochMilli(nowMs).toString(),
        source = source,
        discoveryBias = discoveryBias,
        targetDurationMs = targetDurationMs,
        cooldownPlaylists = cooldownPlaylists,
        trackCount = trackLogs.size,
        artistCount = tracks.flatMap { it.artists }.map { it.id }.toSet().size,
        tierACount = trackLogs.count { it.tier == "A" },
        tierBCount = trackLogs.count { it.tier == "B" },
        tierCCount = trackLogs.count { it.tier == "C" },
        tracks = trackLogs
    )
}

/**
 * Reads and writes the playlist log to/from internal app storage.
 * Pattern mirrors [ShuffleHistoryStorage] — plain JSON via Gson with an explicit
 * TypeToken (defensive against the R8/TypeToken issue seen in earlier releases).
 */
class PlaylistLogStorage(context: Context) {

    private val file = File(context.filesDir, LOG_FILE)
    private val gson = Gson()

    fun load(): PlaylistLog {
        if (!file.exists()) return PlaylistLog()
        return try {
            val type = object : TypeToken<PlaylistLog>() {}.type
            gson.fromJson<PlaylistLog>(file.readText(), type) ?: PlaylistLog()
        } catch (e: Exception) {
            Log.w(TAG, "Log load failed — returning empty: ${e.message}")
            PlaylistLog()
        }
    }

    /**
     * Prepends a new playlist entry and trims the list to [MAX_LOGGED].
     * Call this after a playlist is successfully written to Spotify.
     */
    fun record(entry: PlaylistLogEntry) {
        val current = load()
        val updated = PlaylistLog(
            entries = (listOf(entry) + current.entries).take(MAX_LOGGED)
        )
        save(updated)
        Log.d(TAG, "Recorded playlist: ${entry.trackCount} tracks " +
            "(A=${entry.tierACount}, B=${entry.tierBCount}, C=${entry.tierCCount}) " +
            "source=${entry.source} (log depth: ${updated.entries.size})")
    }

    /** Removes all recorded playlists. */
    fun clear() {
        save(PlaylistLog())
        Log.d(TAG, "Playlist log cleared")
    }

    /** Serializes the whole log to pretty-printed JSON. */
    fun toJson(log: PlaylistLog = load()): String = gson.toJson(log)

    private fun save(log: PlaylistLog) {
        try {
            file.writeText(gson.toJson(log))
        } catch (e: Exception) {
            Log.w(TAG, "Log save failed: ${e.message}")
        }
    }
}
