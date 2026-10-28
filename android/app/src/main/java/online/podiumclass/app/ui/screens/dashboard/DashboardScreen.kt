package online.podiumclass.app.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import online.podiumclass.app.data.api.models.AutoAttendanceSettingsDto
import online.podiumclass.app.data.api.models.CreateSessionRequest
import online.podiumclass.app.data.api.models.RecordingDto
import online.podiumclass.app.data.api.models.SessionDto
import online.podiumclass.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    sessions: List<SessionDto>,
    isLoading: Boolean,
    isLecturer: Boolean,
    // Admins get the control panel alongside their own classes.
    isAdmin: Boolean = false,
    onNavigateAdmin: () -> Unit = {},
    userName: String = "User",
    userId: String? = null,
    walletBalance: Long = 10000,
    themeMode: String = "system",
    onToggleTheme: () -> Unit = {},
    onJoinClass: (String) -> Unit,
    onCreateClass: (CreateSessionRequest) -> Unit,
    onNavigateAttendance: () -> Unit = {},
    onNavigateProfile: () -> Unit = {},
    onLogout: () -> Unit,
    onRefresh: () -> Unit,
    onEndClass: (String) -> Unit = {},
    onDeleteClass: (String) -> Unit = {},
    // Records: the classes this user runs, newest first. Empty for a student --
    // the server answers a participant with nothing at all.
    recordings: List<RecordingDto> = emptyList(),
    isLoadingRecords: Boolean = false,
    recordsError: String? = null,
    onRefreshRecords: () -> Unit = {},
    // The class just created, if there is one: a new class does not open, so
    // this is the host's moment to share the code before anyone tries to join.
    createdSession: SessionDto? = null,
    onDismissCreatedSession: () -> Unit = {},
    onStartCreatedSession: (String) -> Unit = {},
    apiUrl: String = ""
) {
    val colors = AppTheme.colors
    var showCreateModal by remember { mutableStateOf(false) }
    var quickMeetingCode by remember { mutableStateOf("") }
    // Sessions awaiting confirmation, kept separate so we never act on a tap by
    // accident: end and delete are both irreversible from the dashboard.
    var sessionToEnd by remember { mutableStateOf<SessionDto?>(null) }
    var sessionToDelete by remember { mutableStateOf<SessionDto?>(null) }

    val calendar = remember { Calendar.getInstance() }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val greetingTime = when {
        hour < 12 -> "morning"
        hour < 17 -> "afternoon"
        else -> "evening"
    }

    val currentDateStr = remember {
        val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
        sdf.format(Date())
    }

    val liveSessions = remember(sessions) {
        sessions.filter { it.status.equals("active", ignoreCase = true) || it.is_active }
    }

    // "Hosted" means classes this user actually owns, not every class on screen
    // (which, for a student, is none of them).
    val hostedCount = remember(sessions, userId) {
        sessions.count { !it.hostId.isNullOrBlank() && it.hostId == userId }
    }

    val enrolledCount = sessions.size - hostedCount

    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            NavigationBar(
                containerColor = colors.surface,
                contentColor = colors.textPrimary,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = true,
                    onClick = { /* Current Screen */ },
                    icon = { Icon(Icons.Default.School, contentDescription = "Classes") },
                    label = { Text("Classes", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = colors.primary,
                        indicatorColor = colors.primary,
                        unselectedIconColor = colors.textSecondary,
                        unselectedTextColor = colors.textSecondary
                    )
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateAttendance,
                    icon = { Icon(Icons.Default.FactCheck, contentDescription = "Attendance") },
                    label = { Text("Attendance", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = colors.primary,
                        indicatorColor = colors.primary,
                        unselectedIconColor = colors.textSecondary,
                        unselectedTextColor = colors.textSecondary
                    )
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateProfile,
                    icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
                    label = { Text("Profile", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = colors.primary,
                        indicatorColor = colors.primary,
                        unselectedIconColor = colors.textSecondary,
                        unselectedTextColor = colors.textSecondary
                    )
                )
                if (isAdmin) {
                    NavigationBarItem(
                        selected = false,
                        onClick = onNavigateAdmin,
                        icon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = "Admin") },
                        label = { Text("Admin", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = colors.primary,
                            indicatorColor = colors.primary,
                            unselectedIconColor = colors.textSecondary,
                            unselectedTextColor = colors.textSecondary
                        )
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 1. Header (Mirroring DashboardView.vue lines 4-19)
            //
            // Two rows on purpose. Greeting, theme, refresh and "New class"
            // used to share one line, which on a phone squeezed the name and
            // left the two round icon buttons touching each other. The date
            // now keeps the icons company on the second row instead.
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Good ",
                                    color = colors.textPrimary,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = greetingTime,
                                    color = colors.primary,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = ",",
                                    color = colors.textPrimary,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = userName,
                                color = colors.textPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Hosting is a lecturer action -- the server refuses a
                        // student's create call, so it is not offered to one.
                        if (isLecturer) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { showCreateModal = true },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "New class", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("New class", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = currentDateStr,
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        // Their own row, and 12dp apart, so neither button is a
                        // mis-tap for the other on a narrow screen.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Light / Dark Theme Toggle directly on Main Screen
                            IconButton(
                                onClick = onToggleTheme,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(colors.surfaceElevated)
                                    .border(1.dp, colors.cardBorder, CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (colors.isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = "Toggle theme",
                                    tint = if (colors.isDark) AmberWarning else IndigoPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = onRefresh,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(colors.surfaceElevated)
                                    .border(1.dp, colors.cardBorder, CircleShape)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // 2. Quick Join Card (Mirroring DashboardView.vue lines 22-39)
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "JOIN A CLASS",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = quickMeetingCode,
                                onValueChange = { quickMeetingCode = it },
                                placeholder = { Text("Enter meeting code...", color = colors.textTertiary, fontSize = 13.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary,
                                    focusedBorderColor = colors.primary,
                                    unfocusedBorderColor = colors.cardBorder,
                                    focusedContainerColor = colors.surfaceElevated,
                                    unfocusedContainerColor = colors.surfaceElevated
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            )

                            Button(
                                onClick = {
                                    if (quickMeetingCode.isNotBlank()) {
                                        onJoinClass(quickMeetingCode.trim())
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Join", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // 3. Live Now Banner (Mirroring DashboardView.vue lines 42-65)
            if (liveSessions.isNotEmpty()) {
                val liveSession = liveSessions.first()
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, colors.error.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            // Red indicator bar & dot
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(colors.error)
                            )
                            Spacer(modifier = Modifier.width(12.dp))

                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(colors.error)
                            )
                            Spacer(modifier = Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "LIVE NOW",
                                    color = colors.error,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "${liveSession.title} — ${liveSession.course ?: "General"}",
                                    color = colors.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Button(
                                onClick = { onJoinClass(liveSession.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Join", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 4. Stat Cards (Enrolled & Hosted)
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Card 1: Enrolled
                    StatCard(
                        title = "ENROLLED",
                        value = "$enrolledCount",
                        icon = Icons.Default.School,
                        iconColor = Color(0xFF3B82F6),
                        iconBg = Color(0xFF1E3A8A).copy(alpha = 0.3f),
                        modifier = Modifier.weight(1f)
                    )

                    // Card 2: Hosted
                    StatCard(
                        title = "HOSTED",
                        value = "$hostedCount",
                        icon = Icons.Default.CastForEducation,
                        iconColor = Color(0xFFF59E0B),
                        iconBg = Color(0xFF78350F).copy(alpha = 0.3f),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 4b. Nothing here yet. The list now holds only the classes this
            // account is actually in, so a new student arrives with an empty
            // dashboard -- which needs to say how a class is joined, rather than
            // looking broken.
            if (sessions.isEmpty() && !isLoading) {
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 28.dp)
                        ) {
                            Icon(
                                Icons.Default.School,
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (isLecturer) "No classes yet" else "You have not joined a class yet",
                                color = colors.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isLecturer)
                                    "Create a class to get started."
                                else
                                    "Enter the meeting code from your lecturer above, or open the invite link you were sent.",
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // 5. Your classes. Every class, as a lazy list rather than three
            // cards crammed into one column: the dashboard is the screen people
            // return to, so it has to survive a term's worth of classes.
            if (sessions.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.error)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "YOUR CLASSES",
                            color = colors.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "${sessions.size}",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                items(sessions, key = { "session-" + it.id }) { session ->
                    SessionItemCard(
                        session = session,
                        canManage = isLecturer,
                        onClick = { onJoinClass(session.id) },
                        onEnd = { sessionToEnd = session },
                        onDelete = { sessionToDelete = session }
                    )
                }
            }

            // 6. Action Card: Create a Class
            if (isLecturer) {
                item {
                    ActionBannerCard(
                        title = "Create a class",
                        description = "Set up a new classroom session and invite students.",
                        linkText = "Get started →",
                        icon = Icons.Default.Add,
                        iconBg = Color(0xFF1845D4),
                        onClick = { showCreateModal = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 6b. Admins: the control panel, one tap from their own classes.
            if (isAdmin) {
                item {
                    ActionBannerCard(
                        title = "Control panel",
                        description = "Accounts, class-creation grants, live classes and platform flags.",
                        linkText = "Open panel →",
                        icon = Icons.Default.AdminPanelSettings,
                        iconBg = colors.error,
                        onClick = onNavigateAdmin,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 7. Records: the host's recordings, where only they can watch,
            // save and pass them on.
            if (isLecturer) {
                item {
                    RecordsSection(
                        recordings = recordings,
                        isLoading = isLoadingRecords,
                        error = recordsError,
                        onRefresh = onRefreshRecords
                    )
                }
            }
        }
    }

    // A class that was just created stays on the dashboard: it is not live
    // until its host walks in, so the code matters more than the room does.
    createdSession?.let { created ->
        ShareClassDialog(
            session = created,
            apiUrl = apiUrl,
            onDismiss = onDismissCreatedSession,
            onStart = { onStartCreatedSession(created.id) }
        )
    }

    // Modal: Create Class Modal (Matching web modal)
    if (showCreateModal && isLecturer) {
        CreateClassDialog(
            defaultLecturer = userName,
            onDismiss = { showCreateModal = false },
            onCreate = { request ->
                showCreateModal = false
                onCreateClass(request)
            }
        )
    }

    sessionToEnd?.let { session ->
        ConfirmActionDialog(
            title = "End class?",
            message = "\"${session.title}\" will be marked as ended and students can no longer join.",
            confirmLabel = "End class",
            onConfirm = {
                val id = session.id
                sessionToEnd = null
                onEndClass(id)
            },
            onDismiss = { sessionToEnd = null }
        )
    }

    sessionToDelete?.let { session ->
        ConfirmActionDialog(
            title = "Delete class?",
            message = "\"${session.title}\" will be removed from your dashboard. Attendance history is kept.",
            confirmLabel = "Delete",
            onConfirm = {
                val id = session.id
                sessionToDelete = null
                onDeleteClass(id)
            },
            onDismiss = { sessionToDelete = null }
        )
    }
}

// ----------------- SUB-COMPONENTS -----------------

@Composable
fun StatCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    iconBg: Color,
    subtext: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = modifier
            .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconBg)
                ) {
                    Icon(imageVector = icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = value,
                color = colors.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            if (!subtext.isNullOrBlank()) {
                Text(
                    text = subtext,
                    color = iconColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ActionBannerCard(
    title: String,
    description: String,
    linkText: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = modifier
            .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBg)
            ) {
                Icon(imageVector = icon, contentDescription = title, tint = Color.White, modifier = Modifier.size(20.dp))
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                color = colors.textSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = linkText,
                color = iconBg,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SessionItemCard(
    session: SessionDto,
    canManage: Boolean = false,
    onClick: () -> Unit,
    onEnd: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val isLive = session.status.equals("active", ignoreCase = true) || session.is_active

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        // Two rows instead of one crowded line. The old single row had an icon,
        // a title, course, lecturer, a LIVE badge and up to two action buttons
        // competing for 360dp, so on a phone the title collapsed to a few
        // letters and the buttons sat under the thumb by accident. Identity
        // lives on the top row; facts and actions get their own row below.
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (isLive) colors.primary.copy(alpha = 0.15f) else colors.surfaceElevated)
                ) {
                    Icon(
                        imageVector = if (isLive) Icons.Default.Videocam else Icons.Default.School,
                        contentDescription = null,
                        tint = if (isLive) colors.primary else colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title,
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = listOfNotNull(
                            session.course ?: "General",
                            session.lecturerName ?: "Faculty Lecturer"
                        ).joinToString(" • "),
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // The code is what people pass around, so it is a control: a tap
                // copies it and never opens the class underneath.
                session.meetingCode?.takeIf { it.isNotBlank() }?.let { code ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.primary.copy(alpha = 0.10f))
                            .clickable { copyToClipboard(context, "Class code", code) }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = code,
                            color = colors.primary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy class code",
                            tint = colors.primary,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(11.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLive) {
                    Surface(color = colors.primary, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            text = "LIVE",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                if (session.attendeeCount > 0) {
                    Text(
                        text = "${session.presentCount}/${session.attendeeCount} present",
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                } else if (!isLive) {
                    Text(
                        text = "Not started",
                        color = colors.textTertiary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Host controls keep their own space, and a full 40dp touch
                // target, so ending a class is a deliberate tap.
                if (canManage) {
                    if (isLive) {
                        IconButton(onClick = onEnd, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = Icons.Default.StopCircle,
                                contentDescription = "End class",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete class",
                            tint = colors.error,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                Text(
                    text = if (isLive) "Enter" else "Open",
                    color = colors.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = AppTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(title, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(message, color = colors.textSecondary, fontSize = 13.sp)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = colors.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary)
            }
        }
    )
}

@Composable
fun PillTabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0xFF1845D4) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else Color(0xFF8888A8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ----------------- MODALS -----------------

@Composable
fun CreateClassDialog(
    onDismiss: () -> Unit,
    onCreate: (CreateSessionRequest) -> Unit,
    defaultLecturer: String = ""
) {
    val colors = AppTheme.colors
    var title by remember { mutableStateOf("") }
    var course by remember { mutableStateOf("") }
    var lecturer by remember(defaultLecturer) { mutableStateOf(defaultLecturer) }
    var enableAutoAttendance by remember { mutableStateOf(true) }
    // Chosen from chips instead of typed. Three number fields side by side were
    // too narrow to read on a phone, and every one of them raised the keyboard
    // over the Create button.
    var checkCount by remember { mutableStateOf(3) }
    var timeLimit by remember { mutableStateOf(30) }
    var intervalMinutes by remember { mutableStateOf(15) }

    Dialog(
        onDismissRequest = onDismiss,
        // The dialog handles its own insets, so imePadding() below reports the
        // keyboard and the card can move above it rather than having the
        // Create button buried under it.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 4.dp)
                    ) {
                        Text("Create New Class", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textSecondary)
                        }
                    }

                    // The form scrolls, so a short phone with the keyboard up
                    // still reaches every setting while the buttons below stay
                    // pinned where they are.
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                    ) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Class title") },
                            placeholder = { Text("e.g. Distributed Systems", fontSize = 13.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Next
                            ),
                            colors = createDialogFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = course,
                                onValueChange = { course = it },
                                label = { Text("Course code") },
                                placeholder = { Text("CS401", fontSize = 13.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters,
                                    imeAction = ImeAction.Next
                                ),
                                colors = createDialogFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = lecturer,
                                onValueChange = { lecturer = it },
                                label = { Text("Lecturer") },
                                placeholder = { Text("Your name", fontSize = 13.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Words,
                                    imeAction = ImeAction.Done
                                ),
                                colors = createDialogFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Automated attendance as one card: a switch, and, when
                        // it is on, the three settings as tap targets.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceElevated)
                                .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Automated attendance", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Queues presence checks while the class runs",
                                        color = colors.textSecondary,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = enableAutoAttendance,
                                    onCheckedChange = { enableAutoAttendance = it },
                                    colors = SwitchDefaults.colors(checkedTrackColor = colors.primary)
                                )
                            }

                            if (enableAutoAttendance) {
                                Spacer(modifier = Modifier.height(14.dp))
                                SettingChips(
                                    label = "Checks per class",
                                    options = listOf(1, 2, 3, 4, 5),
                                    selected = checkCount,
                                    optionText = { "$it" },
                                    onSelect = { checkCount = it }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                SettingChips(
                                    label = "Answer window",
                                    options = listOf(15, 30, 45, 60),
                                    selected = timeLimit,
                                    optionText = { "${it}s" },
                                    onSelect = { timeLimit = it }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                SettingChips(
                                    label = "Every",
                                    options = listOf(5, 10, 15, 30),
                                    selected = intervalMinutes,
                                    optionText = { "${it}m" },
                                    onSelect = { intervalMinutes = it }
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 18.dp)
                    ) {
                        Text(
                            text = if (title.isBlank()) {
                                "Add a title to create the class"
                            } else {
                                "Students join with the class code"
                            },
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = colors.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(
                            onClick = {
                                val autoSettings = if (enableAutoAttendance) {
                                    AutoAttendanceSettingsDto(
                                        enabled = true,
                                        checkCount = checkCount,
                                        timeLimitSeconds = timeLimit,
                                        intervalMinutes = intervalMinutes
                                    )
                                } else {
                                    AutoAttendanceSettingsDto(enabled = false)
                                }

                                onCreate(
                                    CreateSessionRequest(
                                        title = title.trim(),
                                        course = course.trim().ifEmpty { "General" },
                                        // Left blank, the field is dropped from the
                                        // request and the server falls back to the
                                        // host's own account name.
                                        lecturerName = lecturer.trim().ifBlank { null },
                                        autoAttendanceSettings = autoSettings
                                    )
                                )
                            },
                            // Was always tappable and silently did nothing on an
                            // empty title; now it says when it can be used.
                            enabled = title.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Text("Create class", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/** The shared look for the fields in the create-class form. */
@Composable
private fun createDialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AppTheme.colors.textPrimary,
    unfocusedTextColor = AppTheme.colors.textPrimary,
    focusedContainerColor = AppTheme.colors.surfaceElevated,
    unfocusedContainerColor = AppTheme.colors.surfaceElevated,
    focusedBorderColor = AppTheme.colors.primary,
    unfocusedBorderColor = AppTheme.colors.cardBorder,
    focusedLabelColor = AppTheme.colors.primary,
    unfocusedLabelColor = AppTheme.colors.textSecondary,
    focusedPlaceholderColor = AppTheme.colors.textTertiary,
    unfocusedPlaceholderColor = AppTheme.colors.textTertiary
)

/**
 * One setting as a row of choices.
 *
 * Tapping a chip is faster than typing a number on a phone and it cannot be
 * given a value the scheduler would not understand.
 */
@Composable
private fun SettingChips(
    label: String,
    options: List<Int>,
    selected: Int,
    optionText: (Int) -> String,
    onSelect: (Int) -> Unit
) {
    val colors = AppTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            color = colors.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) colors.primary else Color.Transparent)
                        .border(1.dp, if (isSelected) colors.primary else colors.cardBorder, RoundedCornerShape(8.dp))
                        .clickable { onSelect(option) }
                ) {
                    Text(
                        text = optionText(option),
                        color = if (isSelected) Color.White else colors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun WalletDialog(
    balance: Long,
    onDismiss: () -> Unit
) {
    val colors = AppTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 440.dp)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("My Wallet", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceElevated),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF10B981).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("AVAILABLE BALANCE", color = Color(0xFF10B981), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("GHS ${(balance / 100.0).formatDec()}", color = colors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Quick Deposit / Paystack Top-Up", color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("GHS 20", "GHS 50", "GHS 100").forEach { amount ->
                        OutlinedButton(
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.cardBorder)
                        ) {
                            Text(amount, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AttendanceSummaryDialog(
    sessions: List<SessionDto>,
    onDismiss: () -> Unit
) {
    val colors = AppTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 440.dp)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Attendance Records", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceElevated),
                    modifier = Modifier.fillMaxWidth().border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF7C3AED).copy(alpha = 0.2f))) {
                            Icon(Icons.Default.FactCheck, contentDescription = "Check", tint = Color(0xFFA78BFA))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Verified Compliance", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("100% verified attendance telemetry", color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileDialog(
    userName: String,
    isLecturer: Boolean,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = AppTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 440.dp)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp).clip(CircleShape).background(colors.primary)) {
                    Text(userName.take(1).uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(userName, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (isLecturer) "Host & Faculty Account" else "Student Account", color = colors.textSecondary, fontSize = 12.sp)

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onLogout,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Icon(Icons.Default.Logout, contentDescription = "Logout", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sign Out", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun Double.formatDec(): String = String.format(Locale.US, "%.2f", this)
