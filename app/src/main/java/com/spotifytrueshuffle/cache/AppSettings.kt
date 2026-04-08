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
    val discoveryBias: Int = 60,
    val playlistDurationMs: Long = 2L * 60 * 60 * 1000,
    val trackRescanIntervalDays: Int = 30
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

    fun saveTrackRescanIntervalDays(days: Int) {
        save(load().copy(trackRescanIntervalDays = days))
    }
}
