package online.podiumclass.app.ui

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import online.podiumclass.app.data.api.ApiClient
import online.podiumclass.app.data.api.models.DevLoginRequest
import online.podiumclass.app.data.api.models.SessionDto
import online.podiumclass.app.data.storage.TokenStorage
import online.podiumclass.app.ui.screens.auth.LoginScreen
import online.podiumclass.app.ui.screens.classroom.ClassroomScreen
import online.podiumclass.app.ui.screens.dashboard.DashboardScreen
import online.podiumclass.app.ui.theme.BackgroundDark
import online.podiumclass.app.ui.theme.PodiumClassTheme

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
                    val apiUrl by tokenStorage.apiUrlFlow.collectAsState(initial = "http://10.0.2.2:8000")

                    var sessions by remember { mutableStateOf<List<SessionDto>>(emptyList()) }
                    var isLoadingSessions by remember { mutableStateOf(false) }
                    var isAuthLoading by remember { mutableStateOf(false) }

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

                    val startDestination = if (!token.isNullOrBlank()) "dashboard" else "login"

                    NavHost(
                        navController = navController,
                        startDestination = startDestination
                    ) {
                        composable("login") {
                            isInClassroom = false
                            LoginScreen(
                                currentApiUrl = apiUrl,
                                isLoading = isAuthLoading,
                                onDevLogin = { selectedRole ->
                                    scope.launch {
                                        isAuthLoading = true
                                        try {
                                            val api = ApiClient.create(tokenStorage, apiUrl)
                                            val res = api.devLogin(DevLoginRequest(dev_login = true, role = selectedRole))
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
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        } finally {
                                            isAuthLoading = false
                                        }
                                    }
                                },
                                onSaveApiUrl = { newUrl ->
                                    scope.launch {
                                        tokenStorage.setApiUrl(newUrl)
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
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Automatically enter Picture-in-Picture when leaving active classroom
        if (isInClassroom && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }
}
