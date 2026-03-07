# True Shuffle for Spotify

An Android app that builds a ~2-hour playlist containing tracks from **every artist you follow** on Spotify — not just the ones you listen to most.

Spotify's built-in shuffle heavily weights artists and songs you already listen to frequently. This app works like the old iPod "Shuffle All": it spreads the selection across your entire library of followed artists, surfacing artists you forgot you followed alongside your favorites.

---

## How the shuffle works

Artists are sorted into two tiers based on your Spotify listening history:

| Tier | Who | Playlist weight |
|------|-----|-----------------|
| **A — Frequently heard** | In your long-term or medium-term top 50 artists | ~1 in 3 tracks |
| **B — Rarely heard** | Followed but not in your top artists | ~2 in 3 tracks |

Within each tier, tracks are chosen at random. For Tier-B artists, the algorithm biases toward **less-popular tracks** (deep cuts) so you hear songs Spotify would never normally surface.

The final playlist is ~2 hours long (~30 songs at 4 min average).

---

## Setup

### 1. Create a Spotify Developer app

1. Go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
2. Click **Create app**
3. Fill in any name/description
4. Under **Redirect URIs**, add exactly:
   ```
   com.spotifytrueshuffle://callback
   ```
5. Check **Web API** under APIs used
6. Save and copy your **Client ID**

### 2. Add your Client ID to the app

Open `app/src/main/java/com/spotifytrueshuffle/SpotifyConfig.kt` and replace the placeholder:

```kotlin
const val CLIENT_ID = "YOUR_SPOTIFY_CLIENT_ID"  // ← paste here
```

### 3. Build and install

Open the project folder in **Android Studio** (Electric Eel or newer):

1. File → Open → select this folder
2. Wait for Gradle sync to finish
3. Connect your Android phone (API 26 / Android 8.0 or higher) or start an emulator
4. Click **Run** (▶)

---

## Usage

1. Tap **Connect with Spotify** and log in
2. Tap **Build True Shuffle Playlist** — the app fetches your followed artists and builds the playlist (takes ~30–60 seconds depending on how many artists you follow)
3. Tap **Open in Spotify** to start listening
4. Tap **Refresh Playlist** any time to regenerate with a different random selection

The playlist is saved to your Spotify account as a private playlist named **"True Shuffle — All Artists"**. Refreshing replaces the same playlist rather than creating a new one.

---

## Required Spotify scopes

| Scope | Why |
|-------|-----|
| `user-follow-read` | Read your followed artists |
| `user-top-read` | Identify frequently vs. rarely heard artists |
| `playlist-modify-private` | Create/update the playlist |
| `playlist-modify-public` | (requested together with private as recommended by Spotify) |

---

## Project structure

```
app/src/main/java/com/spotifytrueshuffle/
├── SpotifyConfig.kt          ← Client ID, redirect URI, target duration
├── MainActivity.kt           ← Single activity; hosts Compose UI; handles OAuth redirect
├── auth/
│   ├── PKCEUtils.kt          ← PKCE code verifier + challenge generation
│   ├── TokenStorage.kt       ← Encrypted SharedPreferences for tokens
│   └── SpotifyAuthManager.kt ← OAuth flow + token refresh
├── api/
│   ├── SpotifyModels.kt      ← Gson data classes for Spotify API responses
│   ├── SpotifyApiService.kt  ← Retrofit interface
│   ├── AuthInterceptor.kt    ← Injects Bearer token on every request
│   └── SpotifyRepository.kt  ← Data layer; handles pagination + token refresh
├── shuffle/
│   └── TrueShuffleEngine.kt  ← The core algorithm
└── ui/
    ├── MainViewModel.kt       ← State + business logic; orchestrates the build
    ├── HomeScreen.kt          ← Compose UI
    └── theme/
        └── Theme.kt           ← Spotify-style dark theme
```

---

## Notes

- The app samples up to **50 Tier-A + 80 Tier-B artists** per build to stay within Spotify's API rate limits. If you follow hundreds of artists, a different random sample is taken each time you refresh.
- Tokens are stored in AES-256 encrypted SharedPreferences. They are not accessible to other apps.
- The playlist is **private** by default.
