package com.voicegrowth.app.workers

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.scanner.FolderScanner
import com.voicegrowth.app.service.CaptureNotificationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Periodic fallback scan plus user-triggered Scan-now execution. */
class FolderScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as VoiceGrowthApplication
        val repository = app.container.recordingRepository
        val settings = repository.settingsFlow.first()
        val forced = inputData.getBoolean(INPUT_FORCE_SCAN, false)
        if (!settings.autoProcessing && !forced) {
            CaptureNotificationManager.showReady(applicationContext)
            return Result.success()
        }

        val folder = settings.selectedFolderUri?.takeIf { it.isNotBlank() }
        if (folder == null) {
            CaptureNotificationManager.showReady(
                applicationContext,
                if (forced) "Choose the call-recording folder in VoiceGrowth Settings" else null
            )
            return Result.success()
        }

        return try {
            val newCount = FolderScanner(applicationContext, repository)
                .scanFolder(Uri.parse(folder), settings)
            if (newCount > 0) app.enqueueAudioProcessing()
            CaptureNotificationManager.showReady(
                applicationContext,
                if (forced) {
                    if (newCount > 0) "$newCount new recording(s) queued" else "Folder checked · no new completed recordings"
                } else {
                    null
                }
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            CaptureNotificationManager.showReady(applicationContext, "Call-folder access was lost · re-select it in Settings")
            Result.success()
        } catch (e: Exception) {
            if (forced) {
                CaptureNotificationManager.showReady(
                    applicationContext,
                    "Folder scan failed: ${(e.message ?: e::class.java.simpleName).take(90)}"
                )
                Result.success()
            } else {
                CaptureNotificationManager.showReady(applicationContext)
                Result.retry()
            }
        }
    }

    companion object {
        const val WORK_NAME = "VoiceGrowth_FolderScan"
        const val ONE_TIME_WORK_NAME = "VoiceGrowth_FolderScanNow"
        const val INPUT_FORCE_SCAN = "force_scan"
    }
}
