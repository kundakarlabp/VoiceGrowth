package com.voicegrowth.app.workers

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.preferences.AppSettings
import com.voicegrowth.app.sync.DriveTreeSyncService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VoiceGrowth Android transport.
 *
 * The Android app has one durable responsibility: archive the original audio into the user-selected
 * private Drive tree. It does not transcribe, diarize, summarize, or upload locally generated
 * transcript artifacts. The backend transcription bridge discovers the persisted Drive audio by
 * immutable Drive file ID and performs transcription from those exact bytes.
 */
class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val driveTree = DriveTreeSyncService(appContext)
    private val fileStampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    override suspend fun doWork(): Result {
        val app = applicationContext as VoiceGrowthApplication
        val repository = app.container.recordingRepository
        val settings = repository.settingsFlow.first()
        val candidates = repository.getSyncCandidates()
            .filter { it.driveAudioFileId.isNullOrBlank() }
        if (candidates.isEmpty()) return Result.success()

        val treeUri = settings.driveTreeUri?.takeIf(String::isNotBlank)?.let(Uri::parse)
        if (treeUri == null) {
            candidates.forEach {
                repository.updateStatus(
                    it.id,
                    ProcessingStatus.WAITING_FOR_SYNC,
                    "Choose the canonical private VoiceGrowth Drive folder in Settings."
                )
            }
            return Result.success()
        }

        val treeStatus = driveTree.inspect(treeUri)
        if (!treeStatus.usable) {
            candidates.forEach {
                repository.updateStatus(
                    it.id,
                    ProcessingStatus.WAITING_FOR_SYNC,
                    "Drive folder unavailable: ${treeStatus.message}"
                )
            }
            return Result.success()
        }

        var retryNeeded = false
        for (candidate in candidates) {
            var tempAudio: File? = null
            try {
                tempAudio = copyAudioToCache(candidate)
                val audioResult = driveTree.uploadFile(
                    treeUri = treeUri,
                    localFile = tempAudio,
                    mimeType = mimeTypeFor(candidate.fileName),
                    recordedAtMillis = candidate.recordedAt,
                    baseHierarchy = audioHierarchy(settings)
                ).getOrThrow()

                require(audioResult.fileId.isNotBlank()) {
                    "Drive upload completed without a stable file ID"
                }
                repository.updateAudioUpload(candidate.id, audioResult.fileId)
                repository.updateStatusResetRetry(candidate.id, ProcessingStatus.UPLOADED, null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val freshStatus = driveTree.inspect(treeUri)
                if (!freshStatus.usable) {
                    repository.updateStatus(
                        candidate.id,
                        ProcessingStatus.WAITING_FOR_SYNC,
                        "Drive folder access needs attention: ${freshStatus.message}"
                    )
                } else {
                    retryNeeded = recordFileFailure(
                        repository = repository,
                        candidate = candidate,
                        message = error.message ?: error::class.java.simpleName
                    ) || retryNeeded
                }
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
        val configured = settings.driveFolderHierarchy.trim('/').ifBlank { "VoiceGrowth/Audio" }
        val normalized = when {
            configured.equals("VoiceGrowth", ignoreCase = true) -> "VoiceGrowth/Audio"
            configured.endsWith("/Transcripts", ignoreCase = true) -> configured.substringBeforeLast('/') + "/Audio"
            configured.equals("Transcripts", ignoreCase = true) -> "VoiceGrowth/Audio"
            configured.endsWith("/Audio", ignoreCase = true) -> configured
            else -> configured
        }
        return normalized.trim('/').ifBlank { "VoiceGrowth/Audio" }
    }

    /**
     * Copy provider/file audio to a fresh cache object before upload. The cache file is per-recording
     * and deleted after the upload so no transcription worker can accidentally reuse it.
     */
    private fun copyAudioToCache(recording: RecordingEntity): File {
        val ext = recording.fileName.substringAfterLast('.', "m4a")
            .lowercase(Locale.US)
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "m4a"
        val sourceLabel = recording.source.name.lowercase(Locale.US)
        val stamp = synchronized(fileStampFormat) { fileStampFormat.format(Date(recording.recordedAt)) }
        val target = File.createTempFile(
            "VG_${stamp}_${recording.id}_${sourceLabel}_",
            ".$ext",
            applicationContext.cacheDir,
        )
        val uri = Uri.parse(recording.uriString)
        when (uri.scheme?.lowercase(Locale.US)) {
            "file" -> {
                val source = File(requireNotNull(uri.path) { "Invalid file URI" })
                require(source.exists()) { "Original audio is no longer available" }
                source.copyTo(target, overwrite = true)
            }
            else -> {
                val input = applicationContext.contentResolver.openInputStream(uri)
                    ?: error("Original audio is no longer accessible")
                input.use { source -> target.outputStream().use(source::copyTo) }
            }
        }
        require(target.length() > 0L) { "Original audio is empty" }
        return target
    }

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "m4a", "mp4" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "3gp" -> "audio/3gpp"
        "amr" -> "audio/amr"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    companion object {
        const val WORK_NAME = "VoiceGrowth_DriveSync"
        private const val MAX_RETRIES = 5
    }
}
