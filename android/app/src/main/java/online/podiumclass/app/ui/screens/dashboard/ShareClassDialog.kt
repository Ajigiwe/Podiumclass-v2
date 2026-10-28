package online.podiumclass.app.ui.screens.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import online.podiumclass.app.data.api.models.SessionDto
import online.podiumclass.app.ui.theme.*

/**
 * Shown right after a class is created.
 *
 * A new class does *not* open: it is created closed, and the server only opens
 * it when its host walks in. That makes this moment the host's chance to hand
 * out the code or the link, so the dialog is the code and the ways to share it
 * -- plus the button that actually starts the class, for a host who is ready.
 */
@Composable
fun ShareClassDialog(
    session: SessionDto,
    apiUrl: String,
    onDismiss: () -> Unit,
    onStart: () -> Unit
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val code = session.meetingCode ?: ""
    val link = inviteLink(session.id, apiUrl)

    Dialog(
        onDismissRequest = onDismiss,
        // Without this the window is pinned to the platform default (~300dp)
        // and this card can never use the width the phone actually has.
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 460.dp)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Class created",
                    color = colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = session.title,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                        .padding(vertical = 14.dp)
                ) {
                    Text(
                        text = "MEETING CODE",
                        color = colors.textTertiary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = code.ifBlank { "--" },
                        color = colors.primary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Text(
                    text = "Students join with this code or the link, and wait in the lobby. " +
                        "The class starts the moment you enter it.",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Start
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineAction(
                        icon = Icons.Default.ContentCopy,
                        label = "Copy code",
                        modifier = Modifier.weight(1f)
                    ) { copyToClipboard(context, "Meeting code", code) }

                    OutlineAction(
                        icon = Icons.Default.Link,
                        label = "Copy link",
                        modifier = Modifier.weight(1f)
                    ) { copyToClipboard(context, "Invite link", link) }
                }

                OutlineAction(
                    icon = Icons.Default.Share,
                    label = "Share invite",
                    modifier = Modifier.fillMaxWidth()
                ) { shareInvite(context, session, code, link) }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Later", color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            onDismiss()
                            onStart()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Start class", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlineAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceElevated)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = colors.primary, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text(text = label, color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * A shareable invite link, built from the API base URL.
 *
 * The phone knows where the API lives, not where the web app is served. In a
 * deployment the two share an origin, so the API base is reduced to its origin;
 * the one exception is the development API port, whose web app runs beside it.
 */
internal fun inviteLink(sessionId: String, apiUrl: String): String {
    val origin = apiUrl.trim().trimEnd('/').substringBefore("/api/").trimEnd('/')
    val base = if (origin.endsWith(":8000")) origin.dropLast(4) + "5173" else origin
    return "$base/classroom/$sessionId"
}

/** Also used by the dashboard's class-code chip, so copying reads the same everywhere. */
internal fun copyToClipboard(context: Context, label: String, value: String) {
    if (value.isBlank()) {
        Toast.makeText(context, "Nothing to copy yet.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "$label copied.", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not copy: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}

private fun shareInvite(context: Context, session: SessionDto, code: String, link: String) {
    try {
        val text = buildString {
            append(session.title)
            append("\nJoin the class with code ")
            append(code)
            append("\n")
            append(link)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, session.title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share class invite"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}
