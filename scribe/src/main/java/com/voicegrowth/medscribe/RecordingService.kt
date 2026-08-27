package com.voicegrowth.medscribe

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class RecordingService : Service() {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var id: String? = null
    private var startedWall = 0L
    private var startedElapsed = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (recorder == null) startCapture()
            ACTION_STOP -> finishCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val repo = ScribeRepository.get(this)
        val newId = UUID.randomUUID().toString()
        val target = File(repo.audioDir, "$newId.m4a")
        startedWall = System.currentTimeMillis()
        startedElapsed = SystemClock.elapsedRealtime()
        id = newId
        file = target
        startForeground(NOTIFICATION_ID, notification())
        acquireWakeLock()

        try {
            val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            next.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(64_000)
                setOutputFile(target.absolutePath)
                prepare()
                start()
            }
            recorder = next
            repo.setRecordingState(true, startedWall)
        } catch (error: Exception) {
            releaseRecorder()
            releaseWakeLock()
            target.delete()
            repo.setRecordingState(false)
            postError("Recording could not start: ${error.message ?: error::class.java.simpleName}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finishCapture() {
        if (stopping) return
        stopping = true
        val repo = ScribeRepository.get(this)
        val active = recorder
        if (active == null) {
            repo.setRecordingState(false)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val duration = ((SystemClock.elapsedRealtime() - startedElapsed) / 1000L).coerceAtLeast(0L)
        val stoppedCleanly = runCatching { active.stop() }.isSuccess
        releaseRecorder()
        releaseWakeLock()
        repo.setRecordingState(false)
        val output = file
        val itemId = id

        if (stoppedCleanly && output != null && itemId != null && output.isFile && output.length() > 0L && duration >= 1L) {
            val item = ScribeItem(
                id = itemId,
                title = "Conversation ${SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(startedWall))}",
                audioPath = output.absolutePath,
                recordedAt = startedWall,
                durationSeconds = duration,
                status = ItemStatus.RECORDED
            )
            repo.add(item)
            if (repo.settings().autoTranscribe) {
                runCatching { ProcessingService.start(this, itemId) }
                    .onFailure { repo.update(itemId) { current -> current.copy(errorMessage = "Recording saved; open MedScribe to start transcription") } }
            }
        } else {
            output?.delete()
            if (!stoppedCleanly) postError("Android could not finalize the recording; the incomplete file was discarded")
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MedScribeApp.CHANNEL_RECORDING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MedScribe is recording")
            .setContentText("Conversation / speakerphone audio · tap Stop when finished")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setUsesChronometer(true)
            .setWhen(startedWall)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }

    private fun postError(text: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(this).notify(
                ERROR_NOTIFICATION_ID,
                NotificationCompat.Builder(this, MedScribeApp.CHANNEL_STATUS)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("MedScribe recording issue")
                    .setContentText(text.take(180))
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: SecurityException) {
            // Notification permission can be revoked between the explicit check and notify().
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MedScribe:Recording").apply {
            setReferenceCounted(false)
            acquire(MAX_RECORDING_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    private fun releaseRecorder() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    override fun onDestroy() {
        if (!stopping && recorder != null) {
            val stopped = runCatching { recorder?.stop() }.isSuccess
            val output = file
            val itemId = id
            val duration = ((SystemClock.elapsedRealtime() - startedElapsed) / 1000L).coerceAtLeast(0L)
            if (stopped && output != null && itemId != null && output.isFile && output.length() > 0L && duration >= 1L) {
                val repo = ScribeRepository.get(this)
                repo.add(ScribeItem(itemId, "Recovered recording", output.absolutePath, startedWall, duration, ItemStatus.RECORDED))
                if (repo.settings().autoTranscribe) {
                    runCatching { ProcessingService.start(this, itemId) }
                        .onFailure { repo.update(itemId) { current -> current.copy(errorMessage = "Recovered recording saved; reopen MedScribe to transcribe") } }
                }
            } else {
                output?.delete()
            }
        }
        ScribeRepository.get(this).setRecordingState(false)
        releaseRecorder()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.voicegrowth.medscribe.START_RECORDING"
        const val ACTION_STOP = "com.voicegrowth.medscribe.STOP_RECORDING"
        private const val NOTIFICATION_ID = 4101
        private const val ERROR_NOTIFICATION_ID = 4102
        private const val MAX_RECORDING_MS = 12L * 60L * 60L * 1000L
    }
}
