package com.voicegrowth.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.model.RecordingSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Capture-only recording service.
 *
 * VoiceGrowth v2 treats the original recording as the primary artifact. The phone does not run
 * speech recognition or an LLM before upload. Consult audio is captured as mono AAC-LC in an
 * MPEG-4 (.m4a) container and queued directly for Drive sync when recording stops.
 */
class AudioRecordingService : Service() {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L
    private var startedElapsedRealtime = 0L
    private var source = RecordingSource.MANUAL_DISCUSSION
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> if (recorder == null) {
                source = runCatching {
                    RecordingSource.valueOf(intent.getStringExtra(EXTRA_SOURCE).orEmpty())
                }.getOrDefault(RecordingSource.MANUAL_DISCUSSION)
                startRecording()
            }
            ACTION_STOP_RECORDING -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        startedAt = System.currentTimeMillis()
        startedElapsedRealtime = SystemClock.elapsedRealtime()
        CaptureNotificationManager.hideReady(this)
        startForeground(NOTIFICATION_ID, recordingNotification())
        acquireWakeLock()

        try {
            val dir = File(getExternalFilesDir(null), "manual_recordings").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(startedAt))
            val file = File(dir, "REC_$stamp.m4a")
            val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            next.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioEncodingBitRate(AUDIO_BIT_RATE)
                setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            outputFile = file
            recorder = next
            RecordingStateStore.setRecording(this, true)
            requestTileRefresh()
        } catch (error: Exception) {
            RecordingStateStore.setRecording(this, false)
            requestTileRefresh()
            releaseRecorder()
            releaseWakeLock()
            outputFile?.delete()
            outputFile = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            CaptureNotificationManager.showReady(
                this,
                "Recording could not start: ${(error.message ?: error::class.java.simpleName).take(80)}"
            )
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (recorder == null) {
            RecordingStateStore.setRecording(this, false)
            requestTileRefresh()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            CaptureNotificationManager.showReady(this)
            stopSelf()
            return
        }

        val duration = ((SystemClock.elapsedRealtime() - startedElapsedRealtime) / 1_000L).coerceAtLeast(0L)
        val stoppedCleanly = runCatching { recorder?.stop() }.isSuccess
        releaseRecorder()
        releaseWakeLock()
        RecordingStateStore.setRecording(this, false)
        requestTileRefresh()
        val file = outputFile

        if (stoppedCleanly && file != null && file.exists() && file.length() > 0L && duration >= MIN_VALID_SECONDS) {
            (application as VoiceGrowthApplication).registerCompletedRecording(
                file = file,
                recordedAt = startedAt,
                durationSeconds = duration,
                source = source
            )
        } else if (file != null) {
            // Interrupted or failed finalization may still leave recoverable bytes.
            if (stoppedCleanly && duration < MIN_VALID_SECONDS) file.delete()
        }

        outputFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        CaptureNotificationManager.showReady(this)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceGrowth:AudioCapture").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
    }

    private fun releaseRecorder() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    override fun onDestroy() {
        val wasRecording = recorder != null
        if (wasRecording) runCatching { recorder?.stop() }
        // Never delete unfinished original audio; recover playable files on next app launch.
        RecordingStateStore.setRecording(this, false)
        requestTileRefresh()
        releaseRecorder()
        releaseWakeLock()
        if (wasRecording) CaptureNotificationManager.showReady(this, "Recording interrupted. Reopen VoiceGrowth to recover playable audio.")
        super.onDestroy()
    }

    private fun recordingNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, AudioRecordingService::class.java).setAction(ACTION_STOP_RECORDING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val label = when (source) {
            RecordingSource.MANUAL_DISCUSSION -> "Consult / discussion"
            RecordingSource.VOICE_REFLECTION -> "Voice note"
            RecordingSource.CALL_RECORDING -> "Recording"
            RecordingSource.IMPORTED_AUDIO -> "Recording"
        }

        return NotificationCompat.Builder(this, VoiceGrowthApplication.CHANNEL_CAPTURE_ID)
            .setContentTitle("VoiceGrowth is recording")
            .setContentText("$label · tap Stop when finished")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(CaptureNotificationManager.openAppPendingIntent(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    private fun requestTileRefresh() {
        runCatching {
            TileService.requestListeningState(this, ComponentName(this, QuickCaptureTileService::class.java))
        }
    }

    companion object {
        const val NOTIFICATION_ID = 2001
        const val ACTION_START_RECORDING = "com.voicegrowth.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.voicegrowth.action.STOP_RECORDING"
        const val EXTRA_SOURCE = "extra_source"
        private const val REQUEST_STOP = 2201
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_BIT_RATE = 160_000
        private const val MIN_VALID_SECONDS = 3L
        private const val MAX_WAKE_LOCK_MS = 6 * 60 * 60 * 1_000L
    }
}
