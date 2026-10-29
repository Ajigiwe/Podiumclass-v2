package online.podiumclass.app.data.storage

import android.content.Context
import online.podiumclass.app.BuildConfig
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "podium_prefs")

class TokenStorage(private val context: Context) {
    companion object {
        private val KEY_JWT_TOKEN = stringPreferencesKey("jwt_token")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_USER_NAME = stringPreferencesKey("user_name")
        private val KEY_USER_ROLE = stringPreferencesKey("user_role")
        private val KEY_USER_EMAIL = stringPreferencesKey("user_email")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

        // The intro is a first-run experience: once dismissed it never comes
        // back, including after an update or a sign-out.
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarding_seen")

        /**
         * The server this build talks to.
         *
         * Comes from the build (see `podiumApiUrl` in build.gradle.kts) and is
         * the only source of truth: the app has no server field any more, so
         * there is nothing that can drift out of sync with the deployment or
         * send a user's phone at a host that no longer exists. Local testing
         * points the build at a LAN address with `-PpodiumApiUrl=...` instead
         * of editing a setting on the device.
         */
        private const val DEFAULT_API_URL = BuildConfig.DEFAULT_API_URL
    }

    val themeModeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: "system"
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

    val emailFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_EMAIL]
    }

    /**
     * Constant, not a stored preference. A value that was saved by an older
     * build (or typed into the removed server dialog) must not survive an
     * update, because the setting no longer exists to correct it.
     */
    val apiUrlFlow: Flow<String> = flowOf(DEFAULT_API_URL)

    val onboardedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDED] ?: false
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode
        }
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

    suspend fun markOnboarded() {
        context.dataStore.edit { prefs ->
            prefs[KEY_ONBOARDED] = true
        }
    }

    suspend fun getToken(): String? {
        return context.dataStore.data.first()[KEY_JWT_TOKEN]
    }

    suspend fun getUserName(): String? {
        return context.dataStore.data.first()[KEY_USER_NAME]
    }

    suspend fun getUserId(): String? {
        return context.dataStore.data.first()[KEY_USER_ID]
    }

    suspend fun getUserRole(): String? {
        return context.dataStore.data.first()[KEY_USER_ROLE]
    }

    suspend fun setName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_USER_NAME] = name
        }
    }

    suspend fun getEmail(): String? {
        return context.dataStore.data.first()[KEY_USER_EMAIL]
    }

    suspend fun getApiUrl(): String {
        return DEFAULT_API_URL
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
