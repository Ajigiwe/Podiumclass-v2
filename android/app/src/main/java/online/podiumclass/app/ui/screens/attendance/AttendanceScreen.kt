package online.podiumclass.app.ui.screens.attendance

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
import online.podiumclass.app.data.api.models.StudentAttendanceRecordDto
import online.podiumclass.app.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceScreen(
    records: List<StudentAttendanceRecordDto>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateProfile: () -> Unit
) {
    val colors = AppTheme.colors
    var activeFilter by remember { mutableStateOf("all") } // "all", "verified"
    var detailRecord by remember { mutableStateOf<StudentAttendanceRecordDto?>(null) }

    // Records come from the attendance API, one row per class this student
    // joined. They were previously derived from the class list, so the screen
    // had nothing to show but the class's own never-written participant count --
    // a student could answer every check and still read zero.
    // A class that is still running is not a record yet. It appears here once
    // it ends, with the time this student actually spent in it -- this page is
    // a register, not a second doorway into the room.
    val attendedSessions = remember(records) {
        records.filter { !it.status.equals("active", ignoreCase = true) }
    }

    // Time this student actually spent in class, not the class's booked length.
    val totalDurationMinutes = remember(attendedSessions) {
        attendedSessions.sumOf { it.stayMinutes ?: 0 }
    }

    val hoursSpent = totalDurationMinutes / 60
    val remainingMinutes = totalDurationMinutes % 60
    val timeSpentFormatted = if (hoursSpent > 0) "${hoursSpent}h ${remainingMinutes}m" else "${remainingMinutes}m"

    val verifiedCount = remember(attendedSessions) {
        attendedSessions.count { it.present }
    }

    val filteredList = remember(attendedSessions, activeFilter) {
        when (activeFilter) {
            "verified" -> attendedSessions.filter { it.present }
            else -> attendedSessions
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
                    icon = { Icon(Icons.Default.School, contentDescription = "Home") },
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
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "Attendance Records",
                            color = colors.textPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            text = "Classes attended, time spent & verification",
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    IconButton(
                        onClick = onRefresh,
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

            // Stat Cards (Classes Attended, Verified, Time Spent)
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Card 1: Classes Attended
                    AttendanceStatCard(
                        label = "ATTENDED",
                        value = "${attendedSessions.size}",
                        caption = "Classes joined",
                        icon = Icons.Default.FactCheck,
                        accent = colors.primary,
                        modifier = Modifier.weight(1f)
                    )

                    // Card 2: Verified present
                    AttendanceStatCard(
                        label = "VERIFIED",
                        value = "$verifiedCount",
                        caption = "Checks answered",
                        icon = Icons.Default.VerifiedUser,
                        accent = colors.success,
                        modifier = Modifier.weight(1f)
                    )

                    // Card 3: Total Time Spent
                    AttendanceStatCard(
                        label = "TIME SPENT",
                        value = timeSpentFormatted,
                        caption = "In class",
                        icon = Icons.Default.AccessTime,
                        accent = colors.success,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Tab Filter Bar
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceElevated)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "all" to "All Classes",
                        "verified" to "Verified"
                    ).forEach { (key, label) ->
                        val isSelected = activeFilter == key
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) colors.primary else Color.Transparent)
                                .clickable { activeFilter = key }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.White else colors.textSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // List of Attended Classes
            if (isLoading) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp)
                    ) {
                        CircularProgressIndicator(color = colors.primary)
                    }
                }
            } else if (filteredList.isEmpty()) {
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
                                .padding(vertical = 40.dp, horizontal = 20.dp)
                        ) {
                            Icon(
                                Icons.Default.FactCheck,
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No Attendance Records Found",
                                color = colors.textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Join or attend a lecture to start accumulating attendance records.",
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            } else {
                items(filteredList) { record ->
                    val isArchived = record.isDeleted ||
                        record.status.equals("deleted", ignoreCase = true)
                    val spentMin = record.stayMinutes ?: 0
                    val spentStr = when {
                        spentMin <= 0 -> "Not attended"
                        spentMin >= 60 -> "${spentMin / 60}h ${spentMin % 60}m"
                        else -> "${spentMin}m"
                    }

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                            .clickable { detailRecord = record }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = record.title,
                                        color = colors.textPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${record.course ?: "General"} • ${record.lecturerName ?: "Host"}",
                                        color = colors.textSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }

                                val badgeColor = if (record.present) colors.success else colors.textSecondary
                                Surface(
                                    color = badgeColor.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        badgeColor.copy(alpha = 0.3f)
                                    )
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(badgeColor)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = when {
                                                isArchived -> "ARCHIVED"
                                                record.present -> "VERIFIED"
                                                else -> "PENDING"
                                            },
                                            color = badgeColor,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = colors.cardBorder, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Time this student spent in the class
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Schedule,
                                            contentDescription = null,
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = spentStr,
                                            color = colors.textPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    // Checks this student answered
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.VerifiedUser,
                                            contentDescription = null,
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${record.verificationsCompleted}/${record.verificationsSent} checks answered",
                                            color = colors.textSecondary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                // Details only: a finished class cannot be
                                // re-entered, so this must not look like a door.
                                TextButton(
                                    onClick = { detailRecord = record },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Details →",
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

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // The record itself: date, host, how long the class ran and how long this
    // student stayed. No navigation back into the room.
    detailRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { detailRecord = null },
            containerColor = colors.surface,
            title = {
                Column {
                    Text(
                        record.title,
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        record.course ?: "Class record",
                        color = colors.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow("Host", record.lecturerName ?: "—", colors)
                    DetailRow("Date", formatMoment(record.createdAt, "MMM d, yyyy"), colors)
                    DetailRow("Started", formatMoment(record.startedAt, "h:mm a"), colors)
                    DetailRow(
                        "Ended",
                        record.endedAt?.let { formatMoment(it, "h:mm a") } ?: "—",
                        colors
                    )
                    DetailRow(
                        "Class ran",
                        record.actualDurationMinutes?.let { formatMinutes(it) } ?: "—",
                        colors
                    )
                    DetailRow(
                        "You joined",
                        record.joinedAt?.let { formatMoment(it, "h:mm a") } ?: "—",
                        colors
                    )
                    DetailRow(
                        "You left",
                        record.leftAt?.let { formatMoment(it, "h:mm a") } ?: "At the end",
                        colors
                    )
                    DetailRow("Time in class", formatMinutes(record.stayMinutes ?: 0), colors)
                    DetailRow(
                        "Presence checks",
                        "${record.verificationsCompleted} of ${record.verificationsSent}",
                        colors
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "This class has ended. The record is kept on your account even if the class " +
                            "leaves your dashboard, and it cannot be re-entered.",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { detailRecord = null }) {
                    Text("Close", color = colors.primary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    colors: online.podiumclass.app.ui.theme.PodiumColors
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = colors.textSecondary, fontSize = 12.sp)
        Text(value, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatMoment(raw: String?, pattern: String): String {
    if (raw.isNullOrBlank()) return "—"
    val moment = runCatching { java.time.OffsetDateTime.parse(raw) }.getOrNull() ?: return "—"
    return moment.format(
        java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.US)
    )
}

private fun formatMinutes(total: Int): String {
    if (total <= 0) return "0m"
    val hours = total / 60
    val minutes = total % 60
    return when {
        hours <= 0 -> "${minutes}m"
        minutes == 0 -> "${hours}h"
        else -> "${hours}h ${minutes}m"
    }
}

@Composable
private fun AttendanceStatCard(
    label: String,
    value: String,
    caption: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = modifier.border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Text(
                    text = label,
                    color = colors.textSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = value,
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = caption,
                color = colors.textSecondary,
                fontSize = 10.sp
            )
        }
    }
}
