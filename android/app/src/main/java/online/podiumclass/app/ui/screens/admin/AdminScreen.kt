package online.podiumclass.app.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import online.podiumclass.app.data.api.models.AdminSettingsDto
import online.podiumclass.app.data.api.models.AdminUserPatchRequest
import online.podiumclass.app.data.api.models.FeatureDto
import online.podiumclass.app.data.api.models.SessionDto
import online.podiumclass.app.data.api.models.SettingDto
import online.podiumclass.app.data.api.models.UserDto
import online.podiumclass.app.ui.theme.AppTheme

private enum class AdminTab(val label: String, val icon: ImageVector) {
    Overview("Overview", Icons.Default.SpaceDashboard),
    Users("Users", Icons.Default.ManageAccounts),
    Sessions("Sessions", Icons.Default.Videocam),
    Settings("Settings", Icons.Default.Tune)
}

/**
 * The admin control panel, on the phone.
 *
 * Same four surfaces as the web panel -- the institution at a glance, the
 * people in it, the classes running, and what the platform has switched on --
 * arranged for one hand: tabs along the top, lists that fill the width, and a
 * sheet for the one destructive thing an admin actually does (changing what an
 * account is allowed to be).
 *
 * Nothing here is optimistic. A role change waits for the server's answer and
 * shows its refusal in a snackbar, because "demoted" that silently did not
 * happen is worse than a visible error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    adminName: String,
    apiUrl: String,
    sessions: List<SessionDto>,
    isLoadingSessions: Boolean,
    onRefreshSessions: () -> Unit,
    onOpenSession: (String) -> Unit,
    onNavigateHome: () -> Unit,
    viewModel: AdminViewModel = viewModel()
) {
    val colors = AppTheme.colors
    val users by viewModel.users.collectAsState()
    val isLoadingUsers by viewModel.isLoadingUsers.collectAsState()
    val usersError by viewModel.usersError.collectAsState()
    val query by viewModel.query.collectAsState()
    val roleFilter by viewModel.roleFilter.collectAsState()
    val savingUserId by viewModel.savingUserId.collectAsState()

    val settings by viewModel.settings.collectAsState()
    val isLoadingSettings by viewModel.isLoadingSettings.collectAsState()
    val settingsError by viewModel.settingsError.collectAsState()
    val savingKeys by viewModel.savingKeys.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()

    var tab by remember { mutableStateOf(AdminTab.Overview) }
    var editingUser by remember { mutableStateOf<UserDto?>(null) }
    var editingSetting by remember { mutableStateOf<SettingDto?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(actionMessage) {
        val message = actionMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    // Keep the open sheet showing the server's version of the account.
    LaunchedEffect(users) {
        editingUser = editingUser?.let { open -> users.firstOrNull { it.id == open.id } ?: open }
    }

    val liveSessions = remember(sessions) {
        sessions.filter { it.status.equals("active", true) || it.is_active }
    }
    val enabledFeatures = settings?.features?.count { it.enabled } ?: 0

    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(
                                text = "Control Panel",
                                color = colors.textPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = adminName.ifBlank { "Administrator" },
                                color = colors.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Pill(text = "SUPERADMIN", color = colors.error)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateHome) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.refresh()
                        onRefreshSessions()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = colors.textSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = colors.surface, tonalElevation = 8.dp) {
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateHome,
                    icon = { Icon(Icons.Default.School, contentDescription = "Classes") },
                    label = { Text("Classes", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = navItemColors(colors.primary, colors.textSecondary)
                )
                NavigationBarItem(
                    selected = true,
                    onClick = { },
                    icon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = "Admin") },
                    label = { Text("Admin", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = navItemColors(colors.primary, colors.textSecondary)
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab strip: scrolls horizontally on a narrow phone rather than
            // squeezing four labels into unreadable slivers.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                AdminTab.entries.forEach { entry ->
                    TabChip(
                        tab = entry,
                        selected = tab == entry,
                        onClick = {
                            tab = entry
                            if (entry == AdminTab.Users && users.isEmpty()) viewModel.loadUsers()
                            if (entry == AdminTab.Settings && settings == null) viewModel.loadSettings()
                        }
                    )
                }
            }

            when (tab) {
                AdminTab.Overview -> OverviewTab(
                    adminName = adminName,
                    apiUrl = apiUrl,
                    sessions = sessions,
                    liveSessions = liveSessions,
                    isLoadingSessions = isLoadingSessions,
                    users = users,
                    enabledFeatures = enabledFeatures,
                    onOpenSession = onOpenSession,
                    onOpenUsers = { tab = AdminTab.Users },
                    onOpenSettings = { tab = AdminTab.Settings }
                )

                AdminTab.Users -> UsersTab(
                    users = users,
                    query = query,
                    roleFilter = roleFilter,
                    isLoading = isLoadingUsers,
                    error = usersError,
                    onQueryChange = viewModel::onQueryChange,
                    onRoleFilter = viewModel::setRoleFilter,
                    onEdit = { editingUser = it },
                    onRetry = viewModel::loadUsers
                )

                AdminTab.Sessions -> SessionsTab(
                    sessions = sessions,
                    isLoading = isLoadingSessions,
                    onOpenSession = onOpenSession,
                    onRetry = onRefreshSessions
                )

                AdminTab.Settings -> SettingsTab(
                    settings = settings,
                    isLoading = isLoadingSettings,
                    error = settingsError,
                    savingKeys = savingKeys,
                    onToggleFeature = viewModel::toggleFeature,
                    onEditSetting = { editingSetting = it },
                    onRetry = viewModel::loadSettings
                )
            }
        }
    }

    editingUser?.let { user ->
        UserEditDialog(
            user = user,
            isSaving = savingUserId == user.id,
            onDismiss = { editingUser = null },
            onSave = { patch ->
                viewModel.updateUser(user, patch)
                editingUser = null
            }
        )
    }

    editingSetting?.let { setting ->
        SettingEditDialog(
            setting = setting,
            isSaving = savingKeys.contains(setting.key),
            onDismiss = { editingSetting = null },
            onSave = { value ->
                viewModel.saveSetting(setting.key, value)
                editingSetting = null
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Tabs
// ---------------------------------------------------------------------------

@Composable
private fun OverviewTab(
    adminName: String,
    apiUrl: String,
    sessions: List<SessionDto>,
    liveSessions: List<SessionDto>,
    isLoadingSessions: Boolean,
    users: List<UserDto>,
    enabledFeatures: Int,
    onOpenSession: (String) -> Unit,
    onOpenUsers: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = AppTheme.colors
    val lecturers = users.count { it.role == "lecturer" }
    val students = users.count { it.role == "student" }
    val disabled = users.count { it.status == "disabled" }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = "Institutional overview",
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Hello $adminName — here is the platform right now.",
                color = colors.textSecondary,
                fontSize = 12.sp
            )
        }

        // KPI cards: two across on a phone, four on anything wider.
        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = if (maxWidth > 560.dp) 4 else 2
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val kpis = listOf(
                        Triple("CLASSES", sessions.size.toString(), Icons.Default.Layers),
                        Triple("LIVE NOW", liveSessions.size.toString(), Icons.Default.BroadcastOnHome),
                        Triple("PEOPLE", users.size.toString(), Icons.Default.Groups),
                        Triple("FLAGS ON", enabledFeatures.toString(), Icons.Default.ToggleOn)
                    )
                    kpis.chunked(columns).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            rowItems.forEach { (label, value, icon) ->
                                KpiCard(
                                    label = label,
                                    value = value,
                                    icon = icon,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // Keep the grid square when a row is not full.
                            repeat(columns - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Live classrooms", icon = Icons.Default.BroadcastOnHome) {
                if (isLoadingSessions) {
                    LoadingRow()
                } else if (liveSessions.isEmpty()) {
                    Text(
                        text = "No class is running right now.",
                        color = colors.textSecondary,
                        fontSize = 12.sp
                    )
                } else {
                    liveSessions.take(6).forEach { session ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpenSession(session.id) }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(colors.success)
                            )
                            Spacer(modifier = Modifier.width(9.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.title,
                                    color = colors.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = listOfNotNull(
                                        session.course,
                                        session.lecturerName,
                                        "${session.presentCount} present"
                                    ).joinToString(" • "),
                                    color = colors.textSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "Inspect",
                                color = colors.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "People", icon = Icons.Default.ManageAccounts) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    MiniStat("Students", students.toString(), Modifier.weight(1f))
                    MiniStat("Lecturers", lecturers.toString(), Modifier.weight(1f))
                    MiniStat("Disabled", disabled.toString(), Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onOpenUsers,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ManageAccounts, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Manage accounts", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            SectionCard(title = "Platform", icon = Icons.Default.Dns) {
                InfoRow("Server", apiUrl)
                InfoRow("Classes tracked", sessions.size.toString())
                InfoRow("Feature flags", "$enabledFeatures enabled")
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceElevated),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Platform settings", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsersTab(
    users: List<UserDto>,
    query: String,
    roleFilter: String?,
    isLoading: Boolean,
    error: String?,
    onQueryChange: (String) -> Unit,
    onRoleFilter: (String?) -> Unit,
    onEdit: (UserDto) -> Unit,
    onRetry: () -> Unit
) {
    val colors = AppTheme.colors

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search name, email or index number", color = colors.textTertiary, fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = colors.textSecondary) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = colors.textSecondary)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.cardBorder,
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf<Pair<String?, String>>(
                    null to "All",
                    "student" to "Students",
                    "lecturer" to "Lecturers",
                    "admin" to "Admins"
                ).forEach { (value, label) ->
                    FilterPill(
                        label = label,
                        selected = roleFilter == value,
                        onClick = { onRoleFilter(value) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (isLoading && users.isEmpty()) {
            item { LoadingRow() }
        }
        if (error != null) {
            item {
                ErrorCard(message = error, onRetry = onRetry)
            }
        }
        if (!isLoading && error == null && users.isEmpty()) {
            item {
                Text(
                    text = "No account matches that search.",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                )
            }
        }

        items(users, key = { it.id }) { user ->
            UserRow(user = user, onClick = { onEdit(user) })
        }
    }
}

@Composable
private fun SessionsTab(
    sessions: List<SessionDto>,
    isLoading: Boolean,
    onOpenSession: (String) -> Unit,
    onRetry: () -> Unit
) {
    val colors = AppTheme.colors

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = "Classroom registry",
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${sessions.size} classes, ${sessions.count { it.status.equals("active", true) }} live.",
                color = colors.textSecondary,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (isLoading && sessions.isEmpty()) {
            item { LoadingRow() }
        }

        items(sessions, key = { it.id }) { session ->
            val isLive = session.status.equals("active", true) || session.is_active
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                    .clickable { onOpenSession(session.id) }
            ) {
                // Two rows: identity on top, facts underneath. A single row
                // squeezed title, course, host, status and a link into one
                // line, which is exactly how the cards got unreadable.
                Column(modifier = Modifier.padding(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = session.title,
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Pill(
                            text = if (isLive) "LIVE" else session.status.uppercase(),
                            color = if (isLive) colors.success else colors.textSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = listOfNotNull(
                            session.course ?: "General",
                            session.lecturerName ?: "Faculty host",
                            "${session.presentCount}/${session.attendeeCount} present"
                        ).joinToString(" • "),
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    session.meetingCode?.let { code ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = code,
                            color = colors.primary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(
    settings: AdminSettingsDto?,
    isLoading: Boolean,
    error: String?,
    savingKeys: Set<String>,
    onToggleFeature: (String, Boolean) -> Unit,
    onEditSetting: (SettingDto) -> Unit,
    onRetry: () -> Unit
) {
    val colors = AppTheme.colors

    if (isLoading && settings == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.primary, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
        }
        return
    }
    if (error != null && settings == null) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
            ErrorCard(message = error, onRetry = onRetry)
        }
        return
    }

    val payload = settings ?: return
    val groups = payload.featureGroups.ifEmpty {
        listOf(online.podiumclass.app.data.api.models.FeatureGroupDto(key = "all", label = "Features"))
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = "Feature toggles",
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${payload.features.count { it.enabled }} of ${payload.features.size} modules enabled.",
                color = colors.textSecondary,
                fontSize = 12.sp
            )
        }

        groups.forEach { group ->
            val groupFeatures = payload.features.filter {
                it.group == group.key || group.key == "all"
            }
            if (groupFeatures.isEmpty()) return@forEach

            item(key = "feature-header-${group.key}") {
                Text(
                    text = group.label.uppercase(),
                    color = colors.textTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(groupFeatures, key = { "feature-" + it.key }) { feature ->
                FeatureRow(
                    feature = feature,
                    isBusy = savingKeys.contains(feature.key),
                    onToggle = { onToggleFeature(feature.key, !feature.enabled) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Configuration",
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Secrets are masked; leaving one blank keeps the stored value.",
                color = colors.textSecondary,
                fontSize = 12.sp
            )
        }

        items(payload.settings, key = { "setting-" + it.key }) { setting ->
            SettingRow(setting = setting, onClick = { onEditSetting(setting) })
        }
    }
}

// ---------------------------------------------------------------------------
// Rows and cards
// ---------------------------------------------------------------------------

@Composable
private fun UserRow(user: UserDto, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val disabled = user.status == "disabled"
    val initial = (user.fullName ?: user.email).trim().take(1).uppercase()

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when (user.role) {
                                "admin" -> colors.error.copy(alpha = 0.16f)
                                "lecturer" -> colors.primary.copy(alpha = 0.16f)
                                else -> colors.surfaceElevated
                            }
                        )
                ) {
                    Text(
                        text = initial,
                        color = when (user.role) {
                            "admin" -> colors.error
                            "lecturer" -> colors.primary
                            else -> colors.textSecondary
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = user.fullName ?: "Unnamed account",
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = user.email,
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Pill(
                    text = user.role.uppercase(),
                    color = when (user.role) {
                        "admin" -> colors.error
                        "lecturer" -> colors.primary
                        else -> colors.textSecondary
                    }
                )
            }

            val tags = buildList {
                if (user.canCreateClasses) add("Can create classes")
                if (disabled) add("Disabled")
                if (user.indexNumber?.isNotBlank() == true) add(user.indexNumber)
            }
            if (tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        Text(
                            text = tag,
                            color = if (tag == "Disabled") colors.error else colors.textTertiary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(colors.surfaceElevated)
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(feature: FeatureDto, isBusy: Boolean, onToggle: () -> Unit) {
    val colors = AppTheme.colors
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (feature.enabled) colors.primary.copy(alpha = 0.3f) else colors.cardBorder, RoundedCornerShape(12.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = feature.label.ifBlank { feature.key },
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (feature.critical) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Pill(text = if (feature.enabled) "CORE" else "OFF", color = colors.warning)
                    }
                }
                if (feature.description.isNotBlank()) {
                    Text(
                        text = feature.description,
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (isBusy) {
                CircularProgressIndicator(color = colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Switch(
                    checked = feature.enabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = colors.primary,
                        uncheckedThumbColor = colors.textTertiary,
                        uncheckedTrackColor = colors.surfaceElevated
                    )
                )
            }
        }
    }
}

@Composable
private fun SettingRow(setting: SettingDto, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.cardBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = setting.label.ifBlank { setting.key },
                        color = colors.textPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (setting.secret) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Pill(text = "SECRET", color = colors.warning)
                    }
                }
                Text(
                    text = when {
                        setting.secret && setting.isSet -> "Configured — tap to replace"
                        setting.secret -> "Not set"
                        setting.value.isNullOrBlank() -> "Empty"
                        else -> setting.value
                    },
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = colors.textTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun UserEditDialog(
    user: UserDto,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (AdminUserPatchRequest) -> Unit
) {
    val colors = AppTheme.colors
    var role by remember(user.id) { mutableStateOf(user.role) }
    var canCreate by remember(user.id) { mutableStateOf(user.canCreateClasses) }
    var disabled by remember(user.id) { mutableStateOf(user.status == "disabled") }

    Dialog(
        onDismissRequest = onDismiss,
        // Without this the window is pinned to the platform default (~300dp) and
        // the role pills, switches and Save button are all crushed together.
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 520.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = user.fullName ?: "Account",
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(text = user.email, color = colors.textSecondary, fontSize = 11.sp)

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "ROLE",
                    color = colors.textTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("student" to "Student", "lecturer" to "Lecturer", "admin" to "Admin").forEach { (value, label) ->
                        FilterPill(label = label, selected = role == value, onClick = { role = value })
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                SwitchLine(
                    title = "Can create classes",
                    subtitle = "Host without being a lecturer",
                    checked = canCreate,
                    onCheckedChange = { canCreate = it }
                )
                HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 6.dp))
                SwitchLine(
                    title = "Account disabled",
                    subtitle = "Blocks sign-in immediately",
                    checked = disabled,
                    onCheckedChange = { disabled = it },
                    danger = true
                )

                Spacer(modifier = Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Cancel", color = colors.textSecondary, fontSize = 13.sp)
                    }
                    Button(
                        onClick = {
                            // Send only what changed: an untouched field should
                            // not be rewritten by the app on the admin's behalf.
                            val patch = AdminUserPatchRequest(
                                role = role.takeIf { it != user.role },
                                canCreateClasses = canCreate.takeIf { it != user.canCreateClasses },
                                status = (if (disabled) "disabled" else "active")
                                    .takeIf { (it == "disabled") != (user.status == "disabled") }
                            )
                            if (patch.role == null && patch.canCreateClasses == null && patch.status == null) {
                                onDismiss()
                            } else {
                                onSave(patch)
                            }
                        },
                        enabled = !isSaving,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1.4f)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Text("Save changes", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingEditDialog(
    setting: SettingDto,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val colors = AppTheme.colors
    var value by remember(setting.key) { mutableStateOf(setting.value ?: "") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 520.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = setting.label.ifBlank { setting.key },
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                if (setting.help.isNotBlank()) {
                    Text(text = setting.help, color = colors.textSecondary, fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(if (setting.secret) "New value (blank keeps current)" else "Value") },
                    visualTransformation = if (setting.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (setting.type == "integer") KeyboardType.Number else KeyboardType.Text
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.cardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Cancel", color = colors.textSecondary, fontSize = 13.sp)
                    }
                    Button(
                        onClick = { onSave(value) },
                        enabled = !isSaving,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1.4f)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        } else {
                            Text("Save", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Small shared pieces
// ---------------------------------------------------------------------------

@Composable
private fun TabChip(tab: AdminTab, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.primary else colors.surface)
            .border(1.dp, if (selected) colors.primary else colors.cardBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = if (selected) Color.White else colors.textSecondary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = tab.label,
            color = if (selected) Color.White else colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Text(
        text = label,
        color = if (selected) Color.White else colors.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.primary else colors.surfaceElevated)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 0.8.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun KpiCard(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = modifier.border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    color = colors.textSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.7.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = value, color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceElevated)
            .padding(vertical = 9.dp, horizontal = 10.dp)
    ) {
        Text(text = value, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = colors.textSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(text = label, color = colors.textSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = colors.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun SectionCard(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    val colors = AppTheme.colors
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SwitchLine(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    danger: Boolean = false
) {
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (danger && checked) colors.error else colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = subtitle, color = colors.textSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = if (danger) colors.error else colors.primary,
                uncheckedThumbColor = colors.textTertiary,
                uncheckedTrackColor = colors.surfaceElevated
            )
        )
    }
}

@Composable
private fun LoadingRow() {
    val colors = AppTheme.colors
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = colors.primary, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    val colors = AppTheme.colors
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.error.copy(alpha = 0.1f)),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.error.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Text(text = message, color = colors.error, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Text("Retry", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun navItemColors(selected: Color, unselected: Color) = NavigationBarItemDefaults.colors(
    selectedIconColor = Color.White,
    selectedTextColor = selected,
    indicatorColor = selected,
    unselectedIconColor = unselected,
    unselectedTextColor = unselected
)
