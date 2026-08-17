package com.voicegrowth.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.voicegrowth.app.sync.GoogleAuthManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onNavigateBack: () -> Unit) {
    val settings by viewModel.settingsState.collectAsState()
    val aiImporting by viewModel.aiImporting.collectAsState()
    val aiMessage by viewModel.aiMessage.collectAsState()
    val context = LocalContext.current
    var driveMessage by remember { mutableStateOf<String?>(null) }
    val authorizedAccount = GoogleAuthManager.getSignedInAccount(context)

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
            viewModel.setSelectedFolder(uri.toString(), uri.lastPathSegment ?: "Call recordings")
        }
    }

    val aiModelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importAiModel(uri)
    }

    val googleSignIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        runCatching { GoogleSignIn.getSignedInAccountFromIntent(result.data).result }
            .onSuccess { account ->
                val authorized = GoogleAuthManager.getSignedInAccount(context)
                if (authorized != null) {
                    viewModel.setGoogleAccount(authorized.email ?: account.email)
                    driveMessage = "Google Drive connected"
                } else {
                    viewModel.setGoogleAccount(null)
                    driveMessage = "Google sign-in completed, but Drive permission was not granted"
                }
            }
            .onFailure { error ->
                val stillAuthorized = GoogleAuthManager.getSignedInAccount(context)
                viewModel.setGoogleAccount(stillAuthorized?.email)
                driveMessage = error.message?.take(160) ?: "Google Drive sign-in was cancelled or failed"
            }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings & automation", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle("Recording source")
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Call recording folder", fontWeight = FontWeight.SemiBold)
                    Text(settings.selectedFolderDisplayName ?: "Not selected", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Choose folder")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "For one-tap voice capture, add the VoiceGrowth capture tile from Android Quick Settings. Audio files can also be shared to VoiceGrowth from Files, WhatsApp or other apps.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            SectionTitle("Automation & processing")
            Toggle("Automatic processing", "Detect and transcribe newly completed recordings.", settings.autoProcessing, viewModel::setAutoProcessing)
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
                "Speech-to-text uses Android's on-device speech recognizer. LiteRT-LM is used after ASR for optional private AI structuring, not as the primary long-audio transcriber.",
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
                        "VoiceGrowth sends only forcibly de-identified text to the library-query and daily-digest AI paths. AI failure never blocks transcript creation.",
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
                    Text(
                        "GPU first automatically falls back to CPU if LiteRT-LM cannot initialize the GPU backend.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !aiImporting,
                            onClick = { aiModelPicker.launch(arrayOf("*/*")) }
                        ) {
                            Text(if (aiImporting) "Importing…" else if (settings.aiModelPath == null) "Import .litertlm model" else "Replace model")
                        }
                        if (settings.aiModelPath != null) {
                            OutlinedButton(enabled = !aiImporting, onClick = viewModel::removeAiModel) { Text("Remove") }
                        }
                    }
                    Text(
                        "Android app isolation prevents VoiceGrowth from directly reading AI Edge Gallery's private model file. Select an accessible .litertlm file; VoiceGrowth copies it once into its own private storage.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    aiMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            SectionTitle("Privacy & cloud")
            Toggle("Clinical privacy mode", "Pattern-based redaction before AI processing and transcript upload; manual review is still recommended.", settings.clinicalPrivacyMode, viewModel::setClinicalPrivacyMode)
            Toggle("Upload transcript (.md)", "Sync the locally processed Markdown transcript and optional AI synthesis to Drive.", settings.uploadTranscript, viewModel::setUploadTranscript)
            Toggle("Upload original audio", "Original audio is NOT de-identified and may contain patient identifiers. Keep this off unless specifically required.", settings.uploadAudio, viewModel::setUploadAudio)

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

            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Google Drive", fontWeight = FontWeight.SemiBold)
                    Text("Destination: ${settings.driveFolderHierarchy}", style = MaterialTheme.typography.bodySmall)
                    Text(authorizedAccount?.email?.let { "Connected: $it" } ?: "Not connected")
                    if (settings.googleAccountEmail != null && authorizedAccount == null) {
                        Text(
                            "The previously saved account is no longer authorized. Reconnect before sync.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    driveMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            driveMessage = null
                            googleSignIn.launch(GoogleAuthManager.getSignInClient(context).signInIntent)
                        }) {
                            Text(if (authorizedAccount == null) "Connect Google Drive" else "Switch account")
                        }
                        if (authorizedAccount != null || settings.googleAccountEmail != null) {
                            OutlinedButton(onClick = {
                                GoogleAuthManager.getSignInClient(context).signOut().addOnCompleteListener {
                                    viewModel.setGoogleAccount(null)
                                    driveMessage = "Google Drive disconnected"
                                }
                            }) { Text("Disconnect") }
                        }
                    }
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
