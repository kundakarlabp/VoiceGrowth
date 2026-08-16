package com.voicegrowth.app.ui.screens.recording

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.voicegrowth.app.data.model.RecordingSource
import com.voicegrowth.app.service.AudioRecordingService
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingBottomSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableLongStateOf(0L) }
    var source by remember { mutableStateOf(RecordingSource.MANUAL_DISCUSSION) }
    var permissionDenied by remember { mutableStateOf(false) }

    fun start() {
        val intent = Intent(context, AudioRecordingService::class.java).apply {
            action = AudioRecordingService.ACTION_START_RECORDING
            putExtra(AudioRecordingService.EXTRA_SOURCE, source.name)
        }
        ContextCompat.startForegroundService(context, intent)
        seconds = 0
        recording = true
        permissionDenied = false
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else permissionDenied = true
    }

    LaunchedEffect(recording) {
        while (recording) {
            delay(1_000)
            seconds++
        }
    }

    fun stop() {
        context.startService(Intent(context, AudioRecordingService::class.java).apply {
            action = AudioRecordingService.ACTION_STOP_RECORDING
        })
        recording = false
    }

    ModalBottomSheet(
        onDismissRequest = { if (recording) stop(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(if (recording) "Recording in progress" else "New audio capture", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            if (!recording) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = source == RecordingSource.MANUAL_DISCUSSION,
                        onClick = { source = RecordingSource.MANUAL_DISCUSSION },
                        label = { Text("Bedside / Consult") }
                    )
                    FilterChip(
                        selected = source == RecordingSource.VOICE_REFLECTION,
                        onClick = { source = RecordingSource.VOICE_REFLECTION },
                        label = { Text("Reflection") }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(String.format("%02d:%02d", seconds / 60, seconds % 60), fontSize = 48.sp)
            if (permissionDenied) {
                Text("Microphone permission is required for manual recording.", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            IconButton(
                onClick = {
                    if (recording) {
                        stop(); onDismiss()
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        start()
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.size(80.dp).background(
                    if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    CircleShape
                )
            ) {
                Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
