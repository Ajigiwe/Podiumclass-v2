package online.podiumclass.app.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import online.podiumclass.app.data.api.models.*
import retrofit2.http.*

interface PodiumApiService {

    // ==================== AUTHENTICATION ====================
    @POST("/api/v1/accounts/google/")
    suspend fun googleLogin(@Body request: GoogleLoginRequest): DocumentResponse<AuthResponseDto>

    @POST("/api/v1/accounts/google/")
    suspend fun devLogin(@Body request: DevLoginRequest): DocumentResponse<AuthResponseDto>

    // The backend renders every payload through DocumentJSONRenderer, so these
    // arrive wrapped in "document" like every other endpoint. Declaring the bare
    // DTO here made kotlinx.serialization look for id/email at the root and fail
    // with MissingFieldException on an otherwise successful 200.
    @GET("/api/v1/accounts/me/")
    suspend fun getCurrentUser(): DocumentResponse<UserDto>

    @PATCH("/api/v1/accounts/me/")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): DocumentResponse<UserDto>

    // Profile picture. The server stores the image and answers with the new
    // versioned photoUrl, so the picture exists beyond this handset.
    @Multipart
    @POST("/api/v1/accounts/me/photo/")
    suspend fun uploadProfilePhoto(@Part photo: MultipartBody.Part): DocumentResponse<UserDto>

    // ==================== ADMIN: USERS ====================
    // Both are admin-only server-side; a non-admin gets a plain 403, which the
    // admin screen surfaces rather than hiding behind an empty list.
    @GET("/api/v1/accounts/users/")
    suspend fun adminListUsers(
        @Query("q") query: String? = null,
        @Query("role") role: String? = null
    ): DocumentsResponse<UserDto>

    @PATCH("/api/v1/accounts/users/{id}/")
    suspend fun adminUpdateUser(
        @Path("id") userId: String,
        @Body request: AdminUserPatchRequest
    ): DocumentResponse<UserDto>

    // ==================== ADMIN: PLATFORM SETTINGS ====================
    @GET("/api/v1/core/settings/")
    suspend fun getAdminSettings(): DocumentResponse<AdminSettingsDto>

    @PATCH("/api/v1/core/features/{key}/")
    suspend fun toggleFeature(
        @Path("key") key: String,
        @Body request: FeatureToggleRequest
    ): DocumentResponse<FeatureDto>

    // Secrets are masked by the server and a blank value means "unchanged", so
    // the app can edit the rest of the settings without ever holding them.
    @PATCH("/api/v1/core/settings/{key}/")
    suspend fun updateSetting(
        @Path("key") key: String,
        @Body request: SettingUpdateRequest
    ): DocumentResponse<SettingDto>

    // ==================== SESSIONS ====================
    // includeDeleted is for history screens: a deleted class is hidden from the
    // live dashboard but its attendance record is still readable.
    // meetingCode resolves a class the caller is not yet in (join by code or by
    // invite link), which the scoped list cannot do by itself.
    @GET("/api/v1/classrooms/sessions/")
    suspend fun getSessions(
        @Query("status") status: String? = null,
        @Query("includeDeleted") includeDeleted: Boolean? = null,
        @Query("meetingCode") meetingCode: String? = null
    ): DocumentsResponse<SessionDto>

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

    @DELETE("/api/v1/classrooms/sessions/{id}/")
    suspend fun deleteSession(@Path("id") sessionId: String): DocumentResponse<Map<String, Boolean>>

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

    /**
     * Every recording in the classes this user runs, in one request -- what the
     * dashboard's Records area shows. It sits above the sessions it belongs to,
     * which is why it takes no id.
     */
    @GET("/api/v1/classrooms/recordings/")
    suspend fun getMyRecordings(): DocumentsResponse<RecordingDto>

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

    // ==================== MATERIALS ====================
    @GET("/api/v1/classrooms/sessions/{id}/materials/")
    suspend fun getMaterials(@Path("id") sessionId: String): DocumentsResponse<MaterialDto>

    @Multipart
    @POST("/api/v1/classrooms/sessions/{id}/materials/")
    suspend fun uploadMaterial(
        @Path("id") sessionId: String,
        @Part("title") title: RequestBody,
        @Part file: MultipartBody.Part
    ): DocumentResponse<MaterialDto>

    @POST("/api/v1/classrooms/sessions/{id}/materials/")
    suspend fun shareMaterialLink(
        @Path("id") sessionId: String,
        @Body request: ShareLinkRequest
    ): DocumentResponse<MaterialDto>

    @DELETE("/api/v1/classrooms/sessions/{id}/materials/{materialId}/")
    suspend fun deleteMaterial(
        @Path("id") sessionId: String,
        @Path("materialId") materialId: String
    ): ResponseBody

    // ==================== ATTENDANCE ====================
    @POST("/api/v1/attendance/join/")
    suspend fun registerAttendanceJoin(@Body request: AttendanceJoinRequest): DocumentResponse<Map<String, String>>

    @POST("/api/v1/attendance/verification/trigger/")
    suspend fun triggerVerification(@Body request: VerificationTriggerRequest): DocumentResponse<VerificationTriggerDto>

    @POST("/api/v1/attendance/verification/respond/")
    suspend fun respondVerification(@Body request: VerificationRespondRequest): DocumentResponse<VerificationRespondResultDto>

    @GET("/api/v1/attendance/sessions/{id}/")
    suspend fun getAttendanceRoster(@Path("id") sessionId: String): DocumentResponse<AttendanceRosterDto>

    // The caller's own attendance records -- what the Attendance tab shows.
    @GET("/api/v1/attendance/mine/")
    suspend fun getMyAttendance(): DocumentsResponse<StudentAttendanceRecordDto>

    /**
     * One class's register as a file. Host, co-host or admin only.
     * Streamed, because it is written straight to a shareable file.
     */
    @Streaming
    @GET("/api/v1/attendance/sessions/{id}/export")
    suspend fun exportAttendance(
        @Path("id") sessionId: String,
        @Query("format") format: String
    ): ResponseBody

    // ==================== CHAT MESSAGES ====================
    @GET("/api/v1/classrooms/sessions/{id}/messages/")
    suspend fun getChatMessages(@Path("id") sessionId: String): DocumentsResponse<ChatMessageDto>

    @POST("/api/v1/classrooms/sessions/{id}/messages/")
    suspend fun postChatMessage(
        @Path("id") sessionId: String,
        @Body message: ChatMessageDto
    ): DocumentResponse<ChatMessageDto>
}
