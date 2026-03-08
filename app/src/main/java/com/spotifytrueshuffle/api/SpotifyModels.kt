package com.spotifytrueshuffle.api

import com.google.gson.annotations.SerializedName

// ── User ────────────────────────────────────────────────────────────────────

data class UserProfile(
    val id: String,
    @SerializedName("display_name") val displayName: String?,
    val country: String?,
    val images: List<SpotifyImage>?
)

// ── Artist ───────────────────────────────────────────────────────────────────

data class Artist(
    val id: String,
    val name: String,
    val popularity: Int,
    val genres: List<String>?,
    val images: List<SpotifyImage>?
)

// ── Track ────────────────────────────────────────────────────────────────────

data class Track(
    val id: String,
    val name: String,
    @SerializedName("duration_ms") val durationMs: Int,
    val popularity: Int,
    val uri: String,
    val artists: List<ArtistSimple>,
    val album: AlbumSimple,
    @SerializedName("preview_url") val previewUrl: String?
)

data class ArtistSimple(
    val id: String,
    val name: String
)

data class AlbumSimple(
    val id: String,
    val name: String,
    @SerializedName("release_date") val releaseDate: String?,
    val images: List<SpotifyImage>?
)

// ── Shared ───────────────────────────────────────────────────────────────────

data class SpotifyImage(
    val url: String,
    val width: Int?,
    val height: Int?
)

data class ExternalUrls(
    val spotify: String?
)

// ── Pagination ───────────────────────────────────────────────────────────────

/** Standard offset-based paging (used by top artists, user playlists, etc.) */
data class PagingObject<T>(
    val items: List<T>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val next: String?
)

/** Cursor-based paging (used by followed artists). */
data class CursorBasedPaging<T>(
    val items: List<T>,
    val total: Int,
    val limit: Int,
    val next: String?,
    val cursors: Cursor?
)

data class Cursor(
    val after: String?,
    val before: String?
)

// ── API Response wrappers ────────────────────────────────────────────────────

data class FollowedArtistsResponse(
    val artists: CursorBasedPaging<Artist>
)

data class ArtistTopTracksResponse(
    val tracks: List<Track>
)

/** Response from GET /search (tracks) */
data class SearchTracksResponse(
    val tracks: PagingObject<Track>
)

/** A track saved to the user's library via GET /me/tracks. */
data class SavedTrack(
    @SerializedName("added_at") val addedAt: String?,
    val track: Track
)

/**
 * A track in simplified form — used inside album track listings (GET /me/albums).
 * Unlike the full Track, SimplifiedTrack has no popularity field; we default to 0
 * when converting, which causes the shuffle engine to treat these as "deep cuts"
 * (biased toward less-popular selections for rare artists — a good default).
 */
data class SimplifiedTrack(
    val id: String,
    val name: String,
    @SerializedName("duration_ms") val durationMs: Int,
    val uri: String,
    val artists: List<ArtistSimple>,
    @SerializedName("preview_url") val previewUrl: String?,
    @SerializedName("is_local") val isLocal: Boolean = false
)

/** Album object with its track listing — nested inside SavedAlbum. */
data class AlbumWithTracks(
    val id: String,
    val name: String,
    @SerializedName("release_date") val releaseDate: String?,
    val images: List<SpotifyImage>?,
    val tracks: PagingObject<SimplifiedTrack>
)

/** A saved (liked) album from GET /me/albums. Requires user-library-read scope. */
data class SavedAlbum(
    @SerializedName("added_at") val addedAt: String?,
    val album: AlbumWithTracks
)

data class SnapshotResponse(
    @SerializedName("snapshot_id") val snapshotId: String
)

// ── Playlist ─────────────────────────────────────────────────────────────────

data class Playlist(
    val id: String,
    val name: String,
    val uri: String,
    @SerializedName("external_urls") val externalUrls: ExternalUrls?,
    val tracks: PlaylistTracksRef?
)

data class PlaylistTracksRef(val total: Int)

data class UserPlaylist(
    val id: String,
    val name: String,
    val owner: PlaylistOwner,
    val tracks: PlaylistTracksRef?
)

data class PlaylistOwner(val id: String)

// ── Request bodies ───────────────────────────────────────────────────────────

data class CreatePlaylistRequest(
    val name: String,
    val description: String,
    @SerializedName("public") val isPublic: Boolean = false
)

data class TracksBody(
    val uris: List<String>
)
