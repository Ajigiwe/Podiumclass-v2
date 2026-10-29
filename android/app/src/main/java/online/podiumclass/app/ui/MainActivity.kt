package online.podiumclass.app.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import online.podiumclass.app.BuildConfig
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import online.podiumclass.app.data.api.ApiClient
import online.podiumclass.app.data.api.models.GoogleLoginRequest
import online.podiumclass.app.data.api.models.RecordingDto
import online.podiumclass.app.data.api.models.SessionDto
import online.podiumclass.app.data.api.models.StudentAttendanceRecordDto
import online.podiumclass.app.data.api.models.UpdateProfileRequest
import online.podiumclass.app.data.api.models.UserDto
import online.podiumclass.app.data.storage.TokenStorage
import online.podiumclass.app.ui.screens.about.AboutScreen
import online.podiumclass.app.ui.screens.admin.AdminScreen
import online.podiumclass.app.ui.screens.attendance.AttendanceScreen
import online.podiumclass.app.ui.screens.attendance.HostAttendanceScreen
import online.podiumclass.app.ui.screens.auth.LoginScreen
import online.podiumclass.app.ui.screens.auth.SplashScreen
import online.podiumclass.app.ui.screens.classroom.ClassroomScreen
import online.podiumclass.app.ui.screens.dashboard.DashboardScreen
import online.podiumclass.app.ui.screens.onboarding.OnboardingScreen
import online.podiumclass.app.ui.screens.profile.ProfileScreen
import online.podiumclass.app.ui.theme.AppTheme
import online.podiumclass.app.ui.theme.PodiumClassTheme
import retrofit2.HttpException

// Session ids are UUIDs, and an invite link carries one inside it, so a single
// pattern recognises both a pasted link and a bare id. A meeting code
// ("POD-1A2B3C") does not match and is looked up on the server instead.
private val SESSION_ID_IN_TEXT =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

class MainActivity : ComponentActivity() {

    private lateinit var tokenStorage: TokenStorage
    private var isInClassroom = false

    /**
     * Whether the app is currently shrink-wrapped into picture-in-picture.
     *
     * Compose cannot ask the Activity for this, so it is mirrored here; the
     * classroom reads it to swap its full layout for the compact one, instead
     * of squeezing the whole screen (and losing the video) into the window.
     */
    private val pipMode = mutableStateOf(false)

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode.value = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStorage = TokenStorage(applicationContext)

        setContent {
            val themeMode by tokenStorage.themeModeFlow.collectAsState(initial = "system")
            PodiumClassTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppTheme.colors.background
                ) {
                    // Class and recording notifications are worth asking for
                    // once, on the version that requires an answer. A refusal
                    // is respected: every post is guarded.
                    val notifyPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { /* either answer is fine */ }
                    val permissionContext = LocalContext.current
                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                permissionContext,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    val navController = rememberNavController()
                    val scope = rememberCoroutineScope()

                    val token by tokenStorage.tokenFlow.collectAsState(initial = null)
                    val role by tokenStorage.roleFlow.collectAsState(initial = "student")
                    val userName by tokenStorage.nameFlow.collectAsState(initial = "User")
                    val userId by tokenStorage.userIdFlow.collectAsState(initial = "")
                    val userEmail by tokenStorage.emailFlow.collectAsState(initial = "")
                    // Falls back to the build's server (production by default)
                    // for the single frame before DataStore emits.
                    val apiUrl by tokenStorage.apiUrlFlow.collectAsState(initial = BuildConfig.DEFAULT_API_URL)
                    // Only used for a signed-out first run: an existing session
                    // goes straight to the dashboard, never through the tour.
                    val hasOnboarded by tokenStorage.onboardedFlow.collectAsState(initial = true)

                    // The class list, scoped by the server: a lecturer sees the
                    // classes being taught, a student only the ones they have
                    // joined. Attendance history comes from the attendance API
                    // below, which is where a student's own records live (and
                    // where they survive a class being deleted).
                    var sessions by remember { mutableStateOf<List<SessionDto>>(emptyList()) }
                    var myAttendance by remember { mutableStateOf<List<StudentAttendanceRecordDto>>(emptyList()) }
                    var currentUserProfile by remember { mutableStateOf<UserDto?>(null) }
                    var isLoadingSessions by remember { mutableStateOf(false) }
                    var isLoadingAttendance by remember { mutableStateOf(false) }
                    var isUploadingPhoto by remember { mutableStateOf(false) }
                    // The host's recordings for the Records area of the
                    // dashboard, and the class just created, which is shown
                    // with its code instead of being opened automatically.
                    var hostRecordings by remember { mutableStateOf<List<RecordingDto>>(emptyList()) }
                    var isLoadingRecords by remember { mutableStateOf(false) }
                    var recordsError by remember { mutableStateOf<String?>(null) }
                    var createdSession by remember { mutableStateOf<SessionDto?>(null) }

                    val isAdmin = role == "admin"
                    // Hosting is whoever the server says may host: lecturers and
                    // admins by role, plus any account an admin has granted
                    // class creation to (canCreateClasses on their profile).
                    val canHost = role == "lecturer" || role == "admin" ||
                        currentUserProfile?.canCreateClasses == true

                    var isAuthLoading by remember { mutableStateOf(false) }
                    var authError by remember { mutableStateOf<String?>(null) }
                    var showWebOAuthDialog by remember { mutableStateOf(false) }

                    fun loadSessions() {
                        scope.launch {
                            isLoadingSessions = true
                            try {
                                val api = ApiClient.create(tokenStorage, apiUrl)
                                val res = api.getSessions()
                                sessions = res.documents
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                isLoadingSessions = false
                            }
                        }
                    }

                    fun loadMyAttendance() {
                        scope.launch {
                            isLoadingAttendance = true
                            try {
                                val api = ApiClient.create(tokenStorage, apiUrl)
                                myAttendance = api.getMyAttendance().documents
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                isLoadingAttendance = false
                            }
                        }
                    }

                    /**
                     * The Records area: every recording in the classes this user
                     * runs, in one request. A student gets an empty list back,
                     * so this is safe to call for anyone.
                     */
                    fun loadHostRecordings() {
                        scope.launch {
                            isLoadingRecords = true
                            recordsError = null
                            try {
                                val api = ApiClient.create(tokenStorage, apiUrl)
                                hostRecordings = api.getMyRecordings().documents
                            } catch (e: Exception) {
                                // The parser's own words ("Unexpected JSON token at offset
                                // 328") tell the host nothing they can act on; the
                                // retry affordance does.
                                recordsError = "Could not load your recordings. Tap refresh to try again."
                            } finally {
                                isLoadingRecords = false
                            }
                        }
                    }

                    fun loadUserProfile() {
                        scope.launch {
                            try {
                                val api = ApiClient.create(tokenStorage, apiUrl)
                                val u = api.getCurrentUser()
                                currentUserProfile = u.document
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }

                    /**
                     * Open a class from a meeting code, an invite link or a raw
                     * session id.
                     *
                     * The code is resolved by the server: a student's own list
                     * only holds the classes they are already in, so scanning it
                     * locally could never find the class they were invited to.
                     */
                    fun openClassByInput(raw: String) {
                        val value = raw.trim()
                        if (value.isBlank()) return
                        val directId = SESSION_ID_IN_TEXT.find(value)?.value
                        if (directId != null) {
                            navController.navigate("classroom/$directId")
                            return
                        }
                        scope.launch {
                            try {
                                val api = ApiClient.create(tokenStorage, apiUrl)
                                val found = api.getSessions(meetingCode = value).documents.firstOrNull()
                                if (found != null) {
                                    navController.navigate("classroom/${found.id}")
                                } else {
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "No class found with the code \"$value\".",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    "Could not look up that class: ${e.localizedMessage ?: "network error"}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    LaunchedEffect(token) {
                        if (!token.isNullOrBlank()) {
                            loadSessions()
                            loadMyAttendance()
                            loadUserProfile()
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "splash",
                        // Navigation is a small, consistent language: forward
                        // motion drifts in from the right and fades, back
                        // returns with a soft scale. Nothing slides the full
                        // screen width, which reads as slow on a phone.
                        enterTransition = {
                            fadeIn(tween(260)) + slideInHorizontally(
                                tween(340, easing = FastOutSlowInEasing)
                            ) { it / 10 }
                        },
                        exitTransition = {
                            fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 0.97f)
                        },
                        popEnterTransition = {
                            fadeIn(tween(240)) + scaleIn(tween(280), initialScale = 0.98f)
                        },
                        popExitTransition = {
                            fadeOut(tween(180)) + slideOutHorizontally(
                                tween(300, easing = FastOutSlowInEasing)
                            ) { it / 10 }
                        }
                    ) {
                        composable("splash") {
                            isInClassroom = false
                            SplashScreen(
                                onCheckAuth = {
                                    val currentToken = tokenStorage.getToken()
                                    !currentToken.isNullOrBlank()
                                },
                                onNavigate = { hasToken ->
                                    val destination = when {
                                        hasToken -> "dashboard"
                                        !hasOnboarded -> "onboarding"
                                        else -> "login"
                                    }
                                    navController.navigate(destination) {
                                        popUpTo("splash") { inclusive = true }
                                    }
                                }
                            )
                        }

                        // First-run tour. Dismissing it (Skip or Get started)
                        // records that it was seen, so it is a one-time thing
                        // even if the user then closes the app on the login page.
                        composable("onboarding") {
                            isInClassroom = false
                            OnboardingScreen(
                                onFinished = {
                                    scope.launch {
                                        tokenStorage.markOnboarded()
                                        val hasToken = !tokenStorage.getToken().isNullOrBlank()
                                        navController.navigate(if (hasToken) "dashboard" else "login") {
                                            popUpTo("onboarding") { inclusive = true }
                                        }
                                    }
                                }
                            )
                        }

                        composable("login") {
                            isInClassroom = false

                            suspend fun executeLogin(req: GoogleLoginRequest) {
                                isAuthLoading = true
                                authError = null
                                try {
                                    val api = ApiClient.create(tokenStorage, apiUrl)
                                    val res = api.googleLogin(req)
                                    val doc = res.document
                                    tokenStorage.saveAuth(
                                        token = doc.token,
                                        userId = doc.user.id,
                                        name = doc.user.fullName ?: "User",
                                        role = doc.user.role,
                                        email = doc.user.email
                                    )
                                    navController.navigate("dashboard") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                } catch (e: HttpException) {
                                    val errorBody = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                                    val parsedMsg = if (!errorBody.isNullOrBlank() && errorBody.contains("\"message\"")) {
                                        """"message"\s*:\s*"([^"]+)"""".toRegex().find(errorBody)?.groupValues?.get(1)
                                    } else null

                                    authError = when {
                                        !parsedMsg.isNullOrBlank() -> parsedMsg
                                        e.code() == 400 && errorBody?.contains("DisallowedHost") == true ->
                                            "Server rejected Host header ($apiUrl). ALLOWED_HOSTS needs updating."
                                        e.code() == 400 -> "Sign-in request rejected (HTTP 400). Please check server configuration."
                                        e.code() == 401 -> "Authentication failed. Please verify your Google account credentials."
                                        else -> "Server error (${e.code()}). Please try again."
                                    }
                                } catch (e: Exception) {
                                    val rawMsg = e.localizedMessage ?: e.message ?: ""
                                    authError = if (rawMsg.contains("10.0.2.2") || rawMsg.contains("failed to connect") || rawMsg.contains("timeout", ignoreCase = true)) {
                                        "Cannot reach the Podium server at $apiUrl. It may still be starting up — give it a moment and try again, or check your internet connection."
                                    } else {
                                        rawMsg
                                    }
                                } finally {
                                    isAuthLoading = false
                                }
                            }

                            LoginScreen(
                                isLoading = isAuthLoading,
                                errorMessage = authError,
                                showWebOAuth = showWebOAuthDialog,
                                onWebOAuthDismiss = { showWebOAuthDialog = false },
                                onGoogleLogin = {
                                    scope.launch {
                                        try {
                                            isAuthLoading = true
                                            authError = null
                                            val googleToken = performGoogleSignIn(this@MainActivity)
                                            if (!googleToken.isNullOrBlank()) {
                                                executeLogin(GoogleLoginRequest(credential = googleToken))
                                            } else {
                                                isAuthLoading = false
                                                showWebOAuthDialog = true
                                            }
                                        } catch (e: GetCredentialCancellationException) {
                                            isAuthLoading = false
                                        } catch (e: Exception) {
                                            isAuthLoading = false
                                            authError = null
                                            showWebOAuthDialog = true
                                        }
                                    }
                                },
                                onGoogleTokenReceived = { accessToken ->
                                    scope.launch {
                                        executeLogin(GoogleLoginRequest(access_token = accessToken))
                                    }
                                }
                            )
                        }

                        composable("dashboard") {
                            isInClassroom = false
                            // Records belong to whoever runs a class, so only a
                            // host's dashboard asks for them. One request per
                            // visit: a recording added mid-term shows up without
                            // a restart.
                            LaunchedEffect(canHost) {
                                if (canHost) loadHostRecordings()
                            }
                            DashboardScreen(
                                sessions = sessions,
                                isLoading = isLoadingSessions,
                                isLecturer = canHost,
                                isAdmin = isAdmin,
                                onNavigateAdmin = { navController.navigate("admin") },
                                userName = userName,
                                userId = userId,
                                themeMode = themeMode,
                                onToggleTheme = {
                                    scope.launch {
                                        val nextMode = if (themeMode.equals("light", true)) "dark" else "light"
                                        tokenStorage.setThemeMode(nextMode)
                                    }
                                },
                                onJoinClass = { value -> openClassByInput(value) },
                                onCreateClass = { request ->
                                    scope.launch {
                                        try {
                                            val api = ApiClient.create(tokenStorage, apiUrl)
                                            val res = api.createSession(request)
                                            loadSessions()
                                            // Stay on the dashboard. A class is
                                            // created closed -- students wait in
                                            // the lobby -- so what the host needs
                                            // next is the code, not the room.
                                            createdSession = res.document
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            val errorMsg = e.localizedMessage ?: "Failed to create class"
                                            android.widget.Toast.makeText(
                                                this@MainActivity,
                                                "Failed to create class: $errorMsg",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                },
                                onNavigateAttendance = {
                                    navController.navigate("attendance")
                                },
                                onNavigateProfile = {
                                    navController.navigate("profile")
                                },
                                onLogout = {
                                    scope.launch {
                                        tokenStorage.clear()
                                        navController.navigate("login") {
                                            popUpTo("dashboard") { inclusive = true }
                                        }
                                    }
                                },
                                onRefresh = {
                                    loadSessions()
                                    loadHostRecordings()
                                },
                                recordings = hostRecordings,
                                isLoadingRecords = isLoadingRecords,
                                recordsError = recordsError,
                                onRefreshRecords = { loadHostRecordings() },
                                createdSession = createdSession,
                                onDismissCreatedSession = { createdSession = null },
                                onStartCreatedSession = { createdId ->
                                    createdSession = null
                                    navController.navigate("classroom/$createdId")
                                },
                                apiUrl = apiUrl,
                                onEndClass = { sessionId ->
                                    scope.launch {
                                        try {
                                            val api = ApiClient.create(tokenStorage, apiUrl)
                                            api.endSession(sessionId)
                                            loadSessions()
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            android.widget.Toast.makeText(
                                                this@MainActivity,
                                                "Failed to end class: ${e.localizedMessage ?: "unknown error"}",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                },
                                onDeleteClass = { sessionId ->
                                    scope.launch {
                                        try {
                                            val api = ApiClient.create(tokenStorage, apiUrl)
                                            api.deleteSession(sessionId)
                                            loadSessions()
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            android.widget.Toast.makeText(
                                                this@MainActivity,
                                                "Failed to delete class: ${e.localizedMessage ?: "unknown error"}",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            )
                        }

                        // The control panel: same surfaces as the web admin
                        // view, opened from the dashboard's Admin tab.
                        composable("admin") {
                            isInClassroom = false
                            AdminScreen(
                                adminName = userName,
                                apiUrl = apiUrl,
                                sessions = sessions,
                                isLoadingSessions = isLoadingSessions,
                                onRefreshSessions = { loadSessions() },
                                onOpenSession = { sessionId ->
                                    navController.navigate("classroom/$sessionId")
                                },
                                onNavigateHome = {
                                    navController.navigate("dashboard") {
                                        popUpTo("dashboard") { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable("attendance") {
                            isInClassroom = false
                            // Two different pages behind one tab. A host gets the
                            // registers for the classes they run; a student gets
                            // their own record. Neither is a way back into a
                            // finished room.
                            LaunchedEffect(canHost) {
                                if (!canHost) loadMyAttendance()
                            }
                            if (canHost) {
                                HostAttendanceScreen(
                                    apiUrl = apiUrl,
                                    userId = currentUserProfile?.id ?: userId.orEmpty(),
                                    isAdmin = isAdmin,
                                    onNavigateHome = {
                                        navController.navigate("dashboard") {
                                            popUpTo("dashboard") { inclusive = true }
                                        }
                                    },
                                    onNavigateProfile = {
                                        navController.navigate("profile") {
                                            popUpTo("dashboard")
                                        }
                                    }
                                )
                            } else {
                                AttendanceScreen(
                                    records = myAttendance,
                                    isLoading = isLoadingAttendance,
                                    onRefresh = {
                                        loadSessions()
                                        loadMyAttendance()
                                    },
                                    onNavigateHome = {
                                        navController.navigate("dashboard") {
                                            popUpTo("dashboard") { inclusive = true }
                                        }
                                    },
                                    onNavigateProfile = {
                                        navController.navigate("profile") {
                                            popUpTo("dashboard")
                                        }
                                    }
                                )
                            }
                        }

                        composable("profile") {
                            isInClassroom = false
                            ProfileScreen(
                                user = currentUserProfile ?: UserDto(
                                    id = userId ?: "",
                                    email = userEmail ?: "",
                                    fullName = userName,
                                    role = role
                                ),
                                themeMode = themeMode,
                                onSaveProfile = { req ->
                                    scope.launch {
                                        try {
                                            val api = ApiClient.create(tokenStorage, apiUrl)
                                            val updated = api.updateProfile(req).document
                                            currentUserProfile = updated
                                            if (!updated.fullName.isNullOrBlank()) {
                                                tokenStorage.setName(updated.fullName)
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                },
                                onThemeChange = { mode ->
                                    scope.launch {
                                        tokenStorage.setThemeMode(mode)
                                    }
                                },
                                onLogout = {
                                    scope.launch {
                                        tokenStorage.clear()
                                        navController.navigate("login") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                },
                                onNavigateHome = {
                                    navController.navigate("dashboard") {
                                        popUpTo("dashboard") { inclusive = true }
                                    }
                                },
                                onNavigateAttendance = {
                                    navController.navigate("attendance") {
                                        popUpTo("dashboard")
                                    }
                                },
                                onOpenAbout = {
                                    navController.navigate("about") {
                                        popUpTo("dashboard")
                                    }
                                },
                                isUploadingPhoto = isUploadingPhoto,
                                onUploadPhoto = { bytes, fileName, mime ->
                                    scope.launch {
                                        isUploadingPhoto = true
                                        try {
                                            val api = ApiClient.create(tokenStorage, apiUrl)
                                            val part = MultipartBody.Part.createFormData(
                                                "photo",
                                                fileName,
                                                bytes.toRequestBody(mime.toMediaType())
                                            )
                                            // The server stores the image and answers
                                            // with the new versioned photoUrl, so the
                                            // picture is on the account, not the phone.
                                            currentUserProfile = api.uploadProfilePhoto(part).document
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            android.widget.Toast.makeText(
                                                this@MainActivity,
                                                "Profile picture upload failed: ${e.localizedMessage ?: "unknown error"}",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        } finally {
                                            isUploadingPhoto = false
                                        }
                                    }
                                }
                            )
                        }

                        composable("about") {
                            isInClassroom = false
                            AboutScreen(
                                // Read from the build, so the number on screen is
                                // always the version actually installed.
                                versionName = BuildConfig.VERSION_NAME,
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable(
                            route = "classroom/{sessionId}",
                            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                            // Entering a room is the one destination that should
                            // feel like stepping through a door: it grows from
                            // just under full size rather than sliding in.
                            enterTransition = {
                                fadeIn(tween(300)) + scaleIn(tween(400, easing = FastOutSlowInEasing), initialScale = 0.94f)
                            },
                            popExitTransition = {
                                fadeOut(tween(220)) + scaleOut(tween(280), targetScale = 0.96f)
                            }
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
                            isInClassroom = true
                            ClassroomScreen(
                                sessionId = sessionId,
                                isInPip = pipMode.value,
                                onNavigateBack = {
                                    isInClassroom = false
                                    // Leaving a class changes both lists: the
                                    // class may be new to this student, and
                                    // their attendance record is now there.
                                    loadSessions()
                                    loadMyAttendance()
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun performGoogleSignIn(context: Context): String? {
        val credentialManager = CredentialManager.create(context)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId("148808386481-qp65l50jmuabfte437aafmh85esth944.apps.googleusercontent.com")
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = credentialManager.getCredential(context, request)
        val credential = result.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            return googleIdTokenCredential.idToken
        }
        return null
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isInClassroom && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
