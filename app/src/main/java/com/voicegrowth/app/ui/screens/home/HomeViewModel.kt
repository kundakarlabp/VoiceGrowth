package com.voicegrowth.app.ui.screens.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.preferences.AppSettings
import com.voicegrowth.app.scanner.FolderScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File


data class HomeUiState(
    val recordings: List<RecordingEntity> = emptyList(),
    val allRecordings: List<RecordingEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val isScanning: Boolean = false,
    val selectedFilter: ProcessingStatus? = null,
    val previewRecording: RecordingEntity? = null,
    val message: String? = null
)

private data class HomeTransientState(
    val preview: RecordingEntity?,
    val message: String?
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VoiceGrowthApplication
    private val repository = app.container.recordingRepository
    private val settingsStore = app.container.settingsDataStore
    private val folderScanner = FolderScanner(application, repository)

    private val filter = MutableStateFlow<ProcessingStatus?>(null)
    private val scanning = MutableStateFlow(false)
    private val preview = MutableStateFlow<RecordingEntity?>(null)
    private val message = MutableStateFlow<String?>(null)

    private val transient = combine(preview, message) { previewItem, msg ->
        HomeTransientState(previewItem, msg)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        repository.recordingsFlow,
        repository.settingsFlow,
        filter,
        scanning,
        transient
    ) { recordings, settings, selected, isScanning, transientState ->
        HomeUiState(
            recordings = selected?.let { s -> recordings.filter { it.status == s } } ?: recordings,
            allRecordings = recordings,
            settings = settings,
            isScanning = isScanning,
            selectedFilter = selected,
            previewRecording = transientState.preview,
            message = transientState.message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setFilter(status: ProcessingStatus?) { filter.value = status }
    fun setPreviewRecording(recording: RecordingEntity?) { preview.value = recording }
    fun clearMessage() { message.value = null }

    fun selectFolder(uri: Uri, displayName: String) = viewModelScope.launch {
        settingsStore.setSelectedFolder(uri.toString(), displayName)
        scanNow()
    }

    fun scanNow() = viewModelScope.launch {
        if (scanning.value) return@launch
        scanning.value = true
        try {
            val current = repository.settingsFlow.first()
            val selected = current.selectedFolderUri ?: return@launch
            val count = folderScanner.scanFolder(Uri.parse(selected), current)
            if (count > 0) app.enqueueAudioProcessing()
            message.value = if (count > 0) "$count new recording(s) queued" else "No new completed recordings found"
        } catch (e: Exception) {
            message.value = e.message ?: "Folder scan failed"
        } finally {
            scanning.value = false
        }
    }

    fun retryRecording(id: Long) = viewModelScope.launch {
        val item = repository.getById(id) ?: return@launch
        if (item.transcriptPath != null) {
            repository.updateStatus(id, ProcessingStatus.WAITING_FOR_SYNC)
            app.enqueueDriveSync(uiState.value.settings.wifiOnly)
        } else {
            repository.updateStatus(id, ProcessingStatus.PENDING)
            app.enqueueAudioProcessing()
        }
    }

    fun deleteRecording(id: Long) = viewModelScope.launch {
        val item = repository.getById(id) ?: return@launch
        item.transcriptPath?.let { path -> runCatching { File(path).delete() } }
        repository.deleteRecording(id)
    }
}
