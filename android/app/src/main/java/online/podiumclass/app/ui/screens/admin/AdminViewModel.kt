package online.podiumclass.app.ui.screens.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import online.podiumclass.app.data.api.ApiClient
import online.podiumclass.app.data.api.PodiumApiService
import online.podiumclass.app.data.api.models.AdminSettingsDto
import online.podiumclass.app.data.api.models.AdminUserPatchRequest
import online.podiumclass.app.data.api.models.FeatureToggleRequest
import online.podiumclass.app.data.api.models.SettingUpdateRequest
import online.podiumclass.app.data.api.models.UserDto
import online.podiumclass.app.data.storage.TokenStorage
import retrofit2.HttpException

/**
 * State for the admin control panel.
 *
 * Everything here is admin-only on the server, so the app never has to decide
 * what an admin may see -- it asks and either gets the data or a 403, which is
 * surfaced as an error instead of an empty screen that looks like "no users".
 *
 * Reads are cheap and lazy: the user list is a single request with the search
 * applied server-side (debounced while typing), and settings are fetched once
 * when the panel opens because they change rarely.
 */
class AdminViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStorage = TokenStorage(application)
    private var api: PodiumApiService? = null

    // ---- users -----------------------------------------------------------
    val users = MutableStateFlow<List<UserDto>>(emptyList())
    val isLoadingUsers = MutableStateFlow(false)
    val usersError = MutableStateFlow<String?>(null)
    val query = MutableStateFlow("")
    val roleFilter = MutableStateFlow<String?>(null)
    val savingUserId = MutableStateFlow<String?>(null)

    // ---- platform settings ----------------------------------------------
    val settings = MutableStateFlow<AdminSettingsDto?>(null)
    val isLoadingSettings = MutableStateFlow(false)
    val settingsError = MutableStateFlow<String?>(null)
    val savingKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Transient message for the snackbar: a refused action, or a failure. */
    val actionMessage = MutableStateFlow<String?>(null)

    private var searchJob: Job? = null

    private suspend fun client(): PodiumApiService {
        api?.let { return it }
        val url = tokenStorage.getApiUrl()
        return ApiClient.create(tokenStorage, url).also { api = it }
    }

    /** Load (or reload) both halves of the panel. */
    fun refresh() {
        viewModelScope.launch { loadUsers() }
        viewModelScope.launch { loadSettings() }
    }

    fun onQueryChange(value: String) {
        query.value = value
        // Debounced: a request per keystroke would hammer the API for a search
        // that only matters once the typing pauses.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(320)
            loadUsers()
        }
    }

    fun setRoleFilter(role: String?) {
        roleFilter.value = role
        viewModelScope.launch { loadUsers() }
    }

    fun loadUsers() {
        viewModelScope.launch {
            isLoadingUsers.value = true
            usersError.value = null
            try {
                val res = client().adminListUsers(
                    query = query.value.trim().ifBlank { null },
                    role = roleFilter.value
                )
                users.value = res.documents
            } catch (e: Exception) {
                usersError.value = describe(e, "Could not load users")
            } finally {
                isLoadingUsers.value = false
            }
        }
    }

    /**
     * Apply a partial change to one account.
     *
     * The row is replaced with the server's answer rather than patched locally,
     * so a refused change (demoting yourself, disabling the superadmin) leaves
     * the list showing the truth instead of an optimistic lie.
     */
    fun updateUser(user: UserDto, patch: AdminUserPatchRequest) {
        viewModelScope.launch {
            savingUserId.value = user.id
            try {
                val updated = client().adminUpdateUser(user.id, patch).document
                users.value = users.value.map { if (it.id == updated.id) updated else it }
                actionMessage.value = "${updated.fullName ?: updated.email} updated."
            } catch (e: Exception) {
                actionMessage.value = describe(e, "Could not update that account")
            } finally {
                savingUserId.value = null
            }
        }
    }

    fun loadSettings() {
        viewModelScope.launch {
            isLoadingSettings.value = true
            settingsError.value = null
            try {
                settings.value = client().getAdminSettings().document
            } catch (e: Exception) {
                settingsError.value = describe(e, "Could not load platform settings")
            } finally {
                isLoadingSettings.value = false
            }
        }
    }

    fun toggleFeature(key: String, enabled: Boolean) {
        viewModelScope.launch {
            savingKeys.value = savingKeys.value + key
            try {
                val updated = client().toggleFeature(key, FeatureToggleRequest(enabled)).document
                settings.value = settings.value?.let { current ->
                    current.copy(
                        features = current.features.map { if (it.key == updated.key) updated else it }
                    )
                }
            } catch (e: Exception) {
                actionMessage.value = describe(e, "Could not switch that feature")
            } finally {
                savingKeys.value = savingKeys.value - key
            }
        }
    }

    fun saveSetting(key: String, value: String) {
        viewModelScope.launch {
            savingKeys.value = savingKeys.value + key
            try {
                val updated = client().updateSetting(key, SettingUpdateRequest(value)).document
                settings.value = settings.value?.let { current ->
                    current.copy(
                        settings = current.settings.map { if (it.key == updated.key) updated else it }
                    )
                }
                actionMessage.value = "Saved."
            } catch (e: Exception) {
                actionMessage.value = describe(e, "Could not save that setting")
            } finally {
                savingKeys.value = savingKeys.value - key
            }
        }
    }

    fun consumeMessage() {
        actionMessage.value = null
    }

    /** The server's own wording when it sent one, otherwise a readable fallback. */
    private fun describe(e: Exception, fallback: String): String {
        if (e is HttpException) {
            val body = try {
                e.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            val message = body
                ?.let { """\"message\"\s*:\s*\"([^\"]+)\"""".toRegex().find(it)?.groupValues?.get(1) }
            return message ?: "$fallback (HTTP ${e.code()})."
        }
        return "$fallback: ${e.localizedMessage ?: "network error"}"
    }
}
