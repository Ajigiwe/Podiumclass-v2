package online.podiumclass.app.ui.screens.classroom

import android.app.Activity
import android.app.Application
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Rational
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import online.podiumclass.app.data.livekit.ParticipantTileState
import online.podiumclass.app.ui.theme.*

/**
 * The lobby a student waits in until the host opens the class.
 *
 * It is deliberately not a dialog: a dialog says "something went wrong", and
 * nothing has -- the class simply cannot start without its host. The spinner is
 * honest about it being a wait rather than a dead end.
 */
@Composable
private fun WaitingForHostLobby(
    title: String,
    notice: String?,
    onLeave: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(28.dp)
    ) {
        CircularProgressIndicator(color = IndigoPrimary, modifier = Modifier.size(34.dp))
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "WAITING FOR THE HOST",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = notice
                ?: "This class opens as soon as its host joins. Stay here and you will be let in automatically.",
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(22.dp))
        TextButton(onClick = onLeave) {
            Text("Back to classes", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** mm:ss, or h:mm:ss past an hour -- the same shape the web REC badge uses. */
private fun formatRecTimer(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
fun ClassroomScreen(
    sessionId: String,
    // True while the activity is shrink-wrapped into picture-in-picture. The
    // screen then renders the compact classroom instead of the full one.
    isInPip: Boolean = false,
    onNavigateBack: () -> Unit,
    viewModel: ClassroomViewModel = run {
        // The *real* Application. A hand-built PodiumApplication() is not
        // attached to the system, so LiveKit.create(context), the AudioManager
        // lookup and DataStore all ran without a usable context: the room never
        // connected and the camera and microphone stayed dead behind a caught
        // exception. LocalContext gives the running application.
        val app = LocalContext.current.applicationContext as Application
        viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return ClassroomViewModel(app, sessionId) as T
            }
        })
    }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Runtime permission dialog state
    var showPermissionRationale by remember { mutableStateOf(false) }
    var showPermissionSettings by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[android.Manifest.permission.RECORD_AUDIO] == true
        val camGranted = permissions[android.Manifest.permission.CAMERA] == true
        // Handed to the view model, which waits for the room before switching
        // anything on -- turning the camera on before the connection existed
        // was one of the ways it failed silently.
        viewModel.onSystemMediaPermissions(micGranted, camGranted)
        if (!micGranted || !camGranted) {
            val activity = context as? Activity
            val micRationale = !micGranted && activity
                ?.shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO) == true
            val camRationale = !camGranted && activity
                ?.shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA) == true
            if (micRationale || camRationale) {
                showPermissionRationale = true
            } else {
                showPermissionSettings = true
            }
        }
    }

    LaunchedEffect(Unit) {
        // Only request if permissions not yet granted
        val micPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
        val camPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
        if (micPermission != PackageManager.PERMISSION_GRANTED || camPermission != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.CAMERA
                )
            )
        }
    }

    val session by viewModel.session.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isModerator by viewModel.isModerator.collectAsState()
    val participants by viewModel.roomManager.participants.collectAsState()
    val isMicOn by viewModel.roomManager.isMicEnabled.collectAsState()
    val isCameraOn by viewModel.roomManager.isCameraEnabled.collectAsState()
    val isHandRaised by viewModel.roomManager.isHandRaised.collectAsState()
    val pinnedId by viewModel.pinnedParticipantId.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val currentUserName by viewModel.currentUserName.collectAsState()

    // Moderation & Permissions States
    val isMutedAll by viewModel.isMutedAll.collectAsState()
    val autoApproveMic by viewModel.autoApproveMic.collectAsState()
    val permissionPolicy by viewModel.permissionPolicy.collectAsState()
    val pendingRequests by viewModel.pendingRequests.collectAsState()
    val coHosts by viewModel.coHosts.collectAsState()
    val requestedKinds by viewModel.requestedKinds.collectAsState()
    var showModerationModal by remember { mutableStateOf(false) }
    var showParticipantsSheet by remember { mutableStateOf(false) }

    // Live System Banner Message
    var activeBannerMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        viewModel.systemNotification.collect { msg ->
            activeBannerMessage = msg
            delay(3500)
            if (activeBannerMessage == msg) {
                activeBannerMessage = null
            }
        }
    }

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

    // Shared materials: the same server-backed shelf the web classroom shows.
    val materials by viewModel.materials.collectAsState()
    val materialsError by viewModel.materialsError.collectAsState()
    val isSharingMaterial by viewModel.isSharingMaterial.collectAsState()
    var showMaterialsSheet by remember { mutableStateOf(false) }

    val studentPrompt by viewModel.activeVerification.collectAsState()
    val isSubmittingVerification by viewModel.isSubmittingVerification.collectAsState()
    val verificationSuccess by viewModel.verificationSuccess.collectAsState()

    val roomClosed by viewModel.roomClosed.collectAsState()
    val joinBlocked by viewModel.joinBlocked.collectAsState()
    val waitingForHost by viewModel.waitingForHost.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isRecordingStarting by viewModel.isRecordingStarting.collectAsState()
    val recordingSeconds by viewModel.recordingSeconds.collectAsState()

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

    // Picture-in-picture is a miniature of the classroom, not a clipped copy of
    // the full screen. Shrinking the whole layout left only the control bar in
    // the window -- the header took the top and the stage was pushed out of
    // view -- so the window now shows the one thing worth watching.
    if (isInPip) {
        PipClassroomView(
            sessionTitle = session?.title ?: "Classroom",
            participants = participants,
            pinnedId = pinnedId,
            initRenderer = { viewModel.initVideoRenderer(it) }
        )
        return
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
            } else if (waitingForHost) {
                // The class has not opened: its host is not in. Wait here, and
                // walk in the moment they arrive -- the room is being polled.
                WaitingForHostLobby(
                    title = session?.title ?: "Classroom",
                    notice = joinBlocked,
                    onLeave = {
                        viewModel.stopWaitingForHost()
                        viewModel.joinBlocked.value = null
                        onNavigateBack()
                    }
                )
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            // The bar carries back, roster, materials, MOD, REC
                            // and PiP; on a 360dp phone that leaves the title
                            // almost nothing. Let it take what is left and
                            // ellipsise rather than pushing the bar wider.
                            modifier = Modifier.weight(1f)
                        ) {
                            IconButton(onClick = onNavigateBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session?.title ?: "Classroom",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${participants.size} in room • LIVE",
                                    color = EmeraldSuccess,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // The roster. The stage draws a capped number of
                            // tiles, so a large class is actually browsed here.
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable { showParticipantsSheet = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Groups,
                                    contentDescription = "Participants (${participants.size})",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))

                            // The class shelf. Everyone can read it; hosts and
                            // co-hosts can add files or links. Small hit target
                            // because the top bar is already busy.
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable {
                                        viewModel.loadMaterials()
                                        showMaterialsSheet = true
                                    }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = "Class materials",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))

                            // Moderation Shield Button (for Host or Co-Host)
                            if (isModerator) {
                                Box {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (pendingRequests.isNotEmpty()) AmberWarning.copy(alpha = 0.2f)
                                                else IndigoPrimary.copy(alpha = 0.15f)
                                            )
                                            .border(
                                                1.dp,
                                                if (pendingRequests.isNotEmpty()) AmberWarning.copy(alpha = 0.4f)
                                                else IndigoPrimary.copy(alpha = 0.3f),
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable { showModerationModal = true }
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Shield,
                                            contentDescription = "Moderation",
                                            tint = if (pendingRequests.isNotEmpty()) AmberWarning else IndigoPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (pendingRequests.isNotEmpty()) "MOD (${pendingRequests.size})" else "MOD",
                                            color = if (pendingRequests.isNotEmpty()) AmberWarning else IndigoPrimary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                            }

                            // Host: Attendance Manager Button with Live Badge.
                            // Gated on moderation rights, not on the staff role:
                            // somebody an admin granted class creation hosts
                            // their own class and needs its roster too.
                            if (isModerator) {
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
                                Spacer(modifier = Modifier.width(6.dp))
                            }

                            // Host/co-host: record the session. The MP4 is
                            // written on the server, so this button only asks
                            // for it -- which is what lets a phone start one.
                            //
                            // While the worker's pipeline is coming up the
                            // control says "Starting..." and ignores taps: a
                            // stop offered over a take that has not begun is
                            // the one way to lose a recording.
                            if (isModerator) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isRecording) RedUrgent.copy(alpha = 0.2f)
                                            else Color.White.copy(alpha = 0.08f)
                                        )
                                        .border(
                                            1.dp,
                                            if (isRecording) RedUrgent.copy(alpha = 0.45f) else CardBorder,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable(enabled = !isRecordingStarting) {
                                            if (isRecording) viewModel.stopRecording()
                                            else viewModel.startRecording()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    if (isRecordingStarting) {
                                        CircularProgressIndicator(
                                            color = TextSecondary,
                                            strokeWidth = 1.5.dp,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (isRecording) Icons.Default.Stop
                                            else Icons.Default.FiberManualRecord,
                                            contentDescription = if (isRecording) "Stop recording" else "Record",
                                            tint = if (isRecording) RedUrgent else TextSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = when {
                                            isRecordingStarting -> "Starting\u2026"
                                            isRecording -> "REC ${formatRecTimer(recordingSeconds)}"
                                            else -> "Record"
                                        },
                                        color = if (isRecording) RedUrgent else TextSecondary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                            } else if (isRecording && !isRecordingStarting) {
                                // A student should know the class is being
                                // recorded -- the badge is the only sign of it.
                                // The pipeline has to be capturing for that to
                                // be true; a starting one is not a recording yet.
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(RedUrgent.copy(alpha = 0.2f))
                                        .border(1.dp, RedUrgent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(RedUrgent)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "REC",
                                        color = RedUrgent,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
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
                                initRenderer = { viewModel.initVideoRenderer(it) },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // Dynamic grid.
                            //
                            // The old version always used two fixed-width
                            // columns and a fixed 180/260dp tile height, so on
                            // a phone two people got two tall letterboxed
                            // slivers, five people overflowed into a scroll,
                            // and the stage never actually looked full. Now
                            // the columns and tile height are derived from the
                            // space available: the grid fills the stage, and a
                            // lone participant gets the whole stage.
                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val gap = 8.dp
                                val count = participants.size.coerceAtLeast(1)
                                val columns = when {
                                    count == 1 -> 1
                                    // Two people on a portrait phone read better
                                    // stacked than squeezed side by side.
                                    count == 2 -> if (maxWidth > maxHeight) 2 else 1
                                    maxWidth < 480.dp -> 2
                                    maxWidth < 840.dp -> 3
                                    else -> 4
                                }

                                // Three rows is what stays legible on the stage;
                                // past that every tile is a sliver and every
                                // extra one is another video renderer. A class
                                // of 200 keeps the people who matter and puts
                                // the rest behind one tap, exactly like the web
                                // grid does.
                                val cap = columns * 3
                                val ranked = remember(participants) { rankForStage(participants) }
                                val overflow = ranked.size > cap
                                val shown = if (overflow) ranked.take(cap - 1) else ranked
                                val hidden = if (overflow) ranked.drop(cap - 1) else emptyList()
                                val cells = shown.size + if (overflow) 1 else 0

                                val rows = kotlin.math.ceil(cells / columns.toFloat()).toInt().coerceAtLeast(1)
                                val totalGap = gap * (rows - 1)
                                val tileHeight = ((maxHeight - totalGap) / rows).coerceAtLeast(110.dp)

                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(columns),
                                    verticalArrangement = Arrangement.spacedBy(gap),
                                    horizontalArrangement = Arrangement.spacedBy(gap),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(shown, key = { it.identity }) { p ->
                                        StageVideoView(
                                            participant = p,
                                            isSpotlight = false,
                                            onPinClick = { viewModel.pinParticipant(p.identity) },
                                            initRenderer = { viewModel.initVideoRenderer(it) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(tileHeight)
                                        )
                                    }
                                    if (overflow) {
                                        item(key = "stage-overflow") {
                                            StageOverflowTile(
                                                hiddenCount = hidden.size,
                                                preview = hidden,
                                                onClick = { showParticipantsSheet = true },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(tileHeight)
                                            )
                                        }
                                    }
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
                            isModerator = isModerator,
                            canUseMic = viewModel.canUse("mic"),
                            canUseCamera = viewModel.canUse("camera"),
                            isMicRequested = requestedKinds["mic"] == true,
                            isCameraRequested = requestedKinds["camera"] == true,
                            pendingModerationRequests = pendingRequests.size,
                            onToggleMic = {
                                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    scope.launch { viewModel.roomManager.toggleMic() }
                                } else {
                                    permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                                }
                            },
                            onToggleCamera = {
                                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    scope.launch { viewModel.roomManager.toggleCamera() }
                                } else {
                                    permissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA))
                                }
                            },
                            onFlipCamera = { scope.launch { viewModel.roomManager.flipCamera() } },
                            onToggleHand = {
                                val localName = participants.find { it.isLocal }?.name ?: "Student"
                                scope.launch { viewModel.roomManager.toggleHandRaise(localName) }
                            },
                            onSendReaction = { emoji -> scope.launch { viewModel.roomManager.sendReaction(emoji) } },
                            onToggleChat = { showChatDrawer = !showChatDrawer },
                            onOpenModeration = { showModerationModal = true },
                            onRequestMic = { viewModel.requestPermission("mic") },
                            onRequestCamera = { viewModel.requestPermission("camera") },
                            onLeaveClick = onNavigateBack
                        )
                    }
                }
            }

            // Real-Time System Notification Floating Banner
            AnimatedVisibility(
                visible = activeBannerMessage != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
            ) {
                activeBannerMessage?.let { msg ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xE61E1E26))
                            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Notification",
                            tint = IndigoPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = msg,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // In-Class Chat Drawer Overlay with Scrim
            if (showChatDrawer) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { showChatDrawer = false }
                )
                AnimatedVisibility(
                    visible = showChatDrawer,
                    enter = slideInHorizontally(initialOffsetX = { it }),
                    exit = slideOutHorizontally(targetOffsetX = { it }),
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    ChatDrawer(
                        messages = chatMessages,
                        currentUserName = currentUserName,
                        onSendMessage = { text -> viewModel.sendChatMessage(text) },
                        onClose = { showChatDrawer = false },
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.92f)
                    )
                }
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

            // Everyone's read-only roster.
            if (showParticipantsSheet) {
                ParticipantsSheet(
                    participants = participants,
                    onDismiss = { showParticipantsSheet = false }
                )
            }

            // Host / Co-Host Room Moderation Modal
            if (showModerationModal && isModerator) {
                RoomModerationModal(
                    isMutedAll = isMutedAll,
                    autoApproveMic = autoApproveMic,
                    permissionPolicy = permissionPolicy,
                    pendingRequests = pendingRequests,
                    coHosts = coHosts,
                    participants = participants,
                    onDismiss = { showModerationModal = false },
                    onToggleMuteAll = { viewModel.setMuteAll(!isMutedAll) },
                    onToggleSmartMic = { viewModel.setAutoApproveMic(it) },
                    onTogglePolicy = { kind ->
                        val current = when (kind) {
                            "mic" -> permissionPolicy.mic
                            "camera" -> permissionPolicy.camera
                            "screen" -> permissionPolicy.screen
                            else -> "free"
                        }
                        val next = if (current == "approval") "free" else "approval"
                        viewModel.setPermissionPolicy(kind, next)
                    },
                    onReviewRequest = { reqId, approve ->
                        viewModel.reviewPermissionRequest(reqId, approve)
                    },
                    onAppointCoHost = { userId ->
                        viewModel.appointCoHost(userId)
                    },
                    onRemoveCoHost = { userId ->
                        viewModel.removeCoHost(userId)
                    },
                    onEndSession = {
                        viewModel.endSession {
                            showModerationModal = false
                            onNavigateBack()
                        }
                    }
                )
            }

            // Class materials (documents and links). Server-backed, so the
            // list is the same after a refresh or a reconnect.
            if (showMaterialsSheet) {
                MaterialsSheet(
                    materials = materials,
                    canManage = isModerator,
                    isSharing = isSharingMaterial,
                    error = materialsError,
                    onDismiss = { showMaterialsSheet = false },
                    onShareLink = { title, url -> viewModel.shareMaterialLink(title, url) },
                    onShareFile = { bytes, fileName, mime, title ->
                        viewModel.shareMaterialFile(bytes, fileName, mime, title)
                    },
                    onDelete = { id -> viewModel.deleteMaterial(id) }
                )
            }

            // Host Attendance Manager Modal
            if (showHostAttendanceModal && isModerator) {
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

            // Removed from the room (the host ended the class) or refused
            // entry (the class is closed until the host reopens it). Either way
            // the stage is over, so the student is walked back out. A class that
            // simply has not started is *not* a refusal -- that one waits in the
            // lobby below instead of being thrown at the student as an error.
            val closedNotice = joinBlocked ?: roomClosed
            if (closedNotice != null && !waitingForHost) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text(if (joinBlocked != null) "Class Unavailable" else "Class Ended") },
                    text = { Text(closedNotice) },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.joinBlocked.value = null
                            viewModel.roomClosed.value = null
                            onNavigateBack()
                        }) { Text("Back to classes") }
                    }
                )
            }

            // Runtime permission rationale dialog
            if (showPermissionRationale) {
                AlertDialog(
                    onDismissRequest = { showPermissionRationale = false },
                    title = { Text("Permissions Required") },
                    text = {
                        Text(
                            "Camera and microphone access is needed to join the classroom. " +
                                "Grant them now to continue."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showPermissionRationale = false
                            permissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.RECORD_AUDIO,
                                    android.Manifest.permission.CAMERA
                                )
                            )
                        }) { Text("Grant") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPermissionRationale = false }) {
                            Text("Not now")
                        }
                    }
                )
            }

            // Runtime permission settings fallback dialog
            if (showPermissionSettings) {
                AlertDialog(
                    onDismissRequest = { showPermissionSettings = false },
                    title = { Text("Permissions Disabled") },
                    text = {
                        Text(
                            "Camera and microphone access was denied. " +
                                "Enable them in app settings to join classrooms."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showPermissionSettings = false
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        }) { Text("Open Settings") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPermissionSettings = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

/**
 * The classroom as it appears in a picture-in-picture window.
 *
 * It is the same stage tile the full screen uses, sized to the window: whoever
 * is worth watching fills it, and when nobody is on camera their profile tile
 * (initial, name, mic state) stands in for them. The class name sits on top so
 * a shrunk window still says which lecture it is.
 */
@Composable
private fun PipClassroomView(
    sessionTitle: String,
    participants: List<ParticipantTileState>,
    pinnedId: String?,
    initRenderer: (TextureViewRenderer) -> Unit
) {
    val active = participants.firstOrNull { it.identity == pinnedId }
        ?: participants.firstOrNull { it.hasVideo }
        ?: participants.firstOrNull { it.isSpeaking }
        ?: participants.firstOrNull()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        if (active != null) {
            StageVideoView(
                participant = active,
                isSpotlight = true,
                onPinClick = {},
                initRenderer = initRenderer,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                CircularProgressIndicator(
                    color = IndigoPrimary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Connecting…",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Text(
            text = sessionTitle,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * How badly a participant wants one of the stage's limited tiles.
 *
 * Built from slow-moving signals only. `isSpeaking` flips constantly in a live
 * class, and ranking on it would make tiles jump around -- and re-create their
 * video renderers -- while people talk.
 */
private fun stageRank(p: ParticipantTileState): Int {
    var score = 0
    if (p.isLocal) score += 100      // never hide the viewer's own tile
    if (p.hasScreenShare) score += 64
    if (p.role == "lecturer" || p.role == "admin") score += 32
    if (p.isCoHost) score += 24
    if (p.hasVideo) score += 16
    if (p.isHandRaised) score += 8
    return score
}

/** The stage's participants, best-ranked first, ties in join order. */
private fun rankForStage(participants: List<ParticipantTileState>): List<ParticipantTileState> =
    participants.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<ParticipantTileState>> { stageRank(it.value) }
                .thenBy { it.index }
        )
        .map { it.value }
