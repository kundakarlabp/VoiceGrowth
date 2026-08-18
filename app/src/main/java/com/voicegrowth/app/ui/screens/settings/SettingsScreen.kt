package com.voicegrowth.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicegrowth.app.service.CaptureNotificationManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onNavigateBack: () -> Unit) {
    val settings by viewModel.settingsState.collectAsState()
    val aiImporting by viewModel.aiImporting.collectAsState()
    val aiMessage by viewModel.aiMessage.collectAsState()
    val aiProgress by viewModel.aiProgress.collectAsState()
    val folderStatus by viewModel.folderStatus.collectAsState()
    val drive by viewModel.driveUiState.collectAsState()
    val driveResolution by viewModel.driveResolution.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) viewModel.configureRecordingFolder(uri)
    }

    val aiModelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importAiModel(uri)
    }

    val driveAuthorization = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.completeDriveAuthorization(result.data)
    }

    LaunchedEffect(driveResolution) {
        driveResolution?.let { pending ->
            driveAuthorization.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            viewModel.consumeDriveResolution()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings & automation", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle("Recording source")
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Call recording folder", fontWeight = FontWeight.SemiBold)
                    Text(settings.selectedFolderDisplayName ?: "Not selected", style = MaterialTheme.typography.bodySmall)
                    folderStatus?.let { status ->
                        Text(
                            status.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.accessible && status.persistedReadPermission) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        if (status.accessible) {
                            Text(
                                "Visible audio: ${status.audioFileCount} · folders checked: ${status.visitedDirectoryCount}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            folderPicker.launch(settings.selectedFolderUri?.let(Uri::parse))
                        }) {
                            Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Choose folder")
                        }
                        if (settings.selectedFolderUri != null) {
                            OutlinedButton(onClick = viewModel::scanSelectedFolderNow) { Text("Test & scan") }
                        }
                    }
                    Text(
                        "Choose the highest iQOO/Funtouch folder that contains call recordings. VoiceGrowth now checks nested subfolders and verifies that Android retained read access.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Lock-screen & notification controls", fontWeight = FontWeight.SemiBold)
                    val notificationReady = CaptureNotificationManager.canPostNotifications(context)
                    Text(
                        if (notificationReady) {
                            "Notification access is available. VoiceGrowth can keep Record / Scan controls in the notification shade and lock screen."
                        } else {
                            "VoiceGrowth notifications are blocked. Persistent Record / Stop / Scan controls cannot appear until notifications are enabled."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (notificationReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        }) { Text("Notification settings") }
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            )
                        }) { Text("App / battery settings") }
                    }
                    Text(
                        "On iQOO/Funtouch, also allow background activity/auto-start and avoid aggressive battery restriction if the OS removes VoiceGrowth controls.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            SectionTitle("Automation & processing")
            Toggle("Automatic processing", "Periodic WorkManager scans detect completed recordings without keeping a permanent data-sync foreground service alive.", settings.autoProcessing, viewModel::setAutoProcessing)
            Toggle("Wi-Fi only sync", "Drive uploads require an unmetered network.", settings.wifiOnly, viewModel::setWifiOnly)
            Toggle("Only process recordings >30s", "Skip brief calls and accidental recordings.", settings.onlyProcessOver30Sec, viewModel::setOnlyProcessOver30Sec)

            Text("Transcription language", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("auto" to "Auto", "english" to "English", "telugu" to "Telugu", "hindi" to "Hindi").forEach { (value, label) ->
                    FilterChip(
                        selected = settings.transcriptionLanguage == value,
                        onClick = { viewModel.setTranscriptionLanguage(value) },
                        label = { Text(label) }
                    )
                }
            }
            Text(
                "Speech-to-text uses Android's on-device speech recognizer. LiteRT-LM is used after ASR for private structuring, not as the primary long-audio transcriber.",
                style = MaterialTheme.typography.bodySmall
            )

            SectionTitle("On-device AI")
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Gemma / LiteRT-LM", fontWeight = FontWeight.SemiBold)
                    Text(
                        settings.aiModelDisplayName?.let { "Imported model: $it" }
                            ?: "No model imported into VoiceGrowth",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Recommended starting model: Gemma 3 1B IT INT4 LiteRT-LM (about 557 MiB). Downloading may require signing in to Hugging Face and accepting the Gemma license once.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { uriHandler.openUri(RECOMMENDED_MODEL_PAGE) }) {
                            Text("Get Gemma 3 1B")
                        }
                        Button(
                            enabled = !aiImporting,
                            onClick = { aiModelPicker.launch(arrayOf("application/octet-stream", "*/*")) }
                        ) {
                            Text(if (aiImporting) "Importing…" else if (settings.aiModelPath == null) "Import downloaded model" else "Replace model")
                        }
                    }
                    aiProgress?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    aiMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("Model import failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    if (settings.aiModelPath != null) {
                        OutlinedButton(enabled = !aiImporting, onClick = viewModel::removeAiModel) { Text("Remove imported model") }
                    }
                    Text(
                        "The picker must select the actual .litertlm file from Downloads/Files. VoiceGrowth checks free space and shows copy progress; a failed replacement keeps the previous working model.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Toggle(
                        "Enable on-device AI synthesis",
                        "Generate a title, summary, stated decisions/actions, questions, learning points and follow-up.",
                        settings.aiEnabled,
                        viewModel::setAiEnabled
                    )
                    Toggle(
                        "Daily AI digest around 9 PM",
                        "Opt-in. Summarize today's processed VoiceGrowth transcripts locally when battery/storage are healthy.",
                        settings.dailyDigestEnabled,
                        viewModel::setDailyDigestEnabled
                    )
                    Text("Preferred AI backend", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.aiPreferredBackend == "gpu",
                            onClick = { viewModel.setAiPreferredBackend("gpu") },
                            label = { Text("GPU first") }
                        )
                        FilterChip(
                            selected = settings.aiPreferredBackend == "cpu",
                            onClick = { viewModel.setAiPreferredBackend("cpu") },
                            label = { Text("CPU only") }
                        )
                    }
                    Text("GPU first automatically falls back to CPU if LiteRT-LM cannot initialize the GPU backend.", style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionTitle("Privacy & cloud")
            Toggle("Clinical privacy mode", "Pattern-based redaction before AI processing and transcript upload; manual review is still recommended.", settings.clinicalPrivacyMode, viewModel::setClinicalPrivacyMode)
            Toggle("Upload transcript (.md)", "Sync the locally processed Markdown transcript and optional AI synthesis to Drive.", settings.uploadTranscript, viewModel::setUploadTranscript)
            Toggle("Upload original audio", "Original audio is NOT de-identified and may contain patient identifiers. Keep this off unless specifically required.", settings.uploadAudio, viewModel::setUploadAudio)

            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Google Drive", fontWeight = FontWeight.SemiBold)
                    Text("Destination: ${settings.driveFolderHierarchy}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            drive.checking -> "Checking authorization…"
                            drive.authorized -> "Connected: ${drive.accountLabel ?: "Google account"}"
                            else -> "Not connected"
                        }
                    )
                    drive.message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (drive.authorized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    Text("Installed OAuth identity", fontWeight = FontWeight.SemiBold)
                    Text("Package: ${drive.packageName.ifBlank { context.packageName }}", style = MaterialTheme.typography.bodySmall)
                    Text("SHA-1: ${drive.signingSha1.ifBlank { "Checking…" }}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "If you see OAuth client mismatch / status 10, these exact package + SHA-1 values must exist in the Android OAuth client in the same Google Cloud project where Drive API is enabled.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !drive.checking,
                            onClick = { viewModel.connectDrive(forceAccountPicker = true) }
                        ) {
                            Text(if (drive.authorized) "Switch account" else "Connect Google Drive")
                        }
                        OutlinedButton(enabled = !drive.checking, onClick = viewModel::refreshDriveAuthorization) {
                            Text("Recheck")
                        }
                    }
                    if (drive.authorized || settings.googleAccountEmail != null) {
                        OutlinedButton(enabled = !drive.checking, onClick = viewModel::disconnectDrive) { Text("Disconnect") }
                    }
                }
            }

            SectionTitle("Storage & retention")
            Toggle(
                "Delete original source audio automatically",
                "OFF by default. Enabling this permanently deletes the original recording after the retention period and after any required cloud uploads are complete.",
                settings.deleteSourceAudioEnabled,
                viewModel::setDeleteSourceAudioEnabled
            )
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Source-audio retention: ${settings.deleteLocalAudioDays} day(s)", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (settings.deleteSourceAudioEnabled) "Deletion occurs only after processing is complete and all currently required cloud copies exist."
                        else "Automatic source-audio deletion is disabled.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = settings.deleteLocalAudioDays.toFloat(),
                        onValueChange = { viewModel.setDeleteLocalAudioDays(it.toInt()) },
                        valueRange = 1f..30f,
                        steps = 28,
                        enabled = settings.deleteSourceAudioEnabled
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.primary
)

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp)); Switch(checked = checked, onCheckedChange = onChange)
    }
}

private const val RECOMMENDED_MODEL_PAGE = "https://huggingface.co/litert-community/Gemma3-1B-IT"
