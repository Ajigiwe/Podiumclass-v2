package online.podiumclass.app.data.api

import online.podiumclass.app.data.api.models.*
import retrofit2.http.*

interface PodiumApiService {

    // ==================== AUTHENTICATION ====================
    @POST("/api/v1/accounts/google/")
    suspend fun googleLogin(@Body request: GoogleLoginRequest): DocumentResponse<AuthResponseDto>

    @POST("/api/v1/accounts/google/")
    suspend fun devLogin(@Body request: DevLoginRequest): DocumentResponse<AuthResponseDto>

    @GET("/api/v1/accounts/me/")
    suspend fun getCurrentUser(): DocumentResponse<UserDto>

    // ==================== SESSIONS ====================
    @GET("/api/v1/classrooms/sessions/")
    suspend fun getSessions(@Query("status") status: String? = null): DocumentsResponse<SessionDto>

    @GET("/api/v1/classrooms/sessions/{id}/")
    suspend fun getSessionDetail(@Path("id") sessionId: String): DocumentResponse<SessionDto>

    @POST("/api/v1/classrooms/sessions/")
    suspend fun createSession(@Body request: CreateSessionRequest): DocumentResponse<SessionDto>

    @PATCH("/api/v1/classrooms/sessions/{id}/")
    suspend fun patchSession(
        @Path("id") sessionId: String,
        @Body request: PatchSessionRequest
    ): DocumentResponse<SessionDto>

    @POST("/api/v1/classrooms/sessions/{id}/token/")
    suspend fun getLiveKitToken(@Path("id") sessionId: String): DocumentResponse<LiveKitTokenDto>

    @POST("/api/v1/classrooms/sessions/{id}/end/")
    suspend fun endSession(@Path("id") sessionId: String): DocumentResponse<SessionDto>

    // ==================== MODERATION & PERMISSIONS ====================
    @GET("/api/v1/classrooms/sessions/{id}/permissions/")
    suspend fun getMyPermissions(@Path("id") sessionId: String): DocumentResponse<PermissionGrantDto>

    @PATCH("/api/v1/classrooms/sessions/{id}/permissions/")
    suspend fun patchParticipantPermission(
        @Path("id") sessionId: String,
        @Body body: Map<String, String>
    ): DocumentResponse<PermissionGrantDto>

    @GET("/api/v1/classrooms/sessions/{id}/permissions/requests/")
    suspend fun getPendingPermissionRequests(@Path("id") sessionId: String): DocumentsResponse<PermissionRequestDto>

    @POST("/api/v1/classrooms/sessions/{id}/permissions/requests/")
    suspend fun requestPermission(
        @Path("id") sessionId: String,
        @Body request: PermissionRequestCreate
    ): DocumentResponse<PermissionRequestDto>

    @PATCH("/api/v1/classrooms/sessions/{id}/permissions/requests/{requestId}/")
    suspend fun reviewPermissionRequest(
        @Path("id") sessionId: String,
        @Path("requestId") requestId: String,
        @Body request: PermissionReviewRequest
    ): DocumentResponse<PermissionRequestDto>

    // ==================== CO-HOSTS ====================
    @GET("/api/v1/classrooms/sessions/{id}/cohosts/")
    suspend fun getCoHosts(@Path("id") sessionId: String): DocumentsResponse<CoHostDto>

    @POST("/api/v1/classrooms/sessions/{id}/cohosts/")
    suspend fun appointCoHost(
        @Path("id") sessionId: String,
        @Body request: AppointCoHostRequest
    ): DocumentResponse<CoHostDto>

    @DELETE("/api/v1/classrooms/sessions/{id}/cohosts/{userId}/")
    suspend fun removeCoHost(
        @Path("id") sessionId: String,
        @Path("userId") userId: String
    ): DocumentResponse<CoHostDto>

    // ==================== RECORDINGS ====================
    @GET("/api/v1/classrooms/sessions/{id}/recordings/")
    suspend fun getRecordings(@Path("id") sessionId: String): DocumentsResponse<RecordingDto>

    @POST("/api/v1/classrooms/sessions/{id}/recordings/")
    suspend fun startRecording(@Path("id") sessionId: String): DocumentResponse<RecordingDto>

    @GET("/api/v1/classrooms/sessions/{id}/recordings/{recordingId}/")
    suspend fun getRecordingDetail(
        @Path("id") sessionId: String,
        @Path("recordingId") recordingId: String
    ): DocumentResponse<RecordingDto>

    @POST("/api/v1/classrooms/sessions/{id}/recordings/{recordingId}/stop/")
    suspend fun stopRecording(
        @Path("id") sessionId: String,
        @Path("recordingId") recordingId: String
    ): DocumentResponse<RecordingDto>

    // ==================== ATTENDANCE ====================
    @POST("/api/v1/attendance/join/")
    suspend fun registerAttendanceJoin(@Body request: AttendanceJoinRequest): DocumentResponse<Map<String, String>>

    @POST("/api/v1/attendance/verification/trigger/")
    suspend fun triggerVerification(@Body request: VerificationTriggerRequest): DocumentResponse<VerificationTriggerDto>

    @POST("/api/v1/attendance/verification/respond/")
    suspend fun respondVerification(@Body request: VerificationRespondRequest): DocumentResponse<VerificationRespondResultDto>

    @GET("/api/v1/attendance/sessions/{id}/")
    suspend fun getAttendanceRoster(@Path("id") sessionId: String): DocumentResponse<AttendanceRosterDto>

    // ==================== CHAT MESSAGES ====================
    @GET("/api/v1/classrooms/sessions/{id}/messages/")
    suspend fun getChatMessages(@Path("id") sessionId: String): DocumentsResponse<ChatMessageDto>

    @POST("/api/v1/classrooms/sessions/{id}/messages/")
    suspend fun postChatMessage(
        @Path("id") sessionId: String,
        @Body message: ChatMessageDto
    ): DocumentResponse<ChatMessageDto>
}
