package online.podiumclass.app.ui.screens.classroom

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import online.podiumclass.app.data.api.models.MaterialDto
import online.podiumclass.app.ui.theme.AppTheme

/**
 * The class shelf.
 *
 * Same list the web classroom shows: the file lives on the server (or the link
 * points wherever it points), so it survives a refresh, a reconnect, or a
 * different device. The host and co-hosts can add to it; students can only
 * read and open.
 */
@Composable
fun MaterialsSheet(
    materials: List<MaterialDto>,
    canManage: Boolean,
    isSharing: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onShareLink: (title: String, url: String) -> Unit,
    onShareFile: (bytes: ByteArray, fileName: String, mime: String, title: String) -> Unit,
    onDelete: (String) -> Unit
) {
    val colors = AppTheme.colors
    val context = LocalContext.current

    var showForm by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("file") } // "file" or "link"
    var title by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    // 50 MB is generous for a document and still safe to hold in memory while
    // the multipart body is built on a phone.
    val maxBytes = 50L * 1024L * 1024L

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        localError = null
        val resolver = context.contentResolver

        var displayName = "document"
        var sizeBytes: Long? = null
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }

        if (sizeBytes != null && sizeBytes!! > maxBytes) {
            localError = "That file is larger than 50 MB. Share it as a link instead."
            return@rememberLauncherForActivityResult
        }

        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            localError = "That file could not be read."
            return@rememberLauncherForActivityResult
        }
        if (bytes.size > maxBytes) {
            localError = "That file is larger than 50 MB. Share it as a link instead."
            return@rememberLauncherForActivityResult
        }

        val mime = resolver.getType(uri) ?: "application/octet-stream"
        onShareFile(bytes, displayName, mime, title.trim().ifBlank { displayName })
        title = ""
        showForm = false
    }

    fun open(material: MaterialDto) {
        val ok = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(material.url)))
        }.isSuccess
        if (!ok) {
            Toast.makeText(context, "Nothing on this phone can open that file.", Toast.LENGTH_LONG).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // The shelf is a list, so it takes the width the screen has (94%, capped
        // on tablets) rather than the platform dialog default of ~300dp.
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 720.dp)
                .fillMaxHeight(0.92f)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(24.dp))
        ) {
            Column(modifier = Modifier.fillMaxHeight().padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.primary.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Class materials",
                                color = colors.textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                "${materials.size} shared resource${if (materials.size == 1) "" else "s"}",
                                color = colors.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (canManage) {
                            IconButton(onClick = { showForm = !showForm; localError = null }) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Share a resource",
                                    tint = colors.primary
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.textSecondary)
                        }
                    }
                }

                if (showForm && canManage) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surfaceElevated)
                            .padding(4.dp)
                    ) {
                        listOf("file" to "Upload file", "link" to "Paste link").forEach { (key, label) ->
                            val selected = mode == key
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) colors.primary else Color.Transparent)
                                    .clickable { mode = key; localError = null }
                                    .padding(vertical = 7.dp)
                            ) {
                                Text(
                                    label,
                                    color = if (selected) Color.White else colors.textSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = { Text("Title (optional)", color = colors.textTertiary, fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.cardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    if (mode == "file") {
                        Button(
                            onClick = { filePicker.launch("*/*") },
                            enabled = !isSharing,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isSharing) "Uploading..." else "Choose a file", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "The file is stored on the server, so it survives a refresh.",
                            color = colors.textTertiary,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    } else {
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            placeholder = { Text("https://…", color = colors.textTertiary, fontSize = 12.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary,
                                focusedBorderColor = colors.primary,
                                unfocusedBorderColor = colors.cardBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (!link.trim().startsWith("http")) {
                                    localError = "Enter a full http(s) link."
                                } else {
                                    onShareLink(title.trim().ifBlank { link.trim() }, link.trim())
                                    title = ""
                                    link = ""
                                    showForm = false
                                }
                            },
                            enabled = !isSharing && link.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isSharing) "Sharing..." else "Share link", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                val message = localError ?: error
                if (!message.isNullOrBlank()) {
                    Text(
                        message,
                        color = Color(0xFFE5484D),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (materials.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "Nothing shared yet",
                                color = colors.textSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        items(materials, key = { it.id }) { material ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.surfaceElevated)
                                    .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                                    .clickable { open(material) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(colors.primary.copy(alpha = 0.12f))
                                ) {
                                    Icon(
                                        if (material.type == "link") Icons.Default.Link else Icons.Default.AttachFile,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        material.title,
                                        color = colors.textPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        listOf(material.type.uppercase(), material.size)
                                            .filter { it.isNotBlank() }
                                            .joinToString(" • "),
                                        color = colors.textSecondary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Icon(
                                    Icons.Default.OpenInNew,
                                    contentDescription = "Open",
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                if (canManage) {
                                    TextButton(
                                        onClick = { onDelete(material.id) },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Remove",
                                            tint = colors.textTertiary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (isSharing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = colors.primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Working…", color = colors.textSecondary, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
