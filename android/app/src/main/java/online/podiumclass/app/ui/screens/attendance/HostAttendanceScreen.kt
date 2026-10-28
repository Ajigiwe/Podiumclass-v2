package online.podiumclass.app.ui.screens.attendance

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.podiumclass.app.data.api.ApiClient
import online.podiumclass.app.data.api.models.AttendanceRecordDto
import online.podiumclass.app.data.api.models.AttendanceRosterDto
import online.podiumclass.app.data.api.models.SessionDto
import online.podiumclass.app.data.storage.TokenStorage
import online.podiumclass.app.ui.theme.AppTheme
import online.podiumclass.app.ui.theme.PodiumColors
import java.io.File

/**
 * A lecturer's attendance desk on the phone: one card per class, opening onto
 * the register. Each register lists every student who joined, when they joined
 * and left, how long they stayed and the checks they answered -- and can be
 * shared as a CSV or an Excel file straight from the phone.
 */
@Composable
fun HostAttendanceScreen(
    apiUrl: String,
    userId: String,
    isAdmin: Boolean,
    onNavigateHome: () -> Unit,
    onNavigateProfile: () -> Unit
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(apiUrl) { ApiClient.create(TokenStorage(context), apiUrl) }

    var sessions by remember { mutableStateOf<List<SessionDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var listError by remember { mutableStateOf<String?>(null) }

    // includeDeleted: a class removed from the dashboard keeps its register.
    // Scoped to the classes this person runs.
    suspend fun fetchSessions() {
        isLoading = true
        listError = null
        try {
            val all = api.getSessions(includeDeleted = true).documents
            sessions = if (isAdmin) all else all.filter { it.hostId == userId }
        } catch (e: Exception) {
            listError = e.message ?: "Could not load your classes."
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { fetchSessions() }

    var selected by remember { mutableStateOf<SessionDto?>(null) }
    var roster by remember { mutableStateOf<AttendanceRosterDto?>(null) }
    var isLoadingRoster by remember { mutableStateOf(false) }
    var rosterError by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected) {
        val session = selected ?: return@LaunchedEffect
        isLoadingRoster = true
        rosterError = null
        roster = null
        try {
            roster = api.getAttendanceRoster(session.id).document
        } catch (e: Exception) {
            rosterError = e.message ?: "Could not load this register."
        } finally {
            isLoadingRoster = false
        }
    }

    fun export(session: SessionDto, format: String) {
        if (isExporting != null) return
        isExporting = format
        scope.launch {
            try {
                val body = api.exportAttendance(session.id, format)
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val slug = session.title.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifBlank { "class" }
                val file = File(dir, "attendance-$slug.$format")
                withContext(Dispatchers.IO) { file.writeBytes(body.bytes()) }
                body.close()

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = if (format == "xlsx")
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    else
                        "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share attendance register"))
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    e.message ?: "Could not build that file.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isExporting = null
            }
        }
    }

    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            NavigationBar(
                containerColor = colors.surface,
                contentColor = colors.textPrimary,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateHome,
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
                    selected = true,
                    onClick = {},
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
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            "Attendance",
                            color = colors.textPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            "Registers for the classes you run",
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { fetchSessions() } },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceElevated)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (isLoading && sessions.isEmpty()) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)
                    ) {
                        CircularProgressIndicator(color = colors.primary)
                    }
                }
            } else if (sessions.isEmpty()) {
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
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)
                        ) {
                            Icon(
                                Icons.Default.FactCheck,
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "No class registers yet",
                                color = colors.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                listError ?: "Run a class and its register will appear here.",
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            } else {
                items(sessions, key = { it.id }) { session ->
                    val isLive = session.status.equals("active", ignoreCase = true)
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                            .clickable {
                                selected = session
                                roster = null
                            }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        session.title,
                                        color = colors.textPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${session.course ?: "General"} • ${formatHostMoment(session.startedAt ?: session.createdAt, "MMM d, yyyy")}",
                                        color = colors.textSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                if (isLive) {
                                    Surface(
                                        color = colors.primary.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "LIVE",
                                            color = colors.primary,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = colors.cardBorder)
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "${session.presentCount} of ${session.attendeeCount} present",
                                    color = if (session.attendeeCount > 0) colors.success else colors.textSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(
                                    onClick = {
                                        selected = session
                                        roster = null
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        "View register →",
                                        color = colors.primary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    selected?.let { session ->
        // On a 360dp phone the sheet leaves about 109dp for the name column, less
        // than the index number plus both clock times need -- and the times were
        // what got ellipsised. Below 380dp the row drops the index number and
        // shortens the check count so both times stay whole.
        val compactRegister = LocalConfiguration.current.screenWidthDp < 380
        Dialog(
            onDismissRequest = { selected = null },
            // The register is a small data table, so it takes the width the
            // screen actually has (94%, capped at 720dp on tablets) instead of
            // the platform dialog default of ~300dp, which crushed every row.
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .widthIn(max = 720.dp)
                    .fillMaxHeight(0.92f)
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(22.dp))
            ) {
                Column(modifier = Modifier.fillMaxHeight().padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                session.title,
                                color = colors.textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                buildString {
                                    append(roster?.summary?.present ?: session.presentCount)
                                    append(" of ")
                                    append(roster?.summary?.total ?: session.attendeeCount)
                                    append(" present")
                                    // Which class a register belongs to is not
                                    // obvious when the title repeats weekly.
                                    val whenRun = formatHostMoment(session.startedAt, "EEE d MMM, HH:mm")
                                    if (whenRun != "—") {
                                        append("   ·   ")
                                        append(whenRun)
                                    }
                                },
                                color = colors.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = { selected = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Compact, right-aligned actions: stretched across a wide
                    // sheet they read as two giant bars rather than buttons.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = { export(session, "csv") },
                            enabled = isExporting == null,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.surfaceElevated,
                                contentColor = colors.textPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.width(112.dp)
                        ) {
                            Text(
                                if (isExporting == "csv") "…" else "Export CSV",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                        Button(
                            onClick = { export(session, "xlsx") },
                            enabled = isExporting == null,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.surfaceElevated,
                                contentColor = colors.textPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.width(112.dp)
                        ) {
                            Text(
                                if (isExporting == "xlsx") "…" else "Export Excel",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    val allRecords = roster?.records ?: emptyList()
                    val presentCount = allRecords.count { it.present }
                    val shortCount = allRecords.size - presentCount
                    var registerFilter by remember(session.id) { mutableStateOf("all") }
                    val visibleRecords = when (registerFilter) {
                        "present" -> allRecords.filter { it.present }
                        "short" -> allRecords.filter { !it.present }
                        else -> allRecords
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    when {
                        isLoadingRoster -> Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) { CircularProgressIndicator(color = colors.primary) }

                        rosterError != null -> Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp)
                        ) {
                            Text(
                                rosterError ?: "",
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }

                        allRecords.isEmpty() -> Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            Text("Nobody joined this class", color = colors.textSecondary, fontSize = 12.sp)
                        }

                        // One row per student inside a single bordered list: a full
                        // class reads as a register you can scan, rather than a
                        // stack of cards tall enough to scroll past everyone.
                        else -> Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RegisterFilterChip(
                                    "All", allRecords.size, registerFilter == "all", colors
                                ) { registerFilter = "all" }
                                RegisterFilterChip(
                                    "Present", presentCount, registerFilter == "present", colors
                                ) { registerFilter = "present" }
                                RegisterFilterChip(
                                    "Short", shortCount, registerFilter == "short", colors
                                ) { registerFilter = "short" }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (visibleRecords.isEmpty()) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp)
                                ) {
                                    Text(
                                        if (registerFilter == "present")
                                            "Nobody in this class is marked present."
                                        else
                                            "Everyone in this class is marked present.",
                                        color = colors.textSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(colors.surfaceElevated)
                                        .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                                ) {
                                    itemsIndexed(
                                        visibleRecords,
                                        key = { _, record -> record.studentId }
                                    ) { index, record ->
                                        RegisterRow(
                                            record = record,
                                            colors = colors,
                                            showDivider = index != visibleRecords.lastIndex,
                                            compact = compactRegister
                                        )
                                    }
                                }
                            }

                            Text(
                                "Present means the student answered at least half the checks, " +
                                    "or that no check was ever sent.",
                                color = colors.textTertiary,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisterRow(
    record: AttendanceRecordDto,
    colors: PodiumColors,
    showDivider: Boolean,
    compact: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    if (record.present) colors.primary.copy(alpha = 0.12f)
                    else colors.cardBorder.copy(alpha = 0.6f)
                )
        ) {
            Text(
                record.initials(),
                color = if (record.present) colors.primary else colors.textSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                record.studentName.ifBlank { "Student" },
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    if (record.indexNumber.isNotBlank() && !compact) {
                        append(record.indexNumber)
                        append("  ·  ")
                    }
                    append(registerClock(record.joinedAt))
                    append(" → ")
                    append(record.leftAt?.let { registerClock(it) } ?: "—")
                },
                color = colors.textSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                registerSpan(record.stayMinutes),
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                if (compact) {
                    "${record.verificationsCompleted}/${record.verificationsSent} chk"
                } else {
                    "${record.verificationsCompleted}/${record.verificationsSent} checks"
                },
                color = colors.textTertiary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 1.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (record.present) colors.success.copy(alpha = 0.14f)
                    else colors.cardBorder.copy(alpha = 0.7f)
                )
                .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Text(
                if (record.present) "PRESENT" else "SHORT",
                color = if (record.present) colors.success else colors.textSecondary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.4.sp
            )
        }
    }

    if (showDivider) {
        HorizontalDivider(
            color = colors.cardBorder.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 56.dp)
        )
    }
}

@Composable
private fun RegisterFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    colors: PodiumColors,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) colors.primary else colors.surfaceElevated,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        ) {
            Text(
                label,
                color = if (selected) Color.White else colors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                count.toString(),
                color = if (selected) Color.White.copy(alpha = 0.8f) else colors.textTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

private fun AttendanceRecordDto.initials(): String {
    val parts = studentName.trim().split(" ").filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts.first().take(2).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}

/** Clock time only: two of them sit side by side on a narrow register row. */
private fun registerClock(raw: String?): String = formatHostMoment(raw, "HH:mm")

private fun registerSpan(minutes: Int?): String {
    if (minutes == null) return "—"
    if (minutes < 60) return "${minutes}m"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) "${hours}h" else "${hours}h ${rest}m"
}

private fun formatHostMoment(raw: String?, pattern: String): String {
    if (raw.isNullOrBlank()) return "—"
    val moment = runCatching { java.time.OffsetDateTime.parse(raw) }.getOrNull() ?: return "—"
    return moment.format(
        java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.US)
    )
}
