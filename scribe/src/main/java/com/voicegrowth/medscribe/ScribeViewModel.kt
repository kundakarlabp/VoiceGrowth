package com.voicegrowth.medscribe

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScribeViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()
    private val repo = ScribeRepository.get(application)
    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.items.collect { _state.value = _state.value.copy(items = it) }
        }
        viewModelScope.launch {
            while (true) {
                refreshRuntime()
                delay(750L)
            }
        }
    }

    fun refreshRuntime() {
        val settings = repo.settings()
        _state.value = _state.value.copy(
            settings = settings,
            isRecording = repo.isRecording(),
            recordingStartedAt = repo.recordingStartedAt(),
            modelInstalled = ModelManager.isWhisperInstalled(app, settings.whisperModel),
            diarizationInstalled = ModelManager.isDiarizationInstalled(app),
            voiceProfiles = VoiceProfileStore.all(app)
        )
    }

    fun startRecording() {
        try {
            ContextCompat.startForegroundService(
                app,
                Intent(app, RecordingService::class.java).setAction(RecordingService.ACTION_START)
            )
            refreshRuntime()
        } catch (error: Throwable) {
            message("Recording could not start: ${error.message ?: error::class.java.simpleName}")
        }
    }

    fun stopRecording() {
        app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    fun importAudio(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            setBusy("Importing audio…")
            try {
                uris.forEach { uri ->
                    val item = AudioUtils.importAudio(app, uri)
                    repo.add(item)
                    if (repo.settings().autoTranscribe) ProcessingService.start(app, item.id)
                }
                message("Imported ${uris.size} audio file${if (uris.size == 1) "" else "s"}")
            } catch (error: Throwable) {
                message("Audio import failed: ${error.message ?: error::class.java.simpleName}")
            } finally {
                setBusy(null)
            }
        }
    }

    fun process(id: String) {
        repo.update(id) { it.copy(status = ItemStatus.RECORDED, errorMessage = null) }
        ProcessingService.start(app, id)
    }

    fun delete(id: String, deleteFiles: Boolean = true) {
        repo.remove(id, deleteFiles)
        message("Recording removed")
    }

    fun saveTranscript(id: String, text: String) {
        repo.editTranscript(id, text)
        repo.update(id) { it.copy(driveSyncedAt = null) }
        message("Transcript saved locally")
    }

    fun renameSpeaker(id: String, speakerNumber: Int, name: String) {
        val item = repo.get(id) ?: return
        val cleanName = name.trim().replace(Regex("[\\r\\n*]+"), " ").take(50)
        if (cleanName.isBlank()) return
        val current = repo.transcriptText(item)
        val pattern = Regex("\\*\\*Speaker\\s+$speakerNumber\\*\\*", RegexOption.IGNORE_CASE)
        val updated = current.replace(pattern, "**$cleanName**")
        if (updated != current) saveTranscript(id, updated)
    }

    fun enrollVoice(id: String, name: String) {
        val item = repo.get(id) ?: return
        viewModelScope.launch {
            setBusy("Enrolling voice profile…")
            val result = SpeakerIdentityEngine.enrollFromRecording(app, item, name)
            if (result.isSuccess) {
                message("Voice profile saved for ${result.getOrThrow().name}")
            } else {
                message("Voice enrollment failed: ${result.exceptionOrNull()?.message ?: "unknown error"}")
            }
            setBusy(null)
            refreshRuntime()
        }
    }

    fun deleteVoiceProfile(id: String) {
        VoiceProfileStore.remove(app, id)
        refreshRuntime()
        message("Voice profile removed")
    }

    fun syncNow(id: String) {
        viewModelScope.launch {
            val item = repo.get(id) ?: return@launch
            val settings = repo.settings()
            setBusy("Syncing transcript…")
            val result = DriveFolderSync.sync(app, item, settings)
            if (result.isSuccess) {
                repo.update(id) { it.copy(driveSyncedAt = System.currentTimeMillis(), errorMessage = null) }
                message("Transcript synced to ${settings.driveFolderName ?: "Drive"}")
            } else {
                message("Drive sync failed: ${result.exceptionOrNull()?.message ?: "unknown error"}")
            }
            setBusy(null)
        }
    }

    fun setDriveFolder(uri: Uri?, name: String?) {
        repo.setDriveFolder(uri, name)
        refreshRuntime()
        if (uri != null) message("Drive folder linked")
    }

    fun updateLanguage(value: String) = updateSettings { it.copy(language = value) }
    fun updateWhisperModel(value: String) = updateSettings { it.copy(whisperModel = value) }
    fun updateDiarization(value: Boolean) = updateSettings { it.copy(diarizationEnabled = value) }
    fun updateVoiceRecognition(value: Boolean) = updateSettings { it.copy(voiceRecognitionEnabled = value) }
    fun updateAutoTranscribe(value: Boolean) = updateSettings { it.copy(autoTranscribe = value) }
    fun updateAutoSync(value: Boolean) = updateSettings { it.copy(autoSync = value) }
    fun updateUploadAudio(value: Boolean) = updateSettings { it.copy(uploadAudio = value) }

    fun installWhisper() {
        val selected = repo.settings().whisperModel
        viewModelScope.launch {
            setBusy("Downloading Whisper model…")
            try {
                ModelManager.installWhisper(app, selected) { progress ->
                    _state.value = _state.value.copy(modelProgress = progress)
                }
                message("Whisper ${selected.replaceFirstChar(Char::uppercase)} installed")
            } catch (error: Throwable) {
                message("Model install failed: ${error.message ?: error::class.java.simpleName}")
            } finally {
                _state.value = _state.value.copy(modelProgress = null)
                setBusy(null)
                refreshRuntime()
            }
        }
    }

    fun installDiarization() {
        viewModelScope.launch {
            setBusy("Downloading speaker models…")
            try {
                ModelManager.installDiarization(app) { progress ->
                    _state.value = _state.value.copy(modelProgress = progress)
                }
                message("Speaker diarization and voice recognition models installed")
            } catch (error: Throwable) {
                message("Speaker model install failed: ${error.message ?: error::class.java.simpleName}")
            } finally {
                _state.value = _state.value.copy(modelProgress = null)
                setBusy(null)
                refreshRuntime()
            }
        }
    }

    fun setSearch(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    fun transcript(id: String): String = repo.get(id)?.let(repo::transcriptText).orEmpty()

    suspend fun transcriptForShare(id: String): String = withContext(Dispatchers.IO) { transcript(id) }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun updateSettings(transform: (ScribeSettings) -> ScribeSettings) {
        repo.updateSettings(transform)
        refreshRuntime()
    }

    private fun snapshot(): UiState {
        val settings = repo.settings()
        return UiState(
            items = repo.items.value,
            settings = settings,
            isRecording = repo.isRecording(),
            recordingStartedAt = repo.recordingStartedAt(),
            modelInstalled = ModelManager.isWhisperInstalled(app, settings.whisperModel),
            diarizationInstalled = ModelManager.isDiarizationInstalled(app),
            voiceProfiles = VoiceProfileStore.all(app)
        )
    }

    private fun message(text: String) {
        _state.value = _state.value.copy(message = text, busyMessage = null)
    }

    private fun setBusy(text: String?) {
        _state.value = _state.value.copy(busyMessage = text)
    }
}
