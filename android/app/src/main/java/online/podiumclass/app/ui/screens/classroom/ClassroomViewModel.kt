package online.podiumclass.app.ui.screens.classroom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import online.podiumclass.app.data.api.ApiClient
import online.podiumclass.app.data.api.PodiumApiService
import online.podiumclass.app.data.api.models.*
import online.podiumclass.app.data.livekit.LiveKitRoomManager
import online.podiumclass.app.data.notify.PodiumNotifications
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
    private val _currentUserName = MutableStateFlow("User")
    val currentUserName: StateFlow<String> = _currentUserName.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessageDto>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessageDto>> = _chatMessages.asStateFlow()

    private val _systemNotification = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val systemNotification: SharedFlow<String> = _systemNotification.asSharedFlow()

    /**
     * Set when the room has dropped us: the host ended the class, or the
     * connection failed. The screen shows it and walks the student back out,
     * instead of leaving them on a stage that can never come back.
     */
    val roomClosed = MutableStateFlow<String?>(null)

    /**
     * Set when the server refuses the join outright, carrying its own wording
     * ("This class has ended. It reopens when the host returns.").
     */
    val joinBlocked = MutableStateFlow<String?>(null)

    /**
     * The class exists but has not opened: its host is not in yet.
     *
     * This is a *waiting* state, not a refusal, so the screen shows a lobby and
     * this device keeps asking until the host's arrival opens the class -- the
     * same thing the web client does. Nobody has to press anything.
     */
    val waitingForHost = MutableStateFlow(false)
    private var waitingJob: Job? = null

    /** Media the student granted permission for before the room was ready. */
    private val pendingMediaEnable = mutableSetOf<String>()

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

    // ==================== RECORDING ====================
    // The MP4 is written by the server (LiveKit Egress), so this device only
    // ever holds a *claim* about a recording: what the last status call said.
    // Nothing here can make one exist, which is exactly why a phone can start
    // one at all -- the work happens on the host machine, not on the handset.
    // Shared materials for this class. The list is server-backed, so a
    // student who joins late -- or reopens the app -- sees the same shelf as
    // everyone else, and a host's upload survives their refresh.
    val materials = MutableStateFlow<List<MaterialDto>>(emptyList())
    val materialsError = MutableStateFlow<String?>(null)
    val isSharingMaterial = MutableStateFlow(false)

    val recordings = MutableStateFlow<List<RecordingDto>>(emptyList())
    val activeRecording = MutableStateFlow<RecordingDto?>(null)
    val recordingError = MutableStateFlow<String?>(null)
    val recordingSeconds = MutableStateFlow(0)
    val isRecording: StateFlow<Boolean> = activeRecording
        .map { it?.isLive == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Accepted, but the worker's pipeline is not capturing yet.
     *
     * A composite pipeline needs a second or two to attach to the room. The
     * server waits for it before stopping, so a stop is safe -- but the button
     * should not invite one over a take that has not started, hence the
     * "Starting..." state instead of the REC timer and the stop icon.
     */
    val isRecordingStarting: StateFlow<Boolean> = activeRecording
        .map { it?.status?.equals("starting", ignoreCase = true) == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var recordingClockJob: Job? = null
    private var recordingPollJob: Job? = null

    private fun stopRecordingClock() {
        recordingClockJob?.cancel()
        recordingClockJob = null
    }

    private fun stopRecordingPoll() {
        recordingPollJob?.cancel()
        recordingPollJob = null
    }

    /** Seconds since an ISO-8601 start stamp, or 0 when it cannot be read. */
    private fun elapsedSince(iso: String?): Int {
        if (iso == null) return 0
        return try {
            val start = java.time.OffsetDateTime.parse(iso).toInstant()
            val seconds = java.time.Duration.between(start, java.time.Instant.now()).seconds
            if (seconds < 0) 0 else seconds.toInt()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Fold one status answer into this device's view of the live recording.
     *
     * Going live starts a 1s tick for the REC timer and a 5s poll of the
     * status route; finishing stops both and reloads the shelf, because the MP4
     * the worker just closed is now something the class can watch. Calling this
     * again while live only refreshes the record -- the timers start once.
     */
    fun applyActiveRecording(doc: RecordingDto?) {
        if (doc == null || !doc.isLive) {
            val wasLive = activeRecording.value != null
            activeRecording.value = null
            stopRecordingClock()
            stopRecordingPoll()
            if (wasLive) {
                // The poll just watched the worker finish, which is the moment
                // the video lands in Records: worth telling the host about
                // even if they have put the phone down.
                val title = doc?.title ?: _session.value?.title ?: ""
                when (doc?.status) {
                    "complete" -> PodiumNotifications.recordingReady(
                        getApplication(), title, recordingLength(doc.durationSeconds)
                    )
                    "failed", "aborted" -> {
                        PodiumNotifications.recordingFailed(
                            getApplication(), title, doc.error
                        )
                        // The banner explains it in the room too: the host is
                        // usually looking at the screen when this lands.
                        pushSystemMessage(doc.error ?: "The recording did not finish.")
                    }
                    else -> Unit
                }
                loadRecordings()
            }
            return
        }

        val wasLive = activeRecording.value != null
        activeRecording.value = doc
        if (wasLive) return

        recordingSeconds.value = elapsedSince(doc.startedAt)
        recordingClockJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                recordingSeconds.value += 1
            }
        }
        recordingPollJob = viewModelScope.launch {
            while (true) {
                // While the pipeline is coming up the row changes in a second
                // or two, and the button is waiting on it; once it is
                // capturing, five seconds is plenty.
                val starting = activeRecording.value?.status?.equals("starting", ignoreCase = true) == true
                delay(if (starting) 1500 else 5000)
                val client = api ?: continue
                val id = activeRecording.value?.id ?: break
                try {
                    applyActiveRecording(client.getRecordingDetail(sessionId, id).document)
                } catch (e: Exception) {
                    // A dropped poll changes nothing: the server's row is still
                    // the truth, and the next tick catches up.
                }
            }
        }
    }

    /** "4:05" or "1:02:30", for the body of a notification. */
    private fun recordingLength(seconds: Long?): String? {
        val total = seconds ?: return null
        if (total <= 0) return null
        val minutes = total / 60
        val remainder = total % 60
        return if (minutes >= 60) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", minutes / 60, minutes % 60, remainder)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, remainder)
        }
    }

    /** Everything recorded for this session, newest first. */
    fun loadRecordings() {
        viewModelScope.launch {
            val client = api ?: return@launch
            try {
                recordings.value = client.getRecordings(sessionId).documents
            } catch (e: Exception) {
                // Recordings switched off by an admin is a configuration, not a
                // fault; the button that would call this is hidden in that case.
            }
        }
    }

    /**
     * Re-attach to a recording that is already running: a host who reopens the
     * app mid-lecture, or a co-host who joins halfway through. Without this the
     * server would keep writing an MP4 that nobody's UI knows about.
     */
    /** Reload the class shelf. Cheap enough to call whenever it is opened. */
    fun loadMaterials() {
        viewModelScope.launch {
            val client = api ?: return@launch
            materialsError.value = null
            try {
                materials.value = client.getMaterials(sessionId).documents
            } catch (e: Exception) {
                materialsError.value = serverMessage(e as? retrofit2.HttpException)
                    ?: "Could not load the class materials."
            }
        }
    }

    /** Share a link (Drive, YouTube, a department page) with the class. */
    fun shareMaterialLink(title: String, url: String) {
        viewModelScope.launch {
            val client = api ?: return@launch
            isSharingMaterial.value = true
            materialsError.value = null
            try {
                client.shareMaterialLink(
                    sessionId,
                    ShareLinkRequest(title = title.ifBlank { url }, url = url)
                )
                loadMaterials()
            } catch (e: Exception) {
                materialsError.value = serverMessage(e as? retrofit2.HttpException)
                    ?: "Could not share that link."
            } finally {
                isSharingMaterial.value = false
            }
        }
    }

    /** Upload a document picked on the phone; the server keeps the file. */
    fun shareMaterialFile(bytes: ByteArray, fileName: String, mime: String, title: String) {
        viewModelScope.launch {
            val client = api ?: return@launch
            isSharingMaterial.value = true
            materialsError.value = null
            try {
                val body = bytes.toRequestBody(mime.toMediaType())
                val part = MultipartBody.Part.createFormData("file", fileName, body)
                val titleBody = title.ifBlank { fileName }.toRequestBody("text/plain".toMediaType())
                client.uploadMaterial(sessionId, titleBody, part)
                loadMaterials()
            } catch (e: Exception) {
                materialsError.value = serverMessage(e as? retrofit2.HttpException)
                    ?: "Could not upload that file."
            } finally {
                isSharingMaterial.value = false
            }
        }
    }

    fun deleteMaterial(materialId: String) {
        viewModelScope.launch {
            val client = api ?: return@launch
            materialsError.value = null
            try {
                client.deleteMaterial(sessionId, materialId)
                materials.value = materials.value.filterNot { it.id == materialId }
            } catch (e: Exception) {
                materialsError.value = serverMessage(e as? retrofit2.HttpException)
                    ?: "Could not remove that resource."
            }
        }
    }

    fun resumeRecordingState() {
        viewModelScope.launch {
            val client = api ?: return@launch
            try {
                val list = client.getRecordings(sessionId).documents
                recordings.value = list
                list.firstOrNull { it.isLive }?.let { applyActiveRecording(it) }
            } catch (e: Exception) {
                // No shelf to show; the stage is unaffected.
            }
        }
    }

    /** Ask the server to record this room (server-side MP4). */
    fun startRecording() {
        viewModelScope.launch {
            val client = api ?: return@launch
            if (activeRecording.value != null) return@launch
            recordingError.value = null
            try {
                applyActiveRecording(client.startRecording(sessionId).document)
                loadRecordings()
                pushSystemMessage("Recording started. The video file is written on the server.")
            } catch (e: Exception) {
                // 503 RECORDING_UNAVAILABLE is the honest answer when the worker
                // is down; say so instead of leaving a dead button.
                recordingError.value = serverMessage(e as? retrofit2.HttpException)
                    ?: "Recording could not be started."
                pushSystemMessage(recordingError.value ?: "Recording could not be started.")
            }
        }
    }

    /**
     * Finish the recording cleanly. Egress answers "ending" and finalises the
     * MP4 a moment later, so polling keeps running until the file is whole.
     */
    fun stopRecording() {
        viewModelScope.launch {
            val client = api ?: return@launch
            val id = activeRecording.value?.id ?: return@launch
            recordingError.value = null
            try {
                applyActiveRecording(client.stopRecording(sessionId, id).document)
                pushSystemMessage("Finishing the recording...")
            } catch (e: Exception) {
                recordingError.value = serverMessage(e as? retrofit2.HttpException)
                    ?: "The recording could not be stopped."
                pushSystemMessage(recordingError.value ?: "The recording could not be stopped.")
            }
        }
    }

    init {
        viewModelScope.launch {
            val apiUrl = tokenStorage.getApiUrl()
            api = ApiClient.create(tokenStorage, apiUrl)
            _currentUserRole.value = tokenStorage.roleFlow.first()
            _currentUserId.value = tokenStorage.userIdFlow.first()
            _currentUserName.value = tokenStorage.nameFlow.first()
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

        // Dropped out of the room (host ended the class, or the link died).
        viewModelScope.launch {
            roomManager.onRoomClosed.collect { reason ->
                roomClosed.value = reason
                // The room went away under them: say so where the phone will
                // show it, since the screen may already be off.
                PodiumNotifications.classEnded(
                    getApplication(),
                    _session.value?.title ?: "",
                    reason
                )
            }
        }

        // Microphone/camera failures, which used to vanish into a stack trace.
        viewModelScope.launch {
            roomManager.mediaErrors.collect { message ->
                pushSystemMessage(message)
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
            // The server resolves and normalises this endpoint itself (it knows
            // the host the request arrived on, so a development loopback URL has
            // already been rewritten for this device). Take it verbatim: the old
            // text substitution here also rewrote the host of a real LiveKit
            // Cloud URL whenever a path happened to contain "localhost".
            val livekitUrl = tokenData.livekitUrl?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "This class has no live video endpoint configured. Ask your administrator to set it."
                )

            val localName = tokenStorage.getUserName() ?: "Mobile User"
            roomManager.connect(
                url = livekitUrl,
                token = tokenData.token,
                localName = localName,
                localRole = _currentUserRole.value
            )

            // Media the user asked for while the room was still connecting.
            val queued = pendingMediaEnable.toList()
            pendingMediaEnable.clear()
            queued.forEach { kind ->
                if (kind == "mic") roomManager.setMicEnabled(true)
                else roomManager.setCameraEnabled(true)
            }

            // The door opened (or never was shut): we are in.
            waitingForHost.value = false
            joinBlocked.value = null

            // 5. Load permissions & co-hosts
            loadOwnPermissions()
            loadCoHosts()

            // 6. If Host/Moderator: start auto-checker engine and load inbox
            if (isModerator.value) {
                loadAttendanceRoster()
                loadPendingRequests()
                startAutoCheckerEngine()
            }

            // 7. A recording already in progress belongs to the room, not to the
            // app that started it: adopt it so the REC badge and the host's stop
            // button match the server. One request, and only on join.
            resumeRecordingState()
        } catch (e: retrofit2.HttpException) {
            // The class is closed to everybody but the host. Say so in the
            // server's own words rather than showing an empty, silent stage.
            val message = serverMessage(e)
                ?: "You cannot join this class right now (HTTP ${e.code()})."
            val waiting = serverErrorCode(e) == "SESSION_NOT_STARTED"
            waitingForHost.value = waiting
            joinBlocked.value = message
            if (waiting) startWaitingForHost()
        } catch (e: Exception) {
            e.printStackTrace()
            val raw = e.localizedMessage ?: ""
            joinBlocked.value = if (raw.contains("url scheme", ignoreCase = true)) {
                // The SDK's own words for a malformed server address are useless
                // to a student; the address comes from the server's settings.
                "This class has no valid live video endpoint configured. Ask your administrator to check the LiveKit server URL in the admin settings."
            } else {
                "Could not join the class: ${raw.ifBlank { "connection failed" }}"
            }
        } finally {
            _isLoading.value = false
        }
    }

    /** The `message` field of a `{"error": {...}}` response, when there is one. */
    private fun serverMessage(e: retrofit2.HttpException?): String? {
        if (e == null) return null
        val body = try {
            e.response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        if (body.isNullOrBlank()) return null
        return """"message"\s*:\s*"([^"]+)"""".toRegex().find(body)?.groupValues?.get(1)
    }

    /** The `code` field of a `{"error": {...}}` response, when there is one. */
    private fun serverErrorCode(e: retrofit2.HttpException?): String? {
        if (e == null) return null
        val body = try {
            e.response()?.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        if (body.isNullOrBlank()) return null
        return """"code"\s*:\s*"([^"]+)"""".toRegex().find(body)?.groupValues?.get(1)
    }

    /**
     * Wait outside a class that has not opened yet.
     *
     * One small GET every five seconds, and only while the student is parked in
     * the lobby: the moment the host's arrival flips the session to active, the
     * join is attempted again and they walk straight in.
     */
    private fun startWaitingForHost() {
        if (waitingJob?.isActive == true) return
        waitingJob = viewModelScope.launch {
            while (waitingForHost.value) {
                delay(5000)
                if (!waitingForHost.value) break
                val client = api ?: continue
                val session = try {
                    client.getSessionDetail(sessionId).document
                } catch (e: Exception) {
                    continue
                }
                _session.value = session
                if (session.status == "ended" || !session.is_active) continue
                // The host is in: try the door again.
                initClassroom()
            }
        }
    }

    /** Give up on the lobby -- the student is leaving the screen. */
    fun stopWaitingForHost() {
        waitingForHost.value = false
    }

    /**
     * The user's answer to the system camera/microphone prompt.
     *
     * Turning the devices on has to wait for the room, so it is queued until
     * the connection is up instead of racing it and failing silently.
     */
    fun onSystemMediaPermissions(micGranted: Boolean, cameraGranted: Boolean) {
        viewModelScope.launch {
            if (micGranted) enableMedia("mic")
            if (cameraGranted) enableMedia("camera")
        }
    }

    private suspend fun enableMedia(kind: String) {
        if (!roomManager.isConnected.value) {
            pendingMediaEnable.add(kind)
            return
        }
        if (kind == "mic") roomManager.setMicEnabled(true) else roomManager.setCameraEnabled(true)
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

    /**
     * Tell the user about something the app did -- in the banner, not the chat.
     *
     * Every action used to be appended to the class chat as a "Podium" message,
     * which turned a conversation with the lecturer into a log of button presses
     * ("Recording started", "Co-host appointed", and an error for every failure).
     * The class chat is for people; app feedback belongs in the transient banner
     * and, when the phone is in a pocket, in a system notification.
     */
    fun pushSystemMessage(message: String) {
        _systemNotification.tryEmit(message)
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
            val sender = _currentUserName.value.ifBlank { if (isModerator.value) "Host" else "Student" }
            roomManager.sendChatMessage(
                text = text,
                senderName = sender,
                role = _currentUserRole.value
            )
        }
    }

    fun initVideoRenderer(view: io.livekit.android.renderer.TextureViewRenderer) {
        roomManager.initVideoRenderer(view)
    }

    override fun onCleared() {
        super.onCleared()
        autoCheckerJob?.cancel()
        viewModelScope.launch {
            roomManager.disconnect()
        }
    }
}
