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
