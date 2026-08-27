package com.voicegrowth.medscribe

import android.content.Context
import android.util.Base64
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/** App-private biometric voice template. Raw enrolment audio is not copied into this store. */
data class VoiceProfile(
    val id: String,
    val name: String,
    val embedding: FloatArray,
    val createdAt: Long
)

object VoiceProfileStore {
    private const val PREFS = "medscribe_voice_profiles"
    private const val KEY = "profiles"

    fun all(context: Context): List<VoiceProfile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    decodeEmbedding(o.optString("embedding")).takeIf { it.isNotEmpty() }?.let { embedding ->
                        add(
                            VoiceProfile(
                                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                                name = o.optString("name").trim().take(60),
                                embedding = embedding,
                                createdAt = o.optLong("createdAt", System.currentTimeMillis())
                            )
                        )
                    }
                }
            }.filter { it.name.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun upsert(context: Context, profile: VoiceProfile) {
        val clean = profile.copy(name = cleanName(profile.name))
        val existing = all(context).filterNot { it.name.equals(clean.name, ignoreCase = true) }
        persist(context, existing + clean)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        persist(context, all(context).filterNot { it.id == id })
    }

    private fun persist(context: Context, profiles: List<VoiceProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", cleanName(p.name))
                    .put("createdAt", p.createdAt)
                    .put("embedding", encodeEmbedding(p.embedding))
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    private fun cleanName(name: String): String =
        name.replace(Regex("[\\r\\n*#]+"), " ").trim().take(60).ifBlank { "Speaker" }

    private fun encodeEmbedding(values: FloatArray): String {
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(bytes::putFloat)
        return Base64.encodeToString(bytes.array(), Base64.NO_WRAP)
    }

    private fun decodeEmbedding(encoded: String): FloatArray {
        if (encoded.isBlank()) return FloatArray(0)
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(bytes.size / 4) { buffer.getFloat() }
        }.getOrDefault(FloatArray(0))
    }
}

/**
 * Speaker identification is deliberately opt-in. The official sherpa-onnx example uses 0.6 as
 * the search threshold for this embedding family; unknown voices remain unnamed below threshold.
 */
object SpeakerIdentityEngine {
    private const val SAMPLE_RATE = 16_000
    private const val SEARCH_THRESHOLD = 0.60f
    private const val MAX_ENROL_SECONDS = 30L
    private const val MAX_IDENTITY_SECONDS_PER_SPEAKER = 24.0
    private const val MIN_EMBEDDING_SECONDS = 1.5

    suspend fun enrollFromRecording(context: Context, item: ScribeItem, name: String): Result<VoiceProfile> =
        withContext(Dispatchers.Default) {
            runCatching {
                require(ModelManager.isDiarizationInstalled(context)) {
                    "Install speaker models before enrolling a voice"
                }
                val cleanName = name.replace(Regex("[\\r\\n*#]+"), " ").trim().take(60)
                require(cleanName.isNotBlank()) { "Enter a speaker name" }
                val pcm = AudioUtils.decodeToMonoPcm16(context, java.io.File(item.audioPath))
                try {
                    val source = PcmAccess.readSegment(
                        pcm,
                        0.0,
                        min(pcm.durationSeconds, MAX_ENROL_SECONDS).toDouble()
                    )
                    val samples = AudioUtils.resample(source, pcm.sampleRate, SAMPLE_RATE)
                    require(samples.size >= (SAMPLE_RATE * MIN_EMBEDDING_SECONDS).toInt()) {
                        "Use at least 2 seconds of clear single-speaker audio"
                    }
                    val extractor = extractor(context)
                    try {
                        val embedding = computeEmbedding(extractor, samples)
                            ?: error("Could not extract a stable voice embedding; use a clearer sample")
                        VoiceProfile(
                            id = UUID.randomUUID().toString(),
                            name = cleanName,
                            embedding = embedding,
                            createdAt = System.currentTimeMillis()
                        ).also { VoiceProfileStore.upsert(context, it) }
                    } finally {
                        extractor.release()
                    }
                } finally {
                    pcm.file.delete()
                }
            }
        }

    internal suspend fun identify(
        context: Context,
        pcm: DecodedPcmFile,
        turns: List<SpeechTurn>
    ): Map<Int, String> = withContext(Dispatchers.Default) {
        val profiles = VoiceProfileStore.all(context)
        if (profiles.isEmpty() || turns.isEmpty() || !ModelManager.isDiarizationInstalled(context)) {
            return@withContext emptyMap()
        }
        val extractor = extractor(context)
        val manager = SpeakerEmbeddingManager(extractor.dim())
        try {
            profiles.forEach { profile ->
                if (profile.embedding.size == extractor.dim()) manager.add(profile.name, profile.embedding)
            }
            if (manager.numSpeakers() == 0) return@withContext emptyMap()

            val result = mutableMapOf<Int, String>()
            for (speaker in turns.map { it.speaker }.distinct()) {
                coroutineContext.ensureActive()
                val samples = speakerSamples(pcm, turns.filter { it.speaker == speaker })
                if (samples.size < (SAMPLE_RATE * MIN_EMBEDDING_SECONDS).toInt()) continue
                val embedding = computeEmbedding(extractor, samples) ?: continue
                manager.search(embedding, SEARCH_THRESHOLD)
                    .takeIf(String::isNotBlank)
                    ?.let { result[speaker] = it }
            }
            result
        } finally {
            manager.release()
            extractor.release()
        }
    }

    private fun extractor(context: Context): SpeakerEmbeddingExtractor {
        val model = ModelManager.diarizationFiles(context).embedding
        return SpeakerEmbeddingExtractor(
            config = SpeakerEmbeddingExtractorConfig(
                model = model.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )
    }

    private fun computeEmbedding(extractor: SpeakerEmbeddingExtractor, samples: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (!extractor.isReady(stream)) null else extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    private fun speakerSamples(pcm: DecodedPcmFile, turns: List<SpeechTurn>): FloatArray {
        val parts = mutableListOf<FloatArray>()
        var total = 0
        val maxSamples = (MAX_IDENTITY_SECONDS_PER_SPEAKER * SAMPLE_RATE).toInt()
        for (turn in turns.sortedByDescending { it.end - it.start }) {
            if (total >= maxSamples) break
            val source = PcmAccess.readSegment(pcm, turn.start, turn.end)
            var samples = AudioUtils.resample(source, pcm.sampleRate, SAMPLE_RATE)
            if (samples.isEmpty()) continue
            val remaining = maxSamples - total
            if (samples.size > remaining) samples = samples.copyOf(remaining)
            parts += samples
            total += samples.size
        }
        if (total == 0) return FloatArray(0)
        val out = FloatArray(total)
        var offset = 0
        parts.forEach { part ->
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }
}
