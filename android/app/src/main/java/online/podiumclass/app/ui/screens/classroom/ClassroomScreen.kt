package online.podiumclass.app.ui.screens.classroom

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import online.podiumclass.app.ui.theme.*

@Composable
fun ClassroomScreen(
    sessionId: String,
    onNavigateBack: () -> Unit,
    viewModel: ClassroomViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            val app = online.podiumclass.app.PodiumApplication()
            return ClassroomViewModel(app, sessionId) as T
        }
    })
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val session by viewModel.session.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLecturer by viewModel.isLecturer.collectAsState()
    val participants by viewModel.roomManager.participants.collectAsState()
    val isMicOn by viewModel.roomManager.isMicEnabled.collectAsState()
    val isCameraOn by viewModel.roomManager.isCameraEnabled.collectAsState()
    val isHandRaised by viewModel.roomManager.isHandRaised.collectAsState()
    val pinnedId by viewModel.pinnedParticipantId.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()

    // Attendance States
    var showHostAttendanceModal by remember { mutableStateOf(false) }
    val autoCheckerActive by viewModel.autoCheckerActive.collectAsState()
    val checksCompleted by viewModel.checksCompleted.collectAsState()
    val totalTargetChecks by viewModel.totalTargetChecks.collectAsState()
    val checkDuration by viewModel.checkDurationSeconds.collectAsState()
    val checkInterval by viewModel.checkIntervalMinutes.collectAsState()
    val countdownSeconds by viewModel.nextCheckCountdownSeconds.collectAsState()
    val isSavingSettings by viewModel.isSavingSettings.collectAsState()
    val summary by viewModel.attendanceSummary.collectAsState()
    val records by viewModel.attendanceRecords.collectAsState()

    val studentPrompt by viewModel.activeVerification.collectAsState()
    val isSubmittingVerification by viewModel.isSubmittingVerification.collectAsState()
    val verificationSuccess by viewModel.verificationSuccess.collectAsState()

    var showChatDrawer by remember { mutableStateOf(false) }

    fun enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val activity = context as? Activity
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            activity?.enterPictureInPictureMode(params)
        }
    }

    Scaffold(
        containerColor = BackgroundDark
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(color = IndigoPrimary)
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top App Bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(Color.Black)
                            .padding(horizontal = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onNavigateBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Column {
                                Text(
                                    text = session?.title ?: "Classroom",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${participants.size} in room • LIVE",
                                    color = EmeraldSuccess,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Host: Attendance Manager Button with Live Badge
                            if (isLecturer) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(EmeraldSuccess.copy(alpha = 0.15f))
                                        .border(1.dp, EmeraldSuccess.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                        .clickable {
                                            viewModel.loadAttendanceRoster()
                                            showHostAttendanceModal = true
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = "Attendance",
                                        tint = EmeraldSuccess,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (checksCompleted >= totalTargetChecks) "Done ($checksCompleted/$totalTargetChecks)"
                                        else if (autoCheckerActive) "Auto: $checksCompleted/$totalTargetChecks"
                                        else "Paused",
                                        color = EmeraldSuccess,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            // Picture-in-Picture Button
                            IconButton(onClick = { enterPiP() }) {
                                Icon(
                                    imageVector = Icons.Default.PictureInPictureAlt,
                                    contentDescription = "PiP",
                                    tint = TextSecondary
                                )
                            }
                        }
                    }

                    // Main Stage Canvas
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        val spotlightParticipant = participants.find { it.identity == pinnedId }
                            ?: participants.firstOrNull { it.hasVideo }
                            ?: participants.firstOrNull()

                        if (pinnedId != null && spotlightParticipant != null) {
                            // Spotlight Mode
                            StageVideoView(
                                participant = spotlightParticipant,
                                isSpotlight = true,
                                onPinClick = { viewModel.pinParticipant(spotlightParticipant.identity) },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // Dynamic Grid View
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(if (participants.size <= 1) 1 else 2),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(participants, key = { it.identity }) { p ->
                                    StageVideoView(
                                        participant = p,
                                        isSpotlight = false,
                                        onPinClick = { viewModel.pinParticipant(p.identity) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(if (participants.size <= 2) 260.dp else 180.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Floating Control Bar Space
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        ControlBar(
                            isMicOn = isMicOn,
                            isCameraOn = isCameraOn,
                            isHandRaised = isHandRaised,
                            onToggleMic = { scope.launch { viewModel.roomManager.toggleMic() } },
                            onToggleCamera = { scope.launch { viewModel.roomManager.toggleCamera() } },
                            onFlipCamera = { scope.launch { viewModel.roomManager.flipCamera() } },
                            onToggleHand = { scope.launch { viewModel.roomManager.toggleHandRaise("Mobile User") } },
                            onSendReaction = { emoji -> scope.launch { viewModel.roomManager.sendReaction(emoji) } },
                            onToggleChat = { showChatDrawer = !showChatDrawer },
                            onLeaveClick = onNavigateBack
                        )
                    }
                }
            }

            // In-Class Chat Drawer Overlay
            AnimatedVisibility(
                visible = showChatDrawer,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                ChatDrawer(
                    messages = chatMessages,
                    onSendMessage = { text -> viewModel.sendChatMessage(text) },
                    onClose = { showChatDrawer = false }
                )
            }

            // Student Attendance Verification Dialog (Teleported top modal)
            studentPrompt?.let { prompt ->
                StudentVerificationDialog(
                    verificationNumber = prompt.verificationNumber ?: 1,
                    timeLimitSeconds = prompt.timeLimitSeconds ?: 30,
                    isSubmitting = isSubmittingVerification,
                    isSuccess = verificationSuccess,
                    onConfirmPresent = { viewModel.submitStudentVerification() }
                )
            }

            // Host Attendance Manager Modal
            if (showHostAttendanceModal && isLecturer) {
                HostAttendanceManagerModal(
                    autoCheckerActive = autoCheckerActive,
                    checksCompleted = checksCompleted,
                    totalTargetChecks = totalTargetChecks,
                    checkDurationSeconds = checkDuration,
                    checkIntervalMinutes = checkInterval,
                    nextCheckCountdownSeconds = countdownSeconds,
                    attendanceSummary = summary,
                    attendanceRecords = records,
                    isSavingSettings = isSavingSettings,
                    onDismiss = { showHostAttendanceModal = false },
                    onToggleAutoChecker = { viewModel.toggleAutoChecker() },
                    onUpdateSettings = { target, duration, interval ->
                        viewModel.updateSettings(target, duration, interval)
                    },
                    onTriggerInstantCheck = { duration ->
                        viewModel.triggerAttendanceCheck(duration)
                    }
                )
            }
        }
    }
}
