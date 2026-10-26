package online.podiumclass.app.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import kotlinx.coroutines.launch
import online.podiumclass.app.data.api.ApiClient
import online.podiumclass.app.data.api.models.GoogleLoginRequest
import online.podiumclass.app.data.api.models.SessionDto
import online.podiumclass.app.data.storage.TokenStorage
import online.podiumclass.app.ui.screens.auth.LoginScreen
import online.podiumclass.app.ui.screens.auth.SplashScreen
import online.podiumclass.app.ui.screens.classroom.ClassroomScreen
import online.podiumclass.app.ui.screens.dashboard.DashboardScreen
import online.podiumclass.app.ui.theme.BackgroundDark
import online.podiumclass.app.ui.theme.PodiumClassTheme
import retrofit2.HttpException

class MainActivity : ComponentActivity() {

    private lateinit var tokenStorage: TokenStorage
    private var isInClassroom = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStorage = TokenStorage(applicationContext)

        setContent {
            PodiumClassTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BackgroundDark
                ) {
                    val navController = rememberNavController()
                    val scope = rememberCoroutineScope()

                    val token by tokenStorage.tokenFlow.collectAsState(initial = null)
                    val role by tokenStorage.roleFlow.collectAsState(initial = "student")
                    val userName by tokenStorage.nameFlow.collectAsState(initial = "User")
                    val apiUrl by tokenStorage.apiUrlFlow.collectAsState(initial = "http://10.0.2.2:8000")

                    var sessions by remember { mutableStateOf<List<SessionDto>>(emptyList()) }
                    var isLoadingSessions by remember { mutableStateOf(false) }
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

                    LaunchedEffect(token) {
                        if (!token.isNullOrBlank()) {
                            loadSessions()
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "splash"
                    ) {
                        composable("splash") {
                            isInClassroom = false
                            SplashScreen(
                                onCheckAuth = {
                                    val currentToken = tokenStorage.getToken()
                                    !currentToken.isNullOrBlank()
                                },
                                onNavigate = { hasToken ->
                                    val destination = if (hasToken) "dashboard" else "login"
                                    navController.navigate(destination) {
                                        popUpTo("splash") { inclusive = true }
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
                                        "Cannot reach backend server at $apiUrl. Physical phones cannot use 10.0.2.2 — tap ⚙️ at top right to enter your PC's IP address (e.g. http://192.168.x.x:8000)."
                                    } else {
                                        rawMsg
                                    }
                                } finally {
                                    isAuthLoading = false
                                }
                            }

                            LoginScreen(
                                currentApiUrl = apiUrl,
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
                                },
                                onSaveApiUrl = { newUrl ->
                                    scope.launch {
                                        tokenStorage.setApiUrl(newUrl)
                                        authError = null
                                    }
                                }
                            )
                        }

                        composable("dashboard") {
                            isInClassroom = false
                            DashboardScreen(
                                sessions = sessions,
                                isLoading = isLoadingSessions,
                                isLecturer = role == "lecturer" || role == "admin",
                                userName = userName,
                                onJoinClass = { sessionId ->
                                    navController.navigate("classroom/$sessionId")
                                },
                                onCreateClass = { request ->
                                    scope.launch {
                                        try {
                                            val api = ApiClient.create(tokenStorage, apiUrl)
                                            val res = api.createSession(request)
                                            loadSessions()
                                            navController.navigate("classroom/${res.document.id}")
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                },
                                onLogout = {
                                    scope.launch {
                                        tokenStorage.clear()
                                        navController.navigate("login") {
                                            popUpTo("dashboard") { inclusive = true }
                                        }
                                    }
                                },
                                onRefresh = { loadSessions() }
                            )
                        }

                        composable(
                            route = "classroom/{sessionId}",
                            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
                            isInClassroom = true
                            ClassroomScreen(
                                sessionId = sessionId,
                                onNavigateBack = {
                                    isInClassroom = false
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
