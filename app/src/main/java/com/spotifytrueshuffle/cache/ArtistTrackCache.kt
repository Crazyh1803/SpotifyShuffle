package com.spotifytrueshuffle.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.spotifytrueshuffle.api.Track
import java.io.File

private const val TAG = "ArtistTrackCache"
private const val CACHE_FILE = "artist_track_cache.json"

/**
 * Persisted cache of artist → tracks mappings, stored as JSON in internal app storage.
 *
 * This avoids re-fetching track data from Spotify on every playlist build.
 * Only new artists (followed after the last cache update) need their tracks fetched.
 * File location: [Context.filesDir]/artist_track_cache.json
 */
data class TrackCacheData(
    /** Epoch millis when this cache was last written. */
    val lastRefreshedMs: Long = 0L,
    /** Map of Spotify artist ID → list of their tracks. */
    val artistTracks: Map<String, List<Track>> = emptyMap()
)

class ArtistTrackCache(context: Context) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val gson = Gson()

    val isEmpty: Boolean get() = !file.exists() || file.length() == 0L

    fun load(): TrackCacheData {
        if (isEmpty) return TrackCacheData()
        return try {
            val type = object : TypeToken<TrackCacheData>() {}.type
            gson.fromJson<TrackCacheData>(file.readText(), type) ?: TrackCacheData()
        } catch (e: Exception) {
            Log.w(TAG, "Cache load failed — returning empty: ${e.message}")
            TrackCacheData()
        }
    }

    fun save(data: TrackCacheData) {
        try {
            file.writeText(gson.toJson(data))
            val trackCount = data.artistTracks.values.sumOf { it.size }
            Log.d(TAG, "Cache saved: ${data.artistTracks.size} artists, $trackCount tracks")
        } catch (e: Exception) {
            Log.w(TAG, "Cache save failed: ${e.message}")
        }
    }

    fun clear() {
        file.delete()
        Log.d(TAG, "Cache cleared")
    }
}
