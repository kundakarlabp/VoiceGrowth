package com.voicegrowth.medscribe

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: ScribeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingAudio(intent)
        setContent {
            MaterialTheme {
                MedScribeScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingAudio(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingAudio(incoming: Intent?) {
        when (incoming?.action) {
            Intent.ACTION_SEND -> {
                val uri = incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                if (uri != null) viewModel.importAudio(listOf(uri))
                incoming.action = null
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList().orEmpty()
                if (uris.isNotEmpty()) viewModel.importAudio(uris)
                incoming.action = null
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MedScribeScreen(viewModel: ScribeViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var selectedItem by remember { mutableStateOf<ScribeItem?>(null) }
    var enrollItem by remember { mutableStateOf<ScribeItem?>(null) }
    var showSetup by remember { mutableStateOf(true) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val micGranted = result[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (micGranted) viewModel.startRecording()
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importAudio(uris)
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "Google Drive folder"
            viewModel.setDriveFolder(uri, name)
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MedScribe Local", fontWeight = FontWeight.Bold)
                        Text(
                            if (state.modelInstalled) "Offline Whisper ready" else "Install speech model in Setup",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSetup = !showSetup }) {
                        Icon(Icons.Default.Settings, contentDescription = "Setup")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                CaptureCard(
                    state = state,
                    onRecord = {
                        val missing = buildList {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                add(Manifest.permission.RECORD_AUDIO)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (missing.isEmpty()) viewModel.startRecording()
                        else permissions.launch(missing.toTypedArray())
                    },
                    onStop = viewModel::stopRecording,
                    onImport = { audioPicker.launch(arrayOf("audio/*")) }
                )
            }

            if (showSetup) {
                item {
                    SetupCard(
                        state = state,
                        viewModel = viewModel,
                        onPickDrive = { folderPicker.launch(null) }
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearch,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    label = { Text("Filter recordings") },
                    placeholder = { Text("consult, call, discussion…") },
                    singleLine = true
                )
            }

            val filtered = remember(state.items, state.searchQuery) {
                val q = state.searchQuery.trim().lowercase()
                if (q.isBlank()) state.items else state.items.filter {
                    it.title.lowercase().contains(q) || it.errorMessage.orEmpty().lowercase().contains(q)
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().height(180.dp).padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Audiotrack, null, Modifier.size(46.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No recordings yet")
                            Text("Record a consult/discussion or import existing audio.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                items(filtered, key = { it.id }) { item ->
                    RecordingCard(
                        item = item,
                        driveLinked = state.settings.driveFolderUri != null,
                        onOpen = { selectedItem = item },
                        onProcess = { viewModel.process(item.id) },
                        onSync = { viewModel.syncNow(item.id) },
                        onEnroll = { enrollItem = item },
                        onDelete = { viewModel.delete(item.id) }
                    )
                }
            }
        }
    }

    state.busyMessage?.let { BusyDialog(it, state.modelProgress) }
    selectedItem?.let { item ->
        TranscriptEditorDialog(
            item = item,
            initialText = viewModel.transcript(item.id),
            onDismiss = { selectedItem = null },
            onSave = { viewModel.saveTranscript(item.id, it) },
            onRename = { number, name -> viewModel.renameSpeaker(item.id, number, name) },
            onShare = { text -> shareText(context, "MedScribe transcript", text) },
            onCaseLearning = { text -> shareText(context, "ID case learning", caseLearningPrompt(text)) }
        )
    }
    enrollItem?.let { item ->
        VoiceEnrollDialog(
            item = item,
            onDismiss = { enrollItem = null },
            onEnroll = { name ->
                viewModel.enrollVoice(item.id, name)
                enrollItem = null
            }
        )
    }
}

@Composable
private fun CaptureCard(
    state: UiState,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onImport: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (state.isRecording) "Recording in progress" else "Capture a clinical discussion",
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp
            )
            Text(
                if (state.isRecording) "Screen-off recording continues through the foreground service."
                else "Long conversation, bedside consult, teaching discussion, speakerphone call or voice reflection.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.isRecording) {
                    Button(onClick = onStop, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Stop")
                    }
                } else {
                    Button(onClick = onRecord, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Mic, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Record")
                    }
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Import audio")
                }
            }
        }
    }
}

@Composable
private fun SetupCard(state: UiState, viewModel: ScribeViewModel, onPickDrive: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Setup", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            Text("Speech model", fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ModelManager.whisperChoices) { choice ->
                    FilterChip(
                        selected = state.settings.whisperModel == choice.first,
                        onClick = { viewModel.updateWhisperModel(choice.first) },
                        label = { Text(choice.first.replaceFirstChar(Char::uppercase)) }
                    )
                }
            }
            Text(
                ModelManager.whisperChoices.firstOrNull { it.first == state.settings.whisperModel }?.second.orEmpty(),
                style = MaterialTheme.typography.bodySmall
            )
            if (!state.modelInstalled) {
                Button(onClick = viewModel::installWhisper) { Text("Install selected Whisper model") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudDone, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Selected Whisper model installed")
                }
            }

            Divider()
            Text("Language", fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("auto" to "Auto", "en" to "English", "te" to "Telugu", "hi" to "Hindi")) { option ->
                    FilterChip(
                        selected = state.settings.language == option.first,
                        onClick = { viewModel.updateLanguage(option.first) },
                        label = { Text(option.second) }
                    )
                }
            }

            Divider()
            SettingSwitch(
                title = "Separate speakers",
                detail = "Offline diarization identifies who spoke when for conversations up to 45 minutes.",
                checked = state.settings.diarizationEnabled,
                onChecked = viewModel::updateDiarization
            )
            if (!state.diarizationInstalled) {
                Button(onClick = viewModel::installDiarization) { Text("Install speaker models (~46 MB)") }
            }
            SettingSwitch(
                title = "Recognize enrolled voices",
                detail = "Matches diarized speakers to voice profiles stored only inside this app.",
                checked = state.settings.voiceRecognitionEnabled,
                onChecked = viewModel::updateVoiceRecognition
            )
            if (state.voiceProfiles.isEmpty()) {
                Text(
                    "To recognize your voice: make a clear 10–30 second recording with only you speaking, then tap Enroll voice on that recording.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text("Enrolled voices", fontWeight = FontWeight.SemiBold)
                state.voiceProfiles.forEach { profile ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.name, Modifier.weight(1f))
                        TextButton(onClick = { viewModel.deleteVoiceProfile(profile.id) }) { Text("Remove") }
                    }
                }
                Text(
                    "Voice matching is probabilistic; verify speaker labels in important clinical transcripts.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Divider()
            Text("Google Drive", fontWeight = FontWeight.SemiBold)
            Text(
                state.settings.driveFolderName ?: "No folder linked. Choose a Google Drive folder from Android Files; access is limited to that folder tree.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = onPickDrive) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(6.dp))
                Text(if (state.settings.driveFolderUri == null) "Select Drive folder" else "Change Drive folder")
            }
            SettingSwitch(
                title = "Auto-sync transcripts",
                detail = "Writes Markdown under MedScribe/Transcripts/YYYY/MM-MMM after transcription.",
                checked = state.settings.autoSync,
                onChecked = viewModel::updateAutoSync
            )
            SettingSwitch(
                title = "Upload original audio",
                detail = "Off by default. Raw clinical audio may contain identifiers.",
                checked = state.settings.uploadAudio,
                onChecked = viewModel::updateUploadAudio
            )
        }
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun RecordingCard(
    item: ScribeItem,
    driveLinked: Boolean,
    onOpen: () -> Unit,
    onProcess: () -> Unit,
    onSync: () -> Unit,
    onEnroll: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(item.recordedAt))} · ${formatDuration(item.durationSeconds)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(statusLabel(item.status), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp)
                }
            }
            if (item.speakerCount > 0) {
                Text("${item.speakerCount} speaker${if (item.speakerCount == 1) "" else "s"} separated", style = MaterialTheme.typography.bodySmall)
            }
            item.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (item.status == ItemStatus.READY) {
                    TextButton(onClick = onOpen) { Text("Open") }
                    if (driveLinked) {
                        TextButton(onClick = onSync) {
                            Icon(if (item.driveSyncedAt != null) Icons.Default.CloudDone else Icons.Default.CloudUpload, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Sync")
                        }
                    }
                    TextButton(onClick = onEnroll) {
                        Icon(Icons.Default.PersonAdd, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Enroll voice")
                    }
                }
                if (item.status == ItemStatus.RECORDED || item.status == ItemStatus.FAILED || item.status == ItemStatus.NEEDS_MODEL) {
                    TextButton(onClick = onProcess) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Transcribe")
                    }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
}

@Composable
private fun TranscriptEditorDialog(
    item: ScribeItem,
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRename: (Int, String) -> Unit,
    onShare: (String) -> Unit,
    onCaseLearning: (String) -> Unit
) {
    var text by remember(item.id, initialText) { mutableStateOf(initialText) }
    val renameValues = remember(item.id, item.speakerCount) {
        MutableList(item.speakerCount) { "" }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 760.dp),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 4.dp
        ) {
            LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(item.title, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                item {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 460.dp),
                        label = { Text("Editable transcript") }
                    )
                }
                if (item.speakerCount > 0) {
                    item { Text("Manual speaker naming", fontWeight = FontWeight.SemiBold) }
                    items((1..item.speakerCount).toList()) { number ->
                        var value by remember(item.id, number) { mutableStateOf(renameValues[number - 1]) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { value = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("Speaker $number name") },
                                singleLine = true
                            )
                            TextButton(onClick = {
                                onRename(number, value)
                                text = text.replace(
                                    Regex("\\*\\*Speaker\\s+$number\\*\\*", RegexOption.IGNORE_CASE),
                                    "**${value.trim()}**"
                                )
                            }) { Text("Apply") }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { onSave(text) }, modifier = Modifier.weight(1f)) { Text("Save") }
                        OutlinedButton(onClick = { onShare(text) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Share, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Share")
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { onCaseLearning(text) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.School, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Review / learn with ChatGPT")
                    }
                }
                item {
                    Text(
                        "The AI handoff preserves this source transcript and asks the receiving AI to flag uncertain terminology rather than silently rewriting clinical facts.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") } }
            }
        }
    }
}

@Composable
private fun VoiceEnrollDialog(item: ScribeItem, onDismiss: () -> Unit, onEnroll: (String) -> Unit) {
    var name by remember { mutableStateOf("Me") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enroll voice profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Use this only when the recording contains predominantly one person's clear speech. A dedicated 10–30 second voice sample is best.")
                Text("Source: ${item.title}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Speaker name") }, singleLine = true)
                Text("The stored template is an app-private voice embedding. You can remove it from Setup.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onEnroll(name) }, enabled = name.isNotBlank()) { Text("Enroll") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun BusyDialog(message: String, progress: ModelProgress?) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("MedScribe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(message)
                }
                if (progress != null) {
                    LinearProgressIndicator(progress = progress.percent / 100f, modifier = Modifier.fillMaxWidth())
                    Text("${progress.currentFile} · ${progress.percent}%", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    )
}

private fun statusLabel(status: ItemStatus): String = when (status) {
    ItemStatus.RECORDED -> "Recorded"
    ItemStatus.PROCESSING -> "Transcribing"
    ItemStatus.READY -> "Ready"
    ItemStatus.NEEDS_MODEL -> "Needs model"
    ItemStatus.FAILED -> "Failed"
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
}

private fun shareText(context: Context, subject: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun caseLearningPrompt(transcript: String): String = """
You are reviewing a machine-generated transcript of an infectious-diseases clinical discussion for education and professional development.

Rules:
1. Treat the transcript as fallible source material. Do not silently invent or repair clinical facts.
2. First list words, drug names, organisms, laboratory values or phrases that look mistranscribed or ambiguous. Where useful, use current web/evidence sources to propose likely interpretations and label them as suggestions.
3. Then provide a concise case reconstruction, differential/decision reasoning, antimicrobial-stewardship points, diagnostic opportunities, safety issues and key learning points.
4. Separate what was explicitly said from your inference.
5. For practice-changing claims, verify against current authoritative guidelines or primary literature and cite links.
6. Do not expose or amplify patient identifiers; ignore identifying details that are not needed for learning.

SOURCE TRANSCRIPT
-----------------
$transcript
""".trimIndent()
