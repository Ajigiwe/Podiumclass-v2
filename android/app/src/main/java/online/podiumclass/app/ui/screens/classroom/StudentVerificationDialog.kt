package online.podiumclass.app.ui.screens.classroom

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import online.podiumclass.app.ui.theme.*

@Composable
fun StudentVerificationDialog(
    verificationNumber: Int,
    timeLimitSeconds: Int,
    isSubmitting: Boolean,
    isSuccess: Boolean,
    onConfirmPresent: () -> Unit
) {
    val context = LocalContext.current
    var secondsRemaining by remember { mutableIntStateOf(timeLimitSeconds) }

    // Audio-Haptic Feedback on trigger
    LaunchedEffect(Unit) {
        try {
            // Web Audio equivalent tone
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)

            // Tactile vibration
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 200, 100, 200), -1)
            }
        } catch (e: Exception) {
            // Ignore device sound restriction
        }
    }

    // Countdown Timer
    LaunchedEffect(secondsRemaining, isSuccess) {
        if (!isSuccess && secondsRemaining > 0) {
            delay(1000)
            secondsRemaining--
        }
    }

    val isUrgent = secondsRemaining < 10
    val dynamicBorderColor by animateColorAsState(
        targetValue = if (isUrgent) RedUrgent else IndigoPrimary,
        label = "BorderColor"
    )

    Dialog(
        onDismissRequest = { /* Non-dismissible: Must respond */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, dynamicBorderColor, RoundedCornerShape(24.dp))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                // Header Shield Icon
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(if (isUrgent) RedUrgent.copy(alpha = 0.2f) else IndigoPrimary.copy(alpha = 0.2f))
                        .border(1.dp, dynamicBorderColor.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Shield",
                        tint = if (isUrgent) RedUrgent else IndigoPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Attendance Check",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "Verification Check #$verificationNumber",
                    color = IndigoPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Timer Container
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "TIME REMAINING",
                            color = TextTertiary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Respond before timeout",
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "Timer",
                            tint = if (isUrgent) RedUrgent else IndigoPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = String.format("0:%02d", secondsRemaining),
                            color = if (isUrgent) RedUrgent else Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Progress Bar
                    LinearProgressIndicator(
                        progress = { secondsRemaining.toFloat() / timeLimitSeconds.toFloat() },
                        color = if (isUrgent) RedUrgent else IndigoPrimary,
                        trackColor = Color.White.copy(alpha = 0.1f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Button: "I AM PRESENT"
                Button(
                    onClick = onConfirmPresent,
                    enabled = !isSubmitting && !isSuccess && secondsRemaining > 0,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSuccess) EmeraldSuccess else if (isUrgent) RedUrgent else IndigoPrimary,
                        disabledContainerColor = if (isSuccess) EmeraldSuccess else IndigoPrimary.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(
                        imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.TouchApp,
                        contentDescription = "Confirm",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isSuccess -> "PRESENT RECORDED!"
                            isSubmitting -> "LOGGING IN DATABASE..."
                            else -> "I AM PRESENT"
                        },
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Podium Anti-Cheat Protection Active",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
