package com.spotifytrueshuffle.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

private const val TAG = "ShuffleHistory"
private const val HISTORY_FILE = "shuffle_history.json"

/** Maximum number of past playlists to store on disk (supports cooldown up to this value).
 *  Must be >= the max of track cooldown (10) and artist cooldown (20) slider limits. */
private const val MAX_STORED = 20

/**
 * A snapshot of one generated playlist: the track IDs and primary artist IDs it contained.
 * Used to enforce the per-user cooldown: tracks/artists that appeared in the last N
 * playlists are skipped (or pushed to the end) on the next build.
 */
data class PlaylistSnapshot(
    val trackIds: List<String> = emptyList(),
    val artistIds: List<String> = emptyList()
)

/**
 * Persisted shuffle history: the user's cooldown preference plus the last [MAX_STORED]
 * playlist snapshots (most-recent first).
 */
data class ShuffleHistory(
    /** Number of past playlists during which a track/artist is suppressed (1–10). */
    val cooldownPlaylists: Int = 5,
    /** Most-recent playlist first. Length is capped at MAX_STORED. */
    val recentPlaylists: List<PlaylistSnapshot> = emptyList()
)

/**
 * Reads and writes shuffle history to/from internal app storage.
 * Pattern mirrors [ArtistTrackCache] — plain JSON via Gson.
 */
class ShuffleHistoryStorage(context: Context) {

    private val file = File(context.filesDir, HISTORY_FILE)
    private val gson = Gson()

    fun load(): ShuffleHistory {
        if (!file.exists()) return ShuffleHistory()
        return try {
            val type = object : TypeToken<ShuffleHistory>() {}.type
            gson.fromJson<ShuffleHistory>(file.readText(), type) ?: ShuffleHistory()
        } catch (e: Exception) {
            Log.w(TAG, "History load failed — returning default: ${e.message}")
            ShuffleHistory()
        }
    }

    /** Persists only the cooldown setting; leaves the playlist history untouched. */
    fun saveCooldownCount(n: Int) {
        val updated = load().copy(cooldownPlaylists = n)
        save(updated)
    }

    /**
     * Prepends a new playlist entry and trims the list to [MAX_STORED].
     * Call this after a playlist is successfully written to Spotify.
     */
    fun recordPlaylist(trackIds: List<String>, artistIds: List<String>, cooldownCount: Int) {
        val current = load()
        val updated = ShuffleHistory(
            cooldownPlaylists = cooldownCount,
            recentPlaylists = (listOf(PlaylistSnapshot(trackIds, artistIds)) + current.recentPlaylists)
                .take(MAX_STORED)
        )
        save(updated)
        Log.d(TAG, "Recorded playlist: ${trackIds.size} tracks, ${artistIds.size} artists " +
            "(history depth: ${updated.recentPlaylists.size})")
    }

    /**
     * Returns the song keys and artist IDs that are on cooldown based on the
     * last [n] stored playlists.
     *
     * Song keys are recognised by containing "||" (format: "normalized_title||artistId").
     * Raw Spotify track IDs stored by older app versions do NOT contain "||" and are
     * silently discarded so they can't corrupt the song-cooldown comparison.
     *
     * @param n  The cooldown window (number of past playlists to look back).
     * @param history  Pre-loaded history (avoids re-reading disk when caller already has it).
     * @return  Pair of (cooldownSongKeys, cooldownArtistIds)
     */
    fun getCooldownSets(
        n: Int,
        history: ShuffleHistory = load()
    ): Pair<Set<String>, Set<String>> {
        val recents = history.recentPlaylists.take(n)
        // Only keep entries that are song keys (contain "||"). Raw track IDs from
        // older versions don't match and would silently disable song deduplication.
        val songKeys  = recents.flatMap { it.trackIds }.filter { "||" in it }.toSet()
        val artistIds = recents.flatMap { it.artistIds }.toSet()
        Log.d(TAG, "Cooldown sets (last $n playlists): ${songKeys.size} song keys, ${artistIds.size} artists")
        return songKeys to artistIds
    }

    /**
     * Clears the recent playlist history (resets cooldown suppression) while keeping
     * the user's cooldown count preference intact.
     */
    fun clearHistory() {
        val current = load()
        save(current.copy(recentPlaylists = emptyList()))
        Log.d(TAG, "Cooldown history cleared (cooldownPlaylists setting kept: ${current.cooldownPlaylists})")
    }

    private fun save(history: ShuffleHistory) {
        try {
            file.writeText(gson.toJson(history))
        } catch (e: Exception) {
            Log.w(TAG, "History save failed: ${e.message}")
        }
    }
}
