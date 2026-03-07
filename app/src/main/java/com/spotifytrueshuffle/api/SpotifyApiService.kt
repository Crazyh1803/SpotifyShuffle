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
     */
    @GET("me/top/artists")
    suspend fun getTopArtists(
        @Query("time_range") timeRange: String = "long_term",
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): PagingObject<Artist>

    /**
     * Returns up to 10 of the artist's most popular tracks.
     * [market] is optional — when null (omitted), Spotify infers the market from
     * the OAuth token, which is the correct behaviour for logged-in users.
     */
    @GET("artists/{id}/top-tracks")
    suspend fun getArtistTopTracks(
        @Path("id") artistId: String,
        @Query("market") market: String? = null
    ): ArtistTopTracksResponse

    // ── Playlists ─────────────────────────────────────────────────────────────

    @GET("me/playlists")
    suspend fun getUserPlaylists(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): PagingObject<UserPlaylist>

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
