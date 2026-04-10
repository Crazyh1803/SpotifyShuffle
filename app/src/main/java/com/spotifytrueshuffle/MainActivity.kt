package com.spotifytrueshuffle

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.spotifytrueshuffle.api.SpotifyRepository
import com.spotifytrueshuffle.auth.SpotifyAuthManager
import com.spotifytrueshuffle.auth.TokenStorage
import com.spotifytrueshuffle.cache.AppSettingsStorage
import com.spotifytrueshuffle.cache.ArtistTrackCache
import com.spotifytrueshuffle.cache.GapArtistCache
import com.spotifytrueshuffle.cache.ShuffleHistoryStorage
import com.spotifytrueshuffle.network.buildApiService
import com.spotifytrueshuffle.shuffle.TrueShuffleEngine
import com.spotifytrueshuffle.ui.HomeScreen
import com.spotifytrueshuffle.ui.MainViewModel
import com.spotifytrueshuffle.ui.MainViewModelFactory

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
        val tokenStorage   = TokenStorage(applicationContext)
        val appSettings    = AppSettingsStorage(applicationContext)
        // Read client ID from TokenStorage (SharedPreferences) first — R8-safe.
        // Fall back to Gson settings file for installs that saved it there previously.
        authManager        = SpotifyAuthManager(tokenStorage) {
            tokenStorage.clientId?.takeIf { it.isNotEmpty() }
                ?: appSettings.load().clientId
        }
        val repository     = SpotifyRepository(buildApiService(tokenStorage), authManager, tokenStorage)
        val shuffleEngine  = TrueShuffleEngine()
        val trackCache     = ArtistTrackCache(applicationContext)
        val historyStorage = ShuffleHistoryStorage(applicationContext)
        val gapArtistCache = GapArtistCache(applicationContext)

        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(
                authManager, repository, tokenStorage, shuffleEngine,
                trackCache, historyStorage, appSettings, gapArtistCache,
                applicationContext
            )
        )[MainViewModel::class.java]

        // ── UI ───────────────────────────────────────────────────────────────
        setContent {
            HomeScreen(
                viewModel    = viewModel,
                onLoginClick = { authManager.launchAuthFlow(this@MainActivity) }
            )
        }

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
        if (uri.scheme == "com.appsbydan.trueshuffle" && uri.host == "callback") {
            val code  = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")
            when {
                code  != null -> { viewModel.handleAuthCallback(code); setIntent(Intent()) }
                error != null -> { setIntent(Intent()) }
            }
        }
    }
}
