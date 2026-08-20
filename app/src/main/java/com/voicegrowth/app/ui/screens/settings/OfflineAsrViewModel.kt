package com.voicegrowth.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.engine.transcription.OfflineWhisperModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


data class OfflineAsrUiState(
    val installed: Boolean = false,
    val installing: Boolean = false,
    val progressPercent: Int = 0,
    val progressText: String? = null,
    val message: String = "Checking reliable offline transcription…"
)

class OfflineAsrViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VoiceGrowthApplication
    private val _state = MutableStateFlow(statusState())
    val state: StateFlow<OfflineAsrUiState> = _state.asStateFlow()

    fun refresh() {
        if (!_state.value.installing) _state.value = statusState()
    }

    fun install() {
        if (_state.value.installing) return
        viewModelScope.launch {
            _state.value = OfflineAsrUiState(
                installing = true,
                message = "Downloading reliable offline Whisper model…"
            )
            try {
                OfflineWhisperModelManager.install(app) { progress ->
                    val mb = progress.downloadedBytes / (1024L * 1024L)
                    val totalMb = progress.expectedBytes / (1024L * 1024L)
                    _state.value = OfflineAsrUiState(
                        installed = false,
                        installing = true,
                        progressPercent = progress.percent,
                        progressText = "${progress.percent}% · $mb/$totalMb MB · ${progress.currentFile}",
                        message = "Installing reliable offline transcription…"
                    )
                }
                _state.value = statusState().copy(
                    message = "Offline Whisper is ready. Pending recordings are being retried now."
                )
                app.enqueueAudioProcessing()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = statusState().copy(
                    message = "Offline ASR install failed: ${(error.message ?: error::class.java.simpleName).take(220)}"
                )
            }
        }
    }

    fun remove() {
        if (_state.value.installing) return
        viewModelScope.launch {
            try {
                OfflineWhisperModelManager.remove(app)
                _state.value = statusState().copy(
                    message = "Offline Whisper model removed. Android SpeechRecognizer remains as fallback only."
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = statusState().copy(
                    message = "Could not remove offline ASR: ${(error.message ?: error::class.java.simpleName).take(180)}"
                )
            }
        }
    }

    private fun statusState(): OfflineAsrUiState {
        val status = OfflineWhisperModelManager.status(app)
        return OfflineAsrUiState(
            installed = status.installed,
            installing = false,
            progressPercent = if (status.installed) 100 else 0,
            message = status.message + if (status.installed) {
                " · ${(status.sizeBytes / (1024L * 1024L)).coerceAtLeast(1L)} MB"
            } else ""
        )
    }
}
