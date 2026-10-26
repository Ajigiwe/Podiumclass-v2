package online.podiumclass.app.ui.screens.classroom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import online.podiumclass.app.data.api.ApiClient
import online.podiumclass.app.data.api.PodiumApiService
import online.podiumclass.app.data.api.models.*
import online.podiumclass.app.data.livekit.LiveKitRoomManager
import online.podiumclass.app.data.storage.TokenStorage

class ClassroomViewModel(
    application: Application,
    private val sessionId: String
) : AndroidViewModel(application) {

    private val tokenStorage = TokenStorage(application)
    val roomManager = LiveKitRoomManager(application)
    private var api: PodiumApiService? = null

    // Session Data
    private val _session = MutableStateFlow<SessionDto?>(null)
    val session: StateFlow<SessionDto?> = _session.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentUserRole = MutableStateFlow("student")
    val currentUserRole: StateFlow<String> = _currentUserRole.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    // Moderation & Permissions States
    val isMutedAll = MutableStateFlow(false)
    val autoApproveMic = MutableStateFlow(false)
    val permissionPolicy = MutableStateFlow(PermissionPolicyDto())
    val myPermissions = MutableStateFlow(PermissionGrantDto())
    val isCoHost = MutableStateFlow(false)
    val pendingRequests = MutableStateFlow<List<PermissionRequestDto>>(emptyList())
    val coHosts = MutableStateFlow<List<CoHostDto>>(emptyList())
    val requestedKinds = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    val isLecturer: StateFlow<Boolean> = _currentUserRole.map {
        it == "lecturer" || it == "admin"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isModerator: StateFlow<Boolean> = combine(
        _currentUserRole,
        isCoHost,
        _session,
        _currentUserId
    ) { role, cohost, sess, uid ->
        role == "admin" || role == "lecturer" || cohost || (sess?.hostId != null && sess.hostId == uid)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Stage Layout State
    private val _pinnedParticipantId = MutableStateFlow<String?>(null)
    val pinnedParticipantId: StateFlow<String?> = _pinnedParticipantId.asStateFlow()

    // In-Class Chat & System Notifications
    private val _chatMessages = MutableStateFlow<List<ChatMessageDto>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessageDto>> = _chatMessages.asStateFlow()

    private val _systemNotification = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val systemNotification: SharedFlow<String> = _systemNotification.asSharedFlow()

    // ==================== ATTENDANCE VERIFICATION STATE ====================
    val autoCheckerActive = MutableStateFlow(true)
    val totalTargetChecks = MutableStateFlow(3)
    val checkDurationSeconds = MutableStateFlow(30)
    val checkIntervalMinutes = MutableStateFlow(15)
    val checksCompleted = MutableStateFlow(0)
    val nextCheckCountdownSeconds = MutableStateFlow(900)
    val isSavingSettings = MutableStateFlow(false)

    val attendanceSummary = MutableStateFlow(AttendanceSummaryDto())
    val attendanceRecords = MutableStateFlow<List<AttendanceRecordDto>>(emptyList())

    // Student Active Verification Prompt
    val activeVerification = MutableStateFlow<DataPacket?>(null)
    val isSubmittingVerification = MutableStateFlow(false)
    val verificationSuccess = MutableStateFlow(false)

    private var autoCheckerJob: Job? = null

    init {
        viewModelScope.launch {
            val apiUrl = tokenStorage.getApiUrl()
            api = ApiClient.create(tokenStorage, apiUrl)
            _currentUserRole.value = tokenStorage.roleFlow.first()
            _currentUserId.value = tokenStorage.userIdFlow.first()
            initClassroom()
        }

        // Real-time verification broadcasts
        viewModelScope.launch {
            roomManager.incomingVerification.collect { packet ->
                if (!isModerator.value) {
                    activeVerification.value = packet
                    verificationSuccess.value = false
                }
            }
        }

        // Real-time chat messages
        viewModelScope.launch {
            roomManager.incomingChat.collect { packet ->
                val newMsg = ChatMessageDto(
                    id = packet.id ?: "msg_${System.currentTimeMillis()}",
                    sender = packet.sender ?: "Unknown",
                    message = packet.message ?: "",
                    role = packet.role ?: "student",
                    time = packet.time
                )
                _chatMessages.update { it + newMsg }
            }
        }

        // Real-time moderation broadcast
        viewModelScope.launch {
            roomManager.incomingModeration.collect { packet ->
                applyModerationPacket(packet)
            }
        }

        // Real-time permission request broadcast
        viewModelScope.launch {
            roomManager.incomingPermissionRequest.collect {
                if (isModerator.value) {
                    loadPendingRequests()
                }
            }
        }

        // Real-time permission response broadcast
        viewModelScope.launch {
            roomManager.incomingPermissionResponse.collect { packet ->
                handlePermissionResponse(packet)
            }
        }

        // Reconnect auto-resync
        viewModelScope.launch {
            roomManager.onReconnected.collect {
                resyncModeration()
            }
        }
    }

    private suspend fun initClassroom() {
        val client = api ?: return
        _isLoading.value = true
        try {
            // 1. Fetch Session Detail
            val sessionRes = client.getSessionDetail(sessionId)
            val sessionData = sessionRes.document
            _session.value = sessionData
            applyModerationState(sessionData)

            // 2. Parse Automated Attendance Settings
            sessionData.verificationCount?.let { totalTargetChecks.value = it }
            sessionData.autoAttendanceSettings?.let { auto ->
                autoCheckerActive.value = auto.enabled
                totalTargetChecks.value = auto.checkCount
                checkDurationSeconds.value = auto.timeLimitSeconds
                checkIntervalMinutes.value = auto.intervalMinutes
                nextCheckCountdownSeconds.value = auto.intervalMinutes * 60
            }

            // 3. Register attendance join in PostgreSQL
            try {
                client.registerAttendanceJoin(AttendanceJoinRequest(sessionId))
            } catch (e: Exception) {
                // Join logging fallback
            }

            // 4. Fetch LiveKit Token & Connect
            val tokenRes = client.getLiveKitToken(sessionId)
            val tokenData = tokenRes.document
            var livekitUrl = tokenData.livekitUrl ?: "ws://10.0.2.2:7880"
            val currentApiUrl = tokenStorage.getApiUrl()
            val apiHost = try { java.net.URI(currentApiUrl).host } catch (e: Exception) { null }
            if (livekitUrl.contains("localhost") && !apiHost.isNullOrBlank()) {
                livekitUrl = livekitUrl.replace("localhost", apiHost)
            } else if (livekitUrl.contains("localhost")) {
                livekitUrl = livekitUrl.replace("localhost", "10.0.2.2")
            }

            roomManager.connect(
                url = livekitUrl,
                token = tokenData.token,
                localName = "Mobile User",
                localRole = _currentUserRole.value
            )

            // 5. Load permissions & co-hosts
            loadOwnPermissions()
            loadCoHosts()

            // 6. If Host/Moderator: start auto-checker engine and load inbox
            if (isModerator.value) {
                loadAttendanceRoster()
                loadPendingRequests()
                startAutoCheckerEngine()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _isLoading.value = false
        }
    }

    // ==================== MODERATION & PERMISSIONS ENGINE ====================

    fun applyModerationState(sessionDto: SessionDto) {
        isMutedAll.value = sessionDto.isMutedAll
        autoApproveMic.value = sessionDto.autoApproveMic
        permissionPolicy.value = sessionDto.permissions
        enforceLocks()
    }

    fun applyModerationPacket(packet: DataPacket) {
        val wasMuted = isMutedAll.value
        packet.isMutedAll?.let { isMutedAll.value = it }
        packet.autoApproveMic?.let { autoApproveMic.value = it }
        packet.permissions?.let { permissionPolicy.value = it }
        if (packet.isMutedAll != null && packet.isMutedAll != wasMuted) {
            val msg = if (packet.isMutedAll) "The host muted all microphones." else "The host unmuted microphones."
            pushSystemMessage(msg)
        }
        viewModelScope.launch { loadOwnPermissions() }
        enforceLocks()
    }

    fun handlePermissionResponse(packet: DataPacket) {
        val localIdentity = roomManager.room?.localParticipant?.identity ?: _currentUserId.value
        if (packet.target != null && packet.target == localIdentity) {
            val kind = packet.requestType ?: "mic"
            requestedKinds.update { it + (kind to false) }
            val kindLabel = if (kind == "mic") "microphone" else if (kind == "camera") "camera" else "screen"
            if (packet.granted == true) {
                myPermissions.update {
                    when (kind) {
                        "mic" -> it.copy(mic = true)
                        "camera" -> it.copy(camera = true)
                        "screen" -> it.copy(screen = true)
                        else -> it
                    }
                }
                pushSystemMessage("The host allowed your $kindLabel.")
                viewModelScope.launch {
                    if (kind == "mic") roomManager.setMicEnabled(true)
                    if (kind == "camera") roomManager.setCameraEnabled(true)
                }
            } else {
                pushSystemMessage("The host declined your $kindLabel request.")
            }
        }
    }

    fun canUse(kind: String): Boolean {
        if (isModerator.value) return true
        when (kind) {
            "mic" -> {
                if (myPermissions.value.mic) return true
                if (isMutedAll.value) return false
                return permissionPolicy.value.mic == "free"
            }
            "camera" -> {
                if (myPermissions.value.camera) return true
                return permissionPolicy.value.camera == "free"
            }
            "screen" -> {
                if (myPermissions.value.screen) return true
                return permissionPolicy.value.screen == "free"
            }
            else -> return true
        }
    }

    fun enforceLocks() {
        viewModelScope.launch {
            if (roomManager.isMicEnabled.value && !canUse("mic")) {
                roomManager.setMicEnabled(false)
            }
            if (roomManager.isCameraEnabled.value && !canUse("camera")) {
                roomManager.setCameraEnabled(false)
            }
        }
    }

    private suspend fun persistModeration(patch: PatchSessionRequest): Boolean {
        val client = api ?: return false
        return try {
            val res = client.patchSession(sessionId, patch)
            applyModerationState(res.document)
            roomManager.broadcastModeration(
                isMutedAll = isMutedAll.value,
                autoApproveMic = autoApproveMic.value,
                permissions = permissionPolicy.value
            )
            true
        } catch (e: Exception) {
            pushSystemMessage("That moderation change could not be saved.")
            false
        }
    }

    fun setMuteAll(muted: Boolean) {
        viewModelScope.launch {
            val ok = persistModeration(PatchSessionRequest(isMutedAll = muted))
            if (ok && muted) {
                myPermissions.value = myPermissions.value.copy(mic = false)
                enforceLocks()
            }
        }
    }

    fun setAutoApproveMic(enabled: Boolean) {
        viewModelScope.launch {
            persistModeration(PatchSessionRequest(autoApproveMic = enabled))
        }
    }

    fun setPermissionPolicy(kind: String, mode: String) {
        viewModelScope.launch {
            val current = permissionPolicy.value
            val updated = when (kind) {
                "mic" -> current.copy(mic = mode)
                "camera" -> current.copy(camera = mode)
                "screen" -> current.copy(screen = mode)
                else -> current
            }
            val map = mapOf(
                "mic" to updated.mic,
                "camera" to updated.camera,
                "screen" to updated.screen
            )
            persistModeration(PatchSessionRequest(permissions = map))
        }
    }

    fun loadOwnPermissions() {
        viewModelScope.launch {
            val client = api ?: return@launch
            try {
                val res = client.getMyPermissions(sessionId)
                val grant = res.document
                myPermissions.value = grant
                isCoHost.value = grant.cohost
                enforceLocks()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadPendingRequests() {
        viewModelScope.launch {
            val client = api ?: return@launch
            if (!isModerator.value) return@launch
            try {
                val res = client.getPendingPermissionRequests(sessionId)
                pendingRequests.value = res.documents
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadCoHosts() {
        viewModelScope.launch {
            val client = api ?: return@launch
            try {
                val res = client.getCoHosts(sessionId)
                coHosts.value = res.documents
                val cohostIds = res.documents.map { it.userId }.toSet()
                roomManager.setCoHosts(cohostIds)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun appointCoHost(userId: String) {
        viewModelScope.launch {
            val client = api ?: return@launch
            try {
                client.appointCoHost(sessionId, AppointCoHostRequest(userId))
                loadCoHosts()
                pushSystemMessage("Co-host appointed.")
            } catch (e: Exception) {
                pushSystemMessage("Could not appoint co-host.")
            }
        }
    }

    fun removeCoHost(userId: String) {
        viewModelScope.launch {
            val client = api ?: return@launch
            try {
                client.removeCoHost(sessionId, userId)
                loadCoHosts()
                pushSystemMessage("Co-host removed.")
            } catch (e: Exception) {
                pushSystemMessage("Could not remove co-host.")
            }
        }
    }

    fun requestPermission(kind: String) {
        viewModelScope.launch {
            val client = api ?: return@launch
            if (requestedKinds.value[kind] == true) return@launch
            requestedKinds.update { it + (kind to true) }
            val kindLabel = if (kind == "mic") "microphone" else if (kind == "camera") "camera" else "screen"
            try {
                val res = client.requestPermission(sessionId, PermissionRequestCreate(kind))
                val req = res.document
                if (req.status == "approved") {
                    myPermissions.update {
                        when (kind) {
                            "mic" -> it.copy(mic = true)
                            "camera" -> it.copy(camera = true)
                            "screen" -> it.copy(screen = true)
                            else -> it
                        }
                    }
                    requestedKinds.update { it + (kind to false) }
                    pushSystemMessage("${kindLabel.replaceFirstChar { it.uppercase() }} approved automatically.")
                    if (kind == "mic") roomManager.setMicEnabled(true)
                    if (kind == "camera") roomManager.setCameraEnabled(true)
                } else {
                    pushSystemMessage("Asked the host for the $kindLabel.")
                    roomManager.broadcastPermissionRequest(kind)
                }
            } catch (e: Exception) {
                requestedKinds.update { it + (kind to false) }
                pushSystemMessage("The $kindLabel request could not be sent.")
            }
        }
    }

    fun reviewPermissionRequest(requestId: String, approve: Boolean) {
        viewModelScope.launch {
            val client = api ?: return@launch
            val req = pendingRequests.value.find { it.id == requestId } ?: return@launch
            try {
                client.reviewPermissionRequest(sessionId, requestId, PermissionReviewRequest(if (approve) "approved" else "rejected"))
                pendingRequests.update { list -> list.filter { it.id != requestId } }
                roomManager.broadcastPermissionResponse(req.participantId, req.requestType, approve)
                val kindLabel = if (req.requestType == "mic") "microphone" else req.requestType
                pushSystemMessage(
                    if (approve) "Allowed ${req.participantName} to use their $kindLabel."
                    else "Declined ${req.participantName}'s $kindLabel request."
                )
            } catch (e: Exception) {
                pushSystemMessage("That answer could not be saved.")
            }
        }
    }

    fun endSession(onEnded: () -> Unit) {
        viewModelScope.launch {
            val client = api ?: return@launch
            try {
                client.endSession(sessionId)
                pushSystemMessage("The classroom session has ended.")
                delay(500)
                roomManager.disconnect()
                onEnded()
            } catch (e: Exception) {
                pushSystemMessage("Could not end the session.")
            }
        }
    }

    fun resyncModeration() {
        viewModelScope.launch {
            val client = api ?: return@launch
            try {
                val res = client.getSessionDetail(sessionId)
                applyModerationState(res.document)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            loadOwnPermissions()
            loadCoHosts()
            if (isModerator.value) loadPendingRequests()
        }
    }

    fun pushSystemMessage(message: String) {
        _systemNotification.tryEmit(message)
        val msg = ChatMessageDto(
            id = "sys_${System.currentTimeMillis()}_${(100..999).random()}",
            sender = "Podium",
            message = message,
            role = "system",
            time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        )
        _chatMessages.update { it + msg }
    }

    // ==================== ATTENDANCE VERIFICATION ====================

    private fun startAutoCheckerEngine() {
        autoCheckerJob?.cancel()
        autoCheckerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                if (autoCheckerActive.value && isModerator.value) {
                    if (checksCompleted.value < totalTargetChecks.value) {
                        if (nextCheckCountdownSeconds.value > 0) {
                            nextCheckCountdownSeconds.value--
                        } else {
                            // Trigger automatic check
                            triggerAttendanceCheck(checkDurationSeconds.value)
                            nextCheckCountdownSeconds.value = checkIntervalMinutes.value * 60
                        }
                    }
                }
            }
        }
    }

    fun toggleAutoChecker() {
        val next = !autoCheckerActive.value
        autoCheckerActive.value = next
        if (next && nextCheckCountdownSeconds.value <= 0) {
            nextCheckCountdownSeconds.value = checkIntervalMinutes.value * 60
        }
    }

    fun triggerAttendanceCheck(durationSeconds: Int? = null) {
        viewModelScope.launch {
            val client = api ?: return@launch
            val duration = durationSeconds ?: checkDurationSeconds.value
            try {
                val res = client.triggerVerification(
                    VerificationTriggerRequest(
                        sessionId = sessionId,
                        timeLimitSeconds = duration
                    )
                )
                val doc = res.document
                checksCompleted.value = doc.verificationNumber

                // Broadcast over LiveKit Data Channel to room
                roomManager.broadcastVerification(
                    verificationId = doc.verificationId,
                    number = doc.verificationNumber,
                    timeLimit = duration,
                    totalTarget = totalTargetChecks.value
                )

                loadAttendanceRoster()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateSettings(targetChecks: Int, duration: Int, interval: Int) {
        totalTargetChecks.value = targetChecks
        checkDurationSeconds.value = duration
        checkIntervalMinutes.value = interval
        nextCheckCountdownSeconds.value = interval * 60

        viewModelScope.launch {
            val client = api ?: return@launch
            isSavingSettings.value = true
            try {
                client.patchSession(
                    sessionId = sessionId,
                    request = PatchSessionRequest(
                        verificationCount = targetChecks,
                        autoAttendanceSettings = AutoAttendanceSettingsDto(
                            enabled = autoCheckerActive.value,
                            checkCount = targetChecks,
                            timeLimitSeconds = duration,
                            intervalMinutes = interval
                        )
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSavingSettings.value = false
            }
        }
    }

    fun loadAttendanceRoster() {
        viewModelScope.launch {
            val client = api ?: return@launch
            try {
                val res = client.getAttendanceRoster(sessionId)
                val doc = res.document
                attendanceSummary.value = doc.summary
                attendanceRecords.value = doc.records
                val maxSent = doc.records.maxOfOrNull { it.verificationsSent } ?: 0
                if (maxSent > checksCompleted.value) {
                    checksCompleted.value = maxSent
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun submitStudentVerification() {
        val verif = activeVerification.value ?: return
        viewModelScope.launch {
            val client = api ?: return@launch
            isSubmittingVerification.value = true
            try {
                verif.verificationId?.let { id ->
                    client.respondVerification(VerificationRespondRequest(sessionId, id))
                    verificationSuccess.value = true
                    delay(1200)
                    activeVerification.value = null
                    verificationSuccess.value = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSubmittingVerification.value = false
            }
        }
    }

    fun pinParticipant(identity: String) {
        if (_pinnedParticipantId.value == identity) {
            _pinnedParticipantId.value = null
        } else {
            _pinnedParticipantId.value = identity
        }
    }

    fun sendChatMessage(text: String) {
        viewModelScope.launch {
            roomManager.sendChatMessage(
                text = text,
                senderName = if (isModerator.value) "Host" else "Student",
                role = _currentUserRole.value
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoCheckerJob?.cancel()
        viewModelScope.launch {
            roomManager.disconnect()
        }
    }
}
