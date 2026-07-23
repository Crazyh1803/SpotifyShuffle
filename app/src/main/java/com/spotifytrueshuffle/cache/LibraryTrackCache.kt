package com.spotifytrueshuffle.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.spotifytrueshuffle.api.Track
import java.io.File

private const val TAG = "LibraryTrackCache"
private const val CACHE_FILE = "library_track_pool.json"

/**
 * Cached result of the heavy "library" sources — liked songs (GET /me/tracks) and saved albums
 * (GET /me/albums) — bucketed by primary artist, plus the set of liked track IDs and a fetch
 * timestamp. Top tracks are NOT cached here; they are 3 cheap calls and reflect recent listening.
 *
 * @param tracksByArtist artistId → tracks from liked songs + saved albums.
 * @param likedTrackIds  IDs of tracks from the user's liked-songs list.
 * @param fetchedAtMs    Unix-millis when this pool was fetched.
 */
data class LibraryTrackPool(
    val tracksByArtist: Map<String, List<Track>> = emptyMap(),
    val likedTrackIds: List<String> = emptyList(),
    val fetchedAtMs: Long = 0L
)

/**
 * Persistent cache for the liked-songs + saved-albums track pool.
 *
 * Before this cache, sources 2-3 were re-fetched in full on every build — dozens of paginated
 * calls each time, which (with rapid rebuilds) is a primary cause of Spotify rate-limiting and
 * starves the gap-fill step of API budget. Caching them means a repeat build makes almost no
 * calls, leaving quota for gap scanning.
 *
 * Freshness is the caller's responsibility (TTL check on [LibraryTrackPool.fetchedAtMs]); the
 * cache is invalidated via [clear] on explicit "Refresh Artists" / "Scan for new tracks" /
 * logout so newly-liked songs are picked up.
 */
class LibraryTrackCache(context: Context) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val gson = Gson()

    /** Loads the cached pool, or null if absent / corrupt. */
    fun load(): LibraryTrackPool? {
        if (!file.exists()) return null
        return try {
            val type = object : TypeToken<LibraryTrackPool>() {}.type
            val pool = gson.fromJson<LibraryTrackPool>(file.readText(), type) ?: return null

            // Same R8-corruption guard as GapArtistCache: if tracks came back with all-empty
            // IDs (a renamed field before proper keep rules), treat the cache as unusable.
            val corrupted = pool.tracksByArtist.values.any { tracks ->
                tracks.isNotEmpty() && tracks.all { it.id.isEmpty() }
            }
            if (corrupted) {
                Log.w(TAG, "Library pool cache R8-corrupted — wiping")
                file.delete()
                return null
            }
            pool
        } catch (e: Exception) {
            Log.w(TAG, "Library pool load failed — returning null: ${e.message}")
            null
        }
    }

    fun save(pool: LibraryTrackPool) {
        try {
            file.writeText(gson.toJson(pool))
            Log.d(TAG, "Library pool saved: ${pool.tracksByArtist.size} artists, " +
                "${pool.likedTrackIds.size} liked IDs")
        } catch (e: Exception) {
            Log.w(TAG, "Library pool save failed: ${e.message}")
        }
    }

    /** Deletes the cache so the next build re-fetches liked songs + saved albums. */
    fun clear() {
        if (file.delete()) Log.d(TAG, "Library pool cache cleared")
    }
}
