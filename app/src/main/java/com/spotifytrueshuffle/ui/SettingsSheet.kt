package com.spotifytrueshuffle.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.spotifytrueshuffle.ui.theme.SpotifyGreen
import com.spotifytrueshuffle.ui.theme.SpotifyLightGray

/** Duration preset options shown in the segmented button row. */
private val DURATION_OPTIONS = listOf(
    "30 min" to 30L * 60 * 1000,
    "1 hr"   to 60L * 60 * 1000,
    "1.5 hr" to 90L * 60 * 1000,
    "2 hr"   to 2L * 60 * 60 * 1000,
    "3 hr"   to 3L * 60 * 60 * 1000
)

/**
 * Settings bottom sheet containing all user-configurable options:
 *   • Repeat cooldown slider (1–10 playlists)
 *   • Discovery mix slider (0 % Familiar ↔ 100 % Discovery)
 *   • Playlist duration segmented buttons (30 min / 1 hr / 1.5 hr / 2 hr / 3 hr)
 *   • Reset cooldown memory
 *   • Log out
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val cooldownCount      by viewModel.cooldownCount.collectAsState()
    val discoveryBias      by viewModel.discoveryBias.collectAsState()
    val playlistDurationMs by viewModel.playlistDurationMs.collectAsState()
    val rescanDays         by viewModel.trackRescanIntervalDays.collectAsState()
    val context            = LocalContext.current
    val scope              = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "Settings",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            // ── Repeat Cooldown ──────────────────────────────────────────────
            SettingSection(title = "Repeat cooldown") {
                Text(
                    text = "$cooldownCount ${if (cooldownCount == 1) "playlist" else "playlists"}",
                    color = SpotifyGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = cooldownCount.toFloat(),
                    onValueChange = { viewModel.setCooldownCount(it.toInt()) },
                    valueRange = 1f..10f,
                    steps = 8,  // integer steps between 1 and 10 (10 - 1 - 1 = 8)
                    colors = SliderDefaults.colors(
                        thumbColor = SpotifyGreen,
                        activeTrackColor = SpotifyGreen,
                        inactiveTrackColor = SpotifyLightGray.copy(alpha = 0.3f),
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1", color = SpotifyLightGray.copy(alpha = 0.5f), fontSize = 11.sp)
                    Text("10", color = SpotifyLightGray.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            }

            // ── Discovery Mix ────────────────────────────────────────────────
            SettingSection(title = "Discovery mix") {
                Text(
                    text = "$discoveryBias% discovery",
                    color = SpotifyGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = discoveryBias.toFloat(),
                    onValueChange = { viewModel.setDiscoveryBias(it.toInt()) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = SpotifyGreen,
                        activeTrackColor = SpotifyGreen,
                        inactiveTrackColor = SpotifyLightGray.copy(alpha = 0.3f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Familiar", color = SpotifyLightGray.copy(alpha = 0.5f), fontSize = 11.sp)
                    Text("Discovery", color = SpotifyLightGray.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            }

            // ── Playlist Duration ────────────────────────────────────────────
            SettingSection(title = "Playlist duration") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    DURATION_OPTIONS.forEachIndexed { index, (label, ms) ->
                        SegmentedButton(
                            selected = playlistDurationMs == ms,
                            onClick = { viewModel.setPlaylistDuration(ms) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = DURATION_OPTIONS.size
                            ),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor  = SpotifyGreen.copy(alpha = 0.15f),
                                activeContentColor    = SpotifyGreen,
                                activeBorderColor     = SpotifyGreen,
                                inactiveContainerColor = Color.Transparent,
                                inactiveContentColor  = SpotifyLightGray,
                                inactiveBorderColor   = SpotifyLightGray.copy(alpha = 0.3f)
                            ),
                            icon = {}  // suppress the default check-mark icon
                        ) {
                            Text(label, fontSize = 11.sp)
                        }
                    }
                }
            }

            // ── Track Library ────────────────────────────────────────────────
            SettingSection(title = "Track library") {
                // Auto-rescan interval stepper
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Auto-rescan",
                        color = SpotifyLightGray,
                        fontSize = 14.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.setTrackRescanIntervalDays(rescanDays - 1) },
                            enabled = rescanDays > 0
                        ) {
                            Text(
                                "−",
                                fontSize = 20.sp,
                                color = if (rescanDays > 0) SpotifyGreen else SpotifyLightGray.copy(alpha = 0.3f)
                            )
                        }
                        Text(
                            text = if (rescanDays == 0) "Manual" else "Every $rescanDays ${if (rescanDays == 1) "day" else "days"}",
                            color = SpotifyGreen,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.widthIn(min = 96.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        IconButton(
                            onClick = { viewModel.setTrackRescanIntervalDays(rescanDays + 1) },
                            enabled = rescanDays < 365
                        ) {
                            Text(
                                "+",
                                fontSize = 20.sp,
                                color = if (rescanDays < 365) SpotifyGreen else SpotifyLightGray.copy(alpha = 0.3f)
                            )
                        }
                    }
                }

                // Scan for new tracks button
                TextButton(
                    onClick = {
                        viewModel.rescanAllTracks()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Scan for new tracks",
                        color = SpotifyGreen.copy(alpha = 0.85f),
                        fontSize = 14.sp
                    )
                }
            }

            // ── Buy me a drink ───────────────────────────────────────────────
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.buymeacoffee.com/AppsbyDan"))
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFFFFDD00),
                    contentColor = androidx.compose.ui.graphics.Color.Black
                )
            ) {
                Text("Buy me a drink \uD83C\uDF7A", fontWeight = FontWeight.Bold)
            }

            // ── Export Artist List ───────────────────────────────────────────
            TextButton(
                onClick = {
                    scope.launch {
                        val fileName = viewModel.exportArtistList(context)
                        withContext(Dispatchers.Main) {
                            if (fileName != null) {
                                Toast.makeText(context, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Export failed — load your artist library first", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Export artist list to Downloads",
                    color = SpotifyLightGray.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            }

            HorizontalDivider(
                color = SpotifyLightGray.copy(alpha = 0.15f),
                thickness = 1.dp
            )

            // ── Reset cooldown memory ────────────────────────────────────────
            TextButton(
                onClick = {
                    viewModel.clearCooldownHistory()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Reset cooldown memory",
                    color = SpotifyLightGray.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            }

            // ── Change Client ID ─────────────────────────────────────────────
            TextButton(
                onClick = { viewModel.changeClientId() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Change Spotify Client ID",
                    color = SpotifyLightGray.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )
            }

            // ── Log Out ──────────────────────────────────────────────────────
            TextButton(
                onClick = {
                    viewModel.logout()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Log out",
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun SettingSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(),
            color = SpotifyLightGray.copy(alpha = 0.6f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp
        )
        content()
    }
}
