package online.podiumclass.app.data.storage

import android.content.Context
import online.podiumclass.app.BuildConfig
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

        // The intro is a first-run experience: once dismissed it never comes
        // back, including after an update or a sign-out.
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarding_seen")

        /**
         * Where this backend is served when nothing usable is configured.
         *
         * Comes from the build (see `podiumApiUrl` in build.gradle.kts), so the
         * release APK points at the deployed server while a local build can
         * still target a machine on the LAN. It used to be a hardcoded LAN IP,
         * which meant every fresh install shipped pointing at whatever network
         * that address belonged to on the developer's desk.
         */
        private const val DEFAULT_API_URL = BuildConfig.DEFAULT_API_URL

        /** A host this app used to ship with and must never keep using. */
        private const val SUPERSEDED_API_HOST = "10.208.165.166"

        /** Loopback and private-range hosts: a dev machine, not a deployment. */
        private val PRIVATE_HOST = Regex(
            "^(localhost|127\\.0\\.0\\.1|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}" +
                "|192\\.168\\.\\d{1,3}\\.\\d{1,3}" +
                "|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})$"
        )

        private fun isPrivateHost(url: String): Boolean = runCatching {
            java.net.URI(url).host?.let { PRIVATE_HOST.matches(it) } ?: false
        }.getOrDefault(false)

        /**
         * One resolution rule for the API URL, used by both the flow the UI
         * observes and the one-shot read used to build clients. When these two
         * disagreed, a stale host kept serving one screen while every other
         * screen silently pointed at a dead address.
         */
        private fun normalizeApiUrl(saved: String?): String {
            if (saved.isNullOrBlank() || saved.contains(SUPERSEDED_API_HOST)) {
                return DEFAULT_API_URL
            }
            // A build that targets a deployed server must not keep obeying a
            // saved LAN address: after an update the phone would dial a network
            // it is no longer on, and every screen would look broken. Debug
            // builds keep the saved value, so testing against a local server
            // still works.
            if (!BuildConfig.DEBUG && isPrivateHost(saved) && !isPrivateHost(DEFAULT_API_URL)) {
                return DEFAULT_API_URL
            }
            return saved
        }
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

    val apiUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        normalizeApiUrl(prefs[KEY_API_URL])
    }

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

    suspend fun setApiUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_API_URL] = url
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
        return normalizeApiUrl(context.dataStore.data.first()[KEY_API_URL])
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
