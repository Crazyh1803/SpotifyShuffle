package com.spotifytrueshuffle.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.spotifytrueshuffle.api.SpotifyRepository
import com.spotifytrueshuffle.auth.SpotifyAuthManager
import com.spotifytrueshuffle.auth.TokenStorage
import com.spotifytrueshuffle.background.BuildEventBus
import com.spotifytrueshuffle.background.PlaylistBuildForegroundService
import com.spotifytrueshuffle.background.PlaylistBuildService
import com.spotifytrueshuffle.background.PlaylistRebuildWorker
import com.spotifytrueshuffle.cache.AppSettingsStorage
import com.spotifytrueshuffle.cache.ArtistLibrary
import com.spotifytrueshuffle.cache.ArtistTrackCache
import com.spotifytrueshuffle.cache.ShuffleHistoryStorage
import com.spotifytrueshuffle.cache.TrackPoolCache
import com.spotifytrueshuffle.shuffle.TrueShuffleEngine
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MainViewModel"
private const val WORK_TAG = "auto_rebuild"

class MainViewModel(
    private val authManager: SpotifyAuthManager,
    private val repository: SpotifyRepository,
    private val tokenStorage: TokenStorage,
    private val shuffleEngine: TrueShuffleEngine,
    private val artistCache: ArtistTrackCache,
    private val trackPoolCache: TrackPoolCache,
    private val historyStorage: ShuffleHistoryStorage,
    private val appSettings: AppSettingsStorage,
    private val appContext: Context
) : ViewModel() {

    sealed class UiState {
        /** First-time launch: user hasn't entered their Spotify Developer Client ID yet. */
        object Setup : UiState()
        object NotLoggedIn : UiState()
        /** Logged in and ready. Shows artist library size if available. */
        data class LoggedIn(
            val cachedArtistCount: Int = 0,
            val lastRefreshed: String? = null
        ) : UiState()
        data class Building(
            val progress: String,
            val step: Int,
            val totalSteps: Int,
            val animationArtistNames: List<String> = emptyList()
        ) : UiState()
        data class Success(
            val playlistUrl: String,
            val trackCount: Int,
            val durationMinutes: Int,
            val artistCount: Int,
            /** Number of tracks from Tier C (pure discovery artists). */
            val tierCCount: Int = 0,
            /** Number of tracks from Tier B (familiar non-top artists). */
            val tierBCount: Int = 0,
            /** Number of tracks from Tier A (top artists). */
            val tierACount: Int = 0
        ) : UiState()
        data class Error(val message: String) : UiState()
    }

    // ── Delegated build service (shared with background Worker) ───────────────

    private val buildService = PlaylistBuildService(
        repository      = repository,
        artistCache     = artistCache,
        trackPoolCache  = trackPoolCache,
        shuffleEngine   = shuffleEngine,
        historyStorage  = historyStorage,
        appSettings     = appSettings,
        tokenStorage    = tokenStorage
    )

    // ── State flows ───────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<UiState>(UiState.NotLoggedIn)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** How many past playlists an artist/track must skip before being eligible again (1–10). */
    private val _cooldownCount = MutableStateFlow(historyStorage.load().cooldownPlaylists)
    val cooldownCount: StateFlow<Int> = _cooldownCount.asStateFlow()

    /** 0–100 discovery bias slider (maps to Tier C weight in the engine). */
    private val _discoveryBias = MutableStateFlow(appSettings.load().discoveryBias)
    val discoveryBias: StateFlow<Int> = _discoveryBias.asStateFlow()

    /** Target playlist duration in milliseconds. */
    private val _playlistDurationMs = MutableStateFlow(appSettings.load().playlistDurationMs)
    val playlistDurationMs: StateFlow<Long> = _playlistDurationMs.asStateFlow()

    /** Whether the Settings bottom sheet is currently visible. */
    private val _settingsVisible = MutableStateFlow(false)
    val settingsVisible: StateFlow<Boolean> = _settingsVisible.asStateFlow()

    /** True when the birthday prompt dialog should be shown. */
    private val _showBirthdayPrompt = MutableStateFlow(false)
    val showBirthdayPrompt: StateFlow<Boolean> = _showBirthdayPrompt.asStateFlow()

    /** Increments at ~100ms while a build is running; drives the artist-name scroll animation. */
    private val _buildingAnimTick = MutableStateFlow(0)
    val buildingAnimTick: StateFlow<Int> = _buildingAnimTick.asStateFlow()

    /** 0 = disabled; 1–30 = auto-rebuild every N days. */
    private val _autoRebuildDays = MutableStateFlow(appSettings.load().autoRebuildDays)
    val autoRebuildDays: StateFlow<Int> = _autoRebuildDays.asStateFlow()

    /** How many playlists must pass before the same artist can appear again (1–20). */
    private val _artistCooldownPlaylists = MutableStateFlow(appSettings.load().artistCooldownPlaylists)
    val artistCooldownPlaylists: StateFlow<Int> = _artistCooldownPlaylists.asStateFlow()

    init {
        val settings = appSettings.load()
        _uiState.value = when {
            settings.clientId.isEmpty() -> UiState.Setup
            authManager.isLoggedIn()    -> loggedInState()
            else                        -> UiState.NotLoggedIn
        }
        // Re-register periodic work after app updates or device reboots
        if (settings.autoRebuildDays > 0) scheduleOrCancelRebuild(settings.autoRebuildDays)
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun openSettings()  { _settingsVisible.value = true  }
    fun closeSettings() { _settingsVisible.value = false }

    /**
     * Called when the user finishes entering their Client ID on the Setup screen.
     * Saves the ID and transitions to the normal NotLoggedIn home screen.
     */
    fun completeSetup(clientId: String) {
        appSettings.saveClientId(clientId.trim())
        _uiState.value = UiState.NotLoggedIn
    }

    /**
     * Returns the user to the Setup screen so they can change their Client ID.
     * Closes the settings sheet first if it's open.
     */
    fun changeClientId() {
        _settingsVisible.value = false
        _uiState.value = UiState.Setup
    }

    /** Updates the cooldown setting and persists it immediately. */
    fun setCooldownCount(n: Int) {
        val clamped = n.coerceIn(1, 10)
        _cooldownCount.value = clamped
        historyStorage.saveCooldownCount(clamped)
    }

    /** Updates the discovery bias (0–100) and persists it. */
    fun setDiscoveryBias(bias: Int) {
        val clamped = bias.coerceIn(0, 100)
        _discoveryBias.value = clamped
        appSettings.saveDiscoveryBias(clamped)
    }

    /** Updates the target playlist duration (ms) and persists it. */
    fun setPlaylistDuration(ms: Long) {
        _playlistDurationMs.value = ms
        appSettings.savePlaylistDuration(ms)
    }

    /** Updates the artist repeat cooldown (1–20 playlists) and persists it. */
    fun setArtistCooldownPlaylists(n: Int) {
        val clamped = n.coerceIn(1, 20)
        _artistCooldownPlaylists.value = clamped
        appSettings.saveArtistCooldownPlaylists(clamped)
    }

    /** Clears cooldown history (resets artist/track suppression) without touching the cooldown count setting. */
    fun clearCooldownHistory() {
        historyStorage.clearHistory()
        Log.d(TAG, "Cooldown history cleared by user")
    }

    /**
     * Updates the auto-rebuild interval and reschedules (or cancels) the WorkManager job.
     * @param days 0 = disabled, 1–30 = rebuild every N days.
     */
    fun setAutoRebuildDays(days: Int) {
        val clamped = days.coerceIn(0, 30)
        _autoRebuildDays.value = clamped
        appSettings.saveAutoRebuildDays(clamped)
        scheduleOrCancelRebuild(clamped)
    }

    private fun scheduleOrCancelRebuild(days: Int) {
        val wm = WorkManager.getInstance(appContext)
        if (days == 0) {
            wm.cancelUniqueWork(WORK_TAG)
            Log.d(TAG, "Auto-rebuild cancelled")
        } else {
            val request = PeriodicWorkRequestBuilder<PlaylistRebuildWorker>(
                days.toLong(), TimeUnit.DAYS
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()
            wm.enqueueUniquePeriodicWork(WORK_TAG, ExistingPeriodicWorkPolicy.REPLACE, request)
            Log.d(TAG, "Auto-rebuild scheduled every $days day(s)")
        }
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    fun handleAuthCallback(code: String) {
        viewModelScope.launch {
            val success = authManager.handleCallback(code)
            _uiState.value = if (success) loggedInState()
                             else UiState.Error("Login failed — please try again")
        }
    }

    fun logout() {
        tokenStorage.clearAll()
        _uiState.value = UiState.NotLoggedIn
    }

    fun dismissError() {
        _uiState.value = if (authManager.isLoggedIn()) loggedInState() else UiState.NotLoggedIn
    }

    /** Resets the UI to the LoggedIn state without starting a build. */
    fun backToHome() {
        _uiState.value = loggedInState()
    }

    // ── Build Playlist ───────────────────────────────────────────────────────

    /**
     * Starts the foreground playlist build flow.
     * Delegates core logic to [PlaylistBuildService]; this function manages UiState transitions.
     */
    fun buildPlaylist() {
        viewModelScope.launch {
            // Snapshot + shuffle artist names once for the animation reel
            val animationNames = withContext(Dispatchers.IO) {
                artistCache.load().followedArtists.map { it.name }.shuffled()
            }

            // Tick coroutine drives the BuildingContent scroll animation independently
            val tickJob = launch {
                while (true) {
                    delay(100L)
                    _buildingAnimTick.value++
                }
            }

            _uiState.value = UiState.Building("Starting…", 0, 4, animationNames)

            // Start foreground service — keeps the process alive even when the screen is off
            // or the app is in the background (a PARTIAL_WAKE_LOCK alone doesn't prevent this).
            val serviceIntent = Intent(appContext, PlaylistBuildForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }

            // Collect progress updates from the service and forward them to the UI
            val progressJob = launch {
                BuildEventBus.progress.collect { p ->
                    _uiState.value = UiState.Building(p.message, p.step, p.total, animationNames)
                }
            }

            try {
                // Wait for the single result the service emits when it finishes
                val result = BuildEventBus.results.first()
                progressJob.cancel()

                when (result) {
                    is PlaylistBuildService.BuildResult.Success -> {
                        _uiState.value = UiState.Success(
                            playlistUrl     = result.playlistUrl,
                            trackCount      = result.trackCount,
                            durationMinutes = result.durationMinutes,
                            artistCount     = result.artistCount,
                            tierCCount      = result.tierCCount,
                            tierBCount      = result.tierBCount,
                            tierACount      = result.tierACount
                        )
                    }
                    is PlaylistBuildService.BuildResult.Failure -> {
                        _uiState.value = UiState.Error(result.message)
                    }
                    is PlaylistBuildService.BuildResult.NotLoggedIn -> {
                        _uiState.value = UiState.Error("Session expired. Tap Log Out & Re-authorize.")
                    }
                    is PlaylistBuildService.BuildResult.NoClientId -> {
                        _uiState.value = UiState.Setup
                    }
                }
            } finally {
                progressJob.cancel()
                tickJob.cancel()
                _buildingAnimTick.value = 0
            }
        }
    }

    /**
     * Re-fetches the full followed-artist list and top-artist IDs, then saves to cache.
     * This is a fast operation (a handful of paginated API calls — no track fetching).
     * Called when the user taps "Refresh Artists".
     */
    fun refreshArtists() {
        viewModelScope.launch {
            try {
                progress("Loading your artist library…", 1, 2)
                val followed = repository.getAllFollowedArtists()
                val topIds   = repository.getTopArtists().map { it.id }
                artistCache.save(
                    ArtistLibrary(
                        lastRefreshedMs = System.currentTimeMillis(),
                        followedArtists = followed,
                        topArtistIds    = topIds
                    )
                )
                // Artist library changed — invalidate track pool so next build does a full rescan
                buildService.invalidateTrackPool()
                _uiState.value = loggedInState()
            } catch (e: Exception) {
                Log.e(TAG, "Refresh artists failed", e)
                _uiState.value = UiState.Error(e.message ?: "Failed to refresh artists. Please try again.")
            }
        }
    }

    // ── Birthday prompt ──────────────────────────────────────────────────────

    /** Checks whether today is June 17 and the prompt hasn't been shown this year. */
    fun checkBirthdayPrompt() {
        viewModelScope.launch(Dispatchers.IO) {
            val cal   = Calendar.getInstance()
            val month = cal.get(Calendar.MONTH) + 1   // Calendar.MONTH is 0-based
            val day   = cal.get(Calendar.DAY_OF_MONTH)
            val year  = cal.get(Calendar.YEAR)
            if (month == 6 && day == 17) {
                val lastYear = appSettings.load().lastBirthdayYear
                if (lastYear < year) {
                    _showBirthdayPrompt.value = true
                }
            }
        }
    }

    /** Dismisses the prompt and records the current year so it won't show again until next June 17. */
    fun dismissBirthdayPrompt() {
        _showBirthdayPrompt.value = false
        viewModelScope.launch(Dispatchers.IO) {
            appSettings.saveLastBirthdayYear(Calendar.getInstance().get(Calendar.YEAR))
        }
    }

    // ── Export ───────────────────────────────────────────────────────────────

    /**
     * Writes a de-duplicated, sorted CSV of every followed artist name to the device's
     * Downloads folder. Returns the file name on success, or null on failure.
     */
    suspend fun exportArtistList(context: Context): String? = withContext(Dispatchers.IO) {
        val names = artistCache.load().followedArtists
            .map { it.name }
            .distinct()
            .sorted()
        if (names.isEmpty()) return@withContext null

        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val fileName = "shuffle_all_artists_$date.csv"
        val csv = names.joinToString("\n")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return@withContext null
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, fileName).writeText(csv)
            }
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "Artist export failed", e)
            null
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun loggedInState(): UiState.LoggedIn {
        val lib = artistCache.load()
        return UiState.LoggedIn(
            cachedArtistCount = lib.followedArtists.size,
            lastRefreshed     = formatDate(lib.lastRefreshedMs)
        )
    }

    private fun formatDate(ms: Long): String? {
        if (ms == 0L) return null
        val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        return date.format(DateTimeFormatter.ofPattern("MMM d"))
    }

    private fun progress(message: String, step: Int, totalSteps: Int) {
        _uiState.value = UiState.Building(message, step, totalSteps)
    }
}

// ── Factory ──────────────────────────────────────────────────────────────────

class MainViewModelFactory(
    private val authManager: SpotifyAuthManager,
    private val repository: SpotifyRepository,
    private val tokenStorage: TokenStorage,
    private val shuffleEngine: TrueShuffleEngine,
    private val trackCache: ArtistTrackCache,
    private val trackPoolCache: TrackPoolCache,
    private val historyStorage: ShuffleHistoryStorage,
    private val appSettings: AppSettingsStorage,
    private val appContext: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MainViewModel(
            authManager, repository, tokenStorage, shuffleEngine,
            trackCache, trackPoolCache, historyStorage, appSettings, appContext
        ) as T
}
