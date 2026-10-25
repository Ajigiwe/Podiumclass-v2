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

    val isLecturer: StateFlow<Boolean> = _currentUserRole.map {
        it == "lecturer" || it == "admin"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Stage Layout State
    private val _pinnedParticipantId = MutableStateFlow<String?>(null)
    val pinnedParticipantId: StateFlow<String?> = _pinnedParticipantId.asStateFlow()

    // In-Class Chat
    private val _chatMessages = MutableStateFlow<List<ChatMessageDto>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessageDto>> = _chatMessages.asStateFlow()

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
            initClassroom()
        }

        // Listen for real-time verification broadcasts
        viewModelScope.launch {
            roomManager.incomingVerification.collect { packet ->
                if (!isLecturer.value) {
                    activeVerification.value = packet
                    verificationSuccess.value = false
                }
            }
        }

        // Listen for real-time chat messages
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
    }

    private suspend fun initClassroom() {
        val client = api ?: return
        _isLoading.value = true
        try {
            // 1. Fetch Session Detail
            val sessionRes = client.getSessionDetail(sessionId)
            val sessionData = sessionRes.document
            _session.value = sessionData

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
            if (livekitUrl.contains("localhost")) {
                livekitUrl = livekitUrl.replace("localhost", "10.0.2.2")
            }

            roomManager.connect(
                url = livekitUrl,
                token = tokenData.token,
                localName = "Mobile User",
                localRole = _currentUserRole.value
            )

            // 5. If Host: Start automated attendance checker loop & load roster
            if (isLecturer.value) {
                loadAttendanceRoster()
                startAutoCheckerEngine()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _isLoading.value = false
        }
    }

    private fun startAutoCheckerEngine() {
        autoCheckerJob?.cancel()
        autoCheckerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                if (autoCheckerActive.value && isLecturer.value) {
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
                senderName = if (isLecturer.value) "Host" else "Student",
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
