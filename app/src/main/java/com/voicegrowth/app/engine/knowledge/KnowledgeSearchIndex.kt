package com.voicegrowth.app.engine.knowledge

import com.voicegrowth.app.data.local.entity.RecordingEntity
import java.io.File
import java.util.Locale

data class KnowledgeEntry(
    val recordingId: Long,
    val fileName: String,
    val themes: String,
    val recordedAt: Long,
    val source: String,
    val content: String
)

data class KnowledgeMatch(
    val entry: KnowledgeEntry,
    val score: Int,
    val excerpt: String
)

object KnowledgeSearchIndex {
    private val tokenRegex = Regex("[\\p{L}\\p{N}]+")

    fun build(recordings: List<RecordingEntity>): List<KnowledgeEntry> = recordings.mapNotNull { recording ->
        val file = recording.transcriptPath?.let(::File) ?: return@mapNotNull null
        if (!file.exists() || file.length() <= 0L) return@mapNotNull null
        val content = runCatching { file.readText().take(MAX_INDEX_CHARS) }.getOrNull()
            ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        KnowledgeEntry(
            recordingId = recording.id,
            fileName = recording.fileName,
            themes = recording.detectedThemes,
            recordedAt = recording.recordedAt,
            source = recording.source.name,
            content = content
        )
    }

    fun search(entries: List<KnowledgeEntry>, query: String, limit: Int = 50): List<KnowledgeMatch> {
        val phrase = query.trim().lowercase(Locale.ROOT)
        if (phrase.isEmpty()) return emptyList()
        val terms = tokenRegex.findAll(phrase).map { it.value }.filter { it.length >= 2 }.distinct().toList()
        if (terms.isEmpty()) return emptyList()
        return entries.mapNotNull { entry ->
            val name = entry.fileName.lowercase(Locale.ROOT)
            val themes = entry.themes.lowercase(Locale.ROOT)
            val body = entry.content.lowercase(Locale.ROOT)
            var score = 0
            if (name.contains(phrase)) score += 18
            if (themes.contains(phrase)) score += 22
            if (body.contains(phrase)) score += 14
            terms.forEach { term ->
                if (name.contains(term)) score += 5
                if (themes.contains(term)) score += 7
                score += occurrenceCount(body, term).coerceAtMost(8)
            }
            if (score == 0) null else KnowledgeMatch(entry, score, excerpt(entry.content, phrase, terms))
        }.sortedWith(compareByDescending<KnowledgeMatch> { it.score }.thenByDescending { it.entry.recordedAt })
            .take(limit.coerceIn(1, 100))
    }

    fun evidenceForAi(matches: List<KnowledgeMatch>, maxChars: Int = 12_000): String {
        val out = StringBuilder()
        matches.forEach { match ->
            if (out.length >= maxChars) return@forEach
            val entry = match.entry
            val header = "RECORDING ${entry.recordingId} | ${entry.source} | ${entry.fileName}"
            val body = entry.content.take(2_500)
            if (out.isNotEmpty()) out.append("\n\n---\n\n")
            out.append(header).append('\n').append(body)
        }
        return out.toString().take(maxChars)
    }

    private fun excerpt(content: String, phrase: String, terms: List<String>): String {
        val lower = content.lowercase(Locale.ROOT)
        var index = lower.indexOf(phrase)
        if (index < 0) index = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (index - 120).coerceAtLeast(0)
        val end = (index + 320).coerceAtMost(content.length)
        return content.substring(start, end).replace(Regex("\\s+"), " ").trim()
    }

    private fun occurrenceCount(text: String, term: String): Int {
        var count = 0
        var cursor = 0
        while (cursor < text.length) {
            val index = text.indexOf(term, cursor)
            if (index < 0) break
            count++
            cursor = index + term.length.coerceAtLeast(1)
        }
        return count
    }

    private const val MAX_INDEX_CHARS = 120_000
}
