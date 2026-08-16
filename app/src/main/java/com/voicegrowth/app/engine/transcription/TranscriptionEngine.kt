package com.voicegrowth.app.engine.transcription

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume

data class TranscriptionResult(
    val transcriptText: String,
    val detectedLanguage: String,
    val engineName: String,
    val durationSeconds: Long,
    val detectedThemes: List<String>
)

interface TranscriptionEngine {
    val engineName: String
    suspend fun transcribe(context: Context, audioFile: File, language: String): Result<TranscriptionResult>
}

/**
 * Uses Android's on-device RecognitionService and injects decoded PCM audio.
 * This replaces the former demo implementation that returned fabricated clinical text.
 * Recorded-file injection requires Android 13 (API 33+).
 */
class LocalMedicalSpeechEngine : TranscriptionEngine {
    override val engineName: String = "Android On-Device ASR (medical-biased)"

    override suspend fun transcribe(
        context: Context,
        audioFile: File,
        language: String
    ): Result<TranscriptionResult> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return Result.failure(UnsupportedOperationException("Recorded-file on-device transcription requires Android 13 or newer"))
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return Result.failure(IllegalStateException("No on-device speech recognition service is installed on this phone"))
        }

        val pcmFile = File(context.cacheDir, "asr_${audioFile.nameWithoutExtension}_${System.nanoTime()}.pcm")
        return try {
            val pcm = AudioPcmDecoder.decodeToMonoPcm16(audioFile, pcmFile)
            recognizePcm(context, pcm, language)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pcmFile.delete()
        }
    }

    private suspend fun recognizePcm(
        context: Context,
        pcm: DecodedPcmAudio,
        language: String
    ): Result<TranscriptionResult> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val recognizer = try {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } catch (e: Exception) {
                continuation.resume(Result.failure(e))
                return@suspendCancellableCoroutine
            }

            val pipe = try {
                ParcelFileDescriptor.createPipe()
            } catch (e: Exception) {
                recognizer.destroy()
                continuation.resume(Result.failure(e))
                return@suspendCancellableCoroutine
            }
            val readFd = pipe[0]
            val writeFd = pipe[1]
            val segments = mutableListOf<String>()
            var finished = false

            fun cleanup() {
                runCatching { readFd.close() }
                runCatching { writeFd.close() }
                runCatching { recognizer.destroy() }
            }

            fun finish(result: Result<TranscriptionResult>) {
                if (finished) return
                finished = true
                cleanup()
                if (continuation.isActive) continuation.resume(result)
            }

            fun transcriptFrom(bundle: Bundle?): String = bundle
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onSegmentResults(segmentResults: Bundle) {
                    transcriptFrom(segmentResults).takeIf { it.isNotBlank() }?.let(segments::add)
                }

                override fun onEndOfSegmentedSession() {
                    val text = segments.joinToString("\n\n").trim()
                    if (text.isBlank()) {
                        finish(Result.failure(IllegalStateException("On-device recognizer returned an empty transcript")))
                    } else {
                        finish(Result.success(buildResult(text, language, pcm.durationSeconds)))
                    }
                }

                override fun onResults(results: Bundle?) {
                    val finalText = transcriptFrom(results)
                    val text = if (segments.isNotEmpty()) segments.joinToString("\n\n") else finalText
                    if (text.isBlank()) {
                        finish(Result.failure(IllegalStateException("On-device recognizer returned an empty transcript")))
                    } else {
                        finish(Result.success(buildResult(text, language, pcm.durationSeconds)))
                    }
                }

                override fun onError(error: Int) {
                    if (segments.isNotEmpty()) {
                        finish(Result.success(buildResult(segments.joinToString("\n\n"), language, pcm.durationSeconds)))
                    } else {
                        finish(Result.failure(IllegalStateException("On-device speech recognition failed (${errorLabel(error)})")))
                    }
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    ArrayList(MedicalVocabularyPrompt.TERMS.take(100))
                )
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcm.sampleRate)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                languageTag(language)?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            }

            continuation.invokeOnCancellation { cleanup() }

            try {
                recognizer.startListening(intent)
                Thread({
                    runCatching {
                        FileInputStream(pcm.file).use { input ->
                            ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { output ->
                                input.copyTo(output, 64 * 1024)
                            }
                        }
                    }
                }, "VoiceGrowth-ASR-Feeder").apply {
                    isDaemon = true
                    start()
                }
            } catch (e: Exception) {
                finish(Result.failure(e))
            }
        }
    }

    private fun buildResult(text: String, language: String, duration: Long): TranscriptionResult =
        TranscriptionResult(
            transcriptText = text.trim(),
            detectedLanguage = languageTag(language) ?: "Auto",
            engineName = engineName,
            durationSeconds = duration,
            detectedThemes = detectThemes(text)
        )

    private fun languageTag(language: String): String? = when (language.lowercase()) {
        "english", "en", "en-in" -> "en-IN"
        "telugu", "te", "te-in" -> "te-IN"
        "hindi", "hi", "hi-in" -> "hi-IN"
        else -> null
    }

    private fun detectThemes(text: String): List<String> {
        val lower = text.lowercase()
        return THEME_TERMS.mapNotNull { (label, terms) ->
            label.takeIf { terms.any(lower::contains) }
        }.take(10)
    }

    private fun errorLabel(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language model unavailable"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network requested by recognizer"
        SpeechRecognizer.ERROR_NO_MATCH -> "no speech match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "recognizer service"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        else -> "code $code"
    }

    companion object {
        private val THEME_TERMS = linkedMapOf(
            "Antimicrobial resistance" to listOf("resistant", "resistance", "mdr", "xdr", "crab", "cre", "dtr"),
            "Antimicrobial stewardship" to listOf("de-escal", "antibiotic", "antimicrobial", "duration", "extended infusion"),
            "Transplant infection" to listOf("transplant", "cmv", "bk virus", "immunosuppression"),
            "Invasive fungal infection" to listOf("asperg", "mucor", "candida", "amphotericin", "voriconazole"),
            "Tuberculosis" to listOf("tuberculosis", "tb", "rifampicin", "isoniazid"),
            "HIV" to listOf("hiv", "antiretroviral", "cd4", "viral load"),
            "Source control" to listOf("source control", "drain", "catheter removal", "debrid"),
            "PK/PD optimization" to listOf("mic", "auc", "loading dose", "creatinine clearance", "pk/pd")
        )
    }
}
