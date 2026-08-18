package com.voicegrowth.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import com.voicegrowth.app.service.CaptureNotificationManager
import com.voicegrowth.app.service.RecordingStateStore
import com.voicegrowth.app.workers.AudioProcessingWorker
import com.voicegrowth.app.workers.CleanupWorker
import com.voicegrowth.app.workers.DailyDigestWorker
import com.voicegrowth.app.workers.DriveSyncWorker
import com.voicegrowth.app.workers.FolderScanWorker
import java.util.Calendar
import java.util.concurrent.TimeUnit

class VoiceGrowthApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        RecordingStateStore.setRecording(this, false)
        createNotificationChannels()
        schedulePeriodicWork(this)
        CaptureNotificationManager.showReady(this)
    }

    fun enqueueAudioProcessing() {
        val request = OneTimeWorkRequestBuilder<AudioProcessingWorker>()
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            AudioProcessingWorker.WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    fun enqueueDriveSync(wifiOnly: Boolean) {
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            DriveSyncWorker.WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    fun enqueueFolderScanNow() {
        val request = OneTimeWorkRequestBuilder<FolderScanWorker>()
            .setInputData(workDataOf(FolderScanWorker.INPUT_FORCE_SCAN to true))
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
        // New ID intentionally avoids inheriting a previously muted recording channel.
        const val CHANNEL_CAPTURE_ID = "voicegrowth_capture_controls_v2"

        fun schedulePeriodicWork(context: Context) {
            val workManager = WorkManager.getInstance(context)
            val scanRequest = PeriodicWorkRequestBuilder<FolderScanWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build()).build()
            workManager.enqueueUniquePeriodicWork(FolderScanWorker.WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, scanRequest)

            val cleanupRequest = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS).build()
            workManager.enqueueUniquePeriodicWork(CleanupWorker.WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, cleanupRequest)

            val digestRequest = PeriodicWorkRequestBuilder<DailyDigestWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(millisUntilNextDigestWindow(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .build()
            workManager.enqueueUniquePeriodicWork(DailyDigestWorker.WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, digestRequest)
        }

        private fun millisUntilNextDigestWindow(): Long {
            val now = Calendar.getInstance()
            val next = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 21)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
            }
            return (next.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
        }
    }
}
