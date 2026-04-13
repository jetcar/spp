package com.spp.spotify.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "spotify_tokens")

object TokenManager {

    private const val TOKEN_REFRESH_BUFFER_MS = 60_000L

    private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
    private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
    private val TOKEN_EXPIRY_KEY = longPreferencesKey("token_expiry")

    suspend fun saveTokens(
        context: Context,
        accessToken: String,
        refreshToken: String?,
        expiresIn: Int
    ) {
        context.dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN_KEY] = accessToken
            refreshToken?.let { prefs[REFRESH_TOKEN_KEY] = it }
            prefs[TOKEN_EXPIRY_KEY] = System.currentTimeMillis() + (expiresIn * 1000L)
        }
    }

    suspend fun getAccessToken(context: Context): String? {
        val prefs = context.dataStore.data.first()
        val token = prefs[ACCESS_TOKEN_KEY]
        val expiry = prefs[TOKEN_EXPIRY_KEY] ?: 0L
        return if (token != null && System.currentTimeMillis() < expiry - TOKEN_REFRESH_BUFFER_MS) token else null
    }

    suspend fun getRefreshToken(context: Context): String? =
        context.dataStore.data.map { it[REFRESH_TOKEN_KEY] }.first()

    suspend fun clearTokens(context: Context) {
        context.dataStore.edit { it.clear() }
    }

    suspend fun isLoggedIn(context: Context): Boolean =
        getRefreshToken(context) != null
}
