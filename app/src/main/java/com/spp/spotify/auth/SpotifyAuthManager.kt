package com.spp.spotify.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.spp.spotify.BuildConfig
import com.spp.spotify.data.api.NetworkModule
import java.security.MessageDigest
import java.security.SecureRandom

object SpotifyAuthManager {

    private const val AUTH_URL = "https://accounts.spotify.com/authorize"

    const val SCOPES = "user-read-private user-read-email user-library-read " +
            "user-library-modify user-read-playback-state user-modify-playback-state " +
            "user-read-currently-playing playlist-read-private playlist-read-collaborative " +
            "user-top-read user-read-recently-played streaming"

    private var codeVerifier: String? = null

    fun generateAuthUrl(): String {
        val verifier = generateCodeVerifier()
        codeVerifier = verifier
        val challenge = generateCodeChallenge(verifier)
        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", BuildConfig.SPOTIFY_REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("show_dialog", "false")
            .build().toString()
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    suspend fun exchangeCodeForTokens(context: Context, code: String): Boolean {
        val verifier = codeVerifier ?: return false
        return try {
            val response = NetworkModule.authService.getToken(
                grantType = "authorization_code",
                code = code,
                redirectUri = BuildConfig.SPOTIFY_REDIRECT_URI,
                clientId = BuildConfig.SPOTIFY_CLIENT_ID,
                codeVerifier = verifier
            )
            if (response.isSuccessful) {
                val body = response.body()!!
                TokenManager.saveTokens(context, body.accessToken, body.refreshToken, body.expiresIn)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun refreshAccessToken(context: Context): Boolean {
        val refreshToken = TokenManager.getRefreshToken(context) ?: return false
        return try {
            val response = NetworkModule.authService.refreshToken(
                refreshToken = refreshToken,
                clientId = BuildConfig.SPOTIFY_CLIENT_ID
            )
            if (response.isSuccessful) {
                val body = response.body()!!
                TokenManager.saveTokens(context, body.accessToken, body.refreshToken ?: refreshToken, body.expiresIn)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }
}
