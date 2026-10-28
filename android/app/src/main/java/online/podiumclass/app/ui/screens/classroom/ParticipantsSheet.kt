package online.podiumclass.app.ui.screens.classroom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import online.podiumclass.app.data.livekit.ParticipantTileState
import online.podiumclass.app.ui.theme.*

/**
 * The full roster, for everyone.
 *
 * The stage draws a capped number of tiles, so this is where the rest of a
 * large class actually lives. It is read-only on purpose: a student should be
 * able to see who is in the room without being handed moderation controls, and
 * hosts already have those in the moderation sheet.
 *
 * Rendered with a LazyColumn, so 200 people cost a handful of rows.
 */
@Composable
fun ParticipantsSheet(
    participants: List<ParticipantTileState>,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.88f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(SurfaceDark)
                    .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 12.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(IndigoPrimary.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = null,
                            tint = IndigoPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Participants",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "${participants.size} IN ROOM",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close participants",
                            tint = TextSecondary
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(CardBorder)
                )

                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(participants, key = { it.identity }) { p ->
                        ParticipantRow(p)
                    }
                }
            }
        }
    }
}

@Composable
private fun ParticipantRow(p: ParticipantTileState) {
    val isHost = p.role == "lecturer" || p.role == "admin"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceElevatedDark)
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(IndigoPrimary)
        ) {
            Text(
                text = p.name.take(1).uppercase(),
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = p.name + if (p.isLocal) " (you)" else "",
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when {
                    isHost -> "HOST"
                    p.isCoHost -> "CO-HOST"
                    else -> "STUDENT"
                },
                color = when {
                    isHost -> AmberWarning
                    p.isCoHost -> Color(0xFF67E8F9)
                    else -> TextTertiary
                },
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        if (p.isHandRaised) {
            Icon(
                imageVector = Icons.Default.PanTool,
                contentDescription = "Hand raised",
                tint = AmberWarning,
                modifier = Modifier
                    .size(16.dp)
                    .padding(end = 2.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        if (p.hasVideo) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = "Camera on",
                tint = EmeraldSuccess,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Icon(
            imageVector = if (p.isMicOn) Icons.Default.Mic else Icons.Default.MicOff,
            contentDescription = if (p.isMicOn) "Microphone on" else "Microphone off",
            tint = if (p.isMicOn) EmeraldSuccess else RedUrgent,
            modifier = Modifier.size(16.dp)
        )
    }
}
