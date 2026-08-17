package com.voicegrowth.app.engine.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.voicegrowth.app.data.model.RecordingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File


data class AiSynthesisResult(
    val markdown: String,
    val engineName: String,
    val backendUsed: String,
    val chunkCount: Int
)

data class AiTextResult(
    val markdown: String,
    val engineName: String,
    val backendUsed: String
)

/** Optional private post-ASR intelligence layer. Coroutine cancellation is always propagated. */
class OnDeviceAiEngine {

    suspend fun synthesize(
        context: Context,
        deidentifiedTranscript: String,
        source: RecordingSource,
        modelPath: String,
        modelDisplayName: String?,
        preferredBackend: String
    ): Result<AiSynthesisResult> {
        return try {
            require(deidentifiedTranscript.isNotBlank()) { "Transcript is empty" }
            val model = requireModel(modelPath)
            val (engine, backendName) = initializeWithFallback(context, model, preferredBackend)
            try {
                val chunks = TranscriptChunker.chunk(deidentifiedTranscript)
                require(chunks.isNotEmpty()) { "Transcript is empty after normalization" }
                var evidence = chunks.mapIndexed { index, chunk ->
                    generate(engine, AiPromptBuilder.evidencePrompt(source, chunk, index, chunks.size), EVIDENCE_TIMEOUT_MS)
                }
                evidence = reduceEvidence(engine, evidence)
                val finalNote = generate(
                    engine,
                    AiPromptBuilder.finalPrompt(source, evidence.joinToString("\n\n---\n\n")),
                    FINAL_TIMEOUT_MS
                ).trim()
                require(finalNote.isNotBlank()) { "On-device AI returned an empty synthesis" }
                Result.success(
                    AiSynthesisResult(
                        markdown = finalNote,
                        engineName = engineName(model, modelDisplayName),
                        backendUsed = backendName,
                        chunkCount = chunks.size
                    )
                )
            } finally {
                closeEngine(engine)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun answerKnowledgeQuestion(
        context: Context,
        question: String,
        deidentifiedEvidence: String,
        modelPath: String,
        modelDisplayName: String?,
        preferredBackend: String
    ): Result<AiTextResult> = textTask(context, modelPath, modelDisplayName, preferredBackend) { engine ->
        require(question.isNotBlank()) { "Knowledge question is empty" }
        require(deidentifiedEvidence.isNotBlank()) { "No matching VoiceGrowth evidence is available" }
        generate(
            engine,
            AiPromptBuilder.knowledgeQuestionPrompt(question.trim(), deidentifiedEvidence),
            FINAL_TIMEOUT_MS
        )
    }

    suspend fun synthesizeDailyDigest(
        context: Context,
        deidentifiedNotes: String,
        modelPath: String,
        modelDisplayName: String?,
        preferredBackend: String
    ): Result<AiTextResult> = textTask(context, modelPath, modelDisplayName, preferredBackend) { engine ->
        require(deidentifiedNotes.isNotBlank()) { "There are no processed notes for today's digest" }
        val chunks = TranscriptChunker.chunk(deidentifiedNotes)
        var evidence = chunks.mapIndexed { index, chunk ->
            generate(engine, AiPromptBuilder.dailyEvidencePrompt(chunk, index, chunks.size), EVIDENCE_TIMEOUT_MS)
        }
        evidence = reduceEvidence(engine, evidence)
        generate(
            engine,
            AiPromptBuilder.dailyDigestPrompt(evidence.joinToString("\n\n---\n\n")),
            FINAL_TIMEOUT_MS
        )
    }

    private suspend fun textTask(
        context: Context,
        modelPath: String,
        modelDisplayName: String?,
        preferredBackend: String,
        block: suspend (Engine) -> String
    ): Result<AiTextResult> {
        return try {
            val model = requireModel(modelPath)
            val (engine, backendName) = initializeWithFallback(context, model, preferredBackend)
            try {
                val text = block(engine).trim()
                require(text.isNotBlank()) { "On-device AI returned an empty response" }
                Result.success(AiTextResult(text, engineName(model, modelDisplayName), backendName))
            } finally {
                closeEngine(engine)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun reduceEvidence(engine: Engine, initial: List<String>): List<String> {
        var evidence = initial
        while (evidence.size > 1 && evidence.joinToString("\n\n").length > MAX_FINAL_EVIDENCE_CHARS) {
            evidence = evidence.chunked(EVIDENCE_REDUCTION_GROUP).map { group ->
                generate(
                    engine,
                    AiPromptBuilder.condensePrompt(group.joinToString("\n\n---\n\n")),
                    EVIDENCE_TIMEOUT_MS
                )
            }
        }
        return evidence
    }

    private fun requireModel(modelPath: String): File {
        val model = File(modelPath)
        require(model.exists() && model.length() > 0L) { "Configured LiteRT-LM model is unavailable" }
        return model
    }

    private fun engineName(model: File, displayName: String?): String =
        "LiteRT-LM 0.11 / ${displayName ?: model.name}"

    private suspend fun initializeWithFallback(
        context: Context,
        model: File,
        preferredBackend: String
    ): Pair<Engine, String> = withContext(Dispatchers.Default) {
        val candidates = if (preferredBackend.equals("cpu", ignoreCase = true)) {
            listOf("CPU" to Backend.CPU())
        } else {
            listOf("GPU" to Backend.GPU(), "CPU" to Backend.CPU())
        }
        var lastError: Throwable? = null
        for ((name, backend) in candidates) {
            val engine = Engine(
                EngineConfig(
                    modelPath = model.absolutePath,
                    backend = backend,
                    maxNumTokens = MAX_MODEL_TOKENS,
                    cacheDir = File(context.cacheDir, "litertlm_cache").apply { mkdirs() }.absolutePath
                )
            )
            try {
                engine.initialize()
                return@withContext engine to name
            } catch (error: CancellationException) {
                closeEngine(engine)
                throw error
            } catch (error: Throwable) {
                lastError = error
                closeEngine(engine)
            }
        }
        throw IllegalStateException(
            "Unable to initialize LiteRT-LM on ${if (preferredBackend.equals("cpu", true)) "CPU" else "GPU or CPU"}",
            lastError
        )
    }

    private suspend fun generate(engine: Engine, prompt: String, timeoutMs: Long): String {
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(AiPromptBuilder.SYSTEM_INSTRUCTION),
                samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.1, seed = 0),
                automaticToolCalling = false,
                channels = emptyList()
            )
        )
        return try {
            val output = StringBuilder()
            withTimeout(timeoutMs) {
                conversation.sendMessageAsync(prompt).collect { message -> output.append(message.toString()) }
            }
            output.toString()
        } catch (error: TimeoutCancellationException) {
            runCatching { conversation.cancelProcess() }
            throw IllegalStateException("On-device AI inference timed out", error)
        } catch (error: CancellationException) {
            runCatching { conversation.cancelProcess() }
            throw error
        } finally {
            if (conversation.isAlive) runCatching { conversation.close() }
        }
    }

    private fun closeEngine(engine: Engine) {
        if (engine.isInitialized()) runCatching { engine.close() }
    }

    companion object {
        private const val MAX_MODEL_TOKENS = 4_096
        private const val MAX_FINAL_EVIDENCE_CHARS = 8_000
        private const val EVIDENCE_REDUCTION_GROUP = 4
        private const val EVIDENCE_TIMEOUT_MS = 180_000L
        private const val FINAL_TIMEOUT_MS = 240_000L
    }
}
