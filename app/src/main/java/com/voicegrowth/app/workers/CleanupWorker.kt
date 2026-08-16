package com.voicegrowth.app.workers

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voicegrowth.app.VoiceGrowthApplication
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/** Removes local source audio only after local processing/sync has completed and retention has expired. */
class CleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as VoiceGrowthApplication
        val repository = app.container.recordingRepository
        val settings = repository.settingsFlow.first()
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(settings.deleteLocalAudioDays.toLong())

        return runCatching {
            val cloudUploadRequired = settings.uploadTranscript || settings.uploadAudio
            repository.getCompletedOlderThan(cutoff).forEach { recording ->
                val requiredCloudCopiesExist =
                    (!settings.uploadTranscript || !recording.driveFileId.isNullOrBlank()) &&
                        (!settings.uploadAudio || !recording.driveAudioFileId.isNullOrBlank())
                // Never remove source audio that may still be needed for a newly enabled cloud upload.
                if (!cloudUploadRequired || requiredCloudCopiesExist) {
                    deleteAudio(recording.uriString, recording.filePath)
                }
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun deleteAudio(uriString: String, fallbackPath: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri != null) {
            when (uri.scheme?.lowercase()) {
                "file" -> return uri.path?.let { File(it).delete() } ?: false
                "content" -> {
                    val deleted = runCatching {
                        DocumentFile.fromSingleUri(applicationContext, uri)?.delete() == true
                    }.getOrDefault(false)
                    if (deleted) return true
                    return runCatching {
                        applicationContext.contentResolver.delete(uri, null, null) > 0
                    }.getOrDefault(false)
                }
            }
        }
        return File(fallbackPath).takeIf { it.exists() }?.delete() ?: false
    }

    companion object {
        const val WORK_NAME = "VoiceGrowth_Cleanup"
    }
}
