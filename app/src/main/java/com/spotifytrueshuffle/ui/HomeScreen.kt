package com.spotifytrueshuffle.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifytrueshuffle.ui.theme.SpotifyGreen
import com.spotifytrueshuffle.ui.theme.SpotifyLightGray
import com.spotifytrueshuffle.ui.theme.SpotifyTrueShuffleTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onLoginClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val settingsVisible by viewModel.settingsVisible.collectAsState()

    SpotifyTrueShuffleTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // ── Setup / Walkthrough screens are fullscreen — skip normal layout ──
            if (uiState is MainViewModel.UiState.Setup) {
                var showWalkthrough by remember { mutableStateOf(false) }
                if (showWalkthrough) {
                    WalkthroughScreen(onBack = { showWalkthrough = false })
                } else {
                    SetupScreen(
                        onContinue = { clientId -> viewModel.completeSetup(clientId) },
                        onViewGuide = { showWalkthrough = true }
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    // ── Main centered content ────────────────────────────────
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        AppHeader()

                        Spacer(modifier = Modifier.height(56.dp))

                        AnimatedContent(
                            targetState = uiState,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "state"
                        ) { state ->
                            when (state) {
                                is MainViewModel.UiState.Setup       -> { /* handled above */ }
                                is MainViewModel.UiState.NotLoggedIn -> NotLoggedInContent(onLoginClick)
                                is MainViewModel.UiState.LoggedIn    -> LoggedInContent(state, viewModel)
                                is MainViewModel.UiState.Building    -> BuildingContent(state)
                                is MainViewModel.UiState.Success     -> SuccessContent(state, viewModel)
                                is MainViewModel.UiState.Error       -> ErrorContent(state, viewModel)
                            }
                        }
                    }

                    // ── Gear icon pinned to top-right corner ─────────────────
                    if (uiState is MainViewModel.UiState.LoggedIn ||
                        uiState is MainViewModel.UiState.Success) {
                        IconButton(
                            onClick = { viewModel.openSettings() },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 8.dp, end = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = SpotifyLightGray.copy(alpha = 0.7f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // ── Settings bottom sheet ─────────────────────────────────
                    if (settingsVisible) {
                        SettingsSheet(
                            viewModel = viewModel,
                            onDismiss = { viewModel.closeSettings() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Icon(
        imageVector = Icons.Default.MusicNote,
        contentDescription = null,
        tint = SpotifyGreen,
        modifier = Modifier.size(56.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "True Shuffle",
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White
    )
    Text(
        text = "for Spotify",
        fontSize = 16.sp,
        color = SpotifyLightGray
    )
}

@Composable
private fun NotLoggedInContent(onLoginClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Shuffle every artist you follow — not just your favorites.",
            color = SpotifyLightGray,
            textAlign = TextAlign.Center,
            fontSize = 15.sp
        )
        Spacer(modifier = Modifier.height(32.dp))
        SpotifyButton(
            text = "Connect with Spotify",
            onClick = onLoginClick
        )
    }
}

@Composable
private fun LoggedInContent(
    state: MainViewModel.UiState.LoggedIn,
    viewModel: MainViewModel
) {
    val scanProgress by viewModel.scanProgress.collectAsState()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.cachedArtistCount > 0) {
            // ── Library status badge ────────────────────────────────────────
            Text(
                text = "${state.cachedArtistCount} artists in library",
                color = SpotifyGreen,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            state.lastRefreshed?.let {
                Text(
                    text = "Last updated $it",
                    color = SpotifyLightGray.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Shuffles from your saved songs & top tracks.",
                color = SpotifyLightGray.copy(alpha = 0.55f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            // Scan progress summary — shows last known status from any previous build
            scanProgress?.let { progress ->
                val remaining = progress.total - progress.scanned
                Spacer(modifier = Modifier.height(4.dp))
                if (remaining > 0) {
                    Text(
                        text = "$remaining of ${progress.total} artists not yet scanned",
                        color = SpotifyGreen.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = "All ${progress.total} artists scanned ✓",
                        color = SpotifyGreen.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(modifier = Modifier.height(28.dp))

            SpotifyButton(
                text = "Build True Shuffle Playlist",
                onClick = { viewModel.buildPlaylist() }
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { viewModel.refreshArtists() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SpotifyGreen),
                border = BorderStroke(1.dp, SpotifyGreen)
            ) {
                Text("Refresh Artist Library", fontWeight = FontWeight.Medium)
            }
        } else {
            // ── First-time empty state ──────────────────────────────────────
            Text(
                text = "First time setup",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Loads your full artist list once, then builds each playlist from your saved songs and top tracks.",
                color = SpotifyLightGray,
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(32.dp))
            SpotifyButton(
                text = "Set Up & Build Playlist",
                onClick = { viewModel.buildPlaylist() }
            )
        }
    }
}

@Composable
private fun BuildingContent(state: MainViewModel.UiState.Building) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        CircularProgressIndicator(
            color = SpotifyGreen,
            strokeWidth = 3.dp,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = state.progress,
            color = Color.White,
            textAlign = TextAlign.Center,
            fontSize = 15.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { state.step.toFloat() / state.totalSteps },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = SpotifyGreen,
            trackColor = SpotifyLightGray.copy(alpha = 0.2f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Step ${state.step} of ${state.totalSteps}",
            color = SpotifyLightGray.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SuccessContent(
    state: MainViewModel.UiState.Success,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val scanProgress by viewModel.scanProgress.collectAsState()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = SpotifyGreen,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Playlist ready!",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${state.trackCount} tracks  •  ${state.durationMinutes} min  •  ${state.artistCount} artists",
            color = SpotifyLightGray,
            textAlign = TextAlign.Center
        )

        // ── Tier breakdown ───────────────────────────────────────────────────
        val tierText = buildTierText(state)
        if (tierText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = tierText,
                color = SpotifyLightGray.copy(alpha = 0.6f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }

        // ── Incremental scan progress ────────────────────────────────────────
        scanProgress?.let { progress ->
            val remaining = progress.total - progress.scanned
            Spacer(modifier = Modifier.height(6.dp))
            if (remaining > 0) {
                Text(
                    text = "$remaining of ${progress.total} artists not yet scanned · Tap Build to continue",
                    color = SpotifyGreen.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = "All ${progress.total} artists scanned ✓",
                    color = SpotifyGreen.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        SpotifyButton(
            text = "Open in Spotify",
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.playlistUrl)))
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = { viewModel.buildPlaylist() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SpotifyGreen),
            border = BorderStroke(1.dp, SpotifyGreen)
        ) {
            Text("Build New Playlist")
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = { viewModel.backToHome() }) {
            Text("← Back to home", color = SpotifyLightGray.copy(alpha = 0.6f), fontSize = 14.sp)
        }
    }
}

/** Builds a human-readable tier breakdown string, omitting any zero-count tiers. */
private fun buildTierText(state: MainViewModel.UiState.Success): String {
    val parts = buildList {
        if (state.tierCCount > 0) add("${state.tierCCount} discovery")
        if (state.tierBCount > 0) add("${state.tierBCount} familiar")
        if (state.tierACount > 0) add("${state.tierACount} top")
    }
    return parts.joinToString(" · ")
}

@Composable
private fun ErrorContent(
    state: MainViewModel.UiState.Error,
    viewModel: MainViewModel
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = state.message,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        SpotifyButton(
            text = "Try Again",
            onClick = { viewModel.dismissError() }
        )
        Spacer(modifier = Modifier.height(12.dp))
        // Always show Log Out on error screen — needed for re-auth after scope changes
        TextButton(onClick = { viewModel.logout() }) {
            Text("Log Out & Re-authorize", color = SpotifyLightGray)
        }
    }
}

@Composable
private fun SpotifyButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = SpotifyGreen,
            contentColor = Color.Black
        )
    ) {
        Text(text = text, fontWeight = FontWeight.Bold)
    }
}
