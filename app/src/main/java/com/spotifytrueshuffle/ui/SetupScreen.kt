package com.spotifytrueshuffle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotifytrueshuffle.ui.theme.*

private const val REDIRECT_URI = "com.appsbydan.trueshuffle://callback"

/**
 * First-time setup screen shown when no Spotify Developer Client ID has been entered yet.
 *
 * The user must register a free Spotify Developer App at developer.spotify.com/dashboard
 * and enter their 32-character Client ID here before they can log in.
 *
 * @param onContinue  Called with the validated Client ID string when the user taps Continue.
 * @param onViewGuide Called when the user taps the "View step-by-step guide" button.
 */
@Composable
fun SetupScreen(
    onContinue: (String) -> Unit,
    onViewGuide: () -> Unit
) {
    var clientId by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current

    fun validate(): Boolean {
        val trimmed = clientId.trim()
        errorMessage = when {
            trimmed.isEmpty()           -> "Please enter your Client ID"
            trimmed.length != 32        -> "Client ID should be 32 characters (yours is ${trimmed.length})"
            !trimmed.all { it.isLetterOrDigit() } -> "Client ID should only contain letters and numbers"
            else                        -> null
        }
        return errorMessage == null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SpotifyBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Header ──────────────────────────────────────────────────────────

        Text(
            text = "🔀",
            fontSize = 52.sp
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Welcome to True Shuffle",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "True Shuffle is a free, open-source app. To keep it working for everyone, " +
                "each user connects their own free Spotify Developer account. " +
                "This takes about 2 minutes and you only have to do it once.",
            style = MaterialTheme.typography.bodyMedium,
            color = SpotifyLightGray,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(Modifier.height(28.dp))

        // ── Step-by-step guide button ────────────────────────────────────────

        OutlinedButton(
            onClick = onViewGuide,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SpotifyGreen),
            border = androidx.compose.foundation.BorderStroke(1.dp, SpotifyGreen)
        ) {
            Text("View step-by-step guide →")
        }

        Spacer(Modifier.height(28.dp))

        // ── Redirect URI reminder card ───────────────────────────────────────

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Required Redirect URI",
                    style = MaterialTheme.typography.labelMedium,
                    color = SpotifyLightGray
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = REDIRECT_URI,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = SpotifyGreen,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(REDIRECT_URI))
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Copy", fontSize = 13.sp, color = SpotifyGreen)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Add this exactly as shown when creating your Spotify app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // ── Client ID input ──────────────────────────────────────────────────

        Text(
            text = "Enter Your Client ID",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = clientId,
            onValueChange = {
                clientId = it
                if (errorMessage != null) errorMessage = null
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    "Paste your 32-character Client ID",
                    color = SpotifyLightGray.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
            },
            singleLine = true,
            isError = errorMessage != null,
            supportingText = {
                if (errorMessage != null) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrect = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    if (validate()) onContinue(clientId.trim())
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SpotifyGreen,
                unfocusedBorderColor = SpotifyLightGray.copy(alpha = 0.4f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = SpotifyGreen
            ),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(Modifier.height(24.dp))

        // ── Continue button ──────────────────────────────────────────────────

        Button(
            onClick = {
                focusManager.clearFocus()
                if (validate()) onContinue(clientId.trim())
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen)
        ) {
            Text(
                "Continue",
                color = SpotifyBlack,
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Your Client ID is stored only on this device and is never shared.",
            style = MaterialTheme.typography.bodySmall,
            color = SpotifyLightGray.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
    }
}
