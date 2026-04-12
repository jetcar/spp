package com.spp.spotify.data.model

import com.google.gson.annotations.SerializedName

data class SpotifyImage(
    @SerializedName("url") val url: String,
    @SerializedName("height") val height: Int?,
    @SerializedName("width") val width: Int?
)

data class Followers(@SerializedName("total") val total: Int)

data class Artist(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("uri") val uri: String,
    @SerializedName("images") val images: List<SpotifyImage>? = null,
    @SerializedName("genres") val genres: List<String>? = null,
    @SerializedName("followers") val followers: Followers? = null,
    @SerializedName("popularity") val popularity: Int? = null
)

data class Album(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("uri") val uri: String,
    @SerializedName("artists") val artists: List<Artist>,
    @SerializedName("images") val images: List<SpotifyImage>,
    @SerializedName("release_date") val releaseDate: String,
    @SerializedName("total_tracks") val totalTracks: Int,
    @SerializedName("album_type") val albumType: String = "",
    @SerializedName("tracks") val tracks: PagingObject<Track>? = null
)

data class Track(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("uri") val uri: String,
    @SerializedName("artists") val artists: List<Artist>,
    @SerializedName("album") val album: Album? = null,
    @SerializedName("duration_ms") val durationMs: Long,
    @SerializedName("preview_url") val previewUrl: String?,
    @SerializedName("explicit") val explicit: Boolean = false,
    @SerializedName("track_number") val trackNumber: Int = 0,
    @SerializedName("popularity") val popularity: Int? = null,
    @SerializedName("is_local") val isLocal: Boolean = false
)

data class Playlist(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("uri") val uri: String,
    @SerializedName("description") val description: String?,
    @SerializedName("images") val images: List<SpotifyImage>,
    @SerializedName("owner") val owner: PlaylistOwner,
    @SerializedName("tracks") val tracks: TracksInfo?,
    @SerializedName("public") val public: Boolean?,
    @SerializedName("collaborative") val collaborative: Boolean = false
)

data class PlaylistOwner(
    @SerializedName("id") val id: String,
    @SerializedName("display_name") val displayName: String?
)

data class TracksInfo(
    @SerializedName("total") val total: Int,
    @SerializedName("href") val href: String,
    @SerializedName("items") val items: List<PlaylistTrack>? = null
)

data class PlaylistTrack(
    @SerializedName("added_at") val addedAt: String?,
    @SerializedName("track") val track: Track?
)

data class User(
    @SerializedName("id") val id: String,
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("email") val email: String?,
    @SerializedName("images") val images: List<SpotifyImage>?,
    @SerializedName("followers") val followers: Followers?,
    @SerializedName("uri") val uri: String,
    @SerializedName("country") val country: String? = null,
    @SerializedName("product") val product: String? = null
)

data class PagingObject<T>(
    @SerializedName("items") val items: List<T>,
    @SerializedName("total") val total: Int,
    @SerializedName("limit") val limit: Int,
    @SerializedName("offset") val offset: Int,
    @SerializedName("next") val next: String?,
    @SerializedName("previous") val previous: String?
)

data class FeaturedPlaylistsResponse(
    @SerializedName("message") val message: String?,
    @SerializedName("playlists") val playlists: PagingObject<Playlist>
)

data class NewReleasesResponse(
    @SerializedName("albums") val albums: PagingObject<Album>
)

data class SearchResponse(
    @SerializedName("tracks") val tracks: PagingObject<Track>?,
    @SerializedName("artists") val artists: PagingObject<Artist>?,
    @SerializedName("albums") val albums: PagingObject<Album>?,
    @SerializedName("playlists") val playlists: PagingObject<Playlist>?
)

data class Category(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("icons") val icons: List<SpotifyImage>
)

data class CategoriesResponse(
    @SerializedName("categories") val categories: PagingObject<Category>
)

data class PlayerState(
    @SerializedName("is_playing") val isPlaying: Boolean,
    @SerializedName("item") val track: Track?,
    @SerializedName("progress_ms") val progressMs: Long?,
    @SerializedName("shuffle_state") val shuffleState: Boolean,
    @SerializedName("repeat_state") val repeatState: String,
    @SerializedName("device") val device: Device?
)

data class Device(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: String,
    @SerializedName("volume_percent") val volumePercent: Int?,
    @SerializedName("is_active") val isActive: Boolean
)

data class DevicesResponse(@SerializedName("devices") val devices: List<Device>)

data class SavedTrack(
    @SerializedName("added_at") val addedAt: String,
    @SerializedName("track") val track: Track
)

data class SavedAlbum(
    @SerializedName("added_at") val addedAt: String,
    @SerializedName("album") val album: Album
)

data class PlayContextRequest(
    @SerializedName("context_uri") val contextUri: String? = null,
    @SerializedName("uris") val uris: List<String>? = null,
    @SerializedName("offset") val offset: Map<String, Any>? = null,
    @SerializedName("position_ms") val positionMs: Long? = null
)

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("scope") val scope: String,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("refresh_token") val refreshToken: String?
)

data class RecommendationsResponse(
    @SerializedName("tracks") val tracks: List<Track>
)

data class PlayHistoryObject(
    @SerializedName("track") val track: Track,
    @SerializedName("played_at") val playedAt: String
)
