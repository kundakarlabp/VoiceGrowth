package com.voicegrowth.app.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.model.RecordingSource
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
    private var source = RecordingSource.MANUAL_DISCUSSION

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
        startForeground(NOTIFICATION_ID, notification("Recording discussion…"))
        try {
            val dir = File(getExternalFilesDir(null), "manual_recordings").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
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
            startedAt = System.currentTimeMillis()
            RecordingStateStore.setRecording(this, true)
        } catch (_: Exception) {
            RecordingStateStore.setRecording(this, false)
            releaseRecorder()
            outputFile?.delete()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (recorder == null) {
            RecordingStateStore.setRecording(this, false)
            stopSelf()
            return
        }
        val duration = ((System.currentTimeMillis() - startedAt) / 1_000L).coerceAtLeast(0L)
        val stoppedCleanly = runCatching { recorder?.stop() }.isSuccess
        releaseRecorder()
        RecordingStateStore.setRecording(this, false)
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
        } else if (file != null && duration < 3L) {
            file.delete()
        }

        outputFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseRecorder() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    override fun onDestroy() {
        RecordingStateStore.setRecording(this, false)
        releaseRecorder()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(
        this,
        VoiceGrowthApplication.CHANNEL_RECORDING_ID
    )
        .setContentTitle("VoiceGrowth recorder")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .build()

    companion object {
        const val NOTIFICATION_ID = 2001
        const val ACTION_START_RECORDING = "com.voicegrowth.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.voicegrowth.action.STOP_RECORDING"
        const val EXTRA_SOURCE = "extra_source"
    }
}
