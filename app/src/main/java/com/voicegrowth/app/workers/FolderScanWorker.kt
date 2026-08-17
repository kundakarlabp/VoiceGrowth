package com.voicegrowth.app.workers

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.scanner.FolderScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Periodic fallback scan so capture continues even if the monitor service is reclaimed. */
class FolderScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as VoiceGrowthApplication
        val repository = app.container.recordingRepository
        val settings = repository.settingsFlow.first()
        if (!settings.autoProcessing) return Result.success()

        val folder = settings.selectedFolderUri?.takeIf { it.isNotBlank() } ?: return Result.success()
        return try {
            val newCount = FolderScanner(applicationContext, repository)
                .scanFolder(Uri.parse(folder), settings)
            if (newCount > 0) app.enqueueAudioProcessing()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "VoiceGrowth_FolderScan"
    }
}
