package com.spotifytrueshuffle.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.spotifytrueshuffle.SpotifyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val TAG = "SpotifyAuth"

/**
 * Manages Spotify OAuth 2.0 PKCE flow.
 *
 * Flow:
 * 1. [launchAuthFlow] — opens Spotify login in a Chrome Custom Tab
 * 2. Spotify redirects to com.appsbydan.trueshuffle://callback?code=...
 * 3. MainActivity receives the intent, calls [handleCallback]
 * 4. [handleCallback] exchanges the code for tokens and stores them
 * 5. [refreshTokenIfNeeded] is called before each API request
 */
class SpotifyAuthManager(
    private val tokenStorage: TokenStorage,
    /** Returns the Spotify Client ID entered by the user during first-time setup. */
    private val getClientId: () -> String
) {
    // Plain OkHttp client (no auth interceptor) for token endpoint calls
    private val httpClient = OkHttpClient()

    fun isLoggedIn(): Boolean = tokenStorage.isLoggedIn()

    /**
     * Opens the Spotify authorization page in a Chrome Custom Tab.
     * Must be called with an Activity context so the Custom Tab can be launched.
     */
    fun launchAuthFlow(activityContext: Context) {
        val verifier = PKCEUtils.generateCodeVerifier()
        // Persist across process death — real devices kill the host process while
        // Chrome Custom Tab is in the foreground; without this the callback fails.
        tokenStorage.pendingCodeVerifier = verifier
        val challenge = PKCEUtils.generateCodeChallenge(verifier)

        val authUri = Uri.parse(SpotifyConfig.AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", getClientId())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SpotifyConfig.REDIRECT_URI)
            .appendQueryParameter("scope", SpotifyConfig.SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            // Force Spotify to always show the consent screen so the user explicitly
            // grants the current scope list (including any newly-added scopes like
            // user-read-private). Without this, Spotify silently reuses old consent
            // and the new token may be missing scopes added after first auth.
            .appendQueryParameter("show_dialog", "true")
            .build()

        Log.d(TAG, "Launching auth URL: $authUri")
        CustomTabsIntent.Builder().build().launchUrl(activityContext, authUri)
    }

    /**
     * Exchange the authorization code for access + refresh tokens.
     * Called from MainActivity when the redirect URI is received.
     */
    suspend fun handleCallback(code: String): Boolean = withContext(Dispatchers.IO) {
        val verifier = tokenStorage.pendingCodeVerifier
        if (verifier == null) {
            Log.e(TAG, "No pending code verifier — was launchAuthFlow called? (process may have been killed)")
            return@withContext false
        }

        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", SpotifyConfig.REDIRECT_URI)
            .add("client_id", getClientId())
            .add("code_verifier", verifier)
            .build()

        return@withContext exchangeTokens(body).also { success ->
            if (success) tokenStorage.pendingCodeVerifier = null  // clean up
        }
    }

    /**
     * Ensures we have a valid access token, refreshing if necessary.
     * Returns false if the refresh fails (user needs to log in again).
     */
    suspend fun refreshTokenIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (tokenStorage.isTokenValid()) return@withContext true

        val refreshToken = tokenStorage.refreshToken
            ?: return@withContext false

        Log.d(TAG, "Access token expired, refreshing...")

        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", getClientId())
            .build()

        val success = exchangeTokens(body)
        if (!success) {
            Log.w(TAG, "Token refresh failed — clearing tokens")
            tokenStorage.clearTokens()
        }
        success
    }

    private fun exchangeTokens(body: FormBody): Boolean {
        return try {
            val request = Request.Builder()
                .url(SpotifyConfig.TOKEN_URL)
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body!!.string())
                saveTokens(json)
                Log.d(TAG, "Tokens saved successfully")
                true
            } else {
                Log.e(TAG, "Token exchange failed: ${response.code} ${response.body?.string()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange exception: ${e.message}")
            false
        }
    }

    private fun saveTokens(json: JSONObject) {
        tokenStorage.accessToken = json.getString("access_token")
        val expiresIn = json.getInt("expires_in")
        tokenStorage.tokenExpiry = System.currentTimeMillis() + (expiresIn * 1000L)

        // Refresh token is not always returned (only on first auth)
        if (json.has("refresh_token")) {
            tokenStorage.refreshToken = json.getString("refresh_token")
        }

        // Persist the scopes Spotify actually granted so we can surface them in error messages.
        val grantedScopes = if (json.has("scope")) json.getString("scope") else "(not returned)"
        tokenStorage.grantedScopes = grantedScopes
        Log.d(TAG, "Granted scopes: $grantedScopes")
        Log.d(TAG, "playlist-modify-public present: ${grantedScopes.contains("playlist-modify-public")}")
        Log.d(TAG, "user-read-private present: ${grantedScopes.contains("user-read-private")}")
    }
}
