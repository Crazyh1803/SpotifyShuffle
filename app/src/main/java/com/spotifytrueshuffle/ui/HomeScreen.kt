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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onLoginClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    SpotifyTrueShuffleTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
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
                        is MainViewModel.UiState.NotLoggedIn -> NotLoggedInContent(onLoginClick)
                        is MainViewModel.UiState.LoggedIn    -> LoggedInContent(state, viewModel)
                        is MainViewModel.UiState.Building    -> BuildingContent(state)
                        is MainViewModel.UiState.Success     -> SuccessContent(state, viewModel)
                        is MainViewModel.UiState.Error       -> ErrorContent(state, viewModel)
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
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.cachedArtistCount > 0) {
            // ── Cache status badge ──────────────────────────────────────────
            Text(
                text = "Library: ${state.cachedArtistCount} artists cached",
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
                text = "Builds are instant — no API calls needed.",
                color = SpotifyLightGray.copy(alpha = 0.55f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
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
                Text("Check for New Artists", fontWeight = FontWeight.Medium)
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
                text = "The app will build a local library of your artists' tracks (~20 seconds). Future playlist builds are instant.",
                color = SpotifyLightGray,
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(32.dp))
            SpotifyButton(
                text = "Build Artist Library & Playlist",
                onClick = { viewModel.buildPlaylist() }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = { viewModel.logout() }) {
            Text("Log out", color = SpotifyLightGray.copy(alpha = 0.5f))
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
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = { viewModel.logout() }) {
            Text("Log out", color = SpotifyLightGray.copy(alpha = 0.5f))
        }
    }
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
