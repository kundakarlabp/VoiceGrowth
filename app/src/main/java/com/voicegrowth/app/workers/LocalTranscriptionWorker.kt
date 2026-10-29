package com.voicegrowth.app.workers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.sync.DriveTreeSyncService
import com.voicegrowth.app.transcription.LocalAudioWavDecoder
import com.voicegrowth.app.transcription.LocalTranscriptPolicy
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Opt-in pilot, no network inference/API. Processes one short recording at a time
 * only after the original has already been saved to the selected Drive provider.
 * Processing never changes the source-audio upload state or deletes source audio.
 */
class LocalTranscriptionWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as VoiceGrowthApplication
        val repository = app.container.recordingRepository
        val settings = repository.settingsFlow.first()
        if (!settings.offlineTranscriptionEnabled) return Result.success()

        val model = settings.offlineWhisperModelPath?.let(::File)
            ?.takeIf { it.isFile && it.length() > 50_000_000L &&
                it.canonicalPath.startsWith(File(applicationContext.filesDir, "models").canonicalPath + File.separator) }
            ?: return Result.success() // first-use model import still required

        // Retry transcript transport first, without rerunning expensive inference.
        val unsynced = repository.getTranscriptSyncCandidates().firstOrNull()
        if (unsynced != null) {
            return try {
                syncTranscript(unsynced, settings)
                scheduleMore(app)
                Result.success()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w(TAG, "Transcript provider sync failed", e)
                if (runAttemptCount < MAX_RETRY) Result.retry() else Result.success()
            }
        }

        val candidate = repository.getLocalTranscriptionCandidates(limit = 1).firstOrNull()
            ?: return Result.success()
        var tempAudio: File? = null
        var tempWav: File? = null
        try {
            val inputAudio = File.createTempFile("vg-asr-source-", ".m4a", applicationContext.cacheDir)
            tempAudio = inputAudio
            val sourceUri = Uri.parse(candidate.uriString)
            applicationContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                inputAudio.outputStream().use(input::copyTo)
            } ?: error("Original recording no longer readable")
            val wavAudio = File.createTempFile("vg-asr-decoded-", ".wav", applicationContext.cacheDir)
            tempWav = wavAudio
            LocalAudioWavDecoder.convert(inputAudio, wavAudio)
            val engine = Whisper.loadModel(applicationContext, model.absolutePath)
            val output = try {
                Whisper.transcribe(engine, wavAudio.absolutePath, WhisperConfig(language = "auto"))
            } finally {
                Whisper.releaseModel(engine)
            }
            require(output.text.isNotBlank()) { "No speech recognized; check audio quality" }
            val transcripts = File(applicationContext.filesDir, "transcripts").apply { mkdirs() }
            val outFile = File(
                transcripts,
                LocalTranscriptPolicy.name(candidate.id, candidate.recordedAt, candidate.source.name, candidate.fileName)
            )
            val header = buildString {
                appendLine("# Draft local transcript — NOT CLINICALLY VERIFIED")
                appendLine()
                appendLine("Source: ${candidate.fileName}")
                appendLine("Engine: offline whisper.cpp (pilot), model: ${model.name}")
                appendLine("Recorded: ${java.util.Date(candidate.recordedAt)}")
                appendLine("Warning: Verify all CR numbers, names, species, culture results and doses against source audio.")
                appendLine()
                appendLine("## Timestamped recognition output")
                appendLine()
                output.segments.forEach {
                    appendLine("[${it.startMs / 1000}s–${it.endMs / 1000}s] ${it.text}")
                }
                appendLine()
                appendLine("## Uncorrected transcript")
                appendLine()
                appendLine(output.text)
            }
            // A completed transcript is persisted before cloud sync is attempted.
            val staging = File(outFile.parentFile, outFile.name + ".partial")
            staging.writeText(header, Charsets.UTF_8)
            require(staging.renameTo(outFile)) { "Could not commit transcript atomically" }
            repository.updateTranscript(candidate.id, outFile.absolutePath, emptyList(),
                candidate.durationSeconds, candidate.status, System.currentTimeMillis())
            syncTranscript(candidate.copy(transcriptPath = outFile.absolutePath), settings)
            scheduleMore(app)
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Local ASR pilot failed for recording ${candidate.id}", e)
            // No fake clinical transcript. The existing audio transport state is untouched.
            if (repository.getById(candidate.id)?.transcriptPath != null) {
                return if (runAttemptCount < MAX_RETRY) Result.retry() else Result.success()
            }
            repository.updateStatus(candidate.id, candidate.status, "Local ASR: ${(e.message ?: e.javaClass.simpleName).take(160)}")
            scheduleMore(app)
            return Result.success()
        } finally {
            tempAudio?.delete()
            tempWav?.delete()
        }
    }

    private suspend fun syncTranscript(
        record: RecordingEntity,
        settings: com.voicegrowth.app.data.preferences.AppSettings
    ) {
        if (record.driveFileId != null) return
        val tree = settings.driveTreeUri?.let(Uri::parse) ?: error("Choose VoiceGrowth Drive root")
        val service = DriveTreeSyncService(applicationContext)
        val status = service.inspect(tree)
        require(status.usable) { status.message }
        // Selecting Audio itself cannot reach sibling Transcripts through SAF.
        require(!status.displayName.equals("Audio", ignoreCase = true)) {
            "Select VoiceGrowth root rather than Audio to save sibling transcripts"
        }
        val saved = requireNotNull(record.transcriptPath).let(::File)
        require(saved.isFile && saved.length() > 0) { "Transcript missing locally" }
        val result = service.uploadFile(
            treeUri = tree, localFile = saved, mimeType = "text/markdown",
            recordedAtMillis = record.recordedAt,
            remoteFileName = LocalTranscriptPolicy.name(record.id, record.recordedAt,
                record.source.name, record.fileName),
            baseHierarchy = LocalTranscriptPolicy.archiveHierarchy(settings.driveFolderHierarchy)
        ).getOrThrow()
        // content:// provider URI, not a remote Google Drive REST ID.
        (applicationContext as VoiceGrowthApplication).container.recordingRepository
            .updateTranscriptUpload(record.id, result.fileId, result.webViewLink)
    }

    private fun scheduleMore(app: VoiceGrowthApplication) = app.enqueueLocalTranscription()

    companion object {
        const val WORK_NAME = "VoiceGrowth_LocalTranscription"
        private const val TAG = "VoiceGrowthASR"
        private const val MAX_RETRY = 3
    }
}
