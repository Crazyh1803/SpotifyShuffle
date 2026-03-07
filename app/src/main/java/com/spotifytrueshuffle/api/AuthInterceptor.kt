package com.spotifytrueshuffle.api

import com.spotifytrueshuffle.auth.TokenStorage
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Injects the Spotify Bearer token into every outgoing API request.
 * Token freshness is guaranteed by SpotifyRepository.ensureValidToken()
 * before each call, so this interceptor can simply read the stored value.
 */
class AuthInterceptor(private val tokenStorage: TokenStorage) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStorage.accessToken
            ?: throw IllegalStateException("No access token available")

        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()

        return chain.proceed(request)
    }
}
