package online.podiumclass.app

import android.app.Application
import io.livekit.android.LiveKit

class PodiumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize LiveKit logger and audio settings
        LiveKit.loggingLevel = io.livekit.android.LiveKit.LoggingLevel.INFO
    }
}
