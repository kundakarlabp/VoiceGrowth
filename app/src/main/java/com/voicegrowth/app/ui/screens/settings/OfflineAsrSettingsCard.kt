package com.voicegrowth.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun OfflineAsrSettingsCard(asrViewModel: OfflineAsrViewModel = viewModel()) {
    val state by asrViewModel.state.collectAsState()

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Reliable offline file transcription", fontWeight = FontWeight.SemiBold)
            Text(
                if (state.installed) {
                    "Whisper tiny multilingual INT8 is installed. VoiceGrowth uses it first for recorded calls, discussions and imported audio; Android SpeechRecognizer is fallback only."
                } else {
                    "Recommended for this phone. Android's built-in recognizer can reject prerecorded audio with ‘no speech match’. Install the dedicated local Whisper model once to transcribe files directly without the microphone."
                },
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.message.contains("failed", ignoreCase = true)) {
                    MaterialTheme.colorScheme.error
                } else MaterialTheme.colorScheme.primary
            )

            if (state.installing) {
                LinearProgressIndicator(
                    progress = { state.progressPercent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                state.progressText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.installed) {
                    Button(enabled = !state.installing, onClick = asrViewModel::install) {
                        Text(if (state.installing) "Installing…" else "Install offline Whisper (~104 MB)")
                    }
                } else {
                    Button(enabled = !state.installing, onClick = asrViewModel::refresh) {
                        Text("Check model")
                    }
                    OutlinedButton(enabled = !state.installing, onClick = asrViewModel::remove) {
                        Text("Remove")
                    }
                }
            }

            Text(
                "This Whisper model performs speech-to-text only. Your imported Gemma model remains a separate post-transcription AI layer for summaries, decisions, action items and daily digest.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
