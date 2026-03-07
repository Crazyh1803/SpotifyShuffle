package com.spotifytrueshuffle

object SpotifyConfig {
    /**
     * Paste your Spotify app's Client ID here.
     * Create one at: https://developer.spotify.com/dashboard
     * Add redirect URI: com.spotifytrueshuffle://callback
     */
    const val CLIENT_ID = "d78040ff13d64a558f845bdf22d02f08"

    const val REDIRECT_URI = "com.spotifytrueshuffle://callback"
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
    const val SCOPES = "user-read-private user-follow-read user-top-read playlist-modify-public playlist-modify-private"

    const val PLAYLIST_NAME = "True Shuffle — All Artists"

    /** Target playlist duration: 2 hours */
    const val TARGET_DURATION_MS = 2L * 60 * 60 * 1000
}
