package com.spotifytrueshuffle.network

import com.spotifytrueshuffle.SpotifyConfig
import com.spotifytrueshuffle.api.AuthInterceptor
import com.spotifytrueshuffle.api.SpotifyApiService
import com.spotifytrueshuffle.auth.TokenStorage
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Builds a configured [SpotifyApiService] for the given [tokenStorage].
 *
 * Extracted here so both [com.spotifytrueshuffle.MainActivity] and
 * [com.spotifytrueshuffle.background.PlaylistRebuildWorker] can share the same
 * construction logic without duplicating OkHttp/Retrofit setup.
 */
fun buildApiService(tokenStorage: TokenStorage): SpotifyApiService {
    val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    val client = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokenStorage))
        .addInterceptor(logging)
        .build()
    return Retrofit.Builder()
        .baseUrl(SpotifyConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SpotifyApiService::class.java)
}
