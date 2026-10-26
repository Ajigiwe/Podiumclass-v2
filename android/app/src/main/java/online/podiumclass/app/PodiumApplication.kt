package online.podiumclass.app

import android.app.Application
import io.livekit.android.LiveKit
import io.livekit.android.util.LoggingLevel

class PodiumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize LiveKit logger and audio settings
        LiveKit.loggingLevel = LoggingLevel.INFO
    }
}

