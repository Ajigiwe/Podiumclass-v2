package online.podiumclass.app.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "podium_prefs")

class TokenStorage(private val context: Context) {
    companion object {
        private val KEY_JWT_TOKEN = stringPreferencesKey("jwt_token")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_USER_NAME = stringPreferencesKey("user_name")
        private val KEY_USER_ROLE = stringPreferencesKey("user_role")
        private val KEY_USER_EMAIL = stringPreferencesKey("user_email")
        private val KEY_API_URL = stringPreferencesKey("api_url")
    }

    val tokenFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_JWT_TOKEN]
    }

    val roleFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_ROLE] ?: "student"
    }

    val nameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_NAME] ?: "User"
    }

    val userIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_ID]
    }

    val apiUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        // Default to standard local dev loopback on Android emulator (10.0.2.2)
        prefs[KEY_API_URL] ?: "http://10.0.2.2:8000"
    }

    suspend fun saveAuth(token: String, userId: String, name: String, role: String, email: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_JWT_TOKEN] = token
            prefs[KEY_USER_ID] = userId
            prefs[KEY_USER_NAME] = name
            prefs[KEY_USER_ROLE] = role
            prefs[KEY_USER_EMAIL] = email
        }
    }

    suspend fun setApiUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_API_URL] = url
        }
    }

    suspend fun getToken(): String? {
        return context.dataStore.data.first()[KEY_JWT_TOKEN]
    }

    suspend fun getApiUrl(): String {
        return context.dataStore.data.first()[KEY_API_URL] ?: "http://10.0.2.2:8000"
    }

    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_JWT_TOKEN)
            prefs.remove(KEY_USER_ID)
            prefs.remove(KEY_USER_NAME)
            prefs.remove(KEY_USER_ROLE)
        }
    }
}
