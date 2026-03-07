package com.spotifytrueshuffle.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for Spotify OAuth tokens using AES-256 encrypted SharedPreferences.
 * Also stores the playlist ID so we can update the same playlist on subsequent runs.
 */
class TokenStorage(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "spotify_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var tokenExpiry: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        set(value) = prefs.edit().putLong(KEY_TOKEN_EXPIRY, value).apply()

    /** Stored so we update the same playlist rather than creating a new one each time. */
    var playlistId: String?
        get() = prefs.getString(KEY_PLAYLIST_ID, null)
        set(value) = prefs.edit().putString(KEY_PLAYLIST_ID, value).apply()

    /**
     * Returns true if we have an access token that won't expire for at least 60 seconds.
     */
    fun isTokenValid(): Boolean =
        accessToken != null && System.currentTimeMillis() < tokenExpiry - 60_000L

    fun isLoggedIn(): Boolean = refreshToken != null

    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_TOKEN_EXPIRY = "token_expiry"
        const val KEY_PLAYLIST_ID = "playlist_id"
    }
}
