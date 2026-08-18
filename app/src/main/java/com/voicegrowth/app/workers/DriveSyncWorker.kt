package com.voicegrowth.app.workers

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.preferences.AppSettings
import com.voicegrowth.app.sync.DriveAuthorizationAttempt
import com.voicegrowth.app.sync.DriveSyncService
import com.voicegrowth.app.sync.DriveTreeSyncService
import com.voicegrowth.app.sync.DriveUploadResult
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
    private val driveTree = DriveTreeSyncService(appContext)

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

        candidates.filterNot(::needsUpload)
            .filter { it.status != ProcessingStatus.UPLOADED }
            .forEach { repository.updateStatusResetRetry(it.id, ProcessingStatus.UPLOADED, null) }

        val pending = candidates.filter(::needsUpload)
        if (pending.isEmpty()) return Result.success()

        val treeUri = settings.driveTreeUri?.takeIf(String::isNotBlank)?.let(Uri::parse)
        if (treeUri != null) {
            val status = driveTree.inspect(treeUri)
            if (!status.usable) {
                pending.forEach {
                    repository.updateStatus(
                        it.id,
                        ProcessingStatus.WAITING_FOR_SYNC,
                        "Drive folder unavailable: ${status.message}"
                    )
                }
                return Result.success()
            }
        }

        val accessToken = if (treeUri == null) {
            try {
                when (val attempt = GoogleAuthManager.authorize(applicationContext)) {
                    is DriveAuthorizationAttempt.Authorized -> attempt.authorization.accessToken
                    is DriveAuthorizationAttempt.NeedsResolution -> {
                        pending.forEach {
                            repository.updateStatus(
                                it.id,
                                ProcessingStatus.WAITING_FOR_SYNC,
                                "Choose a Google Drive folder in Settings (recommended), or confirm optional OAuth access."
                            )
                        }
                        return Result.success()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = GoogleAuthManager.userFacingError(applicationContext, error).take(500)
                pending.forEach { repository.updateStatus(it.id, ProcessingStatus.WAITING_FOR_SYNC, message) }
                return if ((error as? com.google.android.gms.common.api.ApiException)?.statusCode == 7) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }
        } else {
            null
        }

        suspend fun upload(
            localFile: File,
            mimeType: String,
            recordedAtMillis: Long,
            hierarchy: String,
            description: String
        ): DriveUploadResult {
            return if (treeUri != null) {
                driveTree.uploadFile(
                    treeUri = treeUri,
                    localFile = localFile,
                    mimeType = mimeType,
                    recordedAtMillis = recordedAtMillis,
                    baseHierarchy = hierarchy
                ).getOrThrow()
            } else {
                drive.uploadFile(
                    accessToken = requireNotNull(accessToken),
                    localFile = localFile,
                    mimeType = mimeType,
                    recordedAtMillis = recordedAtMillis,
                    baseHierarchy = hierarchy,
                    description = description
                ).getOrThrow()
            }
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
                    val result = upload(
                        localFile = transcript,
                        mimeType = "text/markdown",
                        recordedAtMillis = current.recordedAt,
                        hierarchy = settings.driveFolderHierarchy,
                        description = "VoiceGrowth de-identified transcript; manual privacy review recommended"
                    )
                    repository.updateTranscriptUpload(current.id, result.fileId, result.webViewLink)
                    current = repository.getById(current.id) ?: current
                }

                if (settings.uploadAudio && current.driveAudioFileId.isNullOrBlank()) {
                    tempAudio = copyAudioToCache(current)
                    val audioResult = upload(
                        localFile = tempAudio,
                        mimeType = mimeTypeFor(current.fileName),
                        recordedAtMillis = current.recordedAt,
                        hierarchy = audioHierarchy(settings),
                        description = "VoiceGrowth original audio. May contain identifiable clinical information."
                    )
                    repository.updateAudioUpload(current.id, audioResult.fileId)
                }

                repository.updateStatusResetRetry(current.id, ProcessingStatus.UPLOADED, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GoogleJsonResponseException) {
                when (e.statusCode) {
                    401 -> {
                        repository.updateStatus(
                            candidate.id,
                            ProcessingStatus.WAITING_FOR_SYNC,
                            "Google Drive authorization expired. Reconnect OAuth or use the recommended Drive folder method in Settings."
                        )
                        retryNeeded = true
                    }
                    403 -> {
                        repository.updateStatus(
                            candidate.id,
                            ProcessingStatus.WAITING_FOR_SYNC,
                            "Google Drive denied the OAuth request. Use the recommended Drive folder method or confirm Drive API configuration."
                        )
                    }
                    else -> retryNeeded = recordFileFailure(repository, candidate, e.message ?: "Drive HTTP ${e.statusCode}") || retryNeeded
                }
            } catch (e: Exception) {
                if (treeUri != null) {
                    val status = driveTree.inspect(treeUri)
                    if (!status.usable) {
                        repository.updateStatus(
                            candidate.id,
                            ProcessingStatus.WAITING_FOR_SYNC,
                            "Drive folder access needs attention: ${status.message}"
                        )
                        continue
                    }
                }
                retryNeeded = recordFileFailure(
                    repository = repository,
                    candidate = candidate,
                    message = e.message ?: e::class.java.simpleName
                ) || retryNeeded
            } finally {
                tempAudio?.delete()
            }
        }
        return if (retryNeeded) Result.retry() else Result.success()
    }

    private suspend fun recordFileFailure(
        repository: com.voicegrowth.app.data.repository.RecordingRepository,
        candidate: RecordingEntity,
        message: String
    ): Boolean {
        val attemptsAfterThisFailure = candidate.retryCount + 1
        val clean = message.take(500)
        return if (attemptsAfterThisFailure >= MAX_RETRIES) {
            repository.recordRetry(candidate.id, ProcessingStatus.FAILED, "Drive sync: $clean")
            false
        } else {
            repository.recordRetry(candidate.id, ProcessingStatus.WAITING_FOR_SYNC, "Drive sync: $clean")
            true
        }
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
