package com.voicegrowth.app.transcription

import com.voicegrowth.app.data.local.entity.RecordingEntity
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Private checkpoint files enable resume without Room schema changes. */
internal class AsrCheckpointStore(
    base: File,
    private val recording: RecordingEntity,
    model: File
) {
    val durationMs: Long = recording.durationSeconds * 1_000L
    val windows: Int = TranscriptSegmentPlan.count(durationMs)
    private val fingerprint = sha256(
        listOf("asr.v2", recording.id, recording.uriString, recording.fileSizeBytes,
            recording.durationSeconds, recording.recordedAt,
            model.canonicalPath, model.length(), model.lastModified()).joinToString("|")
    )
    private val dir = File(base, "r${recording.id}_$fingerprint").apply { mkdirs() }

    fun nextMissing(): Int? = (0 until windows).firstOrNull { load(it) == null }

    fun load(index: Int): JSONObject? {
        val file = part(index)
        if (!file.isFile || file.length() !in 5..1_000_000L) return null
        return runCatching {
            JSONObject(file.readText(Charsets.UTF_8)).takeIf {
                it.optString("fingerprint") == fingerprint &&
                    it.optInt("index", -1) == index &&
                    it.optLong("startMs", -1L) == TranscriptSegmentPlan.window(durationMs, index).startMs &&
                    it.has("text") && it.has("segments")
            }
        }.getOrNull()
    }

    fun save(index: Int, text: String, segments: JSONArray) {
        require(index in 0 until windows)
        val w = TranscriptSegmentPlan.window(durationMs, index)
        val data = JSONObject().put("fingerprint", fingerprint)
            .put("index", index).put("startMs", w.startMs).put("endMs", w.endMs)
            .put("text", text).put("segments", segments)
        writeAtomic(part(index), data.toString())
    }

    fun assembleMarkdown(modelName: String): String {
        check(nextMissing() == null) { "Cannot finalize incomplete recording" }
        val terms = JSONArray()
        val seen = mutableSetOf<String>()
        for (index in 0 until windows) {
            val piece = checkNotNull(load(index))
            val segments = piece.optJSONArray("segments") ?: JSONArray()
            if (segments.length() == 0) {
                val w = TranscriptSegmentPlan.window(durationMs, index)
                ClinicalTermIndexer.find(piece.optString("text"), w.startMs).forEach { hit ->
                    val key = "${hit.category}|${hit.raw.lowercase()}|${hit.startMs / 2000}"
                    if (seen.add(key)) {
                        terms.put(JSONObject().put("rawASRTerm", hit.raw)
                            .put("category", hit.category).put("tentativeTerm", hit.tentativeTerm)
                            .put("timestampMs", hit.startMs).put("ASRExcerpt", hit.excerpt)
                            .put("verified", false))
                    }
                }
            } else {
                for (j in 0 until segments.length()) {
                    val seg = segments.getJSONObject(j)
                    ClinicalTermIndexer.find(seg.optString("text"), seg.optLong("startMs")).forEach { hit ->
                        val key = "${hit.category}|${hit.raw.lowercase()}|${hit.startMs / 2000}"
                        if (seen.add(key)) {
                            terms.put(JSONObject().put("rawASRTerm", hit.raw)
                                .put("category", hit.category).put("tentativeTerm", hit.tentativeTerm)
                                .put("timestampMs", hit.startMs).put("ASRExcerpt", hit.excerpt)
                                .put("verified", false))
                        }
                    }
                }
            }
        }
        val draft = JSONObject()
            .put("schema", "voicegrowth.consult-draft.v1")
            .put("reviewStatus", "UNVERIFIED_ASR")
            .put("sourceRecordingId", recording.id)
            .put("sourceAudioFile", recording.fileName)
            .put("durationSeconds", recording.durationSeconds)
            .put("diagnosis", JSONObject.NULL)
            .put("clinicalSyndrome", JSONObject.NULL)
            .put("microbiology", JSONArray())
            .put("antimicrobials", JSONArray())
            .put("idRecommendations", JSONArray())
            .put("recommendationAcceptance", JSONObject.NULL)
            .put("followUp", JSONObject.NULL)
            .put("sourceEvidence", JSONArray())
            .put("unverifiedTermMentions", terms)
            .put("requiresClinicianReview", true)
        return buildString {
            appendLine("# UNVERIFIED VoiceGrowth transcription — review before clinical use")
            appendLine()
            appendLine("Source filename: ${recording.fileName}")
            appendLine("Recording ID: ${recording.id}; duration: ${recording.durationSeconds} seconds")
            appendLine("Model: $modelName; ASR windows: $windows; 28-second segments, 1-second overlap")
            appendLine("WARNING: chunk boundaries can repeat words; language, doses, CRs, organisms and susceptibility may be inaccurate.")
            appendLine("Do not use for patient care, publication or registry write without clinician verification.")
            appendLine()
            appendLine("## Timestamped verbatim ASR draft")
            appendLine()
            for (index in 0 until windows) {
                val p = checkNotNull(load(index))
                val w = TranscriptSegmentPlan.window(durationMs, index)
                appendLine("### Window ${index + 1} [${w.startMs}–${w.endMs} ms]")
                val segs = p.optJSONArray("segments") ?: JSONArray()
                if (segs.length() == 0) {
                    appendLine("[${w.startMs} ms] ${p.optString("text")}")
                } else {
                    for (j in 0 until segs.length()) {
                        val segment = segs.getJSONObject(j)
                        appendLine("[${segment.optLong("startMs")}–${segment.optLong("endMs")} ms] ${segment.optString("text")}")
                    }
                }
                appendLine()
            }
            appendLine("## Structured consultation — EMPTY DRAFT, not AI-extracted")
            appendLine()
            appendLine("This template deliberately does not infer diagnoses, patients, CR numbers or actual drug exposure. Any term mention is an UNVERIFIED match in the ASR text, not proof of a clinical fact.")
            appendLine("A separately authorized extraction process may populate fields with exact audio/text evidence.")
            appendLine()
            appendLine("~~~json")
            appendLine(draft.toString(2))
            appendLine("~~~")
        }
    }

    fun cleanupAfterCommit() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    private fun part(index: Int) = File(dir, "part_${index.toString().padStart(4, '0')}.json")

    companion object {
        fun writeAtomic(destination: File, value: String) {
            destination.parentFile?.mkdirs()
            val temp = File(destination.parentFile, destination.name + ".partial")
            temp.outputStream().use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temp.renameTo(destination)) {
                temp.delete()
                error("Could not atomically commit ASR checkpoint")
            }
        }

        private fun sha256(input: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }
    }
}
