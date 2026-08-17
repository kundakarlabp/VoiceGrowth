package com.voicegrowth.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.voicegrowth.app.data.model.RecordingSource
import com.voicegrowth.app.service.AudioRecordingService
import com.voicegrowth.app.service.RecordingStateStore

/**
 * Transient activity used by the Quick Settings tile.
 *
 * Android 14 checks RECORD_AUDIO while-in-use permission when a microphone foreground service is
 * created. Starting from a resumed activity gives the service a valid user-visible launch context
 * instead of trying to create the microphone FGS directly from a background TileService.
 */
class QuickCaptureActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
    }

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_REQUEST_MIC_PERMISSION, true)
            )
            finish()
            return
        }

        val action = if (RecordingStateStore.isRecording(this)) {
            AudioRecordingService.ACTION_STOP_RECORDING
        } else {
            AudioRecordingService.ACTION_START_RECORDING
        }
        val serviceIntent = Intent(this, AudioRecordingService::class.java).setAction(action)
        if (action == AudioRecordingService.ACTION_START_RECORDING) {
            serviceIntent.putExtra(AudioRecordingService.EXTRA_SOURCE, RecordingSource.VOICE_REFLECTION.name)
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
        finish()
    }
}
