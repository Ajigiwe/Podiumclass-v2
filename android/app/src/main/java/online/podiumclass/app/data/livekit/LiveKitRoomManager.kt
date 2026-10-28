package online.podiumclass.app.data.livekit

import android.content.Context
import android.media.AudioManager
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import online.podiumclass.app.data.api.models.DataPacket

data class ParticipantTileState(
    val identity: String,
    val name: String,
    val role: String = "student",
    val isLocal: Boolean = false,
    val isCoHost: Boolean = false,
    val isSpeaking: Boolean = false,
    val hasVideo: Boolean = false,
    val hasScreenShare: Boolean = false,
    val isMicOn: Boolean = false,
    val isHandRaised: Boolean = false,
    val videoTrack: Track? = null
)

class LiveKitRoomManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    var room: Room? = null
        private set

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _participants = MutableStateFlow<List<ParticipantTileState>>(emptyList())
    val participants: StateFlow<List<ParticipantTileState>> = _participants.asStateFlow()

    private val _isMicEnabled = MutableStateFlow(false)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(false)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _isHandRaised = MutableStateFlow(false)
    val isHandRaised: StateFlow<Boolean> = _isHandRaised.asStateFlow()

    private val _incomingVerification = MutableSharedFlow<DataPacket>(extraBufferCapacity = 10)
    val incomingVerification: SharedFlow<DataPacket> = _incomingVerification.asSharedFlow()

    private val _incomingChat = MutableSharedFlow<DataPacket>(extraBufferCapacity = 50)
    val incomingChat: SharedFlow<DataPacket> = _incomingChat.asSharedFlow()

    private val _incomingReaction = MutableSharedFlow<String>(extraBufferCapacity = 20)
    val incomingReaction: SharedFlow<String> = _incomingReaction.asSharedFlow()

    // Moderation & Permissions real-time events
    private val _incomingModeration = MutableSharedFlow<DataPacket>(extraBufferCapacity = 20)
    val incomingModeration: SharedFlow<DataPacket> = _incomingModeration.asSharedFlow()

    private val _incomingPermissionRequest = MutableSharedFlow<DataPacket>(extraBufferCapacity = 20)
    val incomingPermissionRequest: SharedFlow<DataPacket> = _incomingPermissionRequest.asSharedFlow()

    private val _incomingPermissionResponse = MutableSharedFlow<DataPacket>(extraBufferCapacity = 20)
    val incomingPermissionResponse: SharedFlow<DataPacket> = _incomingPermissionResponse.asSharedFlow()

    private val _onReconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 5)
    val onReconnected: SharedFlow<Unit> = _onReconnected.asSharedFlow()

    /** The room dropped us: the host ended the class, or the connection died. */
    private val _onRoomClosed = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val onRoomClosed: SharedFlow<String> = _onRoomClosed.asSharedFlow()

    /**
     * Media that could not be switched on or off, with the reason.
     *
     * Enabling the microphone or camera can fail for reasons the user has to
     * act on (permission revoked, another app holding the device), and the
     * previous code swallowed the exception, leaving a button that silently did
     * nothing. These are surfaced to the screen instead.
     */
    private val _mediaErrors = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val mediaErrors: SharedFlow<String> = _mediaErrors.asSharedFlow()

    /** True once we asked to leave, so a clean exit is not reported as a drop. */
    private var leaving = false

    private val raisedHandsSet = mutableSetOf<String>()
    private val coHostsSet = mutableSetOf<String>()

    fun setCoHosts(ids: Set<String>) {
        coHostsSet.clear()
        coHostsSet.addAll(ids)
        updateParticipantsRoster()
    }

    fun initVideoRenderer(view: TextureViewRenderer) {
        try {
            room?.initVideoRenderer(view)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun connect(url: String, token: String, localName: String, localRole: String) {
        leaving = false
        val newRoom = LiveKit.create(context)
        room = newRoom

        // Configure audio for communications loudspeaker
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let {
                it.mode = AudioManager.MODE_IN_COMMUNICATION
                it.isSpeakerphoneOn = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        scope.launch {
            newRoom.events.events.collect { event ->
                handleRoomEvent(event, localName, localRole)
            }
        }

        try {
            newRoom.connect(url, token)
            _isConnected.value = true
            updateParticipantsRoster(localName, localRole)
        } catch (e: Exception) {
            _isConnected.value = false
            throw e
        }
    }

    private fun handleRoomEvent(event: RoomEvent, localName: String, localRole: String) {
        when (event) {
            is RoomEvent.Connected -> {
                _isConnected.value = true
                updateParticipantsRoster(localName, localRole)
            }
            is RoomEvent.Disconnected -> {
                _isConnected.value = false
                _participants.value = emptyList()
                _isMicEnabled.value = false
                _isCameraEnabled.value = false
                // Only a drop we did not ask for is worth telling the user
                // about -- leaving with the red button already says so.
                if (!leaving) {
                    _onRoomClosed.tryEmit(
                        "This class has ended and needs its host to start it again. " +
                            "Wait for the host to rejoin, then enter the class again."
                    )
                }
            }
            is RoomEvent.Reconnected -> {
                _isConnected.value = true
                _onReconnected.tryEmit(Unit)
                updateParticipantsRoster(localName, localRole)
            }
            is RoomEvent.ParticipantConnected,
            is RoomEvent.ParticipantDisconnected,
            is RoomEvent.TrackSubscribed,
            is RoomEvent.TrackUnsubscribed,
            is RoomEvent.TrackMuted,
            is RoomEvent.TrackUnmuted,
            is RoomEvent.TrackPublished,
            is RoomEvent.TrackUnpublished,
            is RoomEvent.ActiveSpeakersChanged -> {
                updateParticipantsRoster(localName, localRole)
            }
            is RoomEvent.DataReceived -> {
                handleDataMessage(event.data, event.participant)
            }
            else -> {}
        }
    }

    private fun handleDataMessage(data: ByteArray, sender: RemoteParticipant?) {
        try {
            val text = String(data, Charsets.UTF_8)
            val packet = json.decodeFromString<DataPacket>(text)
            when (packet.type) {
                "verification" -> {
                    _incomingVerification.tryEmit(packet)
                }
                "chat" -> {
                    _incomingChat.tryEmit(packet)
                }
                "reaction" -> {
                    packet.emoji?.let { _incomingReaction.tryEmit(it) }
                }
                "hand" -> {
                    val identity = sender?.identity?.value ?: packet.identity
                    if (identity != null) {
                        if (packet.raised == true) {
                            raisedHandsSet.add(identity)
                        } else {
                            raisedHandsSet.remove(identity)
                        }
                        updateParticipantsRoster()
                    }
                }
                "moderation" -> {
                    _incomingModeration.tryEmit(packet)
                    if (packet.isMutedAll == true && _isMicEnabled.value) {
                        scope.launch { setMicEnabled(false) }
                    }
                }
                "permission_request" -> {
                    _incomingPermissionRequest.tryEmit(packet)
                }
                "permission_response" -> {
                    _incomingPermissionResponse.tryEmit(packet)
                }
                "mute_all" -> {
                    if (packet.muted == true && _isMicEnabled.value) {
                        scope.launch { setMicEnabled(false) }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore malformed packets
        }
    }

    private fun updateParticipantsRoster(
        defaultLocalName: String = "You",
        defaultLocalRole: String = "student"
    ) {
        val r = room ?: return
        val list = mutableListOf<ParticipantTileState>()

        // Local participant
        val local = r.localParticipant
        val hasLocalCam = local.videoTrackPublications.any { (pub, track) -> !pub.muted && (track != null || pub.track != null) }
        val hasLocalMic = local.audioTrackPublications.any { (pub, _) -> !pub.muted }
        val localVideo = local.videoTrackPublications.firstOrNull { (pub, track) ->
            !pub.muted && (track != null || pub.track != null)
        }?.let { it.second ?: it.first.track }
        val localIdentity = local.identity?.value ?: "local"

        list.add(
            ParticipantTileState(
                identity = localIdentity,
                name = local.name ?: defaultLocalName,
                role = defaultLocalRole,
                isLocal = true,
                isCoHost = coHostsSet.contains(localIdentity),
                isSpeaking = local.isSpeaking,
                hasVideo = hasLocalCam,
                hasScreenShare = false,
                isMicOn = hasLocalMic,
                isHandRaised = _isHandRaised.value,
                videoTrack = localVideo
            )
        )

        // Remote participants
        r.remoteParticipants.values.forEach { p ->
            val remoteVideo = p.videoTrackPublications.firstOrNull { (pub, track) ->
                !pub.muted && (track != null || pub.track != null)
            }?.let { it.second ?: it.first.track }
            val hasVideo = remoteVideo != null
            val hasMic = p.audioTrackPublications.any { (pub, _) -> !pub.muted }
            val pIdentity = p.identity?.value ?: "remote_${p.sid.value}"

            list.add(
                ParticipantTileState(
                    identity = pIdentity,
                    name = p.name ?: p.identity?.value ?: "Student",
                    role = "student",
                    isLocal = false,
                    isCoHost = coHostsSet.contains(pIdentity),
                    isSpeaking = p.isSpeaking,
                    hasVideo = hasVideo,
                    hasScreenShare = false,
                    isMicOn = hasMic,
                    isHandRaised = raisedHandsSet.contains(pIdentity),
                    videoTrack = remoteVideo
                )
            )
        }

        _participants.value = list

        // The published tracks are the truth. An optimistic flag could claim the
        // camera was on while no track existed, which is exactly how a failing
        // capture looked "on" and did nothing.
        _isMicEnabled.value = hasLocalMic
        _isCameraEnabled.value = hasLocalCam
    }

    suspend fun setMicEnabled(enabled: Boolean): Boolean {
        val r = room ?: run {
            _mediaErrors.tryEmit("Still connecting to the class. Try the microphone again in a moment.")
            return false
        }
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let {
                it.mode = AudioManager.MODE_IN_COMMUNICATION
                it.isSpeakerphoneOn = true
            }
            r.localParticipant.setMicrophoneEnabled(enabled)
            _isMicEnabled.value = enabled
            updateParticipantsRoster()
            true
        } catch (e: Exception) {
            _isMicEnabled.value = false
            _mediaErrors.tryEmit(
                if (enabled) "The microphone could not start: ${mediaReason(e)}"
                else "The microphone could not be turned off: ${mediaReason(e)}"
            )
            false
        }
    }

    suspend fun toggleMic(): Boolean = setMicEnabled(!_isMicEnabled.value)

    suspend fun setCameraEnabled(enabled: Boolean): Boolean {
        val r = room ?: run {
            _mediaErrors.tryEmit("Still connecting to the class. Try the camera again in a moment.")
            return false
        }
        return try {
            r.localParticipant.setCameraEnabled(enabled)
            _isCameraEnabled.value = enabled
            updateParticipantsRoster()
            true
        } catch (e: Exception) {
            _isCameraEnabled.value = false
            _mediaErrors.tryEmit(
                if (enabled) "The camera could not start: ${mediaReason(e)}"
                else "The camera could not be turned off: ${mediaReason(e)}"
            )
            false
        }
    }

    suspend fun toggleCamera(): Boolean = setCameraEnabled(!_isCameraEnabled.value)

    /** A one-line cause the student can act on, rather than a stack trace. */
    private fun mediaReason(e: Exception): String {
        val raw = e.message ?: e::class.java.simpleName
        return when {
            raw.contains("permission", ignoreCase = true) || e is SecurityException ->
                "camera or microphone permission is not granted."
            raw.contains("in use", ignoreCase = true) || raw.contains("CameraAccessException", ignoreCase = true) ->
                "another app is using the device."
            else -> raw
        }
    }

    suspend fun flipCamera() {
        val r = room ?: return
        val localVideo = r.localParticipant.videoTrackPublications.firstOrNull()?.second as? LocalVideoTrack
        try {
            localVideo?.switchCamera()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun toggleHandRaise(localName: String) {
        val next = !_isHandRaised.value
        _isHandRaised.value = next
        val identity = room?.localParticipant?.identity?.value ?: "local"
        if (next) raisedHandsSet.add(identity) else raisedHandsSet.remove(identity)

        val packet = DataPacket(
            type = "hand",
            raised = next,
            identity = identity,
            name = localName
        )
        publishData(packet, "hand")
        updateParticipantsRoster()
    }

    suspend fun sendReaction(emoji: String) {
        _incomingReaction.tryEmit(emoji)
        val packet = DataPacket(type = "reaction", emoji = emoji)
        publishData(packet, "reaction")
    }

    suspend fun sendChatMessage(text: String, senderName: String, role: String) {
        val packet = DataPacket(
            type = "chat",
            id = "msg_${System.currentTimeMillis()}",
            sender = senderName,
            message = text,
            role = role,
            time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        )
        _incomingChat.tryEmit(packet)
        publishData(packet, "chat")
    }

    suspend fun broadcastVerification(verificationId: String, number: Int, timeLimit: Int, totalTarget: Int) {
        val packet = DataPacket(
            type = "verification",
            verificationId = verificationId,
            verificationNumber = number,
            timeLimitSeconds = timeLimit,
            totalTargetChecks = totalTarget
        )
        publishData(packet, "attendance")
    }

    suspend fun broadcastMuteAll(muted: Boolean) {
        val packet = DataPacket(type = "mute_all", muted = muted)
        publishData(packet, "moderation")
    }

    suspend fun broadcastModeration(
        isMutedAll: Boolean,
        autoApproveMic: Boolean,
        permissions: online.podiumclass.app.data.api.models.PermissionPolicyDto
    ) {
        val packet = DataPacket(
            type = "moderation",
            isMutedAll = isMutedAll,
            autoApproveMic = autoApproveMic,
            permissions = permissions
        )
        publishData(packet, "moderation")
    }

    suspend fun broadcastPermissionRequest(requestType: String) {
        val packet = DataPacket(
            type = "permission_request",
            requestType = requestType
        )
        publishData(packet, "moderation")
    }

    suspend fun broadcastPermissionResponse(target: String, requestType: String, granted: Boolean) {
        val packet = DataPacket(
            type = "permission_response",
            target = target,
            requestType = requestType,
            granted = granted
        )
        publishData(packet, "moderation")
    }

    private suspend fun publishData(packet: DataPacket, topic: String) {
        val r = room ?: return
        try {
            val bytes = json.encodeToString(DataPacket.serializer(), packet).toByteArray(Charsets.UTF_8)
            r.localParticipant.publishData(bytes, topic = topic)
        } catch (e: Exception) {
            // Publish error handling
        }
    }

    suspend fun disconnect() {
        // Leaving on purpose (the red button, or the host ending the class from
        // this device) is not a drop, so it must not raise the removal notice.
        leaving = true
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let {
                it.isSpeakerphoneOn = false
                it.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        room?.disconnect()
        room = null
        _isConnected.value = false
        _participants.value = emptyList()
        raisedHandsSet.clear()
        coHostsSet.clear()
    }
}
