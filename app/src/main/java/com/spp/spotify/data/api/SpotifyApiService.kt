package com.spp.spotify.data.api

import com.spp.spotify.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface SpotifyApiService {

    @GET("v1/me")
    suspend fun getCurrentUser(): Response<User>

    @GET("v1/browse/featured-playlists")
    suspend fun getFeaturedPlaylists(
        @Query("limit") limit: Int = 20,
        @Query("country") country: String = "US"
    ): Response<FeaturedPlaylistsResponse>

    @GET("v1/browse/new-releases")
    suspend fun getNewReleases(
        @Query("limit") limit: Int = 20,
        @Query("country") country: String = "US"
    ): Response<NewReleasesResponse>

    @GET("v1/browse/categories")
    suspend fun getCategories(
        @Query("limit") limit: Int = 50,
        @Query("country") country: String = "US"
    ): Response<CategoriesResponse>

    @GET("v1/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("type") type: String = "track,artist,album,playlist",
        @Query("limit") limit: Int = 20
    ): Response<SearchResponse>

    @GET("v1/me/playlists")
    suspend fun getUserPlaylists(
        @Query("limit") limit: Int = 50
    ): Response<PagingObject<Playlist>>

    @GET("v1/me/tracks")
    suspend fun getLikedTracks(
        @Query("limit") limit: Int = 50
    ): Response<PagingObject<SavedTrack>>

    @GET("v1/me/albums")
    suspend fun getSavedAlbums(
        @Query("limit") limit: Int = 50
    ): Response<PagingObject<SavedAlbum>>

    @GET("v1/playlists/{id}/tracks")
    suspend fun getPlaylistTracks(
        @Path("id") playlistId: String,
        @Query("limit") limit: Int = 100
    ): Response<PagingObject<PlaylistTrack>>

    @GET("v1/me/player")
    suspend fun getPlayerState(): Response<PlayerState?>

    @PUT("v1/me/player/play")
    suspend fun play(
        @Body request: PlayContextRequest? = null,
        @Query("device_id") deviceId: String? = null
    ): Response<Unit>

    @PUT("v1/me/player/pause")
    suspend fun pause(@Query("device_id") deviceId: String? = null): Response<Unit>

    @POST("v1/me/player/next")
    suspend fun skipToNext(@Query("device_id") deviceId: String? = null): Response<Unit>

    @POST("v1/me/player/previous")
    suspend fun skipToPrevious(@Query("device_id") deviceId: String? = null): Response<Unit>

    @PUT("v1/me/player/seek")
    suspend fun seekToPosition(
        @Query("position_ms") positionMs: Long,
        @Query("device_id") deviceId: String? = null
    ): Response<Unit>

    @PUT("v1/me/player/shuffle")
    suspend fun setShuffle(
        @Query("state") state: Boolean,
        @Query("device_id") deviceId: String? = null
    ): Response<Unit>

    @PUT("v1/me/player/repeat")
    suspend fun setRepeat(
        @Query("state") state: String,
        @Query("device_id") deviceId: String? = null
    ): Response<Unit>

    @PUT("v1/me/player/volume")
    suspend fun setVolume(
        @Query("volume_percent") volumePercent: Int,
        @Query("device_id") deviceId: String? = null
    ): Response<Unit>

    @PUT("v1/me/tracks")
    suspend fun saveTracks(@Query("ids") ids: String): Response<Unit>

    @DELETE("v1/me/tracks")
    suspend fun removeTracks(@Query("ids") ids: String): Response<Unit>

    @GET("v1/me/tracks/contains")
    suspend fun checkSavedTracks(@Query("ids") ids: String): Response<List<Boolean>>

    @GET("v1/me/top/tracks")
    suspend fun getTopTracks(
        @Query("time_range") timeRange: String = "medium_term",
        @Query("limit") limit: Int = 50
    ): Response<PagingObject<Track>>

    @GET("v1/me/player/recently-played")
    suspend fun getRecentlyPlayed(
        @Query("limit") limit: Int = 20
    ): Response<PagingObject<PlayHistoryObject>>
}
