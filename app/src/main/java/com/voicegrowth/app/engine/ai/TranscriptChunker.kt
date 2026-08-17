package com.voicegrowth.app.engine.ai

object TranscriptChunker {
    const val DEFAULT_MAX_CHARS = 6_000

    fun chunk(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        require(maxChars >= 500) { "maxChars must be at least 500" }
        val normalized = text.trim()
        if (normalized.isEmpty()) return emptyList()
        if (normalized.length <= maxChars) return listOf(normalized)

        val paragraphs = normalized.split(Regex("\\n\\s*\\n"))
            .map(String::trim)
            .filter(String::isNotEmpty)
        val chunks = mutableListOf<String>()
        var current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                chunks += current.toString().trim()
                current = StringBuilder()
            }
        }

        for (paragraph in paragraphs) {
            if (paragraph.length > maxChars) {
                flush()
                splitLargeBlock(paragraph, maxChars).forEach(chunks::add)
                continue
            }
            val separator = if (current.isEmpty()) 0 else 2
            if (current.length + separator + paragraph.length > maxChars) flush()
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(paragraph)
        }
        flush()
        return chunks
    }

    private fun splitLargeBlock(block: String, maxChars: Int): List<String> {
        val sentences = block.split(Regex("(?<=[.!?])\\s+"))
        val result = mutableListOf<String>()
        var current = StringBuilder()
        for (sentence in sentences) {
            if (sentence.length > maxChars) {
                if (current.isNotEmpty()) {
                    result += current.toString().trim()
                    current = StringBuilder()
                }
                sentence.chunked(maxChars).map(String::trim).filter(String::isNotEmpty).forEach(result::add)
                continue
            }
            val separator = if (current.isEmpty()) 0 else 1
            if (current.length + separator + sentence.length > maxChars) {
                result += current.toString().trim()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) result += current.toString().trim()
        return result
    }
}
