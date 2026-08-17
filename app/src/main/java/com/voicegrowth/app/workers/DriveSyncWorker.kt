package com.voicegrowth.app.workers

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.preferences.AppSettings
import com.voicegrowth.app.sync.DriveSyncService
import com.voicegrowth.app.sync.GoogleAuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File

/** Uploads already-processed files. Network constraints are supplied by VoiceGrowthApplication. */
class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val drive = DriveSyncService(appContext)

    override suspend fun doWork(): Result {
        val app = applicationContext as VoiceGrowthApplication
        val repository = app.container.recordingRepository
        val settings = repository.settingsFlow.first()
        val candidates = repository.getSyncCandidates()
        if (candidates.isEmpty()) return Result.success()

        if (!settings.uploadTranscript && !settings.uploadAudio) {
            candidates.filter { it.status != ProcessingStatus.UPLOADED }.forEach {
                repository.updateStatusResetRetry(it.id, ProcessingStatus.LOCAL_READY)
            }
            return Result.success()
        }

        fun needsUpload(item: RecordingEntity): Boolean =
            (settings.uploadTranscript && item.driveFileId.isNullOrBlank()) ||
                (settings.uploadAudio && item.driveAudioFileId.isNullOrBlank())

        // Reconcile stale local states without touching rows that still need an upload.
        candidates.filterNot(::needsUpload)
            .filter { it.status != ProcessingStatus.UPLOADED }
            .forEach { repository.updateStatusResetRetry(it.id, ProcessingStatus.UPLOADED, null) }

        val pending = candidates.filter(::needsUpload)
        if (pending.isEmpty()) return Result.success()

        val account = GoogleAuthManager.getSignedInAccount(applicationContext)
        if (account == null) {
            pending.forEach {
                repository.updateStatus(it.id, ProcessingStatus.WAITING_FOR_SYNC, "Connect Google Drive to sync")
            }
            return Result.success()
        }

        var retryNeeded = false
        for (candidate in pending) {
            var tempAudio: File? = null
            try {
                var current = candidate
                if (settings.uploadTranscript && current.driveFileId.isNullOrBlank()) {
                    val transcript = current.transcriptPath?.let(::File)
                        ?: error("Transcript path is missing")
                    require(transcript.exists() && transcript.length() > 0L) { "Transcript file is missing" }
                    val result = drive.uploadFile(
                        account = account,
                        localFile = transcript,
                        mimeType = "text/markdown",
                        recordedAtMillis = current.recordedAt,
                        baseHierarchy = settings.driveFolderHierarchy,
                        description = "VoiceGrowth de-identified transcript; manual privacy review recommended"
                    ).getOrThrow()
                    repository.updateTranscriptUpload(current.id, result.fileId, result.webViewLink)
                    current = repository.getById(current.id) ?: current
                }

                if (settings.uploadAudio && current.driveAudioFileId.isNullOrBlank()) {
                    tempAudio = copyAudioToCache(current)
                    val audioResult = drive.uploadFile(
                        account = account,
                        localFile = tempAudio,
                        mimeType = mimeTypeFor(current.fileName),
                        recordedAtMillis = current.recordedAt,
                        baseHierarchy = audioHierarchy(settings),
                        description = "VoiceGrowth original audio. May contain identifiable clinical information."
                    ).getOrThrow()
                    repository.updateAudioUpload(current.id, audioResult.fileId)
                }

                repository.updateStatusResetRetry(current.id, ProcessingStatus.UPLOADED, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message?.take(500) ?: e::class.java.simpleName
                val attemptsAfterThisFailure = candidate.retryCount + 1
                if (attemptsAfterThisFailure >= MAX_RETRIES) {
                    repository.recordRetry(candidate.id, ProcessingStatus.FAILED, "Drive sync: $message")
                } else {
                    repository.recordRetry(candidate.id, ProcessingStatus.WAITING_FOR_SYNC, "Drive sync: $message")
                    retryNeeded = true
                }
            } finally {
                tempAudio?.delete()
            }
        }
        return if (retryNeeded) Result.retry() else Result.success()
    }

    private fun audioHierarchy(settings: AppSettings): String {
        val base = settings.driveFolderHierarchy.trim('/').ifBlank { "VoiceGrowth/Transcripts" }
        return if (base.endsWith("/Transcripts", ignoreCase = true)) {
            base.removeSuffix("/Transcripts") + "/Audio"
        } else {
            "$base/Audio"
        }
    }

    private fun copyAudioToCache(recording: RecordingEntity): File {
        val ext = recording.fileName.substringAfterLast('.', "m4a").lowercase()
        val target = File(applicationContext.cacheDir, "upload_${recording.id}_${System.nanoTime()}.$ext")
        val uri = Uri.parse(recording.uriString)
        when (uri.scheme?.lowercase()) {
            "file" -> File(requireNotNull(uri.path)).copyTo(target, overwrite = true)
            else -> {
                val input = applicationContext.contentResolver.openInputStream(uri)
                    ?: error("Original audio is no longer accessible")
                input.use { source -> target.outputStream().use(source::copyTo) }
            }
        }
        require(target.length() > 0L) { "Original audio is empty" }
        return target
    }

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "3gp" -> "audio/3gpp"
        "amr" -> "audio/amr"
        "ogg", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    companion object {
        const val WORK_NAME = "VoiceGrowth_DriveSync"
        private const val MAX_RETRIES = 5
    }
}
