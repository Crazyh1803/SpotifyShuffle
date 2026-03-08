package com.spotifytrueshuffle.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.spotifytrueshuffle.api.Artist
import java.io.File

private const val TAG = "ArtistTrackCache"

// New filename so old incompatible track-based cache files are ignored
private const val CACHE_FILE = "artist_library.json"

/**
 * The persisted artist library — ALL of the user's followed artists plus their
 * top-artist IDs (used for tier A/B shuffle logic).
 *
 * Intentionally does NOT cache track data. Tracks are fetched fresh for a small
 * random sample of artists on each playlist build (~15 calls, ~15s). This keeps
 * every build feeling different and avoids Spotify rate-limit issues from trying
 * to pre-fetch hundreds of artists at once.
 */
data class ArtistLibrary(
    val lastRefreshedMs: Long = 0L,
    /** Every artist the user follows on Spotify. */
    val followedArtists: List<Artist> = emptyList(),
    /** Spotify IDs of the user's top artists (long + medium term). */
    val topArtistIds: List<String> = emptyList()
)

/** Reads and writes the artist library to/from internal app storage. */
class ArtistTrackCache(context: Context) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val gson = Gson()

    val isEmpty: Boolean get() = !file.exists() || file.length() == 0L

    fun load(): ArtistLibrary {
        if (isEmpty) return ArtistLibrary()
        return try {
            val type = object : TypeToken<ArtistLibrary>() {}.type
            gson.fromJson<ArtistLibrary>(file.readText(), type) ?: ArtistLibrary()
        } catch (e: Exception) {
            Log.w(TAG, "Artist library load failed — returning empty: ${e.message}")
            ArtistLibrary()
        }
    }

    fun save(library: ArtistLibrary) {
        try {
            file.writeText(gson.toJson(library))
            Log.d(TAG, "Artist library saved: ${library.followedArtists.size} artists")
        } catch (e: Exception) {
            Log.w(TAG, "Artist library save failed: ${e.message}")
        }
    }

    fun clear() {
        file.delete()
        Log.d(TAG, "Artist library cleared")
    }
}
