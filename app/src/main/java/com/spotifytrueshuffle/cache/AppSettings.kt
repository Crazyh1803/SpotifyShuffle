package com.spotifytrueshuffle.cache

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

private const val TAG = "AppSettings"
private const val SETTINGS_FILE = "app_settings.json"

/**
 * User-configurable app settings persisted to internal storage.
 *
 * @param clientId                  Spotify Developer App Client ID entered by the user on first launch.
 *                                  Empty string means setup has not been completed yet.
 * @param discoveryBias             0–100 slider position: 0 = all familiar, 100 = all discovery.
 *                                  Default 60 maps to cPerCycle=3 (38 % C, 50 % B, 13 % A).
 * @param playlistDurationMs        Target playlist length in milliseconds. Default 2 hours.
 * @param trackRescanIntervalDays   How often gap-artist tracks are re-fetched automatically.
 *                                  0 = manual only; 1–365 = rescan every N days.
 *                                  Stale entries are re-scanned in batches on each build.
 */
data class AppSettings(
    val clientId: String = "",
    /** 0 = disabled; 1–30 = auto-rebuild playlist every N days via WorkManager. */
    val autoRebuildDays: Int = 0,
    val discoveryBias: Int = 60,
    val playlistDurationMs: Long = 2L * 60 * 60 * 1000,
    /** How many playlists must pass before the same artist can appear again (1–20). */
    val artistCooldownPlaylists: Int = 2,
    val trackRescanIntervalDays: Int = 30,
    /** Last known scan progress — persisted so the status survives app restarts.
     *  -1 means no build has completed yet. */
    val lastScanScanned: Int = -1,
    val lastScanTotal: Int = -1,
    /** Per-strategy breakdown from the most recent gap-fill scan. Empty if no scan has run. */
    val lastScanLog: String = "",
    /**
     * Only relevant when the user has zero followed artists (liked-songs-only mode).
     * false (default) = Strict: shuffle only the exact songs the user has liked.
     * true = Explore: also include top tracks and saved albums from those artists.
     * Has no effect for users who follow artists — their flow is unchanged.
     */
    val likedSongsExploreMode: Boolean = false
)

/** Reads and writes [AppSettings] to/from internal app storage via Gson. */
class AppSettingsStorage(context: Context) {

    private val file = File(context.filesDir, SETTINGS_FILE)
    private val gson = Gson()

    fun load(): AppSettings {
        if (!file.exists()) return AppSettings()
        return try {
            val type = object : TypeToken<AppSettings>() {}.type
            gson.fromJson<AppSettings>(file.readText(), type) ?: AppSettings()
        } catch (e: Exception) {
            Log.w(TAG, "Settings load failed — returning defaults: ${e.message}")
            AppSettings()
        }
    }

    fun save(settings: AppSettings) {
        try {
            file.writeText(gson.toJson(settings))
            Log.d(TAG, "Settings saved: discoveryBias=${settings.discoveryBias}, " +
                "durationMs=${settings.playlistDurationMs}")
        } catch (e: Exception) {
            Log.w(TAG, "Settings save failed: ${e.message}")
        }
    }

    fun saveClientId(id: String) {
        save(load().copy(clientId = id))
    }

    fun saveDiscoveryBias(bias: Int) {
        save(load().copy(discoveryBias = bias))
    }

    fun savePlaylistDuration(ms: Long) {
        save(load().copy(playlistDurationMs = ms))
    }

    fun saveAutoRebuildDays(days: Int) {
        save(load().copy(autoRebuildDays = days))
    }

    fun saveArtistCooldownPlaylists(n: Int) {
        save(load().copy(artistCooldownPlaylists = n))
    }

    fun saveTrackRescanIntervalDays(days: Int) {
        save(load().copy(trackRescanIntervalDays = days))
    }

    fun saveLastScanProgress(scanned: Int, total: Int) {
        save(load().copy(lastScanScanned = scanned, lastScanTotal = total))
    }

    fun saveLastScanLog(log: String) {
        save(load().copy(lastScanLog = log))
    }

    fun saveLikedSongsExploreMode(enabled: Boolean) {
        save(load().copy(likedSongsExploreMode = enabled))
    }
}
