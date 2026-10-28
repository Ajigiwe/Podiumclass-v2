package online.podiumclass.app

import android.app.Application
import io.livekit.android.LiveKit
import io.livekit.android.util.LoggingLevel
import online.podiumclass.app.data.notify.PodiumNotifications

class PodiumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize LiveKit logger and audio settings
        LiveKit.loggingLevel = LoggingLevel.INFO
        // Channels are created once here, so the first notification of a
        // session is not the one that defines them.
        PodiumNotifications.ensureChannels(this)
    }
}

