package com.voicegrowth.app.ui.screens.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.model.RecordingSource
import com.voicegrowth.app.engine.format.TranscriptMarkdownBuilder
import com.voicegrowth.app.ui.screens.recording.RecordingBottomSheet
import com.voicegrowth.app.ui.theme.StatusFailed
import com.voicegrowth.app.ui.theme.StatusPending
import com.voicegrowth.app.ui.theme.StatusSkipped
import com.voicegrowth.app.ui.theme.StatusTranscribing
import com.voicegrowth.app.ui.theme.StatusUploaded
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onNavigateToSettings: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showRecorder by remember { mutableStateOf(false) }
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VoiceGrowth", fontWeight = FontWeight.Bold)
                        Text(
                            state.settings.selectedFolderDisplayName ?: "Call folder not linked",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                actions = {
                    IconButton(onClick = viewModel::scanNow, enabled = !state.isScanning) { Icon(Icons.Default.Refresh, "Scan now") }
                    IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showRecorder = true },
                icon = { Icon(Icons.Default.Mic, null) },
                text = { Text("Record discussion") }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.settings.selectedFolderUri == null) {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Link iQOO call recordings", fontWeight = FontWeight.Bold)
                            Text("Select the OEM call-recording folder once. VoiceGrowth stores persistent SAF access.", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { folderPicker.launch(null) }) { Text("Select") }
                    }
                }
            }

            StatsDashboard(state.allRecordings)
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
                            recording,
                            onRetry = { viewModel.retryRecording(recording.id) },
                            onDelete = { viewModel.deleteRecording(recording.id) },
                            onPreview = { viewModel.setPreviewRecording(recording) }
                        )
                    }
                }
            }
        }
    }

    state.previewRecording?.let { TranscriptDialog(it) { viewModel.setPreviewRecording(null) } }
    if (showRecorder) RecordingBottomSheet { showRecorder = false }
}

@Composable
private fun StatsDashboard(recordings: List<RecordingEntity>) {
    val pending = recordings.count { it.status == ProcessingStatus.PENDING || it.status == ProcessingStatus.TRANSCRIBING }
    val local = recordings.count { it.status == ProcessingStatus.LOCAL_READY || it.status == ProcessingStatus.WAITING_FOR_SYNC }
    val uploaded = recordings.count { it.status == ProcessingStatus.UPLOADED }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("Queue", pending, StatusPending, Modifier.weight(1f))
        Stat("Local", local, StatusTranscribing, Modifier.weight(1f))
        Stat("Drive", uploaded, StatusUploaded, Modifier.weight(1f))
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
        ProcessingStatus.PENDING to "Pending",
        ProcessingStatus.TRANSCRIBING to "Transcribing",
        ProcessingStatus.WAITING_FOR_SYNC to "Waiting sync",
        ProcessingStatus.UPLOADED to "Uploaded",
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

@Composable
private fun RecordingCard(recording: RecordingEntity, onRetry: () -> Unit, onDelete: () -> Unit, onPreview: () -> Unit) {
    val date = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.US) }.format(Date(recording.recordedAt))
    val status = statusPresentation(recording.status)
    Card(
        Modifier.fillMaxWidth().clickable(enabled = recording.transcriptPath != null, onClick = onPreview),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (recording.source) {
                        RecordingSource.CALL_RECORDING -> Icons.Default.Phone
                        RecordingSource.MANUAL_DISCUSSION -> Icons.Default.Group
                        RecordingSource.VOICE_REFLECTION -> Icons.Default.RecordVoiceOver
                    },
                    null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(recording.fileName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusChip(status.first, status.second)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$date • ${TranscriptMarkdownBuilder.formatDuration(recording.durationSeconds)}", style = MaterialTheme.typography.bodySmall)
                    recording.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                }
                if (recording.status == ProcessingStatus.FAILED || recording.status == ProcessingStatus.WAITING_FOR_SYNC) {
                    IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "Retry") }
                }
                if (recording.transcriptPath != null) IconButton(onClick = onPreview) { Icon(Icons.Default.Description, "Transcript") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove from queue") }
            }
        }
    }
}

private fun statusPresentation(status: ProcessingStatus): Pair<String, Color> = when (status) {
    ProcessingStatus.PENDING -> "Pending" to StatusPending
    ProcessingStatus.TRANSCRIBING -> "Transcribing" to StatusTranscribing
    ProcessingStatus.LOCAL_READY -> "Local ready" to StatusUploaded
    ProcessingStatus.WAITING_FOR_SYNC -> "Waiting sync" to StatusPending
    ProcessingStatus.UPLOADED -> "Drive synced" to StatusUploaded
    ProcessingStatus.FAILED -> "Failed" to StatusFailed
    ProcessingStatus.SKIPPED_TOO_SHORT -> "Skipped <30s" to StatusSkipped
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(color = color.copy(alpha = .14f), shape = RoundedCornerShape(14.dp)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun TranscriptDialog(recording: RecordingEntity, onDismiss: () -> Unit) {
    val content = remember(recording.transcriptPath) {
        recording.transcriptPath?.let { runCatching { File(it).readText() }.getOrElse { "Transcript file unavailable." } }
            ?: "No transcript generated."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(recording.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = { LazyColumn(Modifier.heightIn(max = 480.dp)) { item { Text(content, style = MaterialTheme.typography.bodySmall) } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
