package com.voicegrowth.app.engine.format

import com.voicegrowth.app.data.model.RecordingSource
import com.voicegrowth.app.engine.privacy.DeidentificationResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TranscriptMarkdownBuilder {
    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val FILE_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun formatDuration(seconds: Long): String {
        val min = seconds / 60
        val sec = seconds % 60
        return String.format(Locale.US, "%02d:%02d", min, sec)
    }

    fun buildMarkdown(
        recordedAtMillis: Long,
        durationSeconds: Long,
        source: RecordingSource,
        language: String,
        engineName: String,
        rawTranscript: String,
        deidResult: DeidentificationResult,
        detectedThemes: List<String>
    ): String {
        val dateStr = DATE_FORMAT.format(Date(recordedAtMillis))
        val sourceLabel = when (source) {
            RecordingSource.CALL_RECORDING -> "Phone call"
            RecordingSource.MANUAL_DISCUSSION -> "Bedside/Academic Discussion"
            RecordingSource.VOICE_REFLECTION -> "Voice Reflection"
        }
        val themes = detectedThemes.ifEmpty { listOf("Unclassified clinical conversation") }
            .joinToString("\n") { "- $it" }
        val redactions = if (deidResult.identifiersDetectedCount > 0) {
            "${deidResult.identifiersDetectedCount} pattern match(es): ${deidResult.detectedIdentifierTypes.joinToString(", ")}"
        } else {
            "No pattern-based identifiers detected"
        }

        return """
# Conversation $dateStr

- Source: $sourceLabel
- Duration: ${formatDuration(durationSeconds)}
- Language: $language
- Transcript engine: $engineName
- Review status: MANUAL REVIEW RECOMMENDED

## Transcript

$rawTranscript

## Automatic metadata

Possible themes:
$themes

Privacy screen:
- $redactions
- Automated de-identification is heuristic and does not guarantee that all identifiers were removed.
""".trimIndent()
    }

    fun generateFileName(recordedAtMillis: Long, source: RecordingSource): String {
        val prefix = when (source) {
            RecordingSource.CALL_RECORDING -> "call"
            RecordingSource.MANUAL_DISCUSSION -> "discussion"
            RecordingSource.VOICE_REFLECTION -> "reflection"
        }
        return "transcript_${prefix}_${FILE_DATE_FORMAT.format(Date(recordedAtMillis))}.md"
    }
}
