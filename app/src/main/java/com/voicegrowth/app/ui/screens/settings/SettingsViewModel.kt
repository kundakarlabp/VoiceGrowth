package com.voicegrowth.app.ui.screens.settings

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.preferences.AppSettings
import com.voicegrowth.app.engine.ai.AiModelImportProgress
import com.voicegrowth.app.engine.ai.AiModelManager
import com.voicegrowth.app.scanner.FolderAccessManager
import com.voicegrowth.app.scanner.FolderAccessStatus
import com.voicegrowth.app.service.CaptureNotificationManager
import com.voicegrowth.app.sync.AppIdentityDiagnostics
import com.voicegrowth.app.sync.DriveAuthorizationAttempt
import com.voicegrowth.app.sync.DriveTreeStatus
import com.voicegrowth.app.sync.DriveTreeSyncService
import com.voicegrowth.app.sync.GoogleAuthManager
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


data class DriveUiState(
    val authorized: Boolean = false,
    val accountLabel: String? = null,
    val message: String? = null,
    val checking: Boolean = false,
    val packageName: String = "",
    val signingSha1: String = ""
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VoiceGrowthApplication
    private val store = app.container.settingsDataStore
    private val driveTreeService = DriveTreeSyncService(app)

    private val _aiImporting = MutableStateFlow(false)
    val aiImporting: StateFlow<Boolean> = _aiImporting.asStateFlow()
    private val _aiMessage = MutableStateFlow<String?>(null)
    val aiMessage: StateFlow<String?> = _aiMessage.asStateFlow()
    private val _aiProgress = MutableStateFlow<String?>(null)
    val aiProgress: StateFlow<String?> = _aiProgress.asStateFlow()

    private val _folderStatus = MutableStateFlow<FolderAccessStatus?>(null)
    val folderStatus: StateFlow<FolderAccessStatus?> = _folderStatus.asStateFlow()

    private val _driveTreeStatus = MutableStateFlow<DriveTreeStatus?>(null)
    val driveTreeStatus: StateFlow<DriveTreeStatus?> = _driveTreeStatus.asStateFlow()

    private val _driveUiState = MutableStateFlow(DriveUiState())
    val driveUiState: StateFlow<DriveUiState> = _driveUiState.asStateFlow()
    private val _driveResolution = MutableStateFlow<PendingIntent?>(null)
    val driveResolution: StateFlow<PendingIntent?> = _driveResolution.asStateFlow()

    val settingsState: StateFlow<AppSettings> = store.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    init {
        refreshRuntimeDiagnostics()
    }

    fun setAutoProcessing(v: Boolean) = viewModelScope.launch {
        store.setAutoProcessing(v)
        if (v) app.enqueueFolderScanNow()
    }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { store.setWifiOnly(v); app.enqueueDriveSync(v) }
    fun setOnlyProcessOver30Sec(v: Boolean) = viewModelScope.launch { store.setOnlyProcessOver30Sec(v) }
    fun setUploadAudio(v: Boolean) = viewModelScope.launch { store.setUploadAudio(v); enqueueSync() }
    fun setUploadTranscript(v: Boolean) = viewModelScope.launch { store.setUploadTranscript(v); enqueueSync() }
    fun setDeleteSourceAudioEnabled(v: Boolean) = viewModelScope.launch { store.setDeleteSourceAudioEnabled(v) }
    fun setDeleteLocalAudioDays(v: Int) = viewModelScope.launch { store.setDeleteLocalAudioDays(v.coerceIn(1, 30)) }
    fun setTranscriptionLanguage(v: String) = viewModelScope.launch { store.setTranscriptionLanguage(v) }
    fun setDriveFolderHierarchy(v: String) = viewModelScope.launch { store.setDriveFolderHierarchy(v) }
    fun setClinicalPrivacyMode(v: Boolean) = viewModelScope.launch { store.setClinicalPrivacyMode(v) }

    fun setDailyDigestEnabled(v: Boolean) = viewModelScope.launch {
        val current = settingsState.value
        if (v && (!current.aiEnabled || current.aiModelPath.isNullOrBlank())) {
            _aiMessage.value = "Enable on-device AI and import a model before enabling the daily digest"
            return@launch
        }
        store.setDailyDigestEnabled(v)
    }

    fun setAiEnabled(v: Boolean) = viewModelScope.launch {
        if (v && settingsState.value.aiModelPath.isNullOrBlank()) {
            _aiMessage.value = "Import a .litertlm model before enabling on-device AI"
            return@launch
        }
        store.setAiEnabled(v)
        if (!v) store.setDailyDigestEnabled(false)
        if (v) app.enqueueAudioProcessing()
    }

    fun setAiPreferredBackend(v: String) = viewModelScope.launch {
        store.setAiPreferredBackend(if (v.equals("cpu", true)) "cpu" else "gpu")
    }

    fun configureRecordingFolder(uri: Uri) = viewModelScope.launch {
        val persisted = withContext(Dispatchers.IO) { FolderAccessManager.persistReadPermission(app, uri) }
        val status = withContext(Dispatchers.IO) { FolderAccessManager.inspect(app, uri) }
        _folderStatus.value = status
        if (persisted.isFailure || !status.persistedReadPermission) {
            _folderStatus.value = status.copy(
                accessible = false,
                message = "Android did not retain read access. Re-select the folder and choose Use this folder/Allow when prompted."
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

    /** Recommended Drive connection: user-selected cloud folder through Android SAF. */
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
        _driveUiState.value = diagnosticsState(
            authorized = false,
            checking = false,
            message = "Using Android Files/SAF for Drive sync. OAuth is not required."
        )
        enqueueSync()
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
        if (status.usable && testWrite) enqueueSync()
    }

    fun disconnectDriveTree() = viewModelScope.launch {
        val current = store.settingsFlow.first()
        current.driveTreeUri?.let { raw ->
            withContext(Dispatchers.IO) { driveTreeService.releasePermission(Uri.parse(raw)) }
        }
        store.setDriveTree(null, null)
        _driveTreeStatus.value = null
        _driveUiState.value = diagnosticsState(
            checking = false,
            message = "Cloud folder disconnected. Choose a Drive folder or use the optional OAuth connection."
        )
        refreshDriveAuthorization()
    }

    /** Optional advanced OAuth path retained for users who prefer direct Drive REST access. */
    fun connectDrive(forceAccountPicker: Boolean = true) = viewModelScope.launch {
        _driveUiState.value = diagnosticsState(checking = true, message = "Checking Google Drive authorization…")
        try {
            when (val attempt = GoogleAuthManager.authorize(app, forceAccountPicker)) {
                is DriveAuthorizationAttempt.Authorized -> applyAuthorization(attempt.authorization.accountEmail)
                is DriveAuthorizationAttempt.NeedsResolution -> {
                    _driveResolution.value = attempt.pendingIntent
                    _driveUiState.value = diagnosticsState(checking = false, message = "Choose a Google account and allow VoiceGrowth Drive access.")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _driveUiState.value = diagnosticsState(
                checking = false,
                message = GoogleAuthManager.userFacingError(app, error)
            )
        }
    }

    fun completeDriveAuthorization(data: Intent?) = viewModelScope.launch {
        try {
            val authorization = GoogleAuthManager.authorizationFromIntent(app, data)
            applyAuthorization(authorization.accountEmail)
        } catch (error: Exception) {
            _driveUiState.value = diagnosticsState(
                checking = false,
                message = GoogleAuthManager.userFacingError(app, error)
            )
        }
    }

    fun cancelDriveAuthorization() {
        _driveUiState.value = diagnosticsState(
            checking = false,
            message = "Google account authorization was cancelled. You can use the recommended Drive folder method instead."
        )
    }

    fun consumeDriveResolution() {
        _driveResolution.value = null
    }

    fun refreshDriveAuthorization() = viewModelScope.launch {
        val current = store.settingsFlow.first()
        if (!current.driveTreeUri.isNullOrBlank()) {
            _driveUiState.value = diagnosticsState(
                authorized = false,
                checking = false,
                message = "Drive folder sync is configured through Android Files. OAuth is optional."
            )
            return@launch
        }

        val previous = _driveUiState.value
        _driveUiState.value = diagnosticsState(checking = true, message = previous.message)
        try {
            when (val attempt = GoogleAuthManager.authorize(app, false)) {
                is DriveAuthorizationAttempt.Authorized -> applyAuthorization(attempt.authorization.accountEmail, enqueue = false)
                is DriveAuthorizationAttempt.NeedsResolution -> {
                    _driveUiState.value = diagnosticsState(
                        authorized = false,
                        checking = false,
                        message = "OAuth is not authorized. Recommended: choose a Drive folder above; no OAuth setup is required."
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _driveUiState.value = diagnosticsState(
                authorized = false,
                checking = false,
                message = GoogleAuthManager.userFacingError(app, error)
            )
        }
    }

    fun disconnectDrive() = viewModelScope.launch {
        val result = GoogleAuthManager.revoke(app)
        store.setGoogleAccountEmail(null)
        _driveUiState.value = diagnosticsState(
            authorized = false,
            checking = false,
            message = if (result.isSuccess) "Google OAuth access disconnected" else "Local OAuth connection cleared; Google revocation could not be confirmed."
        )
    }

    fun importAiModel(uri: Uri) = viewModelScope.launch {
        if (_aiImporting.value) return@launch
        _aiImporting.value = true
        _aiMessage.value = null
        _aiProgress.value = "Preparing model import…"
        try {
            val name = AiModelManager.displayName(app, uri)
            require(name.endsWith(".litertlm", ignoreCase = true)) { "Select a LiteRT-LM .litertlm model file" }
            val file = withContext(Dispatchers.IO) {
                AiModelManager.importModel(app, uri) { progress ->
                    _aiProgress.value = progressText(progress)
                }
            }
            store.setAiModel(file.absolutePath, name)
            store.setAiEnabled(true)
            _aiProgress.value = null
            _aiMessage.value = "On-device AI model ready: $name"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _aiProgress.value = null
            _aiMessage.value = "Model import failed: ${(error.message ?: error::class.java.simpleName).take(220)}"
        } finally {
            _aiImporting.value = false
        }
    }

    fun removeAiModel() = viewModelScope.launch {
        store.setAiEnabled(false)
        store.setDailyDigestEnabled(false)
        withContext(Dispatchers.IO) { AiModelManager.removeModel(app) }
        store.setAiModel(null, null)
        _aiProgress.value = null
        _aiMessage.value = "Imported AI model removed from VoiceGrowth"
    }

    fun refreshRuntimeDiagnostics() {
        refreshFolderStatus()
        viewModelScope.launch {
            val current = store.settingsFlow.first()
            if (current.driveTreeUri.isNullOrBlank()) {
                refreshDriveAuthorization()
            } else {
                refreshDriveTreeStatus()
                _driveUiState.value = diagnosticsState(
                    checking = false,
                    message = "Drive folder sync is configured through Android Files. OAuth is optional."
                )
            }
        }
        CaptureNotificationManager.showReady(app)
    }

    fun clearAiMessage() { _aiMessage.value = null }

    private suspend fun applyAuthorization(email: String?, enqueue: Boolean = true) {
        val label = email ?: "Authorized Google account"
        store.setGoogleAccountEmail(label)
        _driveUiState.value = diagnosticsState(
            authorized = true,
            accountLabel = label,
            checking = false,
            message = "Google OAuth authorized with drive.file access."
        )
        if (enqueue) enqueueSync()
    }

    private fun diagnosticsState(
        authorized: Boolean = false,
        accountLabel: String? = null,
        checking: Boolean = false,
        message: String? = null
    ): DriveUiState = DriveUiState(
        authorized = authorized,
        accountLabel = accountLabel,
        message = message,
        checking = checking,
        packageName = AppIdentityDiagnostics.packageName(app),
        signingSha1 = runCatching { AppIdentityDiagnostics.signingSha1(app) }.getOrDefault("Unavailable")
    )

    private fun progressText(progress: AiModelImportProgress): String {
        val copiedMb = progress.copiedBytes / (1024L * 1024L)
        val total = progress.totalBytes
        return if (total != null && total > 0L) {
            val totalMb = total / (1024L * 1024L)
            "Importing model… ${progress.percent ?: 0}% ($copiedMb/$totalMb MB)"
        } else {
            "Importing model… $copiedMb MB copied"
        }
    }

    private suspend fun enqueueSync() {
        app.enqueueDriveSync(store.settingsFlow.first().wifiOnly)
    }
}
