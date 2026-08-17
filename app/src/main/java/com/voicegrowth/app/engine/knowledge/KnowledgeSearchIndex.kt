package com.voicegrowth.app.engine.knowledge

import com.voicegrowth.app.data.local.entity.RecordingEntity
import java.io.File
import java.util.Locale

data class KnowledgeEntry(val recordingId: Long, val fileName: String, val themes: String, val recordedAt: Long, val source: String, val content: String)
data class KnowledgeMatch(val entry: KnowledgeEntry, val score: Int, val excerpt: String)

object KnowledgeSearchIndex {
    private val tokenRegex = Regex("[\\p{L}\\p{N}]+")
    private const val SOURCE_HEADING = "## De-identified ASR transcript"
    private const val METADATA_HEADING = "## Automatic metadata"

    fun build(recordings: List<RecordingEntity>): List<KnowledgeEntry> = recordings.mapNotNull { r ->
        val file = r.transcriptPath?.let(::File) ?: return@mapNotNull null
        if (!file.exists() || file.length() <= 0L) return@mapNotNull null
        val markdown = runCatching { file.readText().take(MAX_MARKDOWN_CHARS) }.getOrNull()
            ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val sourceTranscript = extractSourceTranscript(markdown).take(MAX_INDEX_CHARS)
        if (sourceTranscript.isBlank()) return@mapNotNull null
        KnowledgeEntry(r.id, r.fileName, r.detectedThemes, r.recordedAt, r.source.name, sourceTranscript)
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
        for (match in matches) {
            if (out.length >= maxChars) break
            if (out.isNotEmpty()) out.append("\n\n---\n\n")
            out.append("RECORDING ${match.entry.recordingId} | ${match.entry.source} | ${match.entry.fileName}\n")
            out.append("Themes: ${match.entry.themes}\n")
            out.append(match.excerpt)
        }
        return out.toString().take(maxChars)
    }

    internal fun extractSourceTranscript(markdown: String): String {
        val sourceStart = markdown.indexOf(SOURCE_HEADING)
        if (sourceStart < 0) return markdown
        val contentStart = sourceStart + SOURCE_HEADING.length
        val metadataStart = markdown.indexOf(METADATA_HEADING, contentStart)
        val contentEnd = if (metadataStart >= 0) metadataStart else markdown.length
        return markdown.substring(contentStart, contentEnd).trim()
    }

    private fun excerpt(content: String, phrase: String, terms: List<String>): String {
        val lower = content.lowercase(Locale.ROOT)
        var index = lower.indexOf(phrase)
        if (index < 0) index = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        return content.substring((index - 500).coerceAtLeast(0), (index + 1_200).coerceAtMost(content.length))
            .replace(Regex("\\s+"), " ").trim()
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

    private const val MAX_MARKDOWN_CHARS = 180_000
    private const val MAX_INDEX_CHARS = 120_000
}
