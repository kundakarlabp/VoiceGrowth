package com.voicegrowth.app.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.scanner.FolderScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RecordingMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        if (!monitorStarted) {
            monitorStarted = true
            monitor()
        }
        return START_STICKY
    }

    private fun monitor() {
        val app = application as VoiceGrowthApplication
        val scanner = FolderScanner(this, app.container.recordingRepository)
        scope.launch {
            while (isActive) {
                runCatching {
                    val settings = app.container.recordingRepository.settingsFlow.first()
                    val folder = settings.selectedFolderUri
                    if (settings.autoProcessing && !folder.isNullOrBlank()) {
                        val added = scanner.scanFolder(Uri.parse(folder), settings)
                        if (added > 0) app.enqueueAudioProcessing()
                    }
                }
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification = NotificationCompat.Builder(
        this,
        VoiceGrowthApplication.CHANNEL_PROCESSING_ID
    )
        .setContentTitle("VoiceGrowth active")
        .setContentText("Watching the selected call-recording folder")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .build()

    companion object {
        const val NOTIFICATION_ID = 3001
        private const val SCAN_INTERVAL_MS = 60_000L
    }
}
