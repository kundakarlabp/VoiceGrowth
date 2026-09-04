package com.voicegrowth.app.workers

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.localai.AudioPcmDecoder
import com.voicegrowth.app.localai.LocalModelManager
import com.voicegrowth.app.localai.LocalTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zero-cost local ASR stage.
 *
 * Runs only after the original audio has a Drive archive reference. Model inference stays entirely
 * on-device. The resulting provenance-rich transcript is written locally; DriveSyncWorker uploads it.
 */
class LocalTranscriptionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val app = applicationContext as VoiceGrowthApplication
        val repository = app.container.recordingRepository
        val candidates = repository.getLocalTranscriptionCandidates(MAX_FILES_PER_RUN)
        if (candidates.isEmpty()) return@withContext Result.success()

        val models = try {
            LocalModelManager(applicationContext).ensureInstalled()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            candidates.forEach { candidate ->
                repository.updateStatus(
                    candidate.id,
                    candidate.status,
                    "Local ASR model setup will retry automatically: ${clean(error)}",
                )
            }
            return@withContext Result.retry()
        }

        val transcriber = LocalTranscriber(models)
        var retryNeeded = false
        for (candidate in candidates) {
            try {
                repository.updateStatus(candidate.id, ProcessingStatus.TRANSCRIBING, null)
                val sourceUri = sourceUri(candidate)
                val pcm = AudioPcmDecoder.decode(applicationContext, sourceUri)
                require(pcm.samples.isNotEmpty()) { "Decoded audio contained no samples" }

                val result = transcriber.transcribe(pcm.samples, pcm.sampleRate)
                require(result.segments.isNotEmpty()) { "Local ASR returned an empty transcript" }

                val transcriptFile = writeTranscript(candidate, result, pcm.samples.size / pcm.sampleRate.toLong())
                repository.updateTranscript(
                    id = candidate.id,
                    transcriptPath = transcriptFile.absolutePath,
                    themes = emptyList(),
                    durationSeconds = pcm.samples.size / pcm.sampleRate.toLong(),
                    status = ProcessingStatus.LOCAL_READY,
                    processedAt = System.currentTimeMillis(),
                )
                app.enqueueDriveSync(wifiOnly = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val attempts = candidate.retryCount + 1
                val message = "Local ASR retry $attempts: ${clean(error)}"
                // Keep the item eligible. Periodic local-ASR work will try again without user action.
                repository.recordRetry(candidate.id, ProcessingStatus.UPLOADED, message)
                retryNeeded = true
            }
        }
        if (retryNeeded) Result.retry() else Result.success()
    }

    private fun sourceUri(recording: RecordingEntity): Uri {
        val parsed = Uri.parse(recording.uriString)
        if (!parsed.scheme.isNullOrBlank()) return parsed
        val local = File(recording.filePath)
        require(local.exists()) { "Original local audio is unavailable" }
        return Uri.fromFile(local)
    }

    private fun writeTranscript(
        recording: RecordingEntity,
        result: LocalTranscriber.Result,
        durationSeconds: Long,
    ): File {
        val directory = File(applicationContext.filesDir, "transcripts").apply { mkdirs() }
        val stem = archiveStem(recording)
        val file = File(directory, "$stem - Transcript.md")
        val now = ISO_FORMAT.format(Date())
        file.bufferedWriter().use { out ->
            out.appendLine("# $stem - Transcript")
            out.appendLine()
            out.appendLine("- Source archive filename: `${archiveAudioName(recording)}`")
            out.appendLine("- Source Drive reference: `${recording.driveAudioFileId.orEmpty()}`")
            out.appendLine("- VoiceGrowth local record ID: `${recording.id}`")
            out.appendLine("- Recorded at: `${ISO_FORMAT.format(Date(recording.recordedAt))}`")
            out.appendLine("- Transcribed at: `$now`")
            out.appendLine("- Duration seconds: `$durationSeconds`")
            out.appendLine("- Engine: `sherpa-onnx / Whisper base INT8 (offline)`")
            out.appendLine("- Speaker diarization: `${if (result.diarizationAvailable) "available" else "fallback single-speaker/chunked"}`")
            result.language?.takeIf(String::isNotBlank)?.let { out.appendLine("- Detected language: `$it`") }
            out.appendLine()
            out.appendLine("## Verbatim machine transcript")
            out.appendLine()
            out.appendLine("> Machine transcription. Preserve uncertainty; clinically important names, doses and numbers require verification against audio/context when consequential.")
            out.appendLine()
            for (segment in result.segments) {
                val start = LocalTranscriber.formatTimestamp(segment.start)
                val end = LocalTranscriber.formatTimestamp(segment.end)
                out.appendLine("**[$start–$end] Speaker ${segment.speaker + 1}:** ${segment.text}")
                out.appendLine()
            }
        }
        require(file.length() > 0L) { "Transcript file was not persisted" }
        return file
    }

    private fun archiveStem(recording: RecordingEntity): String = archiveAudioName(recording).substringBeforeLast('.')

    private fun archiveAudioName(recording: RecordingEntity): String {
        val ext = recording.fileName.substringAfterLast('.', "m4a")
            .lowercase(Locale.US)
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "m4a"
        val stamp = FILE_STAMP_FORMAT.format(Date(recording.recordedAt))
        return "VG_${stamp}_${recording.id}_${recording.source.name.lowercase(Locale.US)}.$ext"
    }

    private fun clean(error: Throwable): String =
        (error.message ?: error::class.java.simpleName).replace('\n', ' ').take(300)

    companion object {
        const val WORK_NAME = "VoiceGrowth_LocalTranscription"
        private const val MAX_FILES_PER_RUN = 2
        private val FILE_STAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    }
}
