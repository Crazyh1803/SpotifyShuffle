package com.spotifytrueshuffle.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

private const val TAG = "ShuffleHistory"
private const val HISTORY_FILE = "shuffle_history.json"

/**
 * Maximum number of past playlists to store on disk. Must cover the largest cooldown value
 * either song or artist cooldown can be set to (song cooldown maxes at 50).
 */
private const val MAX_STORED = 50

/** Default used when a newly-added Int field is missing/zero after loading older JSON. */
private const val DEFAULT_ARTIST_COOLDOWN = 5

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
 * Persisted shuffle history: the user's cooldown preferences plus the last [MAX_STORED]
 * playlist snapshots (most-recent first).
 *
 * Song and artist cooldown are independent settings — a track and its primary artist can be
 * suppressed for different numbers of past playlists. [cooldownPlaylists] is the song/track
 * cooldown (field name kept from before the split to avoid a Gson migration on existing
 * installs); [artistCooldownPlaylists] is the newer, separate artist cooldown.
 */
data class ShuffleHistory(
    /** Number of past playlists during which a TRACK is suppressed (1–50). */
    val cooldownPlaylists: Int = 5,
    /** Number of past playlists during which an ARTIST is suppressed (1–30). */
    val artistCooldownPlaylists: Int = DEFAULT_ARTIST_COOLDOWN,
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
            val loaded = gson.fromJson<ShuffleHistory>(file.readText(), type) ?: ShuffleHistory()
            // artistCooldownPlaylists was added after cooldownPlaylists; Gson leaves it at the
            // Int default (0) for JSON written by older app versions. Normalize so an upgrading
            // user doesn't end up with an effectively-broken (0) artist cooldown.
            if (loaded.artistCooldownPlaylists <= 0) {
                loaded.copy(artistCooldownPlaylists = DEFAULT_ARTIST_COOLDOWN)
            } else {
                loaded
            }
        } catch (e: Exception) {
            Log.w(TAG, "History load failed — returning default: ${e.message}")
            ShuffleHistory()
        }
    }

    /** Persists only the song/track cooldown setting; leaves everything else untouched. */
    fun saveCooldownCount(n: Int) {
        val updated = load().copy(cooldownPlaylists = n)
        save(updated)
    }

    /** Persists only the artist cooldown setting; leaves everything else untouched. */
    fun saveArtistCooldownCount(n: Int) {
        val updated = load().copy(artistCooldownPlaylists = n)
        save(updated)
    }

    /**
     * Prepends a new playlist entry and trims the list to [MAX_STORED].
     * Call this after a playlist is successfully written to Spotify.
     */
    fun recordPlaylist(
        trackIds: List<String>,
        artistIds: List<String>,
        songCooldownCount: Int,
        artistCooldownCount: Int
    ) {
        val current = load()
        val updated = ShuffleHistory(
            cooldownPlaylists = songCooldownCount,
            artistCooldownPlaylists = artistCooldownCount,
            recentPlaylists = (listOf(PlaylistSnapshot(trackIds, artistIds)) + current.recentPlaylists)
                .take(MAX_STORED)
        )
        save(updated)
        Log.d(TAG, "Recorded playlist: ${trackIds.size} tracks, ${artistIds.size} artists " +
            "(history depth: ${updated.recentPlaylists.size})")
    }

    /**
     * Returns the track IDs that are on cooldown based on the last [n] stored playlists.
     *
     * @param n  The user's song/track cooldown setting (from [ShuffleHistory.cooldownPlaylists]).
     * @param history  Pre-loaded history (avoids re-reading disk when caller already has it).
     */
    fun getCooldownTrackIds(
        n: Int,
        history: ShuffleHistory = load()
    ): Set<String> {
        val trackIds = history.recentPlaylists.take(n).flatMap { it.trackIds }.toSet()
        Log.d(TAG, "Cooldown track set (last $n playlists): ${trackIds.size} tracks")
        return trackIds
    }

    /**
     * Returns the primary-artist ID sets of the last [n] stored playlists, MOST-RECENT FIRST
     * (one set per playlist). Preserves per-playlist recency so the shuffle engine can apply an
     * adaptive cooldown that suppresses the newest playlists first and stops before the fresh
     * artist pool would starve.
     *
     * @param n  The user's artist cooldown setting (from [ShuffleHistory.artistCooldownPlaylists]).
     */
    fun getRecentArtistSets(
        n: Int,
        history: ShuffleHistory = load()
    ): List<Set<String>> =
        history.recentPlaylists.take(n).map { it.artistIds.toSet() }

    /**
     * Clears the recent playlist history (resets cooldown suppression) while keeping
     * the user's cooldown count preferences intact.
     */
    fun clearHistory() {
        val current = load()
        save(current.copy(recentPlaylists = emptyList()))
        Log.d(TAG, "Cooldown history cleared (song=${current.cooldownPlaylists}, " +
            "artist=${current.artistCooldownPlaylists} settings kept)")
    }

    private fun save(history: ShuffleHistory) {
        try {
            file.writeText(gson.toJson(history))
        } catch (e: Exception) {
            Log.w(TAG, "History save failed: ${e.message}")
        }
    }
}
