package com.voicegrowth.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.service.CaptureNotificationManager

class CaptureActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCAN_NOW) return
        val app = context.applicationContext as VoiceGrowthApplication
        app.enqueueFolderScanNow()
        CaptureNotificationManager.showReady(context, "Scanning the selected call-recording folder…")
    }

    companion object {
        const val ACTION_SCAN_NOW = "com.voicegrowth.action.SCAN_NOW"
    }
}
