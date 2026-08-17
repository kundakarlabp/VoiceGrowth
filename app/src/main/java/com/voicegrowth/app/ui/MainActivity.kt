package com.voicegrowth.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.service.RecordingMonitorService
import com.voicegrowth.app.ui.screens.home.HomeScreen
import com.voicegrowth.app.ui.screens.home.HomeViewModel
import com.voicegrowth.app.ui.screens.settings.SettingsScreen
import com.voicegrowth.app.ui.screens.settings.SettingsViewModel
import com.voicegrowth.app.ui.theme.VoiceGrowthTheme

class MainActivity : ComponentActivity() {
    private val homeViewModel: HomeViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startMonitorService()
        if (hasRecordAudioPermission()) {
            (application as VoiceGrowthApplication).enqueueAudioProcessing()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val required = buildList {
            // Android SpeechRecognizer requires RECORD_AUDIO even when a pre-opened audio source is supplied.
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(required.toTypedArray())

        setContent {
            VoiceGrowthTheme {
                var screen by remember { mutableStateOf("home") }
                when (screen) {
                    "settings" -> SettingsScreen(settingsViewModel) { screen = "home" }
                    else -> HomeScreen(homeViewModel) { screen = "settings" }
                }
            }
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startMonitorService() {
        val intent = Intent(this, RecordingMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }
}
