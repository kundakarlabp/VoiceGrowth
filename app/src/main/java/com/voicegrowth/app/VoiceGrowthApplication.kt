package com.voicegrowth.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.util.Log
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.voicegrowth.app.di.AppContainer
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.model.RecordingSource
import com.voicegrowth.app.scanner.InterruptedRecordingRecovery
import com.voicegrowth.app.service.CaptureNotificationManager
import com.voicegrowth.app.service.RecordingStateStore
import com.voicegrowth.app.workers.DriveSyncWorker
import com.voicegrowth.app.workers.FolderScanWorker
import java.util.concurrent.TimeUnit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VoiceGrowthApplication : Application() {
    lateinit var container: AppContainer
        private set
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        RecordingStateStore.setRecording(this, false)
        createNotificationChannels()
        schedulePeriodicWork(this)
        CaptureNotificationManager.showReady(this)
        backgroundScope.launch {
            runCatching {
                val recovered = InterruptedRecordingRecovery(this@VoiceGrowthApplication, container.recordingRepository).recover()
                if (recovered > 0) {
                    val settings = container.recordingRepository.settingsFlow.first()
                    enqueueDriveSync(settings.wifiOnly)
                }
            }
        }
    }

    /**
     * Keep the database insert alive after the foreground recording service stops.
     * A service-owned coroutine can be cancelled in onDestroy before a recording
     * enters the sync queue. Recovery on the next launch remains a second safeguard.
     */
    fun registerCompletedRecording(
        file: File,
        recordedAt: Long,
        durationSeconds: Long,
        source: RecordingSource
    ) {
        backgroundScope.launch {
            try {
                val record = RecordingEntity(
                    uriString = Uri.fromFile(file).toString(),
                    fileName = file.name,
                    filePath = file.absolutePath,
                    source = source,
                    durationSeconds = durationSeconds,
                    fileSizeBytes = file.length(),
                    recordedAt = recordedAt,
                    status = ProcessingStatus.WAITING_FOR_SYNC
                )
                val id = container.recordingRepository.insertRecording(record)
                if (id > 0L) enqueueDriveSync(container.recordingRepository.settingsFlow.first().wifiOnly)
            } catch (error: Exception) {
                // The source file is retained for InterruptedRecordingRecovery.
                Log.e("VoiceGrowth", "Recording queue insert failed; original retained", error)
            }
        }
    }

    fun enqueueDriveSync(wifiOnly: Boolean) {
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            DriveSyncWorker.WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    fun enqueueFolderScanNow(force: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<FolderScanWorker>()
            .setInputData(workDataOf(FolderScanWorker.INPUT_FORCE_SCAN to force))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            FolderScanWorker.ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROCESSING_ID, getString(R.string.channel_processing_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.channel_processing_desc) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING_ID, getString(R.string.channel_recording_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = getString(R.string.channel_recording_desc) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CAPTURE_ID, getString(R.string.channel_capture_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply {
                    description = getString(R.string.channel_capture_desc)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setShowBadge(false)
                }
        )
    }

    companion object {
        const val CHANNEL_PROCESSING_ID = "voicegrowth_processing_channel"
        const val CHANNEL_RECORDING_ID = "voicegrowth_recording_channel"
        const val CHANNEL_CAPTURE_ID = "voicegrowth_capture_controls_v2"

        fun schedulePeriodicWork(context: Context) {
            val workManager = WorkManager.getInstance(context)

            // Only local capture and approved file archival. No paid/automatic AI pipeline.
            workManager.cancelUniqueWork("VoiceGrowth_LocalTranscription")
            workManager.cancelUniqueWork("VoiceGrowth_LocalTranscription_periodic")

            val scanRequest = PeriodicWorkRequestBuilder<FolderScanWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .build()
            workManager.enqueueUniquePeriodicWork(
                FolderScanWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                scanRequest
            )

            // Android provider read-back is not Google cloud verification.
            workManager.cancelUniqueWork("VoiceGrowth_Cleanup")
        }
    }
}
