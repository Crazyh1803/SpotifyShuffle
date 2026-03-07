package com.spotifytrueshuffle.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SpotifyGreen = Color(0xFF1DB954)
val SpotifyBlack = Color(0xFF121212)
val SpotifyDarkGray = Color(0xFF282828)
val SpotifyLightGray = Color(0xFFB3B3B3)

private val DarkColors = darkColorScheme(
    primary = SpotifyGreen,
    onPrimary = Color.Black,
    secondary = SpotifyGreen,
    onSecondary = Color.Black,
    background = SpotifyBlack,
    onBackground = Color.White,
    surface = SpotifyDarkGray,
    onSurface = Color.White,
    error = Color(0xFFCF6679),
    onError = Color.Black
)

@Composable
fun SpotifyTrueShuffleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
