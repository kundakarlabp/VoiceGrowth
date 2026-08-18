package com.voicegrowth.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.model.RecordingSource
import com.voicegrowth.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
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

        if (stoppedCleanly && file != null && file.exists() && file.length() > 0L && duration >= 3L) {
            val app = application as VoiceGrowthApplication
            scope.launch {
                val settings = app.container.recordingRepository.settingsFlow.first()
                val status = if (settings.onlyProcessOver30Sec && duration < 30L) {
                    ProcessingStatus.SKIPPED_TOO_SHORT
                } else ProcessingStatus.PENDING
                val id = app.container.recordingRepository.insertRecording(
                    RecordingEntity(
                        uriString = Uri.fromFile(file).toString(),
                        fileName = file.name,
                        filePath = file.absolutePath,
                        source = source,
                        durationSeconds = duration,
                        fileSizeBytes = file.length(),
                        recordedAt = startedAt,
                        status = status
                    )
                )
                if (id > 0 && status == ProcessingStatus.PENDING && settings.autoProcessing) {
                    app.enqueueAudioProcessing()
                }
            }
        } else if (file != null) {
            if (duration < 3L || !stoppedCleanly) file.delete()
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
        if (wasRecording) {
            // A destroyed recorder cannot be resumed safely. Release resources; keep any incomplete
            // output out of the processing pipeline rather than pretending a valid recording exists.
            outputFile?.delete()
        }
        RecordingStateStore.setRecording(this, false)
        requestTileRefresh()
        releaseRecorder()
        releaseWakeLock()
        scope.cancel()
        if (wasRecording) CaptureNotificationManager.showReady(this, "Recording stopped by Android; tap Record to start again")
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
            RecordingSource.MANUAL_DISCUSSION -> "Discussion recording"
            RecordingSource.VOICE_REFLECTION -> "Voice reflection"
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
        private const val MAX_WAKE_LOCK_MS = 6 * 60 * 60 * 1_000L
    }
}
