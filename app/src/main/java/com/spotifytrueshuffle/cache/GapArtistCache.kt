package com.spotifytrueshuffle.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.spotifytrueshuffle.api.Track
import java.io.File

private const val TAG = "GapArtistCache"
private const val CACHE_FILE = "gap_artist_cache.json"

/**
 * One cached entry per gap artist — the result of a single API scan (top-tracks or search).
 *
 * @param tracks      Up to 10 tracks fetched for this artist during the scan.
 * @param isDiscovery true  → artist is Tier C (followed, not a top artist, no saved music).
 *                    false → artist is Tier A top-gap (streams but never saved their music).
 * @param scannedAtMs Unix-millis when this entry was last fetched.
 *                    0L means "needs rescan" — [GapArtistCache.clearTimestamps] sets this.
 */
data class GapArtistEntry(
    val tracks: List<Track>,
    val isDiscovery: Boolean,
    val scannedAtMs: Long
)

/**
 * Persistent, per-artist cache for gap-fill results.
 *
 * Only the gap-fill step (API calls to /artists/{id}/top-tracks or /search) is cached here.
 * Sources 1-3 (top tracks, liked songs, saved albums) are always re-fetched each build because
 * they are fast paginated calls and need to reflect the user's latest library additions.
 *
 * The cache grows incrementally: each build scans a batch of unscanned artists and merges the
 * results in. Once all followed artists are covered, subsequent builds load from cache
 * instantly — no API calls needed for the gap-fill step.
 *
 * Lifetime:
 *   - Entries persist indefinitely until the user taps "Scan for new tracks" (→ clearTimestamps)
 *     or "Rescan artists" (→ clear, which wipes the file entirely).
 *   - A user-configurable rescan interval causes stale entries (scannedAtMs < threshold) to be
 *     treated as unscanned and picked up in the next batch.
 */
class GapArtistCache(context: Context) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val gson = Gson()

    /**
     * Loads all cached entries from disk.
     * Returns an empty map if the file does not exist or is corrupt.
     *
     * Corruption check: earlier release builds ran R8 without proper ProGuard keep rules,
     * which renamed Track fields before Gson serialized them. Those JSON files have the
     * outer Map<String, GapArtistEntry> structure intact but Track objects inside have
     * empty id/uri strings. We detect this and wipe the file so a fresh rescan runs —
     * discovery artists were completely broken by this because their tracks had no valid URIs.
     */
    fun load(): Map<String, GapArtistEntry> {
        if (!file.exists()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, GapArtistEntry>>() {}.type
            val result = gson.fromJson<Map<String, GapArtistEntry>>(file.readText(), type)
                ?: emptyMap()

            // Detect R8-corrupted track data: entries that have tracks but ALL tracks have
            // empty IDs (meaning R8 renamed the "id" field and Gson read back empty strings).
            val corrupted = result.values.any { entry ->
                entry.tracks.isNotEmpty() && entry.tracks.all { it.id.isEmpty() }
            }
            if (corrupted) {
                Log.w(TAG, "Cache contains R8-corrupted track data — wiping for fresh rescan")
                file.delete()
                return emptyMap()
            }

            result
        } catch (e: Exception) {
            Log.w(TAG, "Cache load failed — returning empty: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Persists the given entries map to disk, replacing any previous contents.
     * The caller is responsible for merging existing entries with newly scanned ones
     * before calling save (e.g. `save(existing + newlyScanned)`).
     */
    fun save(entries: Map<String, GapArtistEntry>) {
        try {
            file.writeText(gson.toJson(entries))
            Log.d(TAG, "Gap artist cache saved: ${entries.size} entries")
        } catch (e: Exception) {
            Log.w(TAG, "Cache save failed: ${e.message}")
        }
    }

    /**
     * Sets [GapArtistEntry.scannedAtMs] to 0 for every entry without removing the tracks.
     *
     * Used by "Scan for new tracks": on the next build all artists are treated as unscanned
     * and re-fetched in batches. This keeps the existing tracks in the pool during the rescan
     * so the user still gets playlists while the rescan progresses.
     */
    fun clearTimestamps() {
        val existing = load()
        if (existing.isEmpty()) return
        val reset = existing.mapValues { (_, entry) -> entry.copy(scannedAtMs = 0L) }
        save(reset)
        Log.d(TAG, "Gap artist cache timestamps cleared (${reset.size} entries marked for rescan)")
    }

    /**
     * Deletes the cache file entirely — used when the user rescans their artist library,
     * since the set of followed artists may have changed significantly.
     */
    fun clear() {
        if (file.delete()) Log.d(TAG, "Gap artist cache cleared")
    }

    /** Returns the total number of cached entries currently on disk. */
    fun totalScanned(): Int = load().size
}
