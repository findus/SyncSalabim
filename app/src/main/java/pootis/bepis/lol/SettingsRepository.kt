package pootis.bepis.lol

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    companion object {
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val BACKGROUND_SYNC = booleanPreferencesKey("background_sync")
        val BACKGROUND_SYNC_INTERVAL = intPreferencesKey("background_sync_interval")
        val SELECTED_FOLDERS = stringSetPreferencesKey("selected_folders")
    }

    val settingsFlow: Flow<WebDavSettings> = context.dataStore.data.map { preferences ->
        WebDavSettings(
            url = preferences[WEBDAV_URL] ?: "",
            username = preferences[USERNAME] ?: "",
            password = preferences[PASSWORD] ?: "",
            backgroundSync = preferences[BACKGROUND_SYNC] ?: false,
            backgroundSyncInterval = preferences[BACKGROUND_SYNC_INTERVAL] ?: 60, // Default 60 minutes
            selectedFolders = preferences[SELECTED_FOLDERS] ?: emptySet()
        )
    }

    suspend fun saveSettings(settings: WebDavSettings) {
        context.dataStore.edit { preferences ->
            preferences[WEBDAV_URL] = settings.url
            preferences[USERNAME] = settings.username
            preferences[PASSWORD] = settings.password
            preferences[BACKGROUND_SYNC] = settings.backgroundSync
            preferences[BACKGROUND_SYNC_INTERVAL] = settings.backgroundSyncInterval
            preferences[SELECTED_FOLDERS] = settings.selectedFolders
        }
    }
}

data class WebDavSettings(
    val url: String,
    val username: String,
    val password: String,
    val backgroundSync: Boolean = false,
    val backgroundSyncInterval: Int = 60,
    val selectedFolders: Set<String> = emptySet()
)
