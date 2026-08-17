package com.voicegrowth.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.voicegrowth.app.ui.QuickCaptureActivity

class QuickCaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        openQuickCaptureActivity()
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
    private fun openQuickCaptureActivity() {
        val intent = Intent(this, QuickCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                702,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            // PendingIntent overload was introduced in API 34. API 24-33 require the legacy
            // activity overload; the SDK guard prevents it from executing on target-34 devices.
            startActivityAndCollapse(intent)
        }
    }
}
