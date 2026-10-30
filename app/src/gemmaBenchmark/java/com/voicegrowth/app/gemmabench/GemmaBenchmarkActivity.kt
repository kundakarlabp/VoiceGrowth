package com.voicegrowth.app.gemmabench

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.StatFs
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.voicegrowth.app.transcription.LocalAudioWavDecoder
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Separate benchmark application ID; never automatically touches clinical recordings.
 * Synthetic <=25-second clips. Sequential model runs; no network or Drive writes.
 */
class GemmaBenchmarkActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var reference: EditText
    private lateinit var layout: LinearLayout
    private var chosenAudio: Uri? = null
    private var requestedModel: String? = null
    private var busy = false
    private data class Outcome(val text: String, val durationMs: Long, val sha: String)
    private val outputs = linkedMapOf<String, Outcome>()

    private val modelPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val model = requestedModel
        if (uri != null && model != null) importModel(model, uri)
    }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            chosenAudio = uri
            outputs.clear()
            status.text = "Audio selected. Use synthetic or approved recordings, at most 25 seconds."
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(layout) })
        label("VoiceGrowth Offline ASR Benchmark: Whisper / Gemma 4", 20f)
        label("Experimental separate APK. No recording, upload, dashboard update, or AI API. Select a synthetic 25-second .m4a sample.", 14f)
        status = label("Import models locally, then select an audio file.", 14f)
        button("Import Whisper multilingual GGML") { requestedModel = "whisper"; modelPicker.launch("*/*") }
        button("Import Gemma 4 E2B LiteRT") { requestedModel = "e2b"; modelPicker.launch("*/*") }
        button("Import Gemma 4 E4B LiteRT") { requestedModel = "e4b"; modelPicker.launch("*/*") }
        button("Choose test .m4a (<=25 s)") { audioPicker.launch("audio/*") }
        label("Optional human-verified reference for WER; never paste patient identifiers.", 14f)
        reference = EditText(this).apply { hint = "Reference transcript"; minLines = 2; maxLines = 6 }
        layout.addView(reference)
        button("Run Whisper baseline") { runModel("whisper") }
        button("Run Gemma E2B") { runModel("e2b") }
        button("Run Gemma E4B") { runModel("e4b") }
        results = label("No model run yet.", 14f)
    }

    private fun dir(): File = File(filesDir, "benchmark_models").apply { mkdirs() }
    private fun model(which: String): File = File(dir(), when(which) {
        "whisper" -> "whisper.bin"
        "e2b" -> "gemma4-e2b.litertlm"
        "e4b" -> "gemma4-e4b.litertlm"
        else -> error("Unknown engine")
    })
    private fun importModel(which: String, uri: Uri) {
        if (busy) return
        busy = true
        status.text = "Importing ${which} into private storage. Large models require several GB."
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val target = model(which)
                    require(StatFs(dir().path).availableBytes >
                        if (which == "whisper") 300_000_000L else 4_000_000_000L) {
                        "Insufficient free device storage"
                    }
                    val temp = File.createTempFile("model-", ".partial", dir())
                    try {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            temp.outputStream().use { stream.copyTo(it, 1024 * 1024) }
                        } ?: error("Cannot read selected model")
                        require(temp.length() >
                            if (which == "whisper") 50_000_000L else 1_000_000_000L) {
                            "Selected file is too small for this model"
                        }
                        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        target.length()
                    } finally { temp.delete() }
                }
            }
            status.text = outcome.fold(
                onSuccess = { "Imported ${which}: ${it / 1_000_000} MB." },
                onFailure = { "Import failed: ${it.message ?: it.javaClass.simpleName}" }
            )
            busy = false
        }
    }

    private fun runModel(which: String) {
        if (busy) return
        val source = chosenAudio ?: run { status.text = "Choose audio first."; return }
        val installed = model(which)
        if (!installed.isFile) { status.text = "Import model ${which} first."; return }
        busy = true
        status.text = "Running ${which} offline; keep the app open. Models run one at a time."
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val audio = File.createTempFile("benchmark-original-", ".m4a", cacheDir)
                    val decoded = File.createTempFile("benchmark-decoded-", ".wav", cacheDir)
                    val wav = File.createTempFile("benchmark-resampled-", ".wav", cacheDir)
                    try {
                        contentResolver.openInputStream(source)?.use { stream ->
                            audio.outputStream().use(stream::copyTo)
                        } ?: error("Selected audio cannot be read")
                        require(audio.length() in 1..15_000_000) { "Audio file exceeds 15 MB limit" }
                        LocalAudioWavDecoder.convert(audio, decoded, maxDurationSeconds = 25)
                        BenchmarkAudio.convertTo16k(decoded, wav)
                        val sha = sha256(audio)
                        val start = SystemClock.elapsedRealtime()
                        val words = if (which == "whisper") {
                            val engine = Whisper.loadModel(this@GemmaBenchmarkActivity, installed.absolutePath)
                            try {
                                Whisper.transcribe(engine, wav.absolutePath,
                                    WhisperConfig(language = "auto")).text
                            } finally { Whisper.releaseModel(engine) }
                        } else {
                            Engine(EngineConfig(modelPath = installed.absolutePath,
                                backend = Backend.CPU(), audioBackend = Backend.CPU())).use { engine ->
                                engine.initialize()
                                engine.createConversation().use { chat ->
                                    chat.sendMessage(Contents.of(
                                        Content.AudioFile(wav.absolutePath),
                                        Content.Text("Transcribe the recorded Telugu/English speech verbatim. " +
                                            "Keep medical wording. Never infer missing words, CR numbers or doses. " +
                                            "Do not summarize. Output only the transcript.")
                                    )).toString()
                                }
                            }
                        }
                        require(words.isNotBlank()) { "Speech engine returned empty response" }
                        Outcome(words, SystemClock.elapsedRealtime() - start, sha)
                    } finally { audio.delete(); decoded.delete(); wav.delete() }
                }
            }
            outcome.onSuccess { out ->
                if (outputs.values.any { it.sha != out.sha }) outputs.clear()
                outputs[which] = out
                status.text = "${which}: completed offline; review all clinical content."
            }.onFailure { status.text = "${which} failed: ${(it.message ?: it.javaClass.simpleName).take(220)}" }
            busy = false
            refresh()
        }
    }

    private fun refresh() {
        if (!::results.isInitialized) return
        val gold = reference.text.toString()
        results.text = if (outputs.isEmpty()) "No results yet." else buildString {
            appendLine("Same-source comparison (SHA-256 prefix: ${outputs.values.first().sha.take(12)})")
            appendLine("WER requires a manually verified reference; it does NOT measure clinical safety.")
            outputs.forEach { (name, outcome) ->
                appendLine()
                appendLine("${name.uppercase()} · ${outcome.durationMs / 1000.0}s")
                WordErrorRate.calculate(gold, outcome.text)?.let {
                    appendLine("WER: ${(it * 100).roundToInt()}%")
                }
                appendLine(outcome.text)
            }
            appendLine("Verify drug names, doses, organism names and CR numbers against the audio.")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(65536)
            while(true) {
                val n = stream.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun label(value: String, size: Float): TextView =
        TextView(this).also {
            it.text = value; it.textSize = size; it.setPadding(0, 8, 0, 8)
            layout.addView(it)
        }

    private fun button(label: String, action: () -> Unit) {
        layout.addView(Button(this).apply {
            text = label
            setOnClickListener { if (!busy) action() }
        })
    }
}
