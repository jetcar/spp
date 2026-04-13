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

    /**
     * Executes [block], refreshing the access token first if needed, and maps the HTTP response
     * to a [Result].  All Spotify "no content" responses (HTTP 204) and responses whose body is
     * legitimately null (e.g. Retrofit `Response<Unit>`) are handled by returning
     * `Result.success(Unit)`.  The single @Suppress is therefore bounded to this one utility
     * method rather than scattered across every call site.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> safeCall(block: suspend () -> Response<T>): Result<T> {
        return try {
            if (TokenManager.getAccessToken(appContext) == null) {
                SpotifyAuthManager.refreshAccessToken(appContext)
            }
            processResponse(block(), block)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> processResponse(
        resp: Response<T>,
        retryBlock: suspend () -> Response<T>
    ): Result<T> {
        return when {
            // 2xx success — body present
            resp.isSuccessful && resp.body() != null -> Result.success(resp.body()!!)
            // 204 No Content or other 2xx with empty body (e.g. Response<Unit>)
            resp.isSuccessful -> Result.success(Unit as T)
            // Token expired — try once more after a refresh
            resp.code() == 401 -> {
                val refreshed = SpotifyAuthManager.refreshAccessToken(appContext)
                if (!refreshed) return Result.failure(Exception("Session expired. Please log in again."))
                val retry = retryBlock()
                when {
                    retry.isSuccessful && retry.body() != null -> Result.success(retry.body()!!)
                    retry.isSuccessful -> Result.success(Unit as T)
                    else -> Result.failure(Exception("API error ${retry.code()}: ${retry.message()}"))
                }
            }
            else -> Result.failure(Exception("API error ${resp.code()}: ${resp.message()}"))
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
