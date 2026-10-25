package online.podiumclass.app.ui.screens.classroom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
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
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.renderer.TextureViewRenderer
import online.podiumclass.app.data.livekit.ParticipantTileState
import online.podiumclass.app.ui.theme.*

@Composable
fun StageVideoView(
    participant: ParticipantTileState,
    isSpotlight: Boolean = false,
    onPinClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        participant.isSpeaking -> EmeraldSuccess
        isSpotlight -> IndigoPrimary
        else -> CardBorder
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(
                width = if (participant.isSpeaking || isSpotlight) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onPinClick() }
    ) {
        if (participant.hasVideo && participant.videoTrack is VideoTrack) {
            AndroidView(
                factory = { context ->
                    TextureViewRenderer(context).apply {
                        participant.videoTrack.addRenderer(this)
                    }
                },
                update = { view ->
                    participant.videoTrack.addRenderer(view)
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Avatar Fallback
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(if (isSpotlight) 88.dp else 64.dp)
                            .clip(CircleShape)
                            .background(IndigoPrimary)
                    ) {
                        Text(
                            text = participant.name.take(1).uppercase(),
                            color = TextPrimary,
                            fontSize = if (isSpotlight) 36.sp else 26.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = participant.name,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Overlay Header: Name, Host badge, Mic indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .align(Alignment.BottomStart)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = participant.name + if (participant.isLocal) " (You)" else "",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (participant.role == "lecturer" || participant.role == "admin") {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "HOST",
                        color = AmberWarning,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black
                    )
                } else if (participant.isCoHost) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "CO-HOST",
                        color = Color(0xFF67E8F9),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (participant.isHandRaised) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(AmberWarning)
                            .padding(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PanTool,
                            contentDescription = "Hand Raised",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (participant.isMicOn) EmeraldSuccess.copy(alpha = 0.2f) else Color.Red.copy(alpha = 0.2f))
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = if (participant.isMicOn) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Mic Status",
                        tint = if (participant.isMicOn) EmeraldSuccess else RedUrgent,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}
