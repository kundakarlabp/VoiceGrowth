package com.voicegrowth.app.workers

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.preferences.AppSettings
import com.voicegrowth.app.engine.ai.OnDeviceAiEngine
import com.voicegrowth.app.engine.format.TranscriptMarkdownBuilder
import com.voicegrowth.app.engine.privacy.ClinicalDeidentifier
import com.voicegrowth.app.engine.transcription.HybridTranscriptionEngine
import com.voicegrowth.app.engine.transcription.OfflineAsrModelRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Local pipeline: audio -> true offline file ASR (with Android fallback) -> de-identification ->
 * optional LiteRT-LM synthesis -> Markdown. Network work is delegated to DriveSyncWorker.
 */
class AudioProcessingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val transcriptionEngine = HybridTranscriptionEngine()
    private val aiEngine = OnDeviceAiEngine()

    override suspend fun doWork(): Result {
        val app = applicationContext as VoiceGrowthApplication
        val repository = app.container.recordingRepository
        val settings = repository.settingsFlow.first()
        if (!settings.autoProcessing) return Result.success()

        val pending = repository.getPendingRecordings()
        if (pending.isEmpty()) return Result.success()

        setForeground(createForegroundInfo("Processing 0/${pending.size}"))
        var retryNeeded = false

        pending.forEachIndexed { index, recording ->
            setForeground(createForegroundInfo("Transcribing ${index + 1}/${pending.size}: ${recording.fileName}"))
            val retry = processOne(recording, settings, app, index + 1, pending.size)
            retryNeeded = retryNeeded || retry
        }
        return if (retryNeeded) Result.retry() else Result.success()
    }

    private suspend fun processOne(
        recording: RecordingEntity,
        settings: AppSettings,
        app: VoiceGrowthApplication,
        queueIndex: Int,
        queueSize: Int
    ): Boolean {
        val repository = app.container.recordingRepository
        var tempAudio: File? = null
        return try {
            if (settings.onlyProcessOver30Sec && recording.durationSeconds in 1 until MIN_DURATION_SECONDS) {
                repository.updateStatusResetRetry(recording.id, ProcessingStatus.SKIPPED_TOO_SHORT)
                return false
            }

            repository.updateStatus(recording.id, ProcessingStatus.TRANSCRIBING)
            tempAudio = copyAudioToCache(recording)

            val transcription = transcriptionEngine
                .transcribe(applicationContext, tempAudio, settings.transcriptionLanguage)
                .getOrThrow()

            if (settings.onlyProcessOver30Sec && transcription.durationSeconds in 1 until MIN_DURATION_SECONDS) {
                repository.updateStatusResetRetry(recording.id, ProcessingStatus.SKIPPED_TOO_SHORT)
                return false
            }

            val transcriptPrivacy = ClinicalDeidentifier.process(
                transcription.transcriptText,
                settings.clinicalPrivacyMode
            )
            val aiPrivacy = if (settings.aiEnabled) {
                ClinicalDeidentifier.process(transcription.transcriptText, enabled = true)
            } else transcriptPrivacy

            var aiWarning: String? = null
            val aiSynthesis = if (settings.aiEnabled) {
                val modelPath = settings.aiModelPath
                if (modelPath.isNullOrBlank()) {
                    aiWarning = "On-device AI is enabled but no LiteRT-LM model has been imported"
                    null
                } else {
                    setForeground(createForegroundInfo("AI structuring $queueIndex/$queueSize: ${recording.fileName}"))
                    try {
                        aiEngine.synthesize(
                            context = applicationContext,
                            deidentifiedTranscript = aiPrivacy.scrubbedText,
                            source = recording.source,
                            modelPath = modelPath,
                            modelDisplayName = settings.aiModelDisplayName,
                            preferredBackend = settings.aiPreferredBackend
                        ).getOrThrow()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        aiWarning = (error.message ?: error::class.java.simpleName).take(300)
                        null
                    }
                }
            } else null

            val transcriptDuration = transcription.durationSeconds.takeIf { it > 0 }
                ?: recording.durationSeconds
            val markdown = TranscriptMarkdownBuilder.buildMarkdown(
                recordedAtMillis = recording.recordedAt,
                durationSeconds = transcriptDuration,
                source = recording.source,
                language = transcription.detectedLanguage,
                engineName = transcription.engineName,
                rawTranscript = transcriptPrivacy.scrubbedText,
                deidResult = transcriptPrivacy,
                detectedThemes = transcription.detectedThemes,
                aiSynthesis = aiSynthesis,
                aiWarning = aiWarning
            )

            val transcriptDir = File(applicationContext.getExternalFilesDir(null), "transcripts")
                .apply { mkdirs() }
            val transcriptFile = File(
                transcriptDir,
                TranscriptMarkdownBuilder.generateFileName(
                    recordedAtMillis = recording.recordedAt,
                    source = recording.source,
                    recordingId = recording.id
                )
            ).apply { writeText(markdown) }

            val requiresSync = settings.uploadTranscript || settings.uploadAudio
            val localStatus = if (requiresSync) ProcessingStatus.WAITING_FOR_SYNC else ProcessingStatus.LOCAL_READY
            repository.updateTranscript(
                id = recording.id,
                transcriptPath = transcriptFile.absolutePath,
                themes = transcription.detectedThemes,
                durationSeconds = transcriptDuration,
                status = localStatus,
                processedAt = System.currentTimeMillis()
            )

            if (requiresSync) app.enqueueDriveSync(settings.wifiOnly)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: OfflineAsrModelRequiredException) {
            // This is a missing prerequisite, not a bad recording. Do not consume retry budget.
            repository.updateStatus(recording.id, ProcessingStatus.PENDING, e.message?.take(500))
            false
        } catch (e: Exception) {
            handleFailure(recording, e, app)
        } finally {
            tempAudio?.delete()
        }
    }

    private suspend fun handleFailure(
        recording: RecordingEntity,
        error: Exception,
        app: VoiceGrowthApplication
    ): Boolean {
        val repository = app.container.recordingRepository
        val message = error.message?.take(500) ?: error::class.java.simpleName
        val attemptsAfterThisFailure = recording.retryCount + 1
        return if (attemptsAfterThisFailure >= MAX_ITEM_RETRIES) {
            repository.recordRetry(recording.id, ProcessingStatus.FAILED, message)
            false
        } else {
            repository.recordRetry(recording.id, ProcessingStatus.PENDING, message)
            true
        }
    }

    private fun copyAudioToCache(recording: RecordingEntity): File {
        val ext = recording.fileName.substringAfterLast('.', "m4a")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "m4a"
        val target = File(applicationContext.cacheDir, "input_${recording.id}_${System.nanoTime()}.$ext")
        val uri = Uri.parse(recording.uriString)

        when (uri.scheme?.lowercase()) {
            "file" -> {
                val source = File(requireNotNull(uri.path) { "Invalid file URI" })
                require(source.exists()) { "Audio file no longer exists" }
                source.copyTo(target, overwrite = true)
            }
            else -> {
                val stream = applicationContext.contentResolver.openInputStream(uri)
                    ?: error("Unable to open selected audio recording")
                stream.use { input -> target.outputStream().use(input::copyTo) }
            }
        }
        require(target.length() > 0L) { "Audio recording is empty" }
        return target
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            VoiceGrowthApplication.CHANNEL_PROCESSING_ID
        )
            .setContentTitle("VoiceGrowth")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val WORK_NAME = "VoiceGrowth_AudioProcessing"
        private const val MIN_DURATION_SECONDS = 30L
        private const val MAX_ITEM_RETRIES = 3
    }
}
