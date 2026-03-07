package com.spotifytrueshuffle.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Utilities for OAuth 2.0 PKCE (Proof Key for Code Exchange).
 * PKCE is required for mobile apps that can't keep a client_secret.
 */
object PKCEUtils {

    /** Generates a cryptographically random code verifier (96 random bytes → base64url). */
    fun generateCodeVerifier(): String {
        val random = SecureRandom()
        val bytes = ByteArray(96)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Derives the code challenge from the verifier using SHA-256 → base64url. */
    fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
