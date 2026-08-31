package com.voicegrowth.app.ui.screens.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.preferences.AppSettings
import com.voicegrowth.app.scanner.FolderAccessManager
import com.voicegrowth.app.scanner.FolderAccessStatus
import com.voicegrowth.app.service.CaptureNotificationManager
import com.voicegrowth.app.sync.DriveTreeStatus
import com.voicegrowth.app.sync.DriveTreeSyncService
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
    private val driveTreeService = DriveTreeSyncService(app)

    private val _folderStatus = MutableStateFlow<FolderAccessStatus?>(null)
    val folderStatus: StateFlow<FolderAccessStatus?> = _folderStatus.asStateFlow()

    private val _driveTreeStatus = MutableStateFlow<DriveTreeStatus?>(null)
    val driveTreeStatus: StateFlow<DriveTreeStatus?> = _driveTreeStatus.asStateFlow()

    val settingsState: StateFlow<AppSettings> = store.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    init {
        refreshRuntimeDiagnostics()
    }

    fun setAutoProcessing(v: Boolean) = viewModelScope.launch {
        store.setAutoProcessing(v)
        if (v) app.enqueueFolderScanNow()
    }

    fun setWifiOnly(v: Boolean) = viewModelScope.launch {
        store.setWifiOnly(v)
        app.enqueueDriveSync(v)
    }

    fun setDeleteSourceAudioEnabled(v: Boolean) = viewModelScope.launch {
        store.setDeleteSourceAudioEnabled(v)
    }

    fun setDeleteLocalAudioDays(v: Int) = viewModelScope.launch {
        store.setDeleteLocalAudioDays(v.coerceIn(1, 30))
    }

    fun configureRecordingFolder(uri: Uri) = viewModelScope.launch {
        val persisted = withContext(Dispatchers.IO) { FolderAccessManager.persistReadPermission(app, uri) }
        val status = withContext(Dispatchers.IO) { FolderAccessManager.inspect(app, uri) }
        _folderStatus.value = status
        if (persisted.isFailure || !status.persistedReadPermission) {
            _folderStatus.value = status.copy(
                accessible = false,
                message = "Android did not retain read access. Re-select the folder and choose Use this folder/Allow."
            )
            return@launch
        }
        if (!status.accessible) return@launch
        store.setSelectedFolder(uri.toString(), status.displayName)
        app.enqueueFolderScanNow()
    }

    fun refreshFolderStatus() = viewModelScope.launch {
        val current = store.settingsFlow.first()
        val folder = current.selectedFolderUri
        if (folder.isNullOrBlank()) {
            _folderStatus.value = null
            return@launch
        }

        val status = withContext(Dispatchers.IO) { FolderAccessManager.inspect(app, Uri.parse(folder)) }
        _folderStatus.value = status
        if (
            status.accessible &&
            status.persistedReadPermission &&
            status.displayName.isNotBlank() &&
            status.displayName != current.selectedFolderDisplayName
        ) {
            store.setSelectedFolder(folder, status.displayName)
        }
    }

    fun scanSelectedFolderNow() {
        app.enqueueFolderScanNow()
        refreshFolderStatus()
    }

    fun configureDriveTree(uri: Uri) = viewModelScope.launch {
        _driveTreeStatus.value = DriveTreeStatus(
            usable = false,
            persistedReadPermission = false,
            persistedWritePermission = false,
            displayName = "Selected folder",
            providerAuthority = uri.authority,
            message = "Checking cloud-folder access…"
        )

        val persisted = withContext(Dispatchers.IO) { driveTreeService.persistPermission(uri) }
        if (persisted.isFailure) {
            _driveTreeStatus.value = withContext(Dispatchers.IO) { driveTreeService.inspect(uri) }.copy(
                usable = false,
                message = "Could not retain read/write access: ${(persisted.exceptionOrNull()?.message ?: "select the folder again").take(180)}"
            )
            return@launch
        }

        val verified = withContext(Dispatchers.IO) { driveTreeService.verifyWrite(uri) }
        if (verified.isFailure) {
            _driveTreeStatus.value = withContext(Dispatchers.IO) { driveTreeService.inspect(uri) }.copy(
                usable = false,
                message = "Cloud-folder write test failed: ${(verified.exceptionOrNull()?.message ?: "unknown error").take(180)}"
            )
            return@launch
        }

        val status = verified.getOrThrow()
        _driveTreeStatus.value = status
        store.setDriveTree(uri.toString(), status.displayName)
        store.setDriveFolderHierarchy("VoiceGrowth/Audio")
        app.enqueueDriveSync(store.settingsFlow.first().wifiOnly)
    }

    fun refreshDriveTreeStatus(testWrite: Boolean = false) = viewModelScope.launch {
        val current = store.settingsFlow.first()
        val raw = current.driveTreeUri
        if (raw.isNullOrBlank()) {
            _driveTreeStatus.value = null
            return@launch
        }
        val uri = Uri.parse(raw)
        val status = withContext(Dispatchers.IO) {
            if (testWrite) {
                driveTreeService.verifyWrite(uri).getOrElse { error ->
                    driveTreeService.inspect(uri).copy(
                        usable = false,
                        message = "Cloud-folder write test failed: ${(error.message ?: error::class.java.simpleName).take(180)}"
                    )
                }
            } else {
                driveTreeService.inspect(uri)
            }
        }
        _driveTreeStatus.value = status
        if (status.usable && status.displayName != current.driveTreeDisplayName) {
            store.setDriveTree(raw, status.displayName)
        }
        if (status.usable && testWrite) app.enqueueDriveSync(current.wifiOnly)
    }

    fun disconnectDriveTree() = viewModelScope.launch {
        val current = store.settingsFlow.first()
        current.driveTreeUri?.let { raw ->
            withContext(Dispatchers.IO) { driveTreeService.releasePermission(Uri.parse(raw)) }
        }
        store.setDriveTree(null, null)
        _driveTreeStatus.value = null
    }

    fun refreshRuntimeDiagnostics() {
        refreshFolderStatus()
        refreshDriveTreeStatus()
        CaptureNotificationManager.showReady(app)
    }
}
