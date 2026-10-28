package online.podiumclass.app.ui.screens.dashboard

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import online.podiumclass.app.data.api.models.RecordingDto
import online.podiumclass.app.ui.theme.*
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The host's recordings, where they can watch, save and pass them on.
 *
 * Only the people who run a class ever receive these rows -- the server answers
 * with an empty list for a student -- and each entry carries short-lived signed
 * URLs, because neither a video player nor the system downloader can send an
 * Authorization header of its own.
 */
@Composable
fun RecordsSection(
    recordings: List<RecordingDto>,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit
) {
    val colors = AppTheme.colors
    // Which recording is open in the player. One at a time: two decoders
    // running at once on a phone is how a stage starts stuttering.
    var openId by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "RECORDS",
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f)
            )
            if (isLoading) {
                CircularProgressIndicator(
                    color = colors.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh recordings",
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onRefresh() }
            )
        }

        if (error != null) {
            Text(
                text = error,
                color = colors.error,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (recordings.isEmpty() && !isLoading && error == null) {
            Text(
                text = "No recordings yet. Start one from inside a class.",
                color = colors.textSecondary,
                fontSize = 12.sp
            )
        }

        recordings.forEach { rec ->
            RecordingCard(
                rec = rec,
                isOpen = openId == rec.id,
                onTogglePlay = { openId = if (openId == rec.id) null else rec.id }
            )
        }
    }
}

@Composable
private fun RecordingCard(
    rec: RecordingDto,
    isOpen: Boolean,
    onTogglePlay: () -> Unit
) {
    val colors = AppTheme.colors
    val context = LocalContext.current

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rec.title ?: "Class recording",
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = metaLine(rec),
                        color = colors.textTertiary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                StatusChip(rec)
            }

            if (rec.isLive) {
                Text(
                    text = "Still recording\u2026",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            } else if (rec.status == "failed" || rec.status == "aborted") {
                Text(
                    text = rec.error ?: "The recording did not finish.",
                    color = colors.error,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (isOpen && rec.playUrl != null) {
                RecordingPlayer(
                    url = rec.playUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (rec.canPlay && rec.playUrl != null) {
                    SmallAction(
                        icon = if (isOpen) Icons.Default.Stop else Icons.Default.PlayArrow,
                        label = if (isOpen) "Close" else "Play",
                        tint = colors.primary,
                        onClick = onTogglePlay
                    )
                }

                // Download and share are the host's call, which is exactly what
                // the server's `canDownload` says: it is decided per caller.
                if (rec.canDownload && rec.downloadUrl != null) {
                    SmallAction(
                        icon = Icons.Default.Download,
                        label = "Download",
                        tint = colors.textSecondary,
                        onClick = { downloadRecording(context, rec) }
                    )
                }

                if (rec.playUrl != null) {
                    SmallAction(
                        icon = Icons.Default.Share,
                        label = "Share",
                        tint = colors.textSecondary,
                        onClick = { shareRecording(context, rec) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(rec: RecordingDto) {
    val colors = AppTheme.colors
    val (label, tint) = when {
        rec.isLive -> "LIVE" to colors.error
        rec.status == "complete" -> "READY" to colors.success
        rec.status == "failed" || rec.status == "aborted" -> "FAILED" to colors.error
        else -> "PROCESSING" to colors.warning
    }
    Text(
        text = label,
        color = tint,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.15f))
            .border(1.dp, tint.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
private fun SmallAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.10f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text(text = label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The platform player, deliberately.
 *
 * An MP4 with range support streams perfectly well through `VideoView`, so the
 * Records area needs no media dependency at all -- and nothing gets read into
 * memory first, which matters when a lecture is a few hundred megabytes.
 */
@Composable
private fun RecordingPlayer(url: String, modifier: Modifier = Modifier) {
    var player by remember { mutableStateOf<VideoView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { media ->
                    media.isLooping = false
                    start()
                }
                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                tag = url
                setVideoURI(Uri.parse(url))
                player = this
            }
        },
        update = { view ->
            // Compose may hand this view to another recording's card when the
            // list reorders, so the source is re-pointed only when it changed.
            if (view.tag != url) {
                view.tag = url
                view.setVideoURI(Uri.parse(url))
                view.start()
            }
        }
    )

    // Leaving the card -- or closing it -- must stop the decoder, not just
    // hide the view.
    DisposableEffect(url) {
        onDispose {
            player?.stopPlayback()
            player = null
        }
    }
}

private fun metaLine(rec: RecordingDto): String {
    val parts = mutableListOf<String>()
    rec.startedAt?.let { parts += formatWhen(it) }
    rec.durationSeconds?.let { parts += formatDuration(it) }
    rec.sizeBytes?.let { if (it > 0) parts += formatSize(it) }
    if (parts.isEmpty()) parts += rec.status.replaceFirstChar { it.uppercase() }
    return parts.joinToString("  •  ")
}

private fun formatWhen(iso: String): String = try {
    OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault()))
} catch (e: Exception) {
    iso
}

private fun formatDuration(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return if (m >= 60) {
        String.format(Locale.US, "%d:%02d:%02d", m / 60, m % 60, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit += 1
    }
    return String.format(Locale.US, if (value < 10) "%.1f %s" else "%.0f %s", value, units[unit])
}

/**
 * Save the file with the system downloader.
 *
 * It lands in the app's own external Downloads folder: no storage permission is
 * needed on any supported version, the transfer survives leaving the app, and
 * the completed notification opens the MP4 with whatever player the phone has.
 */
private fun downloadRecording(context: Context, rec: RecordingDto) {
    val url = rec.downloadUrl ?: return
    try {
        val fileName = rec.fileName?.takeIf { it.isNotBlank() }
            ?: "podium-recording-${rec.id}.mp4"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(rec.title ?: "Class recording")
            .setDescription(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(context, "Downloading $fileName\u2026", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not download: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}

/**
 * Pass the recording on.
 *
 * The link is the signed watch URL, which is the only one this client holds:
 * it expires in a few hours, which is what keeps a share from becoming a
 * permanent public copy of a lecture.
 */
private fun shareRecording(context: Context, rec: RecordingDto) {
    val url = rec.playUrl ?: return
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, rec.title ?: "Class recording")
            putExtra(Intent.EXTRA_TEXT, "${rec.title ?: "Class recording"}\n$url")
        }
        context.startActivity(Intent.createChooser(intent, "Share recording"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}
