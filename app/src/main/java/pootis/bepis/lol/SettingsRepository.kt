package pootis.bepis.lol

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    companion object {
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
    }

    val settingsFlow: Flow<WebDavSettings> = context.dataStore.data.map { preferences ->
        WebDavSettings(
            url = preferences[WEBDAV_URL] ?: "",
            username = preferences[USERNAME] ?: "",
            password = preferences[PASSWORD] ?: ""
        )
    }

    suspend fun saveSettings(settings: WebDavSettings) {
        context.dataStore.edit { preferences ->
            preferences[WEBDAV_URL] = settings.url
            preferences[USERNAME] = settings.username
            preferences[PASSWORD] = settings.password
        }
    }
}

data class WebDavSettings(
    val url: String,
    val username: String,
    val password: String
)
