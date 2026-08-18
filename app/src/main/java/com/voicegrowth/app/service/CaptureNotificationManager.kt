package com.voicegrowth.app.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.receiver.CaptureActionReceiver
import com.voicegrowth.app.ui.MainActivity
import com.voicegrowth.app.ui.QuickCaptureActivity

/**
 * Owns the always-available VoiceGrowth capture control in the notification shade/lock screen.
 *
 * The ready notification is a normal ongoing notification, not a long-running foreground service.
 * Record opens a tiny show-when-locked activity, which creates the microphone foreground service
 * from a user-visible context on Android 14+.
 */
object CaptureNotificationManager {
    const val READY_NOTIFICATION_ID = 1999

    fun showReady(context: Context, statusText: String? = null) {
        if (RecordingStateStore.isRecording(context)) {
            hideReady(context)
            return
        }
        if (!canPostNotifications(context)) return

        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val primaryIntent = if (hasMic) {
            PendingIntent.getActivity(
                context,
                REQUEST_START_RECORDING,
                Intent(context, QuickCaptureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getActivity(
                context,
                REQUEST_OPEN_FOR_PERMISSION,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_REQUEST_MIC_PERMISSION, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val scanIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_SCAN,
            Intent(context, CaptureActionReceiver::class.java).setAction(CaptureActionReceiver.ACTION_SCAN_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, VoiceGrowthApplication.CHANNEL_CAPTURE_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VoiceGrowth ready")
            .setContentText(
                statusText ?: if (hasMic) {
                    "Record from the lock screen or scan call recordings now"
                } else {
                    "Grant microphone permission to enable one-tap recording"
                }
            )
            .setContentIntent(openAppPendingIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(
                android.R.drawable.ic_btn_speak_now,
                if (hasMic) "Record" else "Grant mic",
                primaryIntent
            )
            .addAction(android.R.drawable.ic_popup_sync, "Scan now", scanIntent)
            .build()

        NotificationManagerCompat.from(context).notify(READY_NOTIFICATION_ID, notification)
    }

    fun hideReady(context: Context) {
        NotificationManagerCompat.from(context).cancel(READY_NOTIFICATION_ID)
    }

    fun canPostNotifications(context: Context): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!runtimeGranted || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = manager.getNotificationChannel(VoiceGrowthApplication.CHANNEL_CAPTURE_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    fun openAppPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private const val REQUEST_OPEN_APP = 2100
    private const val REQUEST_START_RECORDING = 2101
    private const val REQUEST_SCAN = 2102
    private const val REQUEST_OPEN_FOR_PERMISSION = 2103
}
