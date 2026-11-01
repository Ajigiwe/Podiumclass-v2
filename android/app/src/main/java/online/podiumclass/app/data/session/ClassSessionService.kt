package online.podiumclass.app.data.session

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import online.podiumclass.app.R
import online.podiumclass.app.data.notify.PodiumNotifications
import online.podiumclass.app.ui.MainActivity

/**
 * Keeps a live class alive while the app is in the background.
 *
 * A class is a WebRTC room plus an audio session living inside this process. The
 * moment the app goes to the background, an ordinary process is a candidate for
 * being frozen or reclaimed -- the lecture goes silent and the "class in
 * progress" reminder vanishes with it. Running this service while the room is
 * connected is what keeps the process (and therefore the Room, its socket and
 * its audio path) out of that state.
 *
 * Two deliberate non-goals:
 *
 *  * **It does not own a second room or a second audio session.** There is
 *    exactly one LiveKit `Room`, owned by `LiveKitRoomManager`, and one audio
 *    device module. A service that opened its own would fight the app for the
 *    microphone and play double audio.
 *  * **It is not a widget for the classroom.** It owns the ongoing notification
 *    and the process's priority, nothing else. All class state still comes from
 *    the ViewModel.
 *
 * If the user swipes the app off the recents list the process ends and the
 * service with it. That is a deliberate *leave*, not a bug: a class must not
 * keep a microphone open after the user got rid of the app.
 */
class ClassSessionService : Service() {

    private var title: String = DEFAULT_TITLE
    private var startedAt: Long = 0L
    private var cameraPublishing: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The live channel is created at process start as well; doing it here
        // too means a service started before any screen has drawn still has a
        // channel to post into.
        PodiumNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                return START_NOT_STICKY
            }

            ACTION_START, ACTION_UPDATE -> {
                val incoming = intent.getStringExtra(EXTRA_TITLE)
                if (!incoming.isNullOrBlank()) title = incoming

                // The chronometer counts from the first start, so a rename or a
                // camera toggle must not reset it to zero.
                if (startedAt == 0L) {
                    startedAt = intent.getLongExtra(EXTRA_STARTED_AT, 0L)
                        .takeIf { it > 0L }
                        ?: System.currentTimeMillis()
                }
                cameraPublishing = intent.getBooleanExtra(EXTRA_CAMERA_ON, cameraPublishing)

                _isRunning.value = true
                ensureForeground()
            }
        }
        // Not sticky: if the process is killed there is no class left to
        // remember, and a restarted sticky service would pin a notification to
        // a room that no longer exists.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        super.onDestroy()
    }

    // ------------------------------------------------------------ foreground

    /**
     * Promote the service, refreshing the notification in place.
     *
     * Called on every start and update, so the chronometer origin, the class
     * name and the foreground types all follow the room without the service
     * being torn down and rebuilt between them.
     */
    private fun ensureForeground() {
        val notification = buildLiveNotification(this, title, startedAt)

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundTypes())
            return
        } catch (e: Exception) {
            // Either a media type was refused (Android 14 checks that the app is
            // allowed to use the microphone/camera right now) or the start came
            // from a state the platform will not promote from. Fall through.
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
        } catch (e: Exception) {
            // Last resort: an ordinary ongoing notification. The class keeps
            // running; only the process anchor is missing.
            postPlain(this, title, startedAt)
        }
    }

    /**
     * The narrowest set of foreground types that honestly describes this class.
     *
     * The platform gates each type on its own runtime permission, and refuses
     * the whole promotion if one of them is missing -- so a student who declined
     * the microphone must not be given a `microphone` service. `dataSync` is the
     * fallback for exactly that case: a muted listener still needs the process
     * kept alive, and a live network session is work the platform should not
     * freeze.
     */
    private fun foregroundTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0

        var types = 0
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        // Only while the camera is actually publishing: claiming the camera
        // type with it off would reserve a hardware path this class is not
        // using, and on Android 14 would be checked against the app's current
        // state.
        if (cameraPublishing && granted(Manifest.permission.CAMERA)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (types == 0) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return types
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun stopSession() {
        _isRunning.value = false
        startedAt = 0L
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // Already gone.
        }
        try {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        } catch (e: SecurityException) {
            // Nothing to cancel.
        }
        stopSelf()
    }

    companion object {
        private const val ACTION_START = "online.podiumclass.app.action.CLASS_START"
        private const val ACTION_UPDATE = "online.podiumclass.app.action.CLASS_UPDATE"
        private const val ACTION_STOP = "online.podiumclass.app.action.CLASS_STOP"

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_STARTED_AT = "started_at"
        private const val EXTRA_CAMERA_ON = "camera_on"

        private const val DEFAULT_TITLE = "Class in progress"

        /**
         * Distinct from the recorder's 2201: notification ids are app-wide, so
         * sharing one would make a host's recording replace the class notice
         * (and then cancel it).
         */
        private const val NOTIFICATION_ID = 2202

        private val _isRunning = MutableStateFlow(false)

        /** Whether a class is currently held open by this service. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        /**
         * Open, or refresh, the session.
         *
         * Idempotent on purpose: the classroom calls this on connect and again
         * whenever the class name or the camera changes, and the service treats
         * all of them as the same session with a new label.
         */
        fun start(
            context: Context,
            title: String,
            cameraOn: Boolean = false,
            startedAtMillis: Long = System.currentTimeMillis()
        ) {
            val intent = Intent(context, ClassSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_STARTED_AT, startedAtMillis)
                putExtra(EXTRA_CAMERA_ON, cameraOn)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
                _isRunning.value = true
            } catch (e: Exception) {
                // Android 12+ refuses to start a foreground service from the
                // background. The class is still running and visible, so fall
                // back to the plain ongoing notice rather than showing nothing:
                // a missing reminder is a smaller failure than a crash.
                postPlain(context, title, startedAtMillis)
            }
        }

        /** Refresh the label/types of a session that is already open. */
        fun update(context: Context, title: String, cameraOn: Boolean) {
            if (!_isRunning.value) {
                start(context, title, cameraOn)
                return
            }
            val intent = Intent(context, ClassSessionService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CAMERA_ON, cameraOn)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // The service died between the check and the call; the next
                // connect starts it again.
                _isRunning.value = false
            }
        }

        /** Close the session. Safe to call when nothing is running. */
        fun stop(context: Context) {
            if (!_isRunning.value) {
                try {
                    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
                } catch (e: SecurityException) {
                    // Nothing to cancel.
                }
                return
            }
            val intent = Intent(context, ClassSessionService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Already gone: clear the state so the next class starts clean.
                _isRunning.value = false
            }
        }

        /** The ongoing reminder, used when the service cannot be promoted. */
        private fun postPlain(context: Context, title: String, startedAt: Long) {
            try {
                PodiumNotifications.ensureChannels(context)
                NotificationManagerCompat.from(context)
                    .notify(NOTIFICATION_ID, buildLiveNotification(context, title, startedAt))
            } catch (e: Exception) {
                // A missing notification must never interrupt the class.
            }
        }

        /**
         * The "class in progress" notice.
         *
         * Silent, ongoing and counting up: it is a status line for a room the
         * user is still in, not an event, so it must not buzz, must not be
         * dismissible, and must not lie about the class ending.
         */
        private fun buildLiveNotification(
            context: Context,
            className: String,
            startedAt: Long
        ): Notification {
            val open = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, PodiumNotifications.LIVE_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_podium)
                .setContentTitle(className.ifBlank { DEFAULT_TITLE })
                .setContentText("Class in progress — tap to return")
                .setSubText("In class")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(true)
                .setWhen(startedAt)
                .setUsesChronometer(true)
                .setAutoCancel(false)
                .setContentIntent(open)
                .build()
        }
    }
}
