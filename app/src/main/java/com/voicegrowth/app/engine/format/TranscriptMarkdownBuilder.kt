package com.voicegrowth.app.engine.format

import com.voicegrowth.app.data.model.RecordingSource
import com.voicegrowth.app.engine.ai.AiSynthesisResult
import com.voicegrowth.app.engine.privacy.DeidentificationResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TranscriptMarkdownBuilder {
    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val FILE_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun formatDuration(seconds: Long): String = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

    fun buildMarkdown(
        recordedAtMillis: Long,
        durationSeconds: Long,
        source: RecordingSource,
        language: String,
        engineName: String,
        rawTranscript: String,
        deidResult: DeidentificationResult,
        detectedThemes: List<String>,
        aiSynthesis: AiSynthesisResult? = null,
        aiWarning: String? = null
    ): String {
        val sourceLabel = when (source) {
            RecordingSource.CALL_RECORDING -> "Phone call"
            RecordingSource.MANUAL_DISCUSSION -> "Bedside/Academic Discussion"
            RecordingSource.VOICE_REFLECTION -> "Voice Reflection"
            RecordingSource.IMPORTED_AUDIO -> "Imported Audio"
        }
        val themes = detectedThemes.ifEmpty { listOf("Unclassified clinical conversation") }.joinToString("\n") { "- $it" }
        val redactions = if (deidResult.identifiersDetectedCount > 0) {
            "${deidResult.identifiersDetectedCount} pattern match(es): ${deidResult.detectedIdentifierTypes.joinToString(", ")}"
        } else "No pattern-based identifiers detected"
        val aiSection = when {
            aiSynthesis != null -> """

## On-device AI synthesis

> AI-GENERATED NOTE — verify all details against the source transcript below. The model was instructed not to add facts or medical recommendations.

- Engine: ${aiSynthesis.engineName}
- Backend: ${aiSynthesis.backendUsed}
- Transcript segments processed: ${aiSynthesis.chunkCount}

${aiSynthesis.markdown}
"""
            !aiWarning.isNullOrBlank() -> """

## On-device AI synthesis

Not generated: $aiWarning

The source transcript remains available below and can be reprocessed after the AI model is configured or the runtime issue is corrected.
"""
            else -> ""
        }
        return """
# Conversation ${DATE_FORMAT.format(Date(recordedAtMillis))}

- Source: $sourceLabel
- Duration: ${formatDuration(durationSeconds)}
- Language: $language
- Transcript engine: $engineName
- Review status: MANUAL REVIEW RECOMMENDED
$aiSection

## De-identified ASR transcript

$rawTranscript

## Automatic metadata

Possible themes:
$themes

Privacy screen:
- $redactions
- Automated de-identification is heuristic and does not guarantee that all identifiers were removed.
- AI synthesis, when enabled, is generated only from the de-identified transcript and must be checked against the source transcript.
""".trimIndent()
    }

    fun generateFileName(recordedAtMillis: Long, source: RecordingSource, recordingId: Long? = null): String {
        val prefix = when (source) {
            RecordingSource.CALL_RECORDING -> "call"
            RecordingSource.MANUAL_DISCUSSION -> "discussion"
            RecordingSource.VOICE_REFLECTION -> "reflection"
            RecordingSource.IMPORTED_AUDIO -> "imported"
        }
        return "transcript_${prefix}_${FILE_DATE_FORMAT.format(Date(recordedAtMillis))}${recordingId?.let { "_r$it" }.orEmpty()}.md"
    }
}
