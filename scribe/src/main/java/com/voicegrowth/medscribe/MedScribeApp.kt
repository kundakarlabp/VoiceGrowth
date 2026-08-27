package com.voicegrowth.medscribe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MedScribeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL_RECORDING, "Active recording", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Persistent controls while MedScribe records audio"
                        setSound(null, null)
                    },
                    NotificationChannel(CHANNEL_PROCESSING, "Transcription", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Offline transcription and Drive sync progress"
                        setSound(null, null)
                    },
                    NotificationChannel(CHANNEL_STATUS, "Status and errors", NotificationManager.IMPORTANCE_DEFAULT)
                )
            )
        }
    }

    companion object {
        const val CHANNEL_RECORDING = "medscribe_recording"
        const val CHANNEL_PROCESSING = "medscribe_processing"
        const val CHANNEL_STATUS = "medscribe_status"
    }
}
