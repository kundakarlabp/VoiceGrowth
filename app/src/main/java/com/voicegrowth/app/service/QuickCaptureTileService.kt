package com.voicegrowth.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.voicegrowth.app.data.model.RecordingSource
import com.voicegrowth.app.ui.MainActivity

class QuickCaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openAppForPermission()
            return
        }

        if (RecordingStateStore.isRecording(this)) {
            startService(
                Intent(this, AudioRecordingService::class.java)
                    .setAction(AudioRecordingService.ACTION_STOP_RECORDING)
            )
        } else {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AudioRecordingService::class.java)
                    .setAction(AudioRecordingService.ACTION_START_RECORDING)
                    .putExtra(AudioRecordingService.EXTRA_SOURCE, RecordingSource.VOICE_REFLECTION.name)
            )
        }
        refreshTile()
    }

    private fun refreshTile() {
        val recording = RecordingStateStore.isRecording(this)
        qsTile?.apply {
            state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (recording) "Stop VoiceGrowth" else "VoiceGrowth capture"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (recording) "Recording now" else "Tap for voice note"
            }
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAppForPermission() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_REQUEST_MIC_PERMISSION, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                701,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            // PendingIntent overload was added in API 34. The guarded legacy overload is the
            // platform-compatible path on API 24-33 and cannot execute on target-34 devices.
            startActivityAndCollapse(intent)
        }
    }
}
