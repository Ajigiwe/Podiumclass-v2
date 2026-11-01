package online.podiumclass.app.data.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import online.podiumclass.app.R
import online.podiumclass.app.data.session.ClassSessionService
import online.podiumclass.app.ui.MainActivity

/**
 * The app's system notifications.
 *
 * These are the things worth knowing when the phone is in a pocket: a class the
 * student was in has ended, a recording finished processing, a lecture's video
 * could not be written. Everything that happens *inside* a class stays in the
 * in-class banner, because a notification for a message the user is already
 * looking at is noise.
 *
 * Android 13+ needs `POST_NOTIFICATIONS` at runtime; every post is guarded so a
 * revoked permission degrades to silence instead of a crash.
 */
object PodiumNotifications {

    /** A class starting, ending, or the host removing someone. */
    private const val CHANNEL_CLASSES = "podium_classes"

    /** A recording finished, failed, or is ready to download. */
    private const val CHANNEL_RECORDINGS = "podium_recordings"

    /**
     * The class running right now. Silent, pinned and never auto-cancelled:
     * it is a status line, not an event, so it must not make a sound or be
     * swiped away while the room is still open.
     *
     * Public because the notification itself is posted by
     * [online.podiumclass.app.data.session.ClassSessionService], which is the
     * foreground service holding the session open; this object only defines the
     * channel it posts into.
     */
    const val LIVE_CHANNEL = "podium_class_live"

    private const val ID_RECORDING_READY = 2101
    private const val ID_RECORDING_FAILED = 2102
    private const val ID_RECORDING_SAVED = 2105
    private const val ID_CLASS_ENDED = 2103
    private const val ID_REMOVED_FROM_CLASS = 2104

    /**
     * Create the channels once, at process start.
     *
     * Creating an existing channel is a no-op, so this is safe on every launch
     * and needs no version check of its own beyond the API level.
     */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CLASSES,
                "Classes",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "When a class you are in ends, or you are asked to rejoin."
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECORDINGS,
                "Recordings",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "When a class recording is ready to watch or download."
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                LIVE_CHANNEL,
                "Class in progress",
                // LOW, so a class you are already sitting in never buzzes at
                // you; it only sits in the shade with its clock.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "A reminder of the class you are in, shown while it runs."
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    /** Whether a notification may actually be posted right now. */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** A recording finished and its MP4 is on the server, ready for Records. */
    fun recordingReady(context: Context, title: String, length: String? = null) {
        val body = buildString {
            append(if (title.isBlank()) "Your class recording" else "\"$title\"")
            append(" is ready")
            if (!length.isNullOrBlank()) append(" — $length")
            append(". Open Records to watch or download it.")
        }
        post(context, CHANNEL_RECORDINGS, ID_RECORDING_READY, "Recording ready", body)
    }

    /**
     * A recording taken on this phone finished and is already on its disk.
     *
     * There is no processing step to wait for: the file was written by the
     * device, so the notification only has to say where it went.
     */
    fun recordingSaved(context: Context, title: String, length: String? = null) {
        val body = buildString {
            append(if (title.isBlank()) "Your class recording" else "\"$title\"")
            append(" is saved on this phone")
            if (!length.isNullOrBlank()) append(" — $length")
            append(". Find it in Records, or in Movies/Podium.")
        }
        post(context, CHANNEL_RECORDINGS, ID_RECORDING_SAVED, "Recording saved", body)
    }

    /** The egress worker gave up; say so instead of leaving an empty shelf. */
    fun recordingFailed(context: Context, title: String, reason: String? = null) {
        val body = buildString {
            append(if (title.isBlank()) "The class recording" else "The recording of \"$title\"")
            append(" did not finish")
            if (!reason.isNullOrBlank()) append(": $reason")
            append(".")
        }
        post(context, CHANNEL_RECORDINGS, ID_RECORDING_FAILED, "Recording failed", body)
    }

    /**
     * The room closed on this user -- the host ended it, or the connection died.
     *
     * `detail` is the reason the classroom itself showed, so the notification and
     * the screen never disagree about what happened.
     */
    fun classEnded(context: Context, title: String, detail: String? = null) {
        val body = detail?.takeIf { it.isNotBlank() } ?: buildString {
            append("The host ended ")
            append(if (title.isBlank()) "the class" else "\"$title\"")
            append(". The room is closed until the host opens it again.")
        }
        post(context, CHANNEL_CLASSES, ID_CLASS_ENDED, "Class ended", body)
    }

    /**
     * Hold the class that is running open, and pin it to the shade.
     *
     * The phone is the thing that stays in a pocket while a lecture goes on, so
     * the reminder has to live outside the app -- and so does the connection.
     * Both are [ClassSessionService]'s job: it posts an ongoing notification
     * with a chronometer, and being a foreground service is what stops Android
     * from freezing the process (and silencing the room) once this screen is
     * backgrounded. This is only the entry point the classroom calls.
     *
     * [cameraPublishing] is passed through so the service can claim the camera
     * foreground type only while a camera is actually on air.
     */
    fun startLiveClass(
        context: Context,
        title: String,
        cameraPublishing: Boolean = false,
        startedAtMillis: Long = System.currentTimeMillis()
    ) {
        ClassSessionService.start(context.applicationContext, title, cameraPublishing, startedAtMillis)
    }

    /**
     * The class was renamed, the session loaded late, or the camera changed:
     * same clock, new label. A no-op when no class is being held open.
     */
    fun updateLiveClass(context: Context, title: String, cameraPublishing: Boolean = false) {
        ClassSessionService.update(context.applicationContext, title, cameraPublishing)
    }

    /** The class ended, the user left it, or the screen closed. */
    fun stopLiveClass(context: Context) {
        ClassSessionService.stop(context.applicationContext)
    }

    /** A moderator removed this person from the room. */
    fun removedFromClass(context: Context, title: String, by: String? = null) {
        val body = buildString {
            append(if (by.isNullOrBlank()) "A moderator removed you" else "$by removed you")
            append(" from ")
            append(if (title.isBlank()) "the class" else "\"$title\"")
            append(".")
        }
        post(context, CHANNEL_CLASSES, ID_REMOVED_FROM_CLASS, "Removed from class", body)
    }

    private fun post(
        context: Context,
        channel: String,
        id: Int,
        title: String,
        body: String
    ) {
        if (!canNotify(context)) return
        ensureChannels(context)

        // Tapping opens the app rather than a deep link: the class may be gone
        // by the time this is read, and the dashboard is the honest landing
        // spot either way.
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_podium)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }
}
