package online.podiumclass.app.data.recording

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import online.podiumclass.app.R
import online.podiumclass.app.data.notify.PodiumNotifications
import online.podiumclass.app.ui.MainActivity
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the phone's own recorder is doing.
 *
 * There is no `processing` state and no poll: the file is written by this
 * device, so the moment [Recording] ends the MP4 is already on the disk.
 */
sealed interface LocalRecordingState {
    data object Idle : LocalRecordingState

    /** The capture prompt was accepted and the encoder is being wired up. */
    data object Preparing : LocalRecordingState

    data class Recording(val startedAtMillis: Long, val title: String) : LocalRecordingState

    data class Failed(val message: String) : LocalRecordingState
}

/**
 * Records the class on the phone, into the phone's own storage.
 *
 * This is a screen recording on purpose. A phone cannot composite four live
 * WebRTC tiles into a file the way the server's egress worker does -- but it
 * does not have to: capturing the screen records exactly what the host is
 * looking at, including the stage, the shared material and the whiteboard, with
 * no server CPU, no egress minutes and no traffic.
 *
 * The MP4 lands in `Movies/Podium/` on Android 10+, so it is visible in the
 * phone's Files app and survives uninstalling the app; on 8 and 9 it goes to the
 * app's own external Movies folder, because the public one would need a storage
 * permission this app never asks for.
 *
 * Host trials of screen capture require a foreground service of type
 * `mediaProjection` to be running *before* the projection is used, which is why
 * this exists as a service rather than inside the ViewModel.
 */
class ScreenRecordService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var outputFile: File? = null

    private var entryId: String? = null
    private var title: String = ""
    private var startedAt = 0L
    private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_STOP -> finishRecording()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- capture

    private fun begin(intent: Intent) {
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Class"

        // Android 14 refuses getMediaProjection() unless the foreground service
        // is already up with the mediaProjection type, so this comes first.
        startForegroundCompat(buildNotification("Preparing the recorder…", withStop = false))
        _state.value = LocalRecordingState.Preparing

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            fail("Recording needs permission to capture this screen.")
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val capture = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        }
        if (capture == null) {
            fail("The screen recorder could not start.")
            return
        }
        projection = capture

        // Required since Android 14; harmless earlier. Without it the platform
        // throws the moment a virtual display is created.
        capture.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // The user stopped sharing from the system UI, or the
                    // activity went away: close the file rather than leaving a
                    // truncated MP4 behind.
                    finishRecording()
                }
            },
            Handler(Looper.getMainLooper())
        )

        val metrics = resources.displayMetrics
        // Cap the long edge at 1080: a class is legible there, and it keeps the
        // encoder off the battery for an hour.
        val longEdge = maxOf(metrics.widthPixels, metrics.heightPixels)
        val scale = minOf(1f, 1080f / longEdge.toFloat())
        val width = even(metrics.widthPixels * scale)
        val height = even(metrics.heightPixels * scale)

        val encoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            encoder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            // The phone's own microphone: on speaker it also picks up the room,
            // which is the one source a MediaProjection can legally record.
            encoder.setAudioSource(MediaRecorder.AudioSource.MIC)
            encoder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            encoder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            encoder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            encoder.setVideoSize(width, height)
            encoder.setVideoFrameRate(30)
            encoder.setVideoEncodingBitRate(bitRateFor(width, height))
            encoder.setAudioEncodingBitRate(128_000)
            encoder.setAudioSamplingRate(44_100)

            val stamp = System.currentTimeMillis()
            val fileName = "Podium-" + String.format(
                Locale.US,
                "%1\$tY%1\$tm%1\$td-%1\$tH%1\$tM%1\$tS",
                java.util.Date(stamp)
            )
            entryId = "local-$stamp"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "$fileName.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/Podium"
                    )
                    // Hidden from other apps until the file is complete, so a
                    // half-written MP4 can never show up in the gallery.
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                val descriptor = uri?.let { contentResolver.openFileDescriptor(it, "rw") }
                if (uri == null || descriptor == null) {
                    encoder.release()
                    fail("There is no room to write the recording on this phone.")
                    return
                }
                outputUri = uri
                fileDescriptor = descriptor
                encoder.setOutputFile(descriptor.fileDescriptor)
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                val file = File(dir, "$fileName.mp4")
                outputFile = file
                encoder.setOutputFile(file.absolutePath)
            }

            encoder.prepare()
            val surface = encoder.surface
            recorder = encoder

            virtualDisplay = capture.createVirtualDisplay(
                "podium-recording",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                Handler(Looper.getMainLooper())
            )

            encoder.start()
        } catch (e: Exception) {
            try {
                encoder.release()
            } catch (_: Exception) {
                // Already unusable.
            }
            recorder = null
            discardOutput()
            fail("The recorder could not start (${e.localizedMessage ?: "unknown error"}).")
            return
        }

        startedAt = System.currentTimeMillis()
        LocalRecords.add(
            this,
            LocalRecording(
                id = entryId!!,
                uri = (outputUri ?: Uri.fromFile(outputFile!!)).toString(),
                title = title,
                fileName = File(outputFile?.name ?: (outputUri?.lastPathSegment ?: "$title.mp4")).name,
                startedAtMillis = startedAt,
                pending = true
            )
        )

        _state.value = LocalRecordingState.Recording(startedAt, title)
        updateNotification(buildNotification("Recording to this phone", withStop = true))
    }

    // ---------------------------------------------------------------- finish

    /**
     * Close the file cleanly.
     *
     * Every step is best-effort: a recorder that never produced a frame throws
     * from `stop()`, and the one thing that must not happen is leaving an empty
     * MP4 in the gallery or an entry on the shelf that cannot be played.
     */
    private fun finishRecording() {
        if (finishing) return
        finishing = true

        var captured = false
        try {
            recorder?.let { rec ->
                try {
                    rec.stop()
                    captured = true
                } catch (e: RuntimeException) {
                    // "stop called before the pipeline could start", or a take
                    // too short to contain a frame. Nothing was written.
                    captured = false
                }
                try {
                    rec.reset()
                } catch (_: Exception) {
                    // Fine either way; release follows.
                }
                rec.release()
            }
        } catch (_: Exception) {
            captured = false
        }
        recorder = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
            // Already released with the projection.
        }
        virtualDisplay = null

        try {
            projection?.stop()
        } catch (_: Exception) {
            // The user may have stopped sharing already.
        }
        projection = null

        try {
            fileDescriptor?.close()
        } catch (_: Exception) {
            // Closed by the platform.
        }
        fileDescriptor = null

        val id = entryId
        val uri = outputUri
        val file = outputFile

        if (!captured) {
            discardOutput()
            if (id != null) {
                LocalRecords.all(this).firstOrNull { it.id == id }?.let { LocalRecords.remove(this, it) }
            }
            entryId = null
            _state.value = LocalRecordingState.Failed("Nothing was captured, so no file was saved.")
            stopForegroundCompat()
            stopSelf()
            return
        }

        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            } catch (_: Exception) {
                // The row is still readable by this app, which is enough.
            }
        }

        // Reading the duration decodes the container, so it happens off the main
        // thread -- and the service stays up until it is done, because the shelf
        // must show a length the moment the file appears.
        Thread {
            val (durationMs, sizeBytes) = mediaStats(uri, file)
            if (id != null) {
                LocalRecords.update(this, id) {
                    it.copy(durationMs = durationMs, sizeBytes = sizeBytes, pending = false)
                }
            }
            PodiumNotifications.recordingSaved(this, title, formatLength(durationMs))
            _state.value = LocalRecordingState.Idle
            stopForegroundCompat()
            stopSelf()
        }.start()
    }

    private fun fail(message: String) {
        _state.value = LocalRecordingState.Failed(message)
        stopForegroundCompat()
        stopSelf()
    }

    /** Delete a half-written file so it cannot appear as a broken recording. */
    private fun discardOutput() {
        try {
            outputUri?.let { contentResolver.delete(it, null, null) }
        } catch (_: Exception) {
            // Nothing to delete.
        }
        try {
            outputFile?.delete()
        } catch (_: Exception) {
            // Same.
        }
        outputUri = null
        outputFile = null
    }

    private fun mediaStats(uri: Uri?, file: File?): Pair<Long, Long> {
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        return cursor.getLong(0) to cursor.getLong(1)
                    }
                }
            } catch (_: Exception) {
                // Fall through to the file-based answer.
            }
        }
        if (file != null) {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                val ms = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                ms to file.length()
            } catch (_: Exception) {
                0L to (if (file.exists()) file.length() else 0L)
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                    // Already released.
                }
            }
        }
        return 0L to 0L
    }

    // ---------------------------------------------------------- notification

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ACTIVE,
                "Class recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while this phone is recording a class."
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String, withStop: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ACTIVE)
            .setSmallIcon(R.drawable.ic_stat_podium)
            .setContentTitle(if (title.isBlank()) "Recording this class" else title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)

        if (withStop) {
            val stop = PendingIntent.getService(
                this,
                1,
                Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Stop", stop)
        }
        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (_: Exception) {
            // Android 14 throws if the service was started from the background
            // or the projection permission is missing; the caller reports it.
        }
    }

    private fun updateNotification(notification: Notification) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // A missing notification does not interrupt the recording.
        }
    }

    private fun stopForegroundCompat() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // Already gone.
        }
    }

    private fun even(value: Float): Int {
        val rounded = value.roundToInt().coerceAtLeast(2)
        return if (rounded % 2 == 0) rounded else rounded - 1
    }

    private fun bitRateFor(width: Int, height: Int): Int = when {
        width * height > 1_400_000 -> 6_000_000
        width * height > 800_000 -> 4_000_000
        else -> 2_500_000
    }

    private fun formatLength(durationMs: Long): String? {
        if (durationMs <= 0) return null
        val total = durationMs / 1000
        val minutes = total / 60
        val seconds = total % 60
        return if (minutes >= 60) {
            String.format(Locale.US, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    companion object {
        private const val CHANNEL_ACTIVE = "podium_recording_active"
        private const val NOTIFICATION_ID = 2201

        private const val ACTION_START = "online.podiumclass.app.action.START_RECORDING"
        private const val ACTION_STOP = "online.podiumclass.app.action.STOP_RECORDING"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_TITLE = "title"

        private val _state = MutableStateFlow<LocalRecordingState>(LocalRecordingState.Idle)

        /** The one live recorder, observed by the classroom screen. */
        val state: StateFlow<LocalRecordingState> = _state.asStateFlow()

        /** Begin capturing, with the consent the user just granted. */
        fun start(context: Context, resultCode: Int, data: Intent, title: String) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_TITLE, title)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Close the file and save it. Safe to call when nothing is running. */
        fun stop(context: Context) {
            if (_state.value !is LocalRecordingState.Recording &&
                _state.value !is LocalRecordingState.Preparing
            ) {
                return
            }
            val intent = Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Exception) {
                // The service is already gone; the state below still clears.
                _state.value = LocalRecordingState.Idle
            }
        }
    }
}
