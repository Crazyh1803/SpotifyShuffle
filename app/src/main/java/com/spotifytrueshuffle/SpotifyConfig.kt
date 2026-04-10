package com.spotifytrueshuffle

object SpotifyConfig {
    // CLIENT_ID is no longer hardcoded here.
    // Users enter their own Spotify Developer Client ID on first launch.
    // It is stored in AppSettings and injected into SpotifyAuthManager as a lambda.

    const val REDIRECT_URI = "com.appsbydan.trueshuffle://callback"
    const val AUTH_URL = "https://accounts.spotify.com/authorize"
    const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    const val API_BASE_URL = "https://api.spotify.com/v1/"

    /**
     * Required OAuth scopes:
     * - user-read-private:        read user's country (Spotify needs this to serve the
     *                             correct regional tracks from artists/{id}/top-tracks)
     * - user-follow-read:         read who the user follows
     * - user-top-read:            read user's top artists (to identify frequently heard)
     * - playlist-modify-public/private: create and update the playlist
     */
    const val SCOPES = "user-read-private user-follow-read user-top-read user-library-read playlist-modify-public playlist-modify-private"

    const val PLAYLIST_NAME = "True Shuffle — All Artists"
}
