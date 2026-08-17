package com.voicegrowth.app.engine.knowledge

import android.content.Context
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.engine.ai.OnDeviceAiEngine
import com.voicegrowth.app.engine.privacy.ClinicalDeidentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DailyDigestGenerator {
    private val dayFileFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    suspend fun generate(context: Context): Result<File?> {
        return try {
            val app = context.applicationContext as VoiceGrowthApplication
            val repository = app.container.recordingRepository
            val settings = repository.settingsFlow.first()
            if (!settings.aiEnabled || settings.aiModelPath.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Enable on-device AI and import a LiteRT-LM model first"))
            }

            val (start, end) = todayBounds()
            val recordings = repository.getRecordingsBetween(start, end)
            if (recordings.isEmpty()) return Result.success(null)

            val combined = withContext(Dispatchers.IO) {
                recordings.mapNotNull { recording ->
                    val file = recording.transcriptPath?.let(::File) ?: return@mapNotNull null
                    if (!file.exists()) return@mapNotNull null
                    val markdown = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
                    val sourceText = extractSourceTranscript(markdown)
                    if (sourceText.isBlank()) return@mapNotNull null
                    "RECORDING ${recording.id} | ${dateTimeFormat.format(Date(recording.recordedAt))} | ${recording.source.name}\n$sourceText"
                }.joinToString("\n\n---\n\n")
            }
            if (combined.isBlank()) return Result.success(null)

            val safeEvidence = ClinicalDeidentifier.process(combined, enabled = true).scrubbedText
            val ai = OnDeviceAiEngine().synthesizeDailyDigest(
                context = context,
                deidentifiedNotes = safeEvidence,
                modelPath = settings.aiModelPath,
                modelDisplayName = settings.aiModelDisplayName,
                preferredBackend = settings.aiPreferredBackend
            ).getOrThrow()

            val directory = File(context.getExternalFilesDir(null), "digests").apply { mkdirs() }
            val file = File(directory, "digest_${dayFileFormat.format(Date(start))}.md")
            withContext(Dispatchers.IO) {
                file.writeText(
                    "${ai.markdown.trim()}\n\n---\n\nGenerated locally by ${ai.engineName} (${ai.backendUsed}).\nAI digest is derived only from forcibly de-identified VoiceGrowth transcript text and must be checked against source transcripts.\n"
                )
            }
            Result.success(file)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    fun todayDigestFile(context: Context): File {
        val (start, _) = todayBounds()
        return File(File(context.getExternalFilesDir(null), "digests"), "digest_${dayFileFormat.format(Date(start))}.md")
    }

    private fun extractSourceTranscript(markdown: String): String {
        val heading = "## De-identified ASR transcript"
        val start = markdown.indexOf(heading)
        if (start < 0) return markdown.take(MAX_SOURCE_CHARS)
        val contentStart = start + heading.length
        val metadata = markdown.indexOf("\n## Automatic metadata", contentStart)
        val end = if (metadata >= 0) metadata else markdown.length
        return markdown.substring(contentStart, end).trim().take(MAX_SOURCE_CHARS)
    }

    private fun todayBounds(): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = start.clone() as Calendar
        end.add(Calendar.DAY_OF_MONTH, 1)
        return start.timeInMillis to end.timeInMillis
    }

    private const val MAX_SOURCE_CHARS = 100_000
}
