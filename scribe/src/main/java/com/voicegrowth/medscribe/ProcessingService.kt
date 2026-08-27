package com.voicegrowth.medscribe

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ArrayDeque<String>()
    private var processing = false

    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        if (!id.isNullOrBlank() && !queue.contains(id)) queue.addLast(id)
        startForeground(NOTIFICATION_ID, notification("Preparing transcription…"))
        if (!processing) {
            processing = true
            scope.launch { drainQueue() }
        }
        return START_NOT_STICKY
    }

    private suspend fun drainQueue() {
        while (true) {
            val id = synchronized(this) { if (queue.isEmpty()) null else queue.removeFirst() } ?: break
            processOne(id)
        }
        synchronized(this) { processing = false }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun processOne(id: String) {
        val repo = ScribeRepository.get(this)
        val item = repo.get(id) ?: return
        try {
            val settings = repo.settings()
            if (!ModelManager.isWhisperInstalled(this, settings.whisperModel)) {
                repo.update(id) {
                    it.copy(status = ItemStatus.NEEDS_MODEL, errorMessage = "Install ${settings.whisperModel} Whisper in Setup, then retry")
                }
                return
            }
            repo.update(id) { it.copy(status = ItemStatus.PROCESSING, errorMessage = null) }
            updateNotification("Transcribing ${item.title.take(42)}…")
            val result = TranscriptionEngine().transcribe(this, item, settings)
            val ready = repo.saveTranscript(id, result.markdown, result.language, result.speakerCount) ?: return

            if (settings.autoSync && settings.driveFolderUri != null) {
                updateNotification("Syncing transcript to Drive…")
                val sync = DriveFolderSync.sync(this, ready, settings)
                if (sync.isSuccess) {
                    repo.update(id) { it.copy(driveSyncedAt = System.currentTimeMillis(), errorMessage = null) }
                } else {
                    repo.update(id) {
                        it.copy(errorMessage = "Transcript ready locally; Drive sync failed: ${sync.exceptionOrNull()?.message ?: "unknown error"}")
                    }
                }
            }
        } catch (error: ModelRequiredException) {
            repo.update(id) { it.copy(status = ItemStatus.NEEDS_MODEL, errorMessage = error.message) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            repo.update(id) {
                it.copy(status = ItemStatus.FAILED, errorMessage = (error.message ?: error::class.java.simpleName).take(240))
            }
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MedScribeApp.CHANNEL_PROCESSING)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("MedScribe")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "recording_id"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context, id: String) {
            val intent = Intent(context, ProcessingService::class.java).putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
