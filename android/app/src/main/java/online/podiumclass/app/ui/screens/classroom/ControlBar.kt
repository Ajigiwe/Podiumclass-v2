package online.podiumclass.app.ui.screens.classroom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.podiumclass.app.ui.theme.*

@Composable
fun ControlBar(
    isMicOn: Boolean,
    isCameraOn: Boolean,
    isHandRaised: Boolean,
    isModerator: Boolean = false,
    canUseMic: Boolean = true,
    canUseCamera: Boolean = true,
    isMicRequested: Boolean = false,
    isCameraRequested: Boolean = false,
    pendingModerationRequests: Int = 0,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleHand: () -> Unit,
    onSendReaction: (String) -> Unit,
    onToggleChat: () -> Unit,
    onOpenModeration: () -> Unit = {},
    onRequestMic: () -> Unit = {},
    onRequestCamera: () -> Unit = {},
    onLeaveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showReactionsPopup by remember { mutableStateOf(false) }
    val emojis = listOf("👍", "👏", "❤️", "🔥", "🎉", "😂", "😮", "🤔")

    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = modifier
    ) {
        // Merged Reactions & Raise Hand Popup (anchored upwards above the bar)
        if (showReactionsPopup) {
            Column(
                modifier = Modifier
                    .padding(bottom = 72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceElevated)
                    .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
                    .padding(12.dp)
                    .widthIn(max = 280.dp)
            ) {
                // Raise / Lower Hand Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isHandRaised) AmberWarning else Color.White.copy(alpha = 0.08f))
                        .clickable {
                            onToggleHand()
                            showReactionsPopup = false
                        }
                        .padding(vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PanTool,
                        contentDescription = "Hand",
                        tint = if (isHandRaised) Color.White else AmberWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isHandRaised) "Lower Hand" else "Raise Hand",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Emoji Reaction Grid
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    emojis.forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 22.sp,
                            modifier = Modifier
                                .clickable {
                                    onSendReaction(emoji)
                                    showReactionsPopup = false
                                }
                                .padding(4.dp)
                        )
                    }
                }
            }
        }

        // Main Floating Control Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xE617171C))
                .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Mic Toggle / Request
            Box {
                IconButton(
                    onClick = {
                        if (canUseMic) {
                            onToggleMic()
                        } else {
                            onRequestMic()
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isMicOn -> IndigoPrimary
                                !canUseMic && isMicRequested -> AmberWarning.copy(alpha = 0.25f)
                                !canUseMic -> Color.White.copy(alpha = 0.05f)
                                else -> Color.White.copy(alpha = 0.08f)
                            }
                        )
                ) {
                    Icon(
                        imageVector = when {
                            isMicOn -> Icons.Default.Mic
                            !canUseMic && isMicRequested -> Icons.Default.HourglassTop
                            !canUseMic -> Icons.Default.Lock
                            else -> Icons.Default.MicOff
                        },
                        contentDescription = "Mic",
                        tint = when {
                            isMicOn -> Color.White
                            !canUseMic && isMicRequested -> AmberWarning
                            !canUseMic -> AmberWarning.copy(alpha = 0.8f)
                            else -> RedUrgent
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Camera Toggle / Request
            Box {
                IconButton(
                    onClick = {
                        if (canUseCamera) {
                            onToggleCamera()
                        } else {
                            onRequestCamera()
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isCameraOn -> IndigoPrimary
                                !canUseCamera && isCameraRequested -> AmberWarning.copy(alpha = 0.25f)
                                !canUseCamera -> Color.White.copy(alpha = 0.05f)
                                else -> Color.White.copy(alpha = 0.08f)
                            }
                        )
                ) {
                    Icon(
                        imageVector = when {
                            isCameraOn -> Icons.Default.Videocam
                            !canUseCamera && isCameraRequested -> Icons.Default.HourglassTop
                            !canUseCamera -> Icons.Default.Lock
                            else -> Icons.Default.VideocamOff
                        },
                        contentDescription = "Camera",
                        tint = when {
                            isCameraOn -> Color.White
                            !canUseCamera && isCameraRequested -> AmberWarning
                            !canUseCamera -> AmberWarning.copy(alpha = 0.8f)
                            else -> RedUrgent
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Flip Camera
            IconButton(
                onClick = onFlipCamera,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Icon(
                    imageVector = Icons.Default.FlipCameraAndroid,
                    contentDescription = "Flip Camera",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Merged Reactions & Hand Raise Pop-up Trigger
            IconButton(
                onClick = { showReactionsPopup = !showReactionsPopup },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isHandRaised) AmberWarning else if (showReactionsPopup) SurfaceElevated else Color.White.copy(alpha = 0.08f))
            ) {
                Icon(
                    imageVector = if (isHandRaised) Icons.Default.PanTool else Icons.Default.AddReaction,
                    contentDescription = "Reactions",
                    tint = if (isHandRaised) Color.White else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Moderator Controls Button (Host / Co-Host)
            if (isModerator) {
                Box {
                    IconButton(
                        onClick = onOpenModeration,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (pendingModerationRequests > 0) AmberWarning.copy(alpha = 0.2f)
                                else IndigoPrimary.copy(alpha = 0.15f)
                            )
                            .border(
                                1.dp,
                                if (pendingModerationRequests > 0) AmberWarning.copy(alpha = 0.4f)
                                else IndigoPrimary.copy(alpha = 0.3f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Moderation",
                            tint = if (pendingModerationRequests > 0) AmberWarning else IndigoPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    if (pendingModerationRequests > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-2).dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(AmberWarning),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = pendingModerationRequests.toString(),
                                color = Color.Black,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }

            // Chat Button
            IconButton(
                onClick = onToggleChat,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Icon(
                    imageVector = Icons.Default.ChatBubble,
                    contentDescription = "Chat",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Leave Classroom
            IconButton(
                onClick = onLeaveClick,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(RedUrgent.copy(alpha = 0.2f))
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = "Leave",
                    tint = RedUrgent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
