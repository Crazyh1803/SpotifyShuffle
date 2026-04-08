package com.spotifytrueshuffle.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.spotifytrueshuffle.api.TrackPool
import java.io.File

private const val TAG        = "TrackPoolCache"
private const val CACHE_FILE = "track_pool_cache.json"

/** Pool is considered fresh for 12 hours. Interactive builds use the cache; force-rescans bypass it. */
private const val CACHE_TTL_MS = 12L * 60 * 60 * 1000

/**
 * Persists a [TrackPool] to disk with a timestamp.
 *
 * Why we cache this:
 *   Building the pool requires paginating all liked songs, all saved albums, and fetching
 *   tracks for every followed artist with no library coverage. For a 200+ artist library this
 *   can take 30–60+ seconds and may hit Spotify's rate limit. Caching means that cost is paid
 *   once (or after an explicit rescan / every 12 hours) rather than on every playlist build.
 */
class TrackPoolCache(context: Context) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val gson = Gson()

    private data class Entry(
        val builtAtMs: Long = 0,
        val pool: TrackPool = TrackPool(emptyMap(), emptySet(), emptySet())
    )

    /**
     * Returns the cached [TrackPool] if it exists and is less than 12 hours old,
     * or null if the cache is missing, expired, or corrupt.
     */
    fun load(): TrackPool? {
        if (!file.exists()) return null
        return try {
            val type  = object : TypeToken<Entry>() {}.type
            val entry = gson.fromJson<Entry>(file.readText(), type) ?: return null
            val ageMs = System.currentTimeMillis() - entry.builtAtMs
            if (ageMs > CACHE_TTL_MS) {
                Log.d(TAG, "Cache expired (${ageMs / 3_600_000}h old) — will rebuild")
                null
            } else {
                Log.d(TAG, "Cache hit: ${entry.pool.tracksByArtist.size} artists, " +
                    "${ageMs / 3_600_000}h old")
                entry.pool
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cache load failed — will rebuild: ${e.message}")
            null
        }
    }

    /** Persists a freshly-built [TrackPool] with the current timestamp. */
    fun save(pool: TrackPool) {
        try {
            file.writeText(gson.toJson(Entry(builtAtMs = System.currentTimeMillis(), pool = pool)))
            Log.d(TAG, "Track pool cached: ${pool.tracksByArtist.size} artists, " +
                "${pool.tracksByArtist.values.sumOf { it.size }} tracks")
        } catch (e: Exception) {
            Log.w(TAG, "Cache save failed: ${e.message}")
        }
    }

    /**
     * Deletes the cache file so the next build triggers a full track-pool rebuild.
     * Call this after a successful artist library refresh.
     */
    fun invalidate() {
        if (file.delete()) Log.d(TAG, "Track pool cache invalidated")
    }
}
