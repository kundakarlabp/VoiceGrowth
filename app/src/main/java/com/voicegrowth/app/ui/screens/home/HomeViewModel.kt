package com.voicegrowth.app.ui.screens.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.preferences.AppSettings
import com.voicegrowth.app.engine.ai.OnDeviceAiEngine
import com.voicegrowth.app.engine.knowledge.DailyDigestGenerator
import com.voicegrowth.app.engine.knowledge.KnowledgeMatch
import com.voicegrowth.app.engine.knowledge.KnowledgeSearchIndex
import com.voicegrowth.app.engine.privacy.ClinicalDeidentifier
import com.voicegrowth.app.scanner.AudioImportManager
import com.voicegrowth.app.scanner.FolderScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File


data class HomeUiState(
    val recordings: List<RecordingEntity> = emptyList(),
    val allRecordings: List<RecordingEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val isScanning: Boolean = false,
    val selectedFilter: ProcessingStatus? = null,
    val previewRecording: RecordingEntity? = null,
    val message: String? = null,
    val searchQuery: String = "",
    val searchExcerpts: Map<Long, String> = emptyMap(),
    val knowledgeAnswer: String? = null,
    val isKnowledgeAnswering: Boolean = false,
    val digestContent: String? = null,
    val isDigestGenerating: Boolean = false
)

private data class SearchState(val query: String, val matches: List<KnowledgeMatch>)
private data class AiState(
    val answer: String?,
    val answering: Boolean,
    val digest: String?,
    val digesting: Boolean
)
private data class TransientState(val preview: RecordingEntity?, val message: String?, val ai: AiState)
private data class BaseState(
    val recordings: List<RecordingEntity>,
    val all: List<RecordingEntity>,
    val settings: AppSettings,
    val scanning: Boolean,
    val filter: ProcessingStatus?,
    val search: SearchState
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VoiceGrowthApplication
    private val repository = app.container.recordingRepository
    private val settingsStore = app.container.settingsDataStore
    private val folderScanner = FolderScanner(application, repository)
    private val aiEngine = OnDeviceAiEngine()

    private val filter = MutableStateFlow<ProcessingStatus?>(null)
    private val scanning = MutableStateFlow(false)
    private val preview = MutableStateFlow<RecordingEntity?>(null)
    private val message = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")
    private val knowledgeAnswer = MutableStateFlow<String?>(null)
    private val knowledgeAnswering = MutableStateFlow(false)
    private val digestContent = MutableStateFlow<String?>(null)
    private val digestGenerating = MutableStateFlow(false)

    private val knowledgeIndex = repository.recordingsFlow
        .mapLatest { recordings -> withContext(Dispatchers.IO) { KnowledgeSearchIndex.build(recordings) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val searchState = combine(searchQuery, knowledgeIndex) { query, index ->
        SearchState(query, if (query.isBlank()) emptyList() else KnowledgeSearchIndex.search(index, query))
    }

    private val aiState = combine(knowledgeAnswer, knowledgeAnswering, digestContent, digestGenerating) {
            answer, answering, digest, digesting -> AiState(answer, answering, digest, digesting)
        }

    private val transient = combine(preview, message, aiState) { item, msg, ai -> TransientState(item, msg, ai) }

    private val baseState = combine(
        repository.recordingsFlow,
        repository.settingsFlow,
        filter,
        scanning,
        searchState
    ) { recordings, settings, selected, isScanning, search ->
        val statusFiltered = selected?.let { status -> recordings.filter { it.status == status } } ?: recordings
        val visible = if (search.query.isBlank()) {
            statusFiltered
        } else {
            val allowed = statusFiltered.associateBy { it.id }
            search.matches.mapNotNull { allowed[it.entry.recordingId] }
        }
        BaseState(visible, recordings, settings, isScanning, selected, search)
    }

    val uiState: StateFlow<HomeUiState> = combine(baseState, transient) { base, transientState ->
        HomeUiState(
            recordings = base.recordings,
            allRecordings = base.all,
            settings = base.settings,
            isScanning = base.scanning,
            selectedFilter = base.filter,
            previewRecording = transientState.preview,
            message = transientState.message,
            searchQuery = base.search.query,
            searchExcerpts = base.search.matches.associate { it.entry.recordingId to it.excerpt },
            knowledgeAnswer = transientState.ai.answer,
            isKnowledgeAnswering = transientState.ai.answering,
            digestContent = transientState.ai.digest,
            isDigestGenerating = transientState.ai.digesting
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setFilter(status: ProcessingStatus?) { filter.value = status }
    fun setPreviewRecording(recording: RecordingEntity?) { preview.value = recording }
    fun setSearchQuery(query: String) { searchQuery.value = query.take(160) }
    fun clearMessage() { message.value = null }
    fun clearKnowledgeAnswer() { knowledgeAnswer.value = null }
    fun clearDigest() { digestContent.value = null }

    fun selectFolder(uri: Uri, displayName: String) = viewModelScope.launch {
        settingsStore.setSelectedFolder(uri.toString(), displayName)
        scanNow()
    }

    fun importAudioUris(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        try {
            val count = AudioImportManager.importUris(app, uris).getOrThrow()
            message.value = if (count > 0) "$count audio file(s) imported" else "No audio files were imported"
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
            if (count > 0) app.enqueueAudioProcessing()
            message.value = if (count > 0) "$count new recording(s) queued" else "No new completed recordings found"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            message.value = error.message ?: "Folder scan failed"
        } finally {
            scanning.value = false
        }
    }

    fun askKnowledgeLibrary() = viewModelScope.launch {
        if (knowledgeAnswering.value) return@launch
        val query = searchQuery.value.trim()
        if (query.isEmpty()) {
            message.value = "Enter a library question first"
            return@launch
        }
        val settings = repository.settingsFlow.first()
        val modelPath = settings.aiModelPath
        if (!settings.aiEnabled || modelPath.isNullOrBlank()) {
            message.value = "Enable on-device AI and import a LiteRT-LM model first"
            return@launch
        }
        val matches = KnowledgeSearchIndex.search(knowledgeIndex.value, query, limit = 6)
        if (matches.isEmpty()) {
            message.value = "No matching transcript evidence found"
            return@launch
        }
        knowledgeAnswering.value = true
        try {
            val evidence = KnowledgeSearchIndex.evidenceForAi(matches)
            val safeEvidence = ClinicalDeidentifier.process(evidence, enabled = true).scrubbedText
            knowledgeAnswer.value = aiEngine.answerKnowledgeQuestion(
                context = app,
                question = query,
                deidentifiedEvidence = safeEvidence,
                modelPath = modelPath,
                modelDisplayName = settings.aiModelDisplayName,
                preferredBackend = settings.aiPreferredBackend
            ).getOrThrow().markdown
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            message.value = "AI library query failed: ${(error.message ?: error::class.java.simpleName).take(180)}"
        } finally {
            knowledgeAnswering.value = false
        }
    }

    fun generateTodayDigest() = viewModelScope.launch {
        if (digestGenerating.value) return@launch
        digestGenerating.value = true
        try {
            val file = DailyDigestGenerator.generate(app).getOrThrow()
            if (file == null) {
                message.value = "No processed transcripts are available for today's digest"
            } else {
                digestContent.value = withContext(Dispatchers.IO) { file.readText() }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            message.value = "Daily digest failed: ${(error.message ?: error::class.java.simpleName).take(180)}"
        } finally {
            digestGenerating.value = false
        }
    }

    fun openTodayDigest() = viewModelScope.launch {
        val file = DailyDigestGenerator.todayDigestFile(app)
        if (!file.exists()) {
            generateTodayDigest()
        } else {
            digestContent.value = withContext(Dispatchers.IO) { file.readText() }
        }
    }

    fun retryRecording(id: Long) = viewModelScope.launch {
        val item = repository.getById(id) ?: return@launch
        if (item.transcriptPath != null) {
            repository.updateStatusResetRetry(id, ProcessingStatus.WAITING_FOR_SYNC)
            app.enqueueDriveSync(uiState.value.settings.wifiOnly)
        } else {
            repository.updateStatusResetRetry(id, ProcessingStatus.PENDING)
            app.enqueueAudioProcessing()
        }
    }

    fun deleteRecording(id: Long) = viewModelScope.launch {
        val item = repository.getById(id) ?: return@launch
        item.transcriptPath?.let { path -> runCatching { File(path).delete() } }
        repository.deleteRecording(id)
    }
}
