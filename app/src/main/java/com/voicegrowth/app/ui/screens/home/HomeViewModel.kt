package com.voicegrowth.app.ui.screens.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.preferences.AppSettings
import com.voicegrowth.app.scanner.AudioImportManager
import com.voicegrowth.app.scanner.FolderScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


data class HomeUiState(
    val recordings: List<RecordingEntity> = emptyList(),
    val allRecordings: List<RecordingEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val isScanning: Boolean = false,
    val selectedFilter: ProcessingStatus? = null,
    val message: String? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VoiceGrowthApplication
    private val repository = app.container.recordingRepository
    private val settingsStore = app.container.settingsDataStore
    private val folderScanner = FolderScanner(application, repository)

    private val filter = MutableStateFlow<ProcessingStatus?>(null)
    private val scanning = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        repository.recordingsFlow,
        repository.settingsFlow,
        filter,
        scanning,
        message
    ) { recordings, settings, selected, isScanning, msg ->
        val visible = selected?.let { status ->
            when (status) {
                ProcessingStatus.WAITING_FOR_SYNC -> recordings.filter {
                    it.driveAudioFileId.isNullOrBlank() && it.status != ProcessingStatus.FAILED
                }
                ProcessingStatus.UPLOADED -> recordings.filter { !it.driveAudioFileId.isNullOrBlank() }
                else -> recordings.filter { it.status == status }
            }
        } ?: recordings

        HomeUiState(
            recordings = visible,
            allRecordings = recordings,
            settings = settings,
            isScanning = isScanning,
            selectedFilter = selected,
            message = msg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setFilter(status: ProcessingStatus?) { filter.value = status }
    fun clearMessage() { message.value = null }

    fun selectFolder(uri: Uri, displayName: String) = viewModelScope.launch {
        settingsStore.setSelectedFolder(uri.toString(), displayName)
        scanNow()
    }

    fun importAudioUris(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        try {
            val count = AudioImportManager.importUris(app, uris).getOrThrow()
            message.value = if (count > 0) "$count audio file(s) imported and queued" else "No audio files were imported"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            message.value = "Audio import failed: ${(error.message ?: error::class.java.simpleName).take(180)}"
        }
    }

    fun scanNow() = viewModelScope.launch {
        if (scanning.value) return@launch
        scanning.value = true
        try {
            val current = repository.settingsFlow.first()
            val selected = current.selectedFolderUri ?: return@launch
            val count = folderScanner.scanFolder(Uri.parse(selected), current)
            if (count > 0) app.enqueueDriveSync(current.wifiOnly)
            message.value = if (count > 0) "$count new recording(s) queued for Drive" else "No new completed recordings found"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            message.value = error.message ?: "Folder scan failed"
        } finally {
            scanning.value = false
        }
    }

    fun retryRecording(id: Long) = viewModelScope.launch {
        val item = repository.getById(id) ?: return@launch
        if (!item.driveAudioFileId.isNullOrBlank()) {
            repository.updateStatusResetRetry(id, ProcessingStatus.UPLOADED)
            return@launch
        }
        repository.updateStatusResetRetry(id, ProcessingStatus.WAITING_FOR_SYNC)
        app.enqueueDriveSync(uiState.value.settings.wifiOnly)
    }

    fun deleteRecording(id: Long) = viewModelScope.launch {
        repository.deleteRecording(id)
    }
}
