package com.zhiliaohub.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

data class AppSettings(
    val serverUrl: String?,
    val isPaired: Boolean,
)

class AppPreferences(private val context: Context) {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val isPaired = booleanPreferencesKey("is_paired")
    }

    private val settingsFlow = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences ->
            AppSettings(
                serverUrl = preferences[Keys.serverUrl]?.takeIf(String::isNotBlank),
                isPaired = preferences[Keys.isPaired] ?: false,
            )
        }

    suspend fun current(): AppSettings = settingsFlow.first()

    suspend fun setServerUrl(serverUrl: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.serverUrl] = serverUrl
        }
    }

    suspend fun setPaired(isPaired: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.isPaired] = isPaired
        }
    }
}

