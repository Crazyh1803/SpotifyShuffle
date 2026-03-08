package com.spotifytrueshuffle.api

import retrofit2.Response
import retrofit2.http.*

interface SpotifyApiService {

    // ── User ─────────────────────────────────────────────────────────────────

    @GET("me")
    suspend fun getUserProfile(): UserProfile

    // ── Artists ───────────────────────────────────────────────────────────────

    /**
     * Cursor-based pagination — pass [after] from previous response's cursors.after.
     * Null [after] means "start from the beginning".
     */
    @GET("me/following")
    suspend fun getFollowedArtists(
        @Query("type") type: String = "artist",
        @Query("limit") limit: Int = 50,
        @Query("after") after: String? = null
    ): FollowedArtistsResponse

    /**
     * [timeRange]: "long_term" (years), "medium_term" (6 months), "short_term" (4 weeks)
     * Requires user-top-read scope.
     */
    @GET("me/top/tracks")
    suspend fun getTopTracks(
        @Query("time_range") timeRange: String = "long_term",
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): PagingObject<Track>

    /**
     * [timeRange]: "long_term" (years), "medium_term" (6 months), "short_term" (4 weeks)
     */
    @GET("me/top/artists")
    suspend fun getTopArtists(
        @Query("time_range") timeRange: String = "long_term",
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): PagingObject<Artist>

    /**
     * Returns up to 10 of the artist's most popular tracks.
     * [market] is the ISO 3166-1 alpha-2 country code to resolve catalog availability.
     */
    @GET("artists/{id}/top-tracks")
    suspend fun getArtistTopTracks(
        @Path("id") artistId: String,
        @Query("market") market: String? = null
    ): ArtistTopTracksResponse

    /**
     * Full-text search for tracks.
     * Used as a fallback when top-tracks returns 403 (known Spotify dev-mode quirk).
     * Use q="artist:\"<name>\"" to restrict results to tracks by that artist.
     * Returns full Track objects including popularity — compatible with our shuffle engine.
     */
    @GET("search")
    suspend fun searchTracks(
        @Query("q") query: String,
        @Query("type") type: String = "track",
        @Query("market") market: String,
        @Query("limit") limit: Int = 10
    ): SearchTracksResponse

    /**
     * Paginated list of tracks saved to the user's library ("liked songs").
     * Requires user-library-read scope.
     */
    @GET("me/tracks")
    suspend fun getSavedTracks(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): PagingObject<SavedTrack>

    // ── Playlists ─────────────────────────────────────────────────────────────

    @GET("me/playlists")
    suspend fun getUserPlaylists(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): PagingObject<UserPlaylist>

    /**
     * Create a playlist for the authenticated user using the token's own identity.
     * Preferred over POST users/{id}/playlists which 403s in Spotify dev mode
     * for non-/me/ paths even when scopes are correctly granted.
     */
    @POST("me/playlists")
    suspend fun createPlaylistForMe(
        @Body request: CreatePlaylistRequest
    ): Playlist

    /** Legacy endpoint — kept as fallback in case me/playlists returns 404. */
    @POST("users/{userId}/playlists")
    suspend fun createPlaylist(
        @Path("userId") userId: String,
        @Body request: CreatePlaylistRequest
    ): Playlist

    /**
     * Replaces ALL tracks in the playlist (up to 100 URIs per call).
     * Returns the new snapshot_id, or 404 if the playlist no longer exists.
     */
    @PUT("playlists/{playlistId}/tracks")
    suspend fun replacePlaylistTracks(
        @Path("playlistId") playlistId: String,
        @Body body: TracksBody
    ): Response<SnapshotResponse>

    /**
     * Appends tracks (used if we need to add >100 tracks in subsequent batches).
     */
    @POST("playlists/{playlistId}/tracks")
    suspend fun addTracksToPlaylist(
        @Path("playlistId") playlistId: String,
        @Body body: TracksBody
    ): Response<SnapshotResponse>
}
