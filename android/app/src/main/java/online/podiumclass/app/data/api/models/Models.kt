package online.podiumclass.app.data.api.models

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
    val university: String? = null
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
data class SessionDto(
    val id: String,
    val title: String,
    val meetingCode: String? = null,
    val status: String = "active",
    val is_active: Boolean = true,
    val is_free: Boolean = true,
    val price: Long = 0,
    val currency: String = "GHS",
    val lecturerName: String? = null,
    val program: String? = null,
    val course: String? = null,
    val scheduledStartTime: String? = null,
    val durationMinutes: Int? = null,
    val participantCount: Int = 0,
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
    val joined: Boolean = false,
    val present: Boolean = false,
    val verificationsSent: Int = 0,
    val verificationsCompleted: Int = 0,
    val verificationPercentage: Double = 0.0
)

@Serializable
data class AttendanceRosterDto(
    val session: SessionDto? = null,
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
