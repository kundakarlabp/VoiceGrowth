package com.voicegrowth.app.sync

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.min

/**
 * Produces sub-8 MB decodable M4A audio parts for request-limited cloud transcription.
 *
 * Parts are intermediates, NOT new source recordings. The original is never modified.
 * No network, paid model, or transcript generation occurs here.
 */
internal object LongAudioChunker {
    const val MAX_CHUNK_BYTES = 7_500_000L
    const val CHUNK_FOLDER_NAME = "__transcription_parts"
    private const val OVERLAP_US = 1_500_000L
    private const val MAX_PARTS = 150
    private const val MIN_SPAN_US = 5_000_000L

    data class Part(
        val file: File,
        val sequence: Int,
        val startUs: Long,
        val endUs: Long
    )

    data class Bundle(val parts: List<Part>, val manifest: File, val directory: File)

    /**
     * If source is below the threshold, leave it alone. AAC/MP4 content only.
     * The resulting manifest binds parts to the ORIGINAL stable filename and recording row ID.
     * The real Drive API source ID must be filled by the cloud worker after cloud discovery:
     * an Android DocumentsProvider content URI must not be mislabelled as a Drive file ID.
     */
    fun prepare(source: File, stableArchiveName: String, sourceLocalId: Long, parentCache: File): Bundle? {
        require(source.isFile && source.length() > 0) { "Missing original recording" }
        if (source.length() < MAX_CHUNK_BYTES) return null
        require(source.extension.lowercase() in setOf("m4a", "mp4")) {
            "Only AAC/MP4 audio is supported for lossless chunking"
        }
        val durationUs = getDurationUs(source)
        require(durationUs > MIN_SPAN_US) { "Invalid audio duration" }
        val safeStem = stableArchiveName.substringBeforeLast('.')
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(120)
        val working = File(parentCache, "vg_parts_${sourceLocalId}").apply { mkdirs() }
        // Use conservative average bitrate and an extra 20% safety margin for variable bitrate.
        val budgetUs = ((MAX_CHUNK_BYTES.toDouble() * durationUs / source.length()) * 0.78)
            .toLong().coerceAtLeast(MIN_SPAN_US)
        val parts = mutableListOf<Part>()
        var startUs = 0L
        try {
            while (startUs < durationUs) {
                require(parts.size < MAX_PARTS) { "Refusing excessive part count" }
                val sequence = parts.size + 1
                val partFile = File(working, "${safeStem}.part${sequence.toString().padStart(3, '0')}.m4a")
                var spanUs = min(budgetUs, durationUs - startUs)
                var completed = false
                repeat(12) {
                    if (!completed) {
                        partFile.delete()
                        val written = writePart(source, partFile, startUs, startUs + spanUs)
                        if (written && partFile.length() in 1 until MAX_CHUNK_BYTES) {
                            completed = true
                        } else {
                            partFile.delete()
                            spanUs = (spanUs * 0.80).toLong()
                            require(spanUs >= MIN_SPAN_US) { "Cannot make an audio part under byte cap" }
                        }
                    }
                }
                require(completed) { "Unable to segment audio under byte cap" }
                val endUs = min(durationUs, startUs + spanUs)
                parts.add(Part(partFile, sequence, startUs, endUs))
                if (endUs >= durationUs) break
                val nextUs = (endUs - OVERLAP_US).coerceAtLeast(0L)
                require(nextUs > startUs) { "Audio chunking made no forward progress" }
                startUs = nextUs
            }

            val manifest = File(working, "${safeStem}.parts.manifest.json")
            val chunks = JSONArray()
            parts.forEach { p ->
                chunks.put(JSONObject()
                    .put("part", p.sequence)
                    .put("filename", p.file.name)
                    .put("source_start_ms", p.startUs / 1000)
                    .put("source_end_ms", p.endUs / 1000)
                    .put("file_size_bytes", p.file.length())
                    .put("sha256", sha256(p.file)))
            }
            manifest.writeText(JSONObject()
                .put("schema", "VoiceGrowth-private-audio-parts-v1")
                .put("original_filename", stableArchiveName)
                .put("original_local_recording_id", sourceLocalId)
                .put("original_drive_file_id", JSONObject.NULL)
                .put("original_bytes", source.length())
                .put("original_sha256", sha256(source))
                .put("original_duration_ms", durationUs / 1000)
                .put("overlap_ms", OVERLAP_US / 1000)
                .put("state", "AUDIO_PARTS_READY_NO_ASR")
                .put("instruction", "Only the original Drive audio ID is the consultation key. Shift ASR timestamps by source_start_ms and deduplicate overlap conservatively. Never treat parts as new original recordings.")
                .put("parts", chunks).toString(2))
            return Bundle(parts, manifest, working)
        } catch (error: Exception) {
            working.deleteRecursively()
            throw error
        }
    }

    private fun getDurationUs(source: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            val audioIndex = (0 until extractor.trackCount)
                .firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("No audio track")
            val format = extractor.getTrackFormat(audioIndex)
            require(format.containsKey(MediaFormat.KEY_DURATION)) { "Unknown audio duration" }
            format.getLong(MediaFormat.KEY_DURATION)
        } finally { extractor.release() }
    }

    private fun writePart(input: File, output: File, startUs: Long, endUs: Long): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var written = 0
        try {
            extractor.setDataSource(input.absolutePath)
            val idx = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track")
            extractor.selectTrack(idx)
            val format = extractor.getTrackFormat(idx)
            require(format.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm") {
                "Expected AAC audio, not ${format.getString(MediaFormat.KEY_MIME)}"
            }
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            started = true
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val timestamp = extractor.sampleTime
                if (timestamp < 0 || timestamp >= endUs) break
                if (timestamp < startUs) {
                    if (!extractor.advance()) break
                    continue
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0) break
                info.set(0, size, timestamp - startUs, extractor.sampleFlags)
                muxer.writeSampleData(outputTrack, buffer, info)
                written++
                if (!extractor.advance()) break
            }
            return written > 0
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
            if (written == 0) output.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buf = ByteArray(65536)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
