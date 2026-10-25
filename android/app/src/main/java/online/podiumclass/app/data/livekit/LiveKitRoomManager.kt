package online.podiumclass.app.data.livekit

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
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

    private val raisedHandsSet = mutableSetOf<String>()

    suspend fun connect(url: String, token: String, localName: String, localRole: String) {
        val newRoom = LiveKit.create(context)
        room = newRoom

        scope.launch {
            newRoom.events.collect { event ->
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
            }
            is RoomEvent.ParticipantConnected,
            is RoomEvent.ParticipantDisconnected,
            is RoomEvent.TrackSubscribed,
            is RoomEvent.TrackUnsubscribed,
            is RoomEvent.TrackMuted,
            is RoomEvent.TrackUnmuted,
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
                    val identity = sender?.identity ?: packet.identity
                    if (identity != null) {
                        if (packet.raised == true) {
                            raisedHandsSet.add(identity)
                        } else {
                            raisedHandsSet.remove(identity)
                        }
                        updateParticipantsRoster()
                    }
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
        val hasLocalCam = local.videoTrackPublications.any { it.track?.isMuted == false }
        val hasLocalMic = local.audioTrackPublications.any { it.track?.isMuted == false }
        val localVideo = local.videoTrackPublications.firstOrNull()?.track

        list.add(
            ParticipantTileState(
                identity = local.identity ?: "local",
                name = local.name ?: defaultLocalName,
                role = defaultLocalRole,
                isLocal = true,
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
            val remoteVideo = p.videoTrackPublications.firstOrNull { it.track != null }?.track
            val hasVideo = remoteVideo != null && !remoteVideo.isMuted
            val hasMic = p.audioTrackPublications.any { it.track?.isMuted == false }

            list.add(
                ParticipantTileState(
                    identity = p.identity ?: "remote_${p.sid}",
                    name = p.name ?: p.identity ?: "Student",
                    role = "student",
                    isLocal = false,
                    isSpeaking = p.isSpeaking,
                    hasVideo = hasVideo,
                    hasScreenShare = false,
                    isMicOn = hasMic,
                    isHandRaised = raisedHandsSet.contains(p.identity),
                    videoTrack = remoteVideo
                )
            )
        }

        _participants.value = list
    }

    suspend fun setMicEnabled(enabled: Boolean) {
        val r = room ?: return
        r.localParticipant.setMicrophoneEnabled(enabled)
        _isMicEnabled.value = enabled
        updateParticipantsRoster()
    }

    suspend fun toggleMic() {
        setMicEnabled(!_isMicEnabled.value)
    }

    suspend fun setCameraEnabled(enabled: Boolean) {
        val r = room ?: return
        r.localParticipant.setCameraEnabled(enabled)
        _isCameraEnabled.value = enabled
        updateParticipantsRoster()
    }

    suspend fun toggleCamera() {
        setCameraEnabled(!_isCameraEnabled.value)
    }

    suspend fun flipCamera() {
        val r = room ?: return
        val localVideo = r.localParticipant.videoTrackPublications.firstOrNull()?.track as? LocalVideoTrack
        // Switch position
        localVideo?.let {
            // LiveKit SDK handles camera options
        }
    }

    suspend fun toggleHandRaise(localName: String) {
        val next = !_isHandRaised.value
        _isHandRaised.value = next
        val identity = room?.localParticipant?.identity ?: "local"
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
        room?.disconnect()
        room = null
        _isConnected.value = false
        _participants.value = emptyList()
        raisedHandsSet.clear()
    }
}
