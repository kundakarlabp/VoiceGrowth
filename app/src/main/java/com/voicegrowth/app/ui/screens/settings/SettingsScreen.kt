package com.voicegrowth.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicegrowth.app.service.CaptureNotificationManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onNavigateBack: () -> Unit) {
    val settings by viewModel.settingsState.collectAsState()
    val folderStatus by viewModel.folderStatus.collectAsState()
    val driveTreeStatus by viewModel.driveTreeStatus.collectAsState()
    val modelMessage by viewModel.modelMessage.collectAsState()
    val context = LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) viewModel.configureRecordingFolder(uri)
    }
    val driveFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) viewModel.configureDriveTree(uri)
    }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importOfflineModel(uri)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("VoiceGrowth settings", fontWeight = FontWeight.Bold) },
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
            SectionTitle("Recording")
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Consult recording quality", fontWeight = FontWeight.SemiBold)
                    Text(
                        "VoiceGrowth records mono AAC-LC in .m4a at 48 kHz / 160 kbps using Android's speech-tuned microphone source. The original audio is preserved for cloud transcription.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "For quiet speakers, place the phone between speakers and avoid covering the microphone. Recording quality matters more than local AI processing.",
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
                            "Record / Stop controls can remain available from the notification shade and lock screen."
                        } else {
                            "Notifications are blocked. Enable them for reliable Record / Stop controls."
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
                        }) { Text("Notifications") }
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            )
                        }) { Text("App / battery") }
                    }
                }
            }

            SectionTitle("Google Drive")
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Original-audio destination", fontWeight = FontWeight.SemiBold)
                    Text(
                        "VoiceGrowth writes the original recording through Android Files/Google Drive. No local transcription or Gemma model is required.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (settings.driveTreeUri.isNullOrBlank()) {
                        Text("No Drive folder selected", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { driveFolderPicker.launch(null) }) {
                            Icon(Icons.Default.Folder, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Choose Google Drive folder")
                        }
                        Text(
                            "In Android Files, choose Google Drive → select a parent folder → Use this folder. VoiceGrowth creates VoiceGrowth/Audio/YYYY/MM-MMM.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        val status = driveTreeStatus
                        Text("Selected: ${settings.driveTreeDisplayName ?: status?.displayName ?: "Cloud folder"}")
                        status?.let {
                            Text(
                                it.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (it.usable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        Text("Destination: VoiceGrowth/Audio/YYYY/MM-MMM (or YYYY/MM-MMM when Audio is selected). Provider read-back checked; remote cloud confirmation is separate.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { driveFolderPicker.launch(Uri.parse(settings.driveTreeUri)) }) { Text("Change folder") }
                            OutlinedButton(onClick = { viewModel.refreshDriveTreeStatus(testWrite = true) }) { Text("Test & sync") }
                        }
                        OutlinedButton(onClick = viewModel::disconnectDriveTree) { Text("Disconnect") }
                    }
                }
            }

            Toggle(
                "Wi-Fi only uploads",
                "When enabled, Drive uploads wait for an unmetered network.",
                settings.wifiOnly,
                viewModel::setWifiOnly
            )

            SectionTitle("Optional phone recording import")
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OEM call-recording folder", fontWeight = FontWeight.SemiBold)
                    Text(settings.selectedFolderDisplayName ?: "Not selected", style = MaterialTheme.typography.bodySmall)
                    folderStatus?.let { status ->
                        Text(
                            status.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.accessible && status.persistedReadPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { folderPicker.launch(settings.selectedFolderUri?.let(Uri::parse)) }) {
                            Icon(Icons.Default.Folder, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Choose folder")
                        }
                        if (settings.selectedFolderUri != null) {
                            OutlinedButton(onClick = viewModel::scanSelectedFolderNow) { Text("Scan now") }
                        }
                    }
                }
            }
            Toggle(
                "Automatically scan linked call folder",
                "WorkManager periodically scans the explicitly selected native call-recording folder. It cannot record calls itself or bypass restricted phone storage.",
                settings.autoProcessing,
                viewModel::setAutoProcessing
            )

            SectionTitle("Offline transcription pilot")
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("On-device Whisper", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Optional and free; no paid API. First import a multilingual ggml Whisper model (.bin, typically 142 MB+). " +
                            "The app processes recordings up to 3 minutes while charging, then saves draft transcripts beside Audio in private Drive.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if (settings.aiModelPath.isNullOrBlank()) "Model: not installed"
                        else "Model: installed locally",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }) {
                        Text("Import offline model")
                    }
                    Toggle(
                        "Automatically transcribe short recordings",
                        "Pilot: disabled by default; review clinical terms, doses and CR numbers against audio.",
                        settings.aiEnabled,
                        viewModel::setOfflineTranscription
                    )
                    if (settings.aiEnabled) {
                        OutlinedButton(onClick = viewModel::runOfflineTranscriptionNow) {
                            Text("Process pending short recordings")
                        }
                    }
                    modelMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            SectionTitle("Local audio protection")
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Automatic deletion disabled", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Original audio is retained on this phone. An Android provider read-back is not proof that Google Drive completed cloud synchronization. Check from a second device before manually clearing local recordings.",
                        style = MaterialTheme.typography.bodySmall
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
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
