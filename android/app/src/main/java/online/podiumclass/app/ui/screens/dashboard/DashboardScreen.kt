package online.podiumclass.app.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import online.podiumclass.app.data.api.models.AutoAttendanceSettingsDto
import online.podiumclass.app.data.api.models.CreateSessionRequest
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
    userName: String = "User",
    walletBalance: Long = 10000,
    onJoinClass: (String) -> Unit,
    onCreateClass: (CreateSessionRequest) -> Unit,
    onLogout: () -> Unit,
    onRefresh: () -> Unit
) {
    var showCreateModal by remember { mutableStateOf(false) }
    var showWalletModal by remember { mutableStateOf(false) }
    var showAttendanceModal by remember { mutableStateOf(false) }
    var showProfileModal by remember { mutableStateOf(false) }
    var quickMeetingCode by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("enrolled") } // "enrolled" or "hosted"
    var selectedBottomNav by remember { mutableStateOf(0) }

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

    val hostedCount = remember(sessions, isLecturer) {
        if (isLecturer) sessions.size else 0
    }

    val filteredSessions = remember(sessions, activeTab, isLecturer) {
        if (activeTab == "hosted") {
            if (isLecturer) sessions else emptyList()
        } else {
            sessions
        }
    }

    Scaffold(
        containerColor = Color(0xFF090B11),
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0D0D1A),
                contentColor = Color.White,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedBottomNav == 0,
                    onClick = { selectedBottomNav = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color(0xFF1845D4),
                        indicatorColor = Color(0xFF1845D4),
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B)
                    )
                )
                NavigationBarItem(
                    selected = selectedBottomNav == 1,
                    onClick = {
                        selectedBottomNav = 1
                        showWalletModal = true
                    },
                    icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = "Wallet") },
                    label = { Text("Wallet", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color(0xFF1845D4),
                        indicatorColor = Color(0xFF1845D4),
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B)
                    )
                )
                NavigationBarItem(
                    selected = selectedBottomNav == 2,
                    onClick = {
                        selectedBottomNav = 2
                        showAttendanceModal = true
                    },
                    icon = { Icon(Icons.Default.AssignmentTurnedIn, contentDescription = "Attendance") },
                    label = { Text("Attendance", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color(0xFF1845D4),
                        indicatorColor = Color(0xFF1845D4),
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B)
                    )
                )
                NavigationBarItem(
                    selected = selectedBottomNav == 3,
                    onClick = {
                        selectedBottomNav = 3
                        showProfileModal = true
                    },
                    icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
                    label = { Text("Profile", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color(0xFF1845D4),
                        indicatorColor = Color(0xFF1845D4),
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B)
                    )
                )
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
            // 1. Header Row (Mirroring DashboardView.vue lines 4-19)
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Good ",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = greetingTime,
                                color = Color(0xFF3B82F6),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = ",",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = userName,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = currentDateStr,
                            color = Color(0xFF8888A8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { showCreateModal = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1845D4)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "New class", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New class", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF8888A8))
                        }
                    }
                }
            }

            // 2. Quick Join Card (Mirroring DashboardView.vue lines 22-39)
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111522)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "JOIN A CLASS",
                            color = Color(0xFF8888A8),
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
                                placeholder = { Text("Enter meeting code...", color = Color(0xFF64748B), fontSize = 13.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF1845D4),
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                                    focusedContainerColor = Color(0xFF0D0D1A),
                                    unfocusedContainerColor = Color(0xFF0D0D1A)
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
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1845D4)),
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
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111522)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
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
                                    .background(Color(0xFFEF4444))
                            )
                            Spacer(modifier = Modifier.width(12.dp))

                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444))
                            )
                            Spacer(modifier = Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "LIVE NOW",
                                    color = Color(0xFFF87171),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "${liveSession.title} — ${liveSession.course ?: "General"}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Button(
                                onClick = { onJoinClass(liveSession.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1845D4)),
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
                        value = "${sessions.size}",
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

            // 5. Jump Back In Section (Mirroring DashboardView.vue lines 132-174)
            if (sessions.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "JUMP BACK IN",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }

                        sessions.take(3).forEach { session ->
                            SessionItemCard(
                                session = session,
                                onClick = { onJoinClass(session.id) }
                            )
                        }
                    }
                }
            }

            // 6. Action Cards (Mirroring DashboardView.vue lines 177-205)
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Create Class Action Card
                    ActionBannerCard(
                        title = "Create a class",
                        description = "Set up a new classroom session and invite students.",
                        linkText = "Get started →",
                        icon = Icons.Default.Add,
                        iconBg = Color(0xFF1845D4),
                        onClick = { showCreateModal = true },
                        modifier = Modifier.weight(1f)
                    )

                    // Biometric Attendance Action Card
                    ActionBannerCard(
                        title = "Biometric Attendance",
                        description = "Inspect check-in telemetry and verify institutional records.",
                        linkText = "Inspect Attendance →",
                        icon = Icons.Default.FactCheck,
                        iconBg = Color(0xFF7C3AED),
                        onClick = { showAttendanceModal = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 7. My Classes with Tabs (Mirroring DashboardView.vue lines 208-250)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "MY CLASSES",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        // Pill Switcher
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF111522))
                                .padding(2.dp)
                        ) {
                            PillTabButton(
                                text = "Enrolled",
                                selected = activeTab == "enrolled",
                                onClick = { activeTab = "enrolled" }
                            )
                            PillTabButton(
                                text = "Hosted",
                                selected = activeTab == "hosted",
                                onClick = { activeTab = "hosted" }
                            )
                        }
                    }

                    if (filteredSessions.isEmpty()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp)
                        ) {
                            Text(
                                text = if (activeTab == "hosted") "No hosted classes yet" else "No enrolled classes yet",
                                color = Color(0xFF64748B),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        filteredSessions.forEach { session ->
                            SessionItemCard(
                                session = session,
                                onClick = { onJoinClass(session.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal 1: Create Class Modal (Matching web modal)
    if (showCreateModal) {
        CreateClassDialog(
            onDismiss = { showCreateModal = false },
            onCreate = { request ->
                showCreateModal = false
                onCreateClass(request)
            }
        )
    }

    // Modal 2: Wallet Sheet
    if (showWalletModal) {
        WalletDialog(
            balance = walletBalance,
            onDismiss = {
                showWalletModal = false
                selectedBottomNav = 0
            }
        )
    }

    // Modal 3: Attendance Sheet
    if (showAttendanceModal) {
        AttendanceSummaryDialog(
            sessions = sessions,
            onDismiss = {
                showAttendanceModal = false
                selectedBottomNav = 0
            }
        )
    }

    // Modal 4: Profile / Settings Sheet
    if (showProfileModal) {
        ProfileDialog(
            userName = userName,
            isLecturer = isLecturer,
            onLogout = {
                showProfileModal = false
                onLogout()
            },
            onDismiss = {
                showProfileModal = false
                selectedBottomNav = 0
            }
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
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111522)),
        modifier = modifier
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
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
                    color = Color(0xFF8888A8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = value,
                color = Color.White,
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
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111522)),
        modifier = modifier
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
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
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                color = Color(0xFF8888A8),
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
    onClick: () -> Unit
) {
    val isLive = session.status.equals("active", ignoreCase = true) || session.is_active

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111522)),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isLive) Color(0xFF1845D4).copy(alpha = 0.2f) else Color(0xFF1E293B))
            ) {
                Icon(
                    imageVector = if (isLive) Icons.Default.Videocam else Icons.Default.School,
                    contentDescription = "Session",
                    tint = if (isLive) Color(0xFF3B82F6) else Color(0xFF8888A8),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${session.course ?: "General"} • ${session.lecturerName ?: "Faculty Lecturer"}",
                    color = Color(0xFF8888A8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isLive) {
                Surface(
                    color = Color(0xFF1845D4),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "LIVE",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Enter",
                    tint = Color(0xFF475569)
                )
            }
        }
    }
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
    onCreate: (CreateSessionRequest) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var course by remember { mutableStateOf("") }
    var enableAutoAttendance by remember { mutableStateOf(true) }
    var checkCount by remember { mutableStateOf("3") }
    var timeLimit by remember { mutableStateOf("30") }
    var intervalMinutes by remember { mutableStateOf("15") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111522)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create New Class", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF8888A8))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Class Title (e.g. Distributed Systems)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF1845D4),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = course,
                    onValueChange = { course = it },
                    label = { Text("Course Code (e.g. CS401)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF1845D4),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = enableAutoAttendance,
                        onCheckedChange = { enableAutoAttendance = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF1845D4))
                    )
                    Text("Enable Automated Attendance", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                if (enableAutoAttendance) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = checkCount,
                            onValueChange = { checkCount = it },
                            label = { Text("Checks") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = timeLimit,
                            onValueChange = { timeLimit = it },
                            label = { Text("Limit (s)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = intervalMinutes,
                            onValueChange = { intervalMinutes = it },
                            label = { Text("Interval (m)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        if (title.isNotBlank()) {
                            val autoSettings = if (enableAutoAttendance) {
                                AutoAttendanceSettingsDto(
                                    enabled = true,
                                    checkCount = checkCount.toIntOrNull() ?: 3,
                                    timeLimitSeconds = timeLimit.toIntOrNull() ?: 30,
                                    intervalMinutes = intervalMinutes.toIntOrNull() ?: 15
                                )
                            } else null

                            onCreate(
                                CreateSessionRequest(
                                    title = title.trim(),
                                    course = course.trim().ifEmpty { "General" },
                                    autoAttendanceSettings = autoSettings ?: AutoAttendanceSettingsDto(enabled = false)
                                )
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1845D4)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Create & Launch Session", fontWeight = FontWeight.Bold)
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
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111522)),
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("My Wallet", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF8888A8))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D1A)),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF10B981).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("AVAILABLE BALANCE", color = Color(0xFF10B981), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("GHS ${(balance / 100.0).formatDec()}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Quick Deposit / Paystack Top-Up", color = Color(0xFF8888A8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("GHS 20", "GHS 50", "GHS 100").forEach { amount ->
                        OutlinedButton(
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Text(amount, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111522)),
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Attendance Records", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF8888A8))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D1A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF7C3AED).copy(alpha = 0.2f))) {
                            Icon(Icons.Default.FactCheck, contentDescription = "Check", tint = Color(0xFFA78BFA))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Verified Compliance", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("100% verified attendance telemetry", color = Color(0xFF8888A8), fontSize = 11.sp)
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
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111522)),
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF1845D4))) {
                    Text(userName.take(1).uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(userName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (isLecturer) "Host & Faculty Account" else "Student Account", color = Color(0xFF8888A8), fontSize = 12.sp)

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onLogout,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Icon(Icons.Default.Logout, contentDescription = "Logout", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sign Out", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun Double.formatDec(): String = String.format(Locale.US, "%.2f", this)
