package com.voicegrowth.medscribe

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class ScribeRepository private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("medscribe_state", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(loadItems())
    val items: StateFlow<List<ScribeItem>> = _items.asStateFlow()

    val baseDir: File = File(context.filesDir, "medscribe").apply { mkdirs() }
    val audioDir: File = File(baseDir, "audio").apply { mkdirs() }
    val transcriptDir: File = File(baseDir, "transcripts").apply { mkdirs() }
    val modelDir: File = File(baseDir, "models").apply { mkdirs() }

    @Synchronized
    fun settings(): ScribeSettings = ScribeSettings(
        language = prefs.getString(KEY_LANGUAGE, "auto") ?: "auto",
        whisperModel = prefs.getString(KEY_MODEL, "base") ?: "base",
        diarizationEnabled = prefs.getBoolean(KEY_DIARIZATION, true),
        voiceRecognitionEnabled = prefs.getBoolean(KEY_VOICE_RECOGNITION, true),
        autoTranscribe = prefs.getBoolean(KEY_AUTO_TRANSCRIBE, true),
        autoSync = prefs.getBoolean(KEY_AUTO_SYNC, true),
        uploadAudio = prefs.getBoolean(KEY_UPLOAD_AUDIO, false),
        driveFolderUri = prefs.getString(KEY_DRIVE_URI, null),
        driveFolderName = prefs.getString(KEY_DRIVE_NAME, null)
    )

    @Synchronized
    fun updateSettings(transform: (ScribeSettings) -> ScribeSettings): ScribeSettings {
        val next = transform(settings())
        prefs.edit()
            .putString(KEY_LANGUAGE, next.language)
            .putString(KEY_MODEL, next.whisperModel)
            .putBoolean(KEY_DIARIZATION, next.diarizationEnabled)
            .putBoolean(KEY_VOICE_RECOGNITION, next.voiceRecognitionEnabled)
            .putBoolean(KEY_AUTO_TRANSCRIBE, next.autoTranscribe)
            .putBoolean(KEY_AUTO_SYNC, next.autoSync)
            .putBoolean(KEY_UPLOAD_AUDIO, next.uploadAudio)
            .apply {
                if (next.driveFolderUri == null) remove(KEY_DRIVE_URI) else putString(KEY_DRIVE_URI, next.driveFolderUri)
                if (next.driveFolderName == null) remove(KEY_DRIVE_NAME) else putString(KEY_DRIVE_NAME, next.driveFolderName)
            }
            .apply()
        return next
    }

    @Synchronized
    fun setDriveFolder(uri: Uri?, name: String?): ScribeSettings = updateSettings {
        it.copy(driveFolderUri = uri?.toString(), driveFolderName = name)
    }

    @Synchronized
    fun add(item: ScribeItem) {
        val next = (_items.value + item).sortedByDescending { it.recordedAt }
        persist(next)
    }

    @Synchronized
    fun update(id: String, transform: (ScribeItem) -> ScribeItem): ScribeItem? {
        var changed: ScribeItem? = null
        val next = _items.value.map {
            if (it.id == id) transform(it).also { v -> changed = v } else it
        }
        if (changed != null) persist(next)
        return changed
    }

    @Synchronized
    fun remove(id: String, deleteFiles: Boolean = false) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (deleteFiles) {
            runCatching { File(item.audioPath).delete() }
            item.transcriptPath?.let { runCatching { File(it).delete() } }
        }
        persist(_items.value.filterNot { it.id == id })
    }

    fun get(id: String): ScribeItem? = _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun saveTranscript(id: String, markdown: String, language: String, speakerCount: Int): ScribeItem? {
        val file = File(transcriptDir, "$id.md")
        file.writeText(markdown)
        return update(id) {
            it.copy(
                status = ItemStatus.READY,
                transcriptPath = file.absolutePath,
                language = language,
                speakerCount = speakerCount,
                errorMessage = null
            )
        }
    }

    fun transcriptText(item: ScribeItem): String =
        item.transcriptPath?.let { runCatching { File(it).readText() }.getOrDefault("") }.orEmpty()

    @Synchronized
    fun editTranscript(id: String, markdown: String): ScribeItem? {
        val item = get(id) ?: return null
        val path = item.transcriptPath ?: File(transcriptDir, "$id.md").absolutePath
        File(path).writeText(markdown)
        return update(id) { it.copy(transcriptPath = path, status = ItemStatus.READY, errorMessage = null) }
    }

    fun setRecordingState(recording: Boolean, startedAt: Long = 0L) {
        prefs.edit().putBoolean(KEY_IS_RECORDING, recording).putLong(KEY_RECORDING_STARTED, startedAt).apply()
    }

    fun isRecording(): Boolean = prefs.getBoolean(KEY_IS_RECORDING, false)
    fun recordingStartedAt(): Long = prefs.getLong(KEY_RECORDING_STARTED, 0L)

    private fun loadItems(): List<ScribeItem> =
        itemsFromJson(prefs.getString(KEY_ITEMS, null))
            .filter { File(it.audioPath).exists() || !it.transcriptPath.isNullOrBlank() }
            .sortedByDescending { it.recordedAt }

    private fun persist(items: List<ScribeItem>) {
        prefs.edit().putString(KEY_ITEMS, itemsToJson(items)).apply()
        _items.value = items
    }

    companion object {
        @Volatile private var instance: ScribeRepository? = null
        fun get(context: Context): ScribeRepository =
            instance ?: synchronized(this) {
                instance ?: ScribeRepository(context.applicationContext).also { instance = it }
            }

        private const val KEY_ITEMS = "items_json"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_MODEL = "whisper_model"
        private const val KEY_DIARIZATION = "diarization"
        private const val KEY_VOICE_RECOGNITION = "voice_recognition"
        private const val KEY_AUTO_TRANSCRIBE = "auto_transcribe"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_UPLOAD_AUDIO = "upload_audio"
        private const val KEY_DRIVE_URI = "drive_folder_uri"
        private const val KEY_DRIVE_NAME = "drive_folder_name"
        private const val KEY_IS_RECORDING = "is_recording"
        private const val KEY_RECORDING_STARTED = "recording_started"
    }
}
