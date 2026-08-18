package com.voicegrowth.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import com.voicegrowth.app.service.CaptureNotificationManager
import com.voicegrowth.app.ui.screens.home.HomeScreen
import com.voicegrowth.app.ui.screens.home.HomeViewModel
import com.voicegrowth.app.ui.screens.settings.SettingsScreen
import com.voicegrowth.app.ui.screens.settings.SettingsViewModel
import com.voicegrowth.app.ui.theme.VoiceGrowthTheme

class MainActivity : ComponentActivity() {
    private val homeViewModel: HomeViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermissionCheck()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMissingPermissions()
        handleIncomingAudio(intent)

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

    override fun onResume() {
        super.onResume()
        CaptureNotificationManager.showReady(this)
        settingsViewModel.refreshRuntimeDiagnostics()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false)) requestMissingPermissions()
        handleIncomingAudio(intent)
    }

    private fun requestMissingPermissions() {
        val missing = buildList {
            if (!hasRecordAudioPermission()) add(Manifest.permission.RECORD_AUDIO)
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray()) else afterPermissionCheck()
    }

    private fun afterPermissionCheck() {
        val app = application as VoiceGrowthApplication
        CaptureNotificationManager.showReady(this)
        if (hasRecordAudioPermission()) app.enqueueAudioProcessing()
        app.enqueueFolderScanNow(force = false)
        settingsViewModel.refreshRuntimeDiagnostics()
    }

    private fun handleIncomingAudio(incoming: Intent?) {
        val action = incoming?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = sharedAudioUris(incoming)
        incoming.action = null
        if (uris.isNotEmpty()) homeViewModel.importAudioUris(uris)
    }

    @Suppress("DEPRECATION")
    private fun sharedAudioUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        Intent.ACTION_SEND_MULTIPLE ->
            (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf()).toList()
        else -> emptyList()
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_REQUEST_MIC_PERMISSION = "request_mic_permission"
    }
}
