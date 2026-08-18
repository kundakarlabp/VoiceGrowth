package com.voicegrowth.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.service.CaptureNotificationManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            VoiceGrowthApplication.schedulePeriodicWork(context.applicationContext)
            CaptureNotificationManager.showReady(context.applicationContext)
        }
    }
}
