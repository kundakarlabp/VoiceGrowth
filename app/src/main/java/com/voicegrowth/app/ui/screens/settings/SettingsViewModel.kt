package com.voicegrowth.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.preferences.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VoiceGrowthApplication
    private val store = app.container.settingsDataStore

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
    fun setDeleteLocalAudioDays(v: Int) = viewModelScope.launch { store.setDeleteLocalAudioDays(v.coerceIn(1, 30)) }
    fun setTranscriptionLanguage(v: String) = viewModelScope.launch { store.setTranscriptionLanguage(v) }
    fun setDriveFolderHierarchy(v: String) = viewModelScope.launch { store.setDriveFolderHierarchy(v) }
    fun setClinicalPrivacyMode(v: Boolean) = viewModelScope.launch { store.setClinicalPrivacyMode(v) }
    fun setSelectedFolder(uri: String, name: String) = viewModelScope.launch { store.setSelectedFolder(uri, name) }
    fun setGoogleAccount(email: String?) = viewModelScope.launch { store.setGoogleAccountEmail(email); enqueueSync() }

    private suspend fun enqueueSync() {
        app.enqueueDriveSync(store.settingsFlow.first().wifiOnly)
    }
}
