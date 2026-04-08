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
 * Builds the Retrofit-backed [SpotifyApiService].
 *
 * Extracted from [com.spotifytrueshuffle.MainActivity] so that background workers
 * ([com.spotifytrueshuffle.background.PlaylistRebuildWorker]) can create the service
 * without depending on Activity context.
 */
fun buildApiService(tokenStorage: TokenStorage): SpotifyApiService {
    val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC   // BODY is too verbose for background tasks
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
