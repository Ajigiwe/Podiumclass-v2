package online.podiumclass.app.data.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DocumentResponse<T>(
    val document: T
)

@Serializable
data class DocumentsResponse<T>(
    val documents: List<T> = emptyList(),
    val pagination: PaginationDto? = null
)

@Serializable
data class PaginationDto(
    val count: Int = 0,
    val limit: Int = 25
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val fullName: String? = null,
    val role: String = "student",
    val photoUrl: String? = null,
    val indexNumber: String? = null,
    val walletBalance: Long = 0,
    val university: String? = null,
    val bio: String? = null,
    val phone: String? = null,
    val year: String? = null,
    // Admin-granted, not self-service: lets an account host without being a
    // lecturer. The server decides; the app only reflects it.
    val canCreateClasses: Boolean = false,
    val status: String = "active",
    val isVerified: Boolean = false,
    val createdAt: String? = null
)

@Serializable
data class UpdateProfileRequest(
    val fullName: String? = null,
    val indexNumber: String? = null,
    val university: String? = null,
    val bio: String? = null,
    val phone: String? = null,
    val year: String? = null
)

@Serializable
data class AuthResponseDto(
    val user: UserDto,
    val token: String
)

@Serializable
data class DevLoginRequest(
    val dev_login: Boolean = true,
    val role: String = "student"
)

@Serializable
data class GoogleLoginRequest(
    val credential: String? = null,
    val access_token: String? = null,
    val dev_login: Boolean? = null,
    val role: String? = null
)

@Serializable
data class AutoAttendanceSettingsDto(
    val enabled: Boolean = true,
    val checkCount: Int = 3,
    val timeLimitSeconds: Int = 30,
    val intervalMinutes: Int = 15
)

@Serializable
data class PermissionPolicyDto(
    val mic: String = "free",
    val camera: String = "free",
    val screen: String = "free"
)

@Serializable
data class PermissionGrantDto(
    val participantId: String? = null,
    val mic: Boolean = false,
    val camera: Boolean = false,
    val screen: Boolean = false,
    val cohost: Boolean = false
)

@Serializable
data class PermissionRequestDto(
    val id: String,
    val participantId: String,
    val participantName: String,
    val requestType: String,
    val status: String = "pending",
    val requestedAt: String? = null,
    val reviewedAt: String? = null,
    val reviewedBy: String? = null
)

@Serializable
data class PermissionRequestCreate(
    val requestType: String
)

@Serializable
data class PermissionReviewRequest(
    val status: String
)

@Serializable
data class CoHostDto(
    val id: String,
    val userId: String,
    val userName: String,
    val assignedBy: String? = null,
    val assignedAt: String? = null
)

@Serializable
data class AppointCoHostRequest(
    val userId: String
)

@Serializable
data class RecordingDto(
    val id: String,
    val sessionId: String,
    val title: String? = null,
    val status: String = "starting",
    val isLive: Boolean = false,
    val canPlay: Boolean = false,
    val error: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    // Long, not Int: a duration reported in the wrong unit can exceed a
    // 32-bit int, and one oversized field must not fail the whole shelf.
    val durationSeconds: Long? = null,
    val sizeBytes: Long? = null,
    // Signed URLs. A player and the system downloader fetch a URL with no
    // headers of their own, so the credential rides in the query string; the
    // watch link is minted for anyone who may see the shelf and the download
    // link only for the people who run the class.
    val playUrl: String? = null,
    val downloadUrl: String? = null,
    val canDownload: Boolean = false,
    val fileName: String? = null,
    val fileUrl: String? = null
)

@Serializable
data class SessionDto(
    val id: String,
    val title: String,
    val meetingCode: String? = null,
    val status: String = "active",
    // When the class was created and when it actually ran. The dashboard uses
    // them for the register list; the length booked is not the length it ran.
    val createdAt: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val is_active: Boolean = true,
    val is_free: Boolean = true,
    val price: Long = 0,
    val currency: String = "GHS",
    val lecturerName: String? = null,
    val program: String? = null,
    val course: String? = null,
    val scheduledStartTime: String? = null,
    val durationMinutes: Int? = null,
    // How long the class really ran, from its start to its end, as opposed to
    // the length it was booked for. Null until it has started.
    val actualDurationMinutes: Int? = null,
    val participantCount: Int = 0,
    // Real attendance for the class, counted from the attendance records.
    // "participantCount" was never written by the server, so anything built on
    // it (every "verified" figure) read zero.
    val attendeeCount: Int = 0,
    val presentCount: Int = 0,
    val hostId: String? = null,
    val verificationCount: Int? = 3,
    val autoAttendanceSettings: AutoAttendanceSettingsDto? = null,
    val isMutedAll: Boolean = false,
    val autoApproveMic: Boolean = false,
    val permissions: PermissionPolicyDto = PermissionPolicyDto(),
    val backupModId: String? = null
)

@Serializable
data class CreateSessionRequest(
    val title: String,
    val course: String? = null,
    val program: String? = null,
    // How the host appears on the class and in attendance records. Left out of
    // the request (the default), the server uses the account's own name.
    val lecturerName: String? = null,
    val is_free: Boolean = true,
    val price: Long = 0,
    val durationMinutes: Int = 60,
    val verificationCount: Int = 3,
    val autoAttendanceSettings: AutoAttendanceSettingsDto = AutoAttendanceSettingsDto()
)

@Serializable
data class PatchSessionRequest(
    val verificationCount: Int? = null,
    val autoAttendanceSettings: AutoAttendanceSettingsDto? = null,
    val title: String? = null,
    val status: String? = null,
    val is_active: Boolean? = null,
    val isMutedAll: Boolean? = null,
    val autoApproveMic: Boolean? = null,
    val permissions: Map<String, String>? = null
)

@Serializable
data class LiveKitTokenDto(
    val token: String,
    val livekitUrl: String? = null
)

@Serializable
data class VerificationTriggerRequest(
    val sessionId: String,
    val timeLimitSeconds: Int = 30,
    val triggeredBy: String? = null
)

@Serializable
data class VerificationTriggerDto(
    val verificationId: String,
    val verificationNumber: Int,
    val expiresAt: String? = null,
    val timeLimitSeconds: Int = 30
)

@Serializable
data class VerificationRespondRequest(
    val sessionId: String,
    val verificationId: String
)

@Serializable
data class VerificationRespondResultDto(
    val success: Boolean = true,
    val verificationId: String,
    val studentId: String? = null,
    val responseTimeSeconds: Int? = null
)

@Serializable
data class AttendanceJoinRequest(
    val sessionId: String
)

@Serializable
data class AttendanceSummaryDto(
    val total: Int = 0,
    val joined: Int = 0,
    val present: Int = 0,
    val absent: Int = 0,
    val averageVerificationPercentage: Double = 0.0
)

@Serializable
data class AttendanceRecordDto(
    val studentId: String,
    val studentName: String,
    val indexNumber: String = "",
    val joined: Boolean = false,
    val present: Boolean = false,
    val joinedAt: String? = null,
    val leftAt: String? = null,
    // Minutes this student was actually in the room, bounded by the class.
    val stayMinutes: Int? = null,
    val verificationsSent: Int = 0,
    val verificationsCompleted: Int = 0,
    val verificationPercentage: Double = 0.0
)

@Serializable
// One student's own attendance in one class, from /attendance/mine/.
data class StudentAttendanceRecordDto(
    val id: String,
    val sessionId: String,
    val title: String = "Class",
    val course: String? = null,
    val lecturerName: String? = null,
    val status: String = "ended",
    val isDeleted: Boolean = false,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val createdAt: String? = null,
    val actualDurationMinutes: Int? = null,
    val joinedAt: String? = null,
    val leftAt: String? = null,
    val stayMinutes: Int? = null,
    val present: Boolean = false,
    val verificationPercentage: Int = 0,
    val verificationsSent: Int = 0,
    val verificationsCompleted: Int = 0
)

@Serializable
// The class block inside a register, from /attendance/sessions/<id>/.
//
// Deliberately not a SessionDto. That serializer names the id "sessionId"
// rather than "id", so decoding it as a SessionDto failed on every register
// with a missing-field error and the app showed that error where the roster
// should have been.
data class AttendanceSessionDto(
    val sessionId: String = "",
    val title: String = "Class",
    val course: String? = null,
    val program: String? = null,
    val lecturerId: String? = null,
    val lecturerName: String? = null,
    val status: String = "ended",
    val isActive: Boolean = false,
    val createdAt: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val durationMinutes: Int? = null,
    val participantCount: Int = 0,
    val attendeeCount: Int = 0,
    val presentCount: Int = 0
)

@Serializable
data class AttendanceRosterDto(
    val session: AttendanceSessionDto? = null,
    val summary: AttendanceSummaryDto = AttendanceSummaryDto(),
    val records: List<AttendanceRecordDto> = emptyList()
)

@Serializable
data class ChatMessageDto(
    val id: String,
    val sender: String,
    val message: String,
    val role: String = "student",
    val time: String? = null
)

@Serializable
data class AdminUserPatchRequest(
    val role: String? = null,
    val canCreateClasses: Boolean? = null,
    val status: String? = null,
    val isVerified: Boolean? = null
)

@Serializable
data class FeatureDto(
    val key: String,
    val label: String = "",
    val description: String = "",
    val group: String = "",
    val critical: Boolean = false,
    @SerialName("default") val defaultEnabled: Boolean = false,
    val enabled: Boolean = false
)

@Serializable
data class FeatureGroupDto(
    val key: String,
    val label: String = "",
    val description: String = ""
)

@Serializable
data class SettingDto(
    val key: String,
    val group: String = "",
    val label: String = "",
    val type: String = "text",
    val help: String = "",
    val secret: Boolean = false,
    val value: String? = null,
    val isSet: Boolean = false,
    // Read-only settings (LiveKit API key/secret) come from the server
    // environment. The admin screen shows them but never offers an edit, and
    // the API rejects a write, so the UI must not pretend otherwise.
    val readOnly: Boolean = false
)

@Serializable
data class SettingGroupDto(
    val key: String,
    val label: String = "",
    val description: String = ""
)

@Serializable
data class AdminSettingsDto(
    val groups: List<SettingGroupDto> = emptyList(),
    val settings: List<SettingDto> = emptyList(),
    val featureGroups: List<FeatureGroupDto> = emptyList(),
    val features: List<FeatureDto> = emptyList()
)

@Serializable
data class FeatureToggleRequest(val enabled: Boolean)

@Serializable
data class SettingUpdateRequest(val value: String)

@Serializable
data class DataPacket(
    val type: String,
    val id: String? = null,
    val sender: String? = null,
    val message: String? = null,
    val role: String? = null,
    val time: String? = null,
    val emoji: String? = null,
    val raised: Boolean? = null,
    val identity: String? = null,
    val name: String? = null,
    val verificationId: String? = null,
    val verificationNumber: Int? = null,
    val timeLimitSeconds: Int? = null,
    val totalTargetChecks: Int? = null,
    val muted: Boolean? = null,
    // Moderation & Permissions
    val isMutedAll: Boolean? = null,
    val autoApproveMic: Boolean? = null,
    val permissions: PermissionPolicyDto? = null,
    val requestType: String? = null,
    val target: String? = null,
    val granted: Boolean? = null
)

/**
 * One resource shared in a class: an uploaded file the server streams back, or
 * a link the host pasted. The two are the same row to the UI.
 */
@Serializable
data class MaterialDto(
    val id: String,
    val title: String = "",
    val url: String = "",
    val type: String = "link",
    val size: String = "",
    val icon: String = "",
    val sharedBy: String? = null,
    val sharedAt: String? = null
)

@Serializable
data class ShareLinkRequest(
    val title: String,
    val url: String,
    val type: String = "link"
)
