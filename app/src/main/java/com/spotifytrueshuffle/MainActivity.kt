package com.spotifytrueshuffle

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.spotifytrueshuffle.api.AuthInterceptor
import com.spotifytrueshuffle.api.SpotifyApiService
import com.spotifytrueshuffle.api.SpotifyRepository
import com.spotifytrueshuffle.auth.SpotifyAuthManager
import com.spotifytrueshuffle.auth.TokenStorage
import com.spotifytrueshuffle.cache.AppSettingsStorage
import com.spotifytrueshuffle.cache.ArtistTrackCache
import com.spotifytrueshuffle.cache.ShuffleHistoryStorage
import com.spotifytrueshuffle.shuffle.TrueShuffleEngine
import com.spotifytrueshuffle.ui.HomeScreen
import com.spotifytrueshuffle.ui.MainViewModel
import com.spotifytrueshuffle.ui.MainViewModelFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Single-activity entry point.
 *
 * Responsible for:
 *   1. Wiring up dependencies (no DI framework — kept simple)
 *   2. Hosting the Compose UI
 *   3. Receiving the OAuth redirect URI intent and forwarding it to the ViewModel
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var authManager: SpotifyAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Dependency wiring ────────────────────────────────────────────────
        val tokenStorage = TokenStorage(applicationContext)
        authManager = SpotifyAuthManager(tokenStorage)
        val apiService = buildApiService(tokenStorage)
        val repository = SpotifyRepository(apiService, authManager, tokenStorage)
        val shuffleEngine = TrueShuffleEngine()
        val trackCache = ArtistTrackCache(applicationContext)
        val historyStorage = ShuffleHistoryStorage(applicationContext)
        val appSettings = AppSettingsStorage(applicationContext)

        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(authManager, repository, tokenStorage, shuffleEngine, trackCache, historyStorage, appSettings)
        )[MainViewModel::class.java]

        // ── UI ───────────────────────────────────────────────────────────────
        setContent {
            HomeScreen(
                viewModel = viewModel,
                // Pass the Activity as context so Chrome Custom Tabs can launch properly
                onLoginClick = { authManager.launchAuthFlow(this@MainActivity) }
            )
        }

        // Handle the case where the activity is launched directly via the redirect URI
        handleIntent(intent)
    }

    /**
     * Called when the activity is already running (singleTop) and a new intent arrives.
     * This is the normal path for Chrome Custom Tab redirects.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return

        // Check it's our redirect URI: com.spotifytrueshuffle://callback
        if (uri.scheme == "com.spotifytrueshuffle" && uri.host == "callback") {
            val code = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")

            when {
                code != null -> {
                    viewModel.handleAuthCallback(code)
                    setIntent(Intent())
                }
                error != null -> {
                    // User cancelled or denied — stay on the login screen
                    setIntent(Intent())
                }
            }
        }
    }

    // ── Retrofit / OkHttp setup ──────────────────────────────────────────────

    private fun buildApiService(tokenStorage: TokenStorage): SpotifyApiService {
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
}
