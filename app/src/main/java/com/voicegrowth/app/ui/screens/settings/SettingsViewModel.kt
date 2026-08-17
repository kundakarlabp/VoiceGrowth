package com.voicegrowth.app.ui.screens.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.preferences.AppSettings
import com.voicegrowth.app.engine.ai.AiModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VoiceGrowthApplication
    private val store = app.container.settingsDataStore

    private val _aiImporting = MutableStateFlow(false)
    val aiImporting: StateFlow<Boolean> = _aiImporting.asStateFlow()
    private val _aiMessage = MutableStateFlow<String?>(null)
    val aiMessage: StateFlow<String?> = _aiMessage.asStateFlow()

    val settingsState: StateFlow<AppSettings> = store.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setAutoProcessing(v: Boolean) = viewModelScope.launch {
        store.setAutoProcessing(v)
        if (v) app.enqueueAudioProcessing()
    }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch {
        store.setWifiOnly(v)
        app.enqueueDriveSync(v)
    }
    fun setOnlyProcessOver30Sec(v: Boolean) = viewModelScope.launch { store.setOnlyProcessOver30Sec(v) }
    fun setUploadAudio(v: Boolean) = viewModelScope.launch { store.setUploadAudio(v); enqueueSync() }
    fun setUploadTranscript(v: Boolean) = viewModelScope.launch { store.setUploadTranscript(v); enqueueSync() }
    fun setDeleteSourceAudioEnabled(v: Boolean) = viewModelScope.launch { store.setDeleteSourceAudioEnabled(v) }
    fun setDeleteLocalAudioDays(v: Int) = viewModelScope.launch { store.setDeleteLocalAudioDays(v.coerceIn(1, 30)) }
    fun setTranscriptionLanguage(v: String) = viewModelScope.launch { store.setTranscriptionLanguage(v) }
    fun setDriveFolderHierarchy(v: String) = viewModelScope.launch { store.setDriveFolderHierarchy(v) }
    fun setClinicalPrivacyMode(v: Boolean) = viewModelScope.launch { store.setClinicalPrivacyMode(v) }
    fun setAiEnabled(v: Boolean) = viewModelScope.launch {
        if (v && settingsState.value.aiModelPath.isNullOrBlank()) {
            _aiMessage.value = "Import a .litertlm model before enabling on-device AI"
            return@launch
        }
        store.setAiEnabled(v)
        if (v) app.enqueueAudioProcessing()
    }
    fun setAiPreferredBackend(v: String) = viewModelScope.launch {
        store.setAiPreferredBackend(if (v.equals("cpu", true)) "cpu" else "gpu")
    }
    fun setSelectedFolder(uri: String, name: String) = viewModelScope.launch { store.setSelectedFolder(uri, name) }
    fun setGoogleAccount(email: String?) = viewModelScope.launch { store.setGoogleAccountEmail(email); enqueueSync() }

    fun importAiModel(uri: Uri) = viewModelScope.launch {
        if (_aiImporting.value) return@launch
        _aiImporting.value = true
        _aiMessage.value = "Importing model into VoiceGrowth private storage…"
        try {
            val name = AiModelManager.displayName(app, uri)
            require(name.endsWith(".litertlm", ignoreCase = true)) {
                "Select a LiteRT-LM .litertlm model file"
            }
            val file = withContext(Dispatchers.IO) { AiModelManager.importModel(app, uri) }
            store.setAiModel(file.absolutePath, name)
            store.setAiEnabled(true)
            _aiMessage.value = "On-device AI model ready: $name"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _aiMessage.value = "Model import failed: ${(error.message ?: error::class.java.simpleName).take(180)}"
        } finally {
            _aiImporting.value = false
        }
    }

    fun removeAiModel() = viewModelScope.launch {
        store.setAiEnabled(false)
        withContext(Dispatchers.IO) { AiModelManager.removeModel(app) }
        store.setAiModel(null, null)
        _aiMessage.value = "Imported AI model removed from VoiceGrowth"
    }

    fun clearAiMessage() { _aiMessage.value = null }

    private suspend fun enqueueSync() {
        app.enqueueDriveSync(store.settingsFlow.first().wifiOnly)
    }
}
