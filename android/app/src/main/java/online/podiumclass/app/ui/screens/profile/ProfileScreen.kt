package online.podiumclass.app.ui.screens.profile

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.podiumclass.app.data.api.models.UpdateProfileRequest
import online.podiumclass.app.data.api.models.UserDto
import online.podiumclass.app.ui.components.RemoteAvatar
import online.podiumclass.app.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    user: UserDto?,
    themeMode: String,
    onSaveProfile: (UpdateProfileRequest) -> Unit,
    onThemeChange: (String) -> Unit,
    onLogout: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateAttendance: () -> Unit,
    onOpenAbout: () -> Unit = {},
    isUploadingPhoto: Boolean = false,
    onUploadPhoto: (bytes: ByteArray, fileName: String, mime: String) -> Unit = { _, _, _ -> }
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    var photoError by remember { mutableStateOf<String?>(null) }
    val maxPhotoBytes = 5 * 1024 * 1024

    // A picture has to outlive the handset and the session: the bytes go to the
    // server, which answers with a new versioned photoUrl.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        photoError = null
        val resolver = context.contentResolver
        var name = "avatar.jpg"
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index)?.let { name = it }
                }
            }
        }
        val mime = resolver.getType(uri) ?: "image/jpeg"
        if (!mime.startsWith("image/")) {
            photoError = "Choose a JPEG, PNG or WebP image."
            return@rememberLauncherForActivityResult
        }
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            photoError = "That image could not be read."
            return@rememberLauncherForActivityResult
        }
        if (bytes.size > maxPhotoBytes) {
            photoError = "That image is larger than 5 MB."
            return@rememberLauncherForActivityResult
        }
        onUploadPhoto(bytes, name, mime)
    }

    var fullName by remember(user) { mutableStateOf(user?.fullName ?: "") }
    var indexNumber by remember(user) { mutableStateOf(user?.indexNumber ?: "") }
    var phone by remember(user) { mutableStateOf(user?.phone ?: "") }
    var year by remember(user) { mutableStateOf(user?.year ?: "Level 100") }
    var university by remember(user) { mutableStateOf(user?.university ?: "") }
    var bio by remember(user) { mutableStateOf(user?.bio ?: "") }

    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var showYearDropdown by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    val yearOptions = listOf(
        "Level 100 / Year 1",
        "Level 200 / Year 2",
        "Level 300 / Year 3",
        "Level 400 / Year 4",
        "Postgraduate / Masters"
    )

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
                    selected = true,
                    onClick = {},
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
            // Header
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Student Profile",
                    color = colors.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "Manage your academic details and app preferences",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Avatar & Identity Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Box(modifier = Modifier.size(72.dp)) {
                            RemoteAvatar(
                                url = user?.photoUrl,
                                fallback = user?.fullName ?: "S",
                                size = 72.dp,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .clickable(enabled = !isUploadingPhoto) {
                                        photoPicker.launch("image/*")
                                    }
                            )
                            // Tap the picture or the small badge: both open the
                            // system image picker.
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(colors.surfaceElevated)
                                    .border(1.dp, colors.cardBorder, CircleShape)
                                    .clickable(enabled = !isUploadingPhoto) {
                                        photoPicker.launch("image/*")
                                    }
                            ) {
                                if (isUploadingPhoto) {
                                    CircularProgressIndicator(
                                        color = colors.primary,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(12.dp)
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Change profile picture",
                                        tint = colors.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user?.fullName ?: "Student",
                                color = colors.textPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = user?.email ?: "",
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                color = colors.primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "STUDENT ACCOUNT",
                                    color = colors.primary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Photo failure: the upload never reached the server, so say why
            // instead of leaving the old picture with no explanation.
            if (!photoError.isNullOrBlank()) {
                item {
                    Text(
                        text = photoError ?: "",
                        color = Color(0xFFE5484D),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Save Confirmation Banner
            if (!saveMessage.isNullOrBlank()) {
                item {
                    Surface(
                        color = colors.success.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.success.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = colors.success,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = saveMessage ?: "",
                                color = colors.success,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Academic & Personal Details Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "EDIT ACADEMIC DETAILS",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        // 1. Full Name
                        Column {
                            Text("Full Name", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = fullName,
                                onValueChange = { fullName = it },
                                placeholder = { Text("Enter your full name", color = colors.textTertiary, fontSize = 13.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary,
                                    focusedBorderColor = colors.primary,
                                    unfocusedBorderColor = colors.cardBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 2. Index Number / Student ID
                        Column {
                            Text("Index Number / Student ID", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = indexNumber,
                                onValueChange = { indexNumber = it },
                                placeholder = { Text("e.g. 10982341", color = colors.textTertiary, fontSize = 13.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary,
                                    focusedBorderColor = colors.primary,
                                    unfocusedBorderColor = colors.cardBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 3. Contact(s)
                        Column {
                            Text("Contact Number(s)", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = phone,
                                onValueChange = { phone = it },
                                placeholder = { Text("e.g. +233 55 123 4567", color = colors.textTertiary, fontSize = 13.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary,
                                    focusedBorderColor = colors.primary,
                                    unfocusedBorderColor = colors.cardBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 4. Academic Year / Level
                        Column {
                            Text("Academic Year / Level", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Box {
                                OutlinedTextField(
                                    value = year,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = {
                                        IconButton(onClick = { showYearDropdown = !showYearDropdown }) {
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Year", tint = colors.textSecondary)
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = colors.textPrimary,
                                        unfocusedTextColor = colors.textPrimary,
                                        focusedBorderColor = colors.primary,
                                        unfocusedBorderColor = colors.cardBorder
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showYearDropdown = true }
                                )
                                DropdownMenu(
                                    expanded = showYearDropdown,
                                    onDismissRequest = { showYearDropdown = false },
                                    modifier = Modifier.background(colors.surfaceElevated)
                                ) {
                                    yearOptions.forEach { opt ->
                                        DropdownMenuItem(
                                            text = { Text(opt, color = colors.textPrimary, fontSize = 13.sp) },
                                            onClick = {
                                                year = opt
                                                showYearDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // 5. University / Campus
                        Column {
                            Text("University / Campus", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = university,
                                onValueChange = { university = it },
                                placeholder = { Text("e.g. University of Ghana", color = colors.textTertiary, fontSize = 13.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary,
                                    focusedBorderColor = colors.primary,
                                    unfocusedBorderColor = colors.cardBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 6. Bio
                        Column {
                            Text("Bio", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = bio,
                                onValueChange = { bio = it },
                                placeholder = { Text("Tell your lecturers about your academic focus...", color = colors.textTertiary, fontSize = 13.sp) },
                                minLines = 2,
                                maxLines = 4,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary,
                                    focusedBorderColor = colors.primary,
                                    unfocusedBorderColor = colors.cardBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Save Button
                        Button(
                            onClick = {
                                isSaving = true
                                saveMessage = null
                                onSaveProfile(
                                    UpdateProfileRequest(
                                        fullName = fullName.trim(),
                                        indexNumber = indexNumber.trim(),
                                        phone = phone.trim(),
                                        year = year.trim(),
                                        university = university.trim(),
                                        bio = bio.trim()
                                    )
                                )
                                isSaving = false
                                saveMessage = "Profile updated successfully!"
                            },
                            enabled = !isSaving,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Save Changes", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }

            // Appearance / Theme Section (Requirement 1: Light Mode)
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "APP THEME & APPEARANCE",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(
                                Triple("light", "Light Mode", Icons.Default.LightMode),
                                Triple("dark", "Dark Mode", Icons.Default.DarkMode),
                                Triple("system", "System", Icons.Default.SettingsBrightness)
                            ).forEach { (mode, label, icon) ->
                                val isSelected = themeMode.equals(mode, ignoreCase = true)
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) colors.primary else colors.surfaceElevated)
                                        .border(
                                            1.dp,
                                            if (isSelected) colors.primary else colors.cardBorder,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable { onThemeChange(mode) }
                                        .padding(vertical = 12.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            icon,
                                            contentDescription = label,
                                            tint = if (isSelected) Color.White else colors.textSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = label,
                                            color = if (isSelected) Color.White else colors.textPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // About: version, contact and the web link.
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "ABOUT",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpenAbout() }
                                .padding(vertical = 10.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "About Podium",
                                    color = colors.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Version, contact and the web app",
                                    color = colors.textSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Account Actions (Sign Out)
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "ACCOUNT SESSION",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )

                        OutlinedButton(
                            onClick = { showLogoutDialog = true },
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.error.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Icon(Icons.Default.ExitToApp, contentDescription = "Sign Out", tint = colors.error, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign Out of Account", color = colors.error, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Sign Out", fontWeight = FontWeight.Bold, color = colors.textPrimary) },
            text = { Text("Are you sure you want to sign out of PodiumClass on this device?", color = colors.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error)
                ) {
                    Text("Sign Out", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
            containerColor = colors.surfaceElevated
        )
    }
}
