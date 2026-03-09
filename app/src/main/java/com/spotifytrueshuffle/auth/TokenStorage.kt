package com.spotifytrueshuffle.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

private const val TAG = "TokenStorage"
private const val PREFS_FILE = "spotify_secure_prefs"

/**
 * Secure storage for Spotify OAuth tokens using AES-256 encrypted SharedPreferences.
 * Also stores the playlist ID so we can update the same playlist on subsequent runs.
 *
 * Self-healing: if the Android Keystore entry becomes stale (e.g. on some devices the
 * Keystore key survives an app uninstall while the encrypted prefs file is wiped),
 * the AEADBadTagException is caught, the corrupted data is cleared, and a fresh
 * EncryptedSharedPreferences is created. The user will need to log in again but
 * the app will not crash.
 */
class TokenStorage(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context)

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            buildEncryptedPrefs(context)
        } catch (e: Exception) {
            // Known Android bug: Keystore entry survives app uninstall on some devices,
            // but the encrypted prefs file is wiped — decryption fails with AEADBadTagException.
            // Fix: delete the stale Keystore entry + prefs file and start fresh.
            Log.w(TAG, "EncryptedSharedPreferences init failed — wiping stale keystore data: ${e.message}")
            try {
                context.deleteSharedPreferences(PREFS_FILE)
                val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
                val alias = MasterKey.DEFAULT_MASTER_KEY_ALIAS
                if (ks.containsAlias(alias)) ks.deleteEntry(alias)
            } catch (wipeEx: Exception) {
                Log.e(TAG, "Failed to wipe stale keystore entry: ${wipeEx.message}")
            }
            buildEncryptedPrefs(context)
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
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
     * PKCE code verifier — persisted across process death so the OAuth callback
     * still succeeds on real devices where Android kills the process while
     * Chrome Custom Tab is in the foreground.
     * Cleared immediately after a successful token exchange.
     */
    var pendingCodeVerifier: String?
        get() = prefs.getString(KEY_CODE_VERIFIER, null)
        set(value) = if (value != null) prefs.edit().putString(KEY_CODE_VERIFIER, value).apply()
                     else prefs.edit().remove(KEY_CODE_VERIFIER).apply()

    /** The scope string Spotify actually returned in the last token exchange (for diagnostics). */
    var grantedScopes: String?
        get() = prefs.getString(KEY_GRANTED_SCOPES, null)
        set(value) = prefs.edit().putString(KEY_GRANTED_SCOPES, value).apply()

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
        const val KEY_GRANTED_SCOPES = "granted_scopes"
        const val KEY_CODE_VERIFIER = "pkce_code_verifier"
    }
}
