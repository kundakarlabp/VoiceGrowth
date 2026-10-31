package com.voicegrowth.app.ui.screens.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.sync.ArchiveFilePolicy
import com.voicegrowth.app.integration.GalleryHandoff
import com.voicegrowth.app.integration.GeminiWebHandoff
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.model.RecordingSource
import com.voicegrowth.app.ui.screens.recording.RecordingBottomSheet
import com.voicegrowth.app.ui.theme.StatusFailed
import com.voicegrowth.app.ui.theme.StatusPending
import com.voicegrowth.app.ui.theme.StatusUploaded
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onNavigateToSettings: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showRecorder by remember { mutableStateOf(false) }
    var geminiReviewRequested by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.recoverCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.selectFolder(uri, uri.lastPathSegment ?: "Call recordings")
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importAudioUris(uris)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VoiceGrowth", fontWeight = FontWeight.Bold)
                        Text(
                            state.settings.driveTreeDisplayName?.let { "Folder: $it" } ?: "Archive folder not linked",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                actions = {
                    IconButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }) { Icon(Icons.Default.Add, "Import audio") }
                    IconButton(onClick = viewModel::scanNow, enabled = !state.isScanning) { Icon(Icons.Default.Refresh, "Scan call folder") }
                    IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showRecorder = true },
                icon = { Icon(Icons.Default.Mic, null) },
                text = { Text("Record consult") }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.settings.driveTreeUri.isNullOrBlank()) {
                Card(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Connect Google Drive", fontWeight = FontWeight.Bold)
                            Text("Audio remains on the phone until an archive folder is selected in Settings.", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = onNavigateToSettings) { Text("Setup") }
                    }
                }
            }

            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f))
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudDone, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Audio-first workflow", fontWeight = FontWeight.SemiBold)
                        Text(
                            "VoiceGrowth saves original audio first. Optional offline Whisper creates an unverified transcript while charging. Structured clinical extraction still requires review.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (state.settings.selectedFolderUri == null) {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Phone, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Optional: link OEM call recordings", fontWeight = FontWeight.SemiBold)
                            Text("Use this only if you also want VoiceGrowth to collect recordings made by the phone's native recorder.", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { folderPicker.launch(null) }) { Text("Link") }
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    if (!GalleryHandoff.openAudioScribe(context)) {
                        android.widget.Toast.makeText(
                            context, "Install or update Google AI Edge Gallery to use Audio Scribe.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
            ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open Google Audio Scribe (select clip manually)")
            }
            Text(
                "Opens Gallery's offline Gemma. For a short recording, choose the archived audio " +
                    "yourself in Gallery; the transcript is not returned to VoiceGrowth.",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = { geminiReviewRequested = true },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open Gemini — consultation extraction")
            }
            Text(
                "Copies a structured clinical-extraction prompt. You manually add an authorized, " +
                    "de-identified recording or transcript in Gemini; nothing is uploaded by this button.",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StatsDashboard(state.allRecordings)
            OutlinedButton(
                onClick = {
                    runCatching { shareSyncManifest(context, state.allRecordings) }.onFailure {
                        android.widget.Toast.makeText(
                            context, "Unable to export sync manifest", android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Share sync manifest")
            }
            FilterRow(state.selectedFilter, viewModel::setFilter)

            if (state.recordings.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Audiotrack, null, Modifier.size(56.dp), tint = Color.Gray)
                        Spacer(Modifier.height(10.dp))
                        Text("No recordings in this view", color = Color.Gray)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.recordings, key = { it.id }) { recording ->
                        RecordingCard(
                            recording = recording,
                            onRetry = { viewModel.retryRecording(recording.id) },
                            onDelete = { viewModel.deleteRecording(recording.id) },
                            onShare = {
                                runCatching { shareRecording(context, recording) }.onFailure {
                                    android.widget.Toast.makeText(context, "Cannot share this audio; confirm that the source file is still accessible", android.widget.Toast.LENGTH_LONG).show()
                                }
                            },
                            onShareDraft = {
                                runCatching { shareTranscriptDraft(context, recording) }.onFailure {
                                    android.widget.Toast.makeText(context, "Draft transcript could not be shared", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (geminiReviewRequested) {
        AlertDialog(
            onDismissRequest = { geminiReviewRequested = false },
            title = { Text("Open Gemini for consultation review?") },
            text = {
                Text(
                    "VoiceGrowth will copy only a generic consultation-extraction template " +
                    "and open Gemini. It will NOT upload any recording or transcript. " +
                    "Only manually attach a de-identified recording after checking " +
                    "institutional authorization, consent and Gemini's privacy terms."
                )
            },
            confirmButton = {
                Button(onClick = {
                    geminiReviewRequested = false
                    GeminiWebHandoff.copyInstructions(context)
                    val opened = GeminiWebHandoff.openGemini(context)
                    android.widget.Toast.makeText(
                        context,
                        if (opened) "Prompt copied. Paste in Gemini and attach approved audio from Drive manually."
                        else "Prompt copied. Open gemini.google.com manually.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }) { Text("Copy prompt & open") }
            },
            dismissButton = {
                OutlinedButton(onClick = { geminiReviewRequested = false }) { Text("Cancel") }
            }
        )
    }

    if (showRecorder) RecordingBottomSheet { showRecorder = false }
}

@Composable
private fun StatsDashboard(recordings: List<RecordingEntity>) {
    val waiting = recordings.count { it.driveAudioFileId.isNullOrBlank() && it.status != ProcessingStatus.FAILED }
    val uploaded = recordings.count { !it.driveAudioFileId.isNullOrBlank() }
    val failed = recordings.count { it.status == ProcessingStatus.FAILED }
    val draftCount = recordings.count { !it.transcriptPath.isNullOrBlank() }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Waiting", waiting, StatusPending, Modifier.weight(1f))
            Stat("Provider saved", uploaded, StatusUploaded, Modifier.weight(1f))
            Stat("Failed", failed, StatusFailed, Modifier.weight(1f))
        }
        if (draftCount > 0) {
            Text(
                "Offline ASR drafts: $draftCount (not clinically verified)",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (uploaded > 0) {
            Text(
                "Provider saved means audio bytes were read back on this phone. It does not confirm Google cloud upload or transcription.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Stat(label: String, count: Int, color: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .12f))) {
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FilterRow(selected: ProcessingStatus?, onSelect: (ProcessingStatus?) -> Unit) {
    val filters = listOf(
        null to "All",
        ProcessingStatus.WAITING_FOR_SYNC to "Waiting",
        ProcessingStatus.UPLOADED to "Provider saved",
        ProcessingStatus.FAILED to "Failed"
    )
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filters) { (status, label) ->
            FilterChip(selected = selected == status, onClick = { onSelect(status) }, label = { Text(label) })
        }
    }
}

/**
 * User-initiated, machine-readable reconciliation manifest. No audio bytes,
 * Android provider account URI, patient metadata, or cloud credentials are sent.
 * The receiving user can compare stable archive names/sizes against Drive.
 */
private fun shareSyncManifest(context: Context, recordings: List<RecordingEntity>) {
    val rows = JSONArray()
    recordings.sortedBy { it.recordedAt }.forEach { item ->
        rows.put(JSONObject().apply {
            put("recording_id", item.id)
            put("archive_filename", ArchiveFilePolicy.stableName(
                item.id, item.recordedAt, item.source.name, item.fileName
            ))
            put("recorded_at_unix_ms", item.recordedAt)
            put("source_size_bytes", item.fileSizeBytes)
            put("source", item.source.name)
            put("provider_saved", !item.driveAudioFileId.isNullOrBlank())
            put("sync_status", item.status.name)
        })
    }
    val payload = JSONObject().apply {
        put("schema", "voicegrowth.sync-manifest.v1")
        put("generated_at_unix_ms", System.currentTimeMillis())
        put("provider_saved_is_cloud_verified", false)
        put("recordings", rows)
    }
    val file = File(context.cacheDir, "voicegrowth_sync_manifest.json")
    file.writeText(payload.toString(2), Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share VoiceGrowth sync manifest"))
}

/**
 * User-initiated Android share sheet. No automatic transmission to ChatGPT or other apps.
 * FileProvider grants scoped read access for VoiceGrowth-owned recordings.
 */
private fun shareRecording(context: Context, recording: RecordingEntity) {
    val originalUri = Uri.parse(recording.uriString)
    val safeUri = when (originalUri.scheme) {
        "file" -> FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", File(recording.filePath)
        )
        "content" -> originalUri
        else -> throw IllegalArgumentException("Unsupported recording URI")
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "audio/*"
        putExtra(Intent.EXTRA_STREAM, safeUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share approved audio recording"))
}

/**
 * Read-only user-triggered export. Never transmits a transcript automatically
 * to Gemini or another consumer provider.
 */
private fun shareTranscriptDraft(context: Context, recording: RecordingEntity) {
    val draft = File(requireNotNull(recording.transcriptPath))
    val privateRoot = File(context.filesDir, "transcripts").canonicalPath + File.separator
    require(draft.isFile && draft.canonicalPath.startsWith(privateRoot)) {
        "Transcript is outside VoiceGrowth private storage"
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", draft)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share unverified ASR draft (approved recipients only)"))
}

@Composable
private fun RecordingCard(
    recording: RecordingEntity,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onShareDraft: () -> Unit
) {
    val date = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.US) }.format(Date(recording.recordedAt))
    val status = statusPresentation(recording)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(sourceIcon(recording.source), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(recording.fileName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusChip(status.first, status.second)
            }
            Spacer(Modifier.height(8.dp))
            Text("$date • ${formatDuration(recording.durationSeconds)}", style = MaterialTheme.typography.bodySmall)
            if (!recording.transcriptPath.isNullOrBlank()) {
                Text("ASR draft saved locally — clinician review required",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            recording.errorMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (recording.driveAudioFileId.isNullOrBlank()) {
                    IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "Retry upload") }
                }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share selected audio") }
                if (!recording.transcriptPath.isNullOrBlank()) {
                    OutlinedButton(onClick = onShareDraft) { Text("Share ASR draft") }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove from list") }
            }
        }
    }
}

private fun sourceIcon(source: RecordingSource) = when (source) {
    RecordingSource.CALL_RECORDING -> Icons.Default.Phone
    RecordingSource.MANUAL_DISCUSSION -> Icons.Default.Group
    RecordingSource.VOICE_REFLECTION -> Icons.Default.RecordVoiceOver
    RecordingSource.IMPORTED_AUDIO -> Icons.Default.Audiotrack
}

private fun statusPresentation(recording: RecordingEntity): Pair<String, Color> = when {
    !recording.driveAudioFileId.isNullOrBlank() -> "Provider saved" to StatusUploaded
    recording.status == ProcessingStatus.FAILED -> "Upload failed" to StatusFailed
    else -> "Waiting sync" to StatusPending
}

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val minutes = safe / 60
    val remaining = safe % 60
    return "%d:%02d".format(Locale.US, minutes, remaining)
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(color = color.copy(alpha = .14f), shape = RoundedCornerShape(14.dp)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
