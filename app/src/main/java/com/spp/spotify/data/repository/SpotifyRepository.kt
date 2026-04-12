package com.spp.spotify.data.repository

import android.content.Context
import com.spp.spotify.auth.SpotifyAuthManager
import com.spp.spotify.auth.TokenManager
import com.spp.spotify.data.api.NetworkModule
import com.spp.spotify.data.api.SpotifyApiService
import com.spp.spotify.data.model.*
import retrofit2.Response

class SpotifyRepository(context: Context) {

    private val api: SpotifyApiService = NetworkModule.createApiService(context.applicationContext)
    private val appContext: Context = context.applicationContext

    private suspend fun <T> safeCall(block: suspend () -> Response<T>): Result<T> {
        return try {
            if (TokenManager.getAccessToken(appContext) == null) {
                SpotifyAuthManager.refreshAccessToken(appContext)
            }
            val resp = block()
            when {
                resp.isSuccessful -> {
                    @Suppress("UNCHECKED_CAST")
                    val body = resp.body() ?: (Unit as T)
                    Result.success(body)
                }
                resp.code() == 401 -> {
                    val refreshed = SpotifyAuthManager.refreshAccessToken(appContext)
                    if (!refreshed) return Result.failure(Exception("Session expired. Please log in again."))
                    val retry = block()
                    if (retry.isSuccessful) {
                        @Suppress("UNCHECKED_CAST")
                        Result.success(retry.body() ?: (Unit as T))
                    } else {
                        Result.failure(Exception("API error ${retry.code()}: ${retry.message()}"))
                    }
                }
                resp.code() == 204 -> {
                    @Suppress("UNCHECKED_CAST")
                    Result.success(Unit as T)
                }
                else -> Result.failure(Exception("API error ${resp.code()}: ${resp.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCurrentUser() = safeCall { api.getCurrentUser() }
    suspend fun getFeaturedPlaylists() = safeCall { api.getFeaturedPlaylists() }
    suspend fun getNewReleases() = safeCall { api.getNewReleases() }
    suspend fun getCategories() = safeCall { api.getCategories() }
    suspend fun search(query: String) = safeCall { api.search(query) }
    suspend fun getUserPlaylists() = safeCall { api.getUserPlaylists() }
    suspend fun getLikedTracks() = safeCall { api.getLikedTracks() }
    suspend fun getSavedAlbums() = safeCall { api.getSavedAlbums() }
    suspend fun getPlaylistTracks(id: String) = safeCall { api.getPlaylistTracks(id) }
    suspend fun getRecentlyPlayed() = safeCall { api.getRecentlyPlayed() }
    suspend fun getTopTracks() = safeCall { api.getTopTracks() }

    suspend fun getPlayerState(): Result<PlayerState?> {
        return try {
            if (TokenManager.getAccessToken(appContext) == null) {
                SpotifyAuthManager.refreshAccessToken(appContext)
            }
            val resp = api.getPlayerState()
            when {
                resp.isSuccessful -> Result.success(resp.body())
                resp.code() == 204 -> Result.success(null)
                else -> Result.failure(Exception("API error ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun play(contextUri: String? = null, uris: List<String>? = null): Result<Unit> {
        val req = if (contextUri != null || uris != null)
            PlayContextRequest(contextUri = contextUri, uris = uris)
        else null
        return safeCall { api.play(req) }
    }

    suspend fun pause() = safeCall { api.pause() }
    suspend fun skipToNext() = safeCall { api.skipToNext() }
    suspend fun skipToPrevious() = safeCall { api.skipToPrevious() }
    suspend fun seekToPosition(ms: Long) = safeCall { api.seekToPosition(ms) }
    suspend fun setShuffle(state: Boolean) = safeCall { api.setShuffle(state) }
    suspend fun setRepeat(state: String) = safeCall { api.setRepeat(state) }
    suspend fun setVolume(pct: Int) = safeCall { api.setVolume(pct) }
    suspend fun saveTracks(ids: List<String>) = safeCall { api.saveTracks(ids.joinToString(",")) }
    suspend fun removeTracks(ids: List<String>) = safeCall { api.removeTracks(ids.joinToString(",")) }
    suspend fun checkSavedTracks(ids: List<String>) = safeCall { api.checkSavedTracks(ids.joinToString(",")) }
}
