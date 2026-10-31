package com.voicegrowth.app.workers

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.preferences.AppSettings
import com.voicegrowth.app.service.RecordingStateStore
import com.voicegrowth.app.sync.DriveTreeSyncService
import com.voicegrowth.app.transcription.AsrCheckpointStore
import com.voicegrowth.app.transcription.AudioDurationProbe
import com.voicegrowth.app.transcription.LocalTranscriptPolicy
import com.voicegrowth.app.transcription.SegmentedAudioWavDecoder
import com.voicegrowth.app.transcription.TranscriptSegmentPlan
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Charging-only offline transcription of archived recordings up to 2h.
 * Stores each completed chunk independently; up to 3 chunks/worker session,
 * then resumes through a queued worker or the 15-minute periodic scanner.
 * Never modifies the original audio or its provider-save status.
 */
class LocalTranscriptionWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = serial.withLock {
        process()
    }

    private suspend fun process(): Result {
        val app = applicationContext as VoiceGrowthApplication
        val repository = app.container.recordingRepository
        val settings = repository.settingsFlow.first()
        if (!settings.offlineTranscriptionEnabled) return Result.success()
        if (RecordingStateStore.isRecording(applicationContext)) return Result.success()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                Log.i(TAG, "ASR deferred due to thermal status; periodic WorkManager will retry")
                return Result.success()
            }
        }

        val model = settings.offlineWhisperModelPath?.let(::File)
            ?.takeIf {
                it.isFile && it.length() > 50_000_000L &&
                    it.canonicalPath.startsWith(
                        File(applicationContext.filesDir, "models").canonicalPath + File.separator
                    )
            } ?: return Result.success()

        // Transport unfinished drafts first without rerunning costly inference.
        val unsynced = repository.getTranscriptSyncCandidates().firstOrNull()
        if (unsynced != null) {
            return try {
                syncTranscript(unsynced, settings)
                app.enqueueLocalTranscription(delayMinutes = 2)
                Result.success()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w(TAG, "Transcript provider sync failed", e)
                if (runAttemptCount < MAX_RETRIES) Result.retry()
                else {
                    repository.updateStatus(unsynced.id, unsynced.status,
                        "Local ASR sync: ${(e.message ?: e.javaClass.simpleName).take(150)}")
                    Result.success()
                }
            }
        }

        var candidate = repository.getLocalTranscriptionCandidates(limit = 1).firstOrNull()
            ?: return Result.success()
        if (candidate.durationSeconds == 0L) {
            try {
                val seconds = AudioDurationProbe.seconds(applicationContext, Uri.parse(candidate.uriString))
                repository.updateDurationIfMissing(candidate.id, seconds)
                candidate = candidate.copy(durationSeconds = seconds)
            } catch (error: Exception) {
                repository.updateStatus(candidate.id, candidate.status,
                    "Local ASR: ${(error.message ?: "Duration unavailable").take(150)}")
                return Result.success()
            }
        }
        val checkpoint = try {
            AsrCheckpointStore(File(applicationContext.filesDir, "asr_checkpoints"), candidate, model)
        } catch (error: Exception) {
            repository.updateStatus(candidate.id, candidate.status,
                "Local ASR: ${(error.message ?: "Invalid recording").take(150)}")
            return Result.success()
        }
        try {
            val started = SystemClock.elapsedRealtime()
            val source = Uri.parse(candidate.uriString)
            var progressed = false
            if (checkpoint.nextMissing() != null) {
                val engine = Whisper.loadModel(applicationContext, model.absolutePath)
                try {
                    var completedThisRun = 0
                    while (completedThisRun < MAX_CHUNKS_PER_RUN &&
                        SystemClock.elapsedRealtime() - started < SESSION_MILLIS &&
                        !isStopped && !RecordingStateStore.isRecording(applicationContext)) {
                        val nextIndex = checkpoint.nextMissing() ?: break
                        val window = TranscriptSegmentPlan.window(checkpoint.durationMs, nextIndex)
                        val wav = File.createTempFile("vg-segment-", ".wav", applicationContext.cacheDir)
                        try {
                            SegmentedAudioWavDecoder.convertWindow(applicationContext, source, wav, window)
                            val output = Whisper.transcribe(engine, wav.absolutePath,
                                WhisperConfig(language = "auto"))
                            val segments = JSONArray()
                            for (s in output.segments) {
                                segments.put(JSONObject()
                                    .put("startMs", window.startMs + s.startMs)
                                    .put("endMs", window.startMs + s.endMs)
                                    .put("text", s.text))
                            }
                            // An empty chunk is valid silence; do not hallucinate text.
                            checkpoint.save(nextIndex, output.text, segments)
                            completedThisRun++
                            progressed = true
                            setProgress(workDataOf(
                                "recordingId" to candidate.id,
                                "completedWindows" to (nextIndex + 1),
                                "totalWindows" to checkpoint.windows
                            ))
                        } finally {
                            wav.delete()
                        }
                    }
                } finally {
                    Whisper.releaseModel(engine)
                }
            }
            if (isStopped) return Result.success()

            if (checkpoint.nextMissing() == null) {
                val outFile = File(File(applicationContext.filesDir, "transcripts").apply { mkdirs() },
                    LocalTranscriptPolicy.name(candidate.id, candidate.recordedAt,
                        candidate.source.name, candidate.fileName))
                AsrCheckpointStore.writeAtomic(outFile, checkpoint.assembleMarkdown(model.name))
                // Persist the committed transcript path before any Drive operation.
                repository.updateTranscript(candidate.id, outFile.absolutePath, emptyList(),
                    candidate.durationSeconds, candidate.status, System.currentTimeMillis())
                checkpoint.cleanupAfterCommit()
                syncTranscript(candidate.copy(transcriptPath = outFile.absolutePath), settings)
                app.enqueueLocalTranscription(delayMinutes = 2)
            } else if (progressed) {
                app.enqueueLocalTranscription(delayMinutes = 2)
            }
            return Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "ASR segment failed for recording ${candidate.id}", e)
            val stored = repository.getById(candidate.id)
            if (stored?.transcriptPath != null) {
                // Transcript safely committed; the next run will retry cloud sync.
                return if (runAttemptCount < MAX_RETRIES) Result.retry()
                else Result.success()
            }
            repository.updateStatus(candidate.id, candidate.status,
                "Local ASR: ${(e.message ?: e.javaClass.simpleName).take(150)}")
            return Result.success() // explicit UI retry clears error; earlier chunks remain
        }
    }

    private suspend fun syncTranscript(record: RecordingEntity, settings: AppSettings) {
        if (record.driveFileId != null) return
        val tree = settings.driveTreeUri?.let(Uri::parse) ?: error("Select a VoiceGrowth Drive root")
        val service = DriveTreeSyncService(applicationContext)
        val status = service.inspect(tree)
        require(status.usable) { status.message }
        require(!status.displayName.equals("Audio", ignoreCase = true)) {
            "Select VoiceGrowth root rather than Audio for sibling transcripts"
        }
        val transcript = requireNotNull(record.transcriptPath).let(::File)
        require(transcript.isFile && transcript.length() > 0) { "Local draft transcript is missing" }
        val uploaded = service.uploadFile(
            treeUri = tree,
            localFile = transcript,
            mimeType = "text/markdown",
            recordedAtMillis = record.recordedAt,
            remoteFileName = LocalTranscriptPolicy.name(record.id, record.recordedAt,
                record.source.name, record.fileName),
            baseHierarchy = LocalTranscriptPolicy.archiveHierarchy(settings.driveFolderHierarchy)
        ).getOrThrow()
        (applicationContext as VoiceGrowthApplication).container.recordingRepository
            .updateTranscriptUpload(record.id, uploaded.fileId, uploaded.webViewLink)
        // provider saved != Google cloud verified; no claim of remote verification.
    }

    companion object {
        const val WORK_NAME = "VoiceGrowth_LocalTranscription"
        private const val TAG = "VoiceGrowthASR"
        private const val MAX_RETRIES = 3
        private const val MAX_CHUNKS_PER_RUN = 3
        private const val SESSION_MILLIS = 150_000L
        private val serial = Mutex()
    }
}
