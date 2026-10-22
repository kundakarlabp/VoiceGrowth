package com.voicegrowth.app.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.model.RecordingSource
import com.voicegrowth.app.data.repository.RecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Recover finalized AAC/M4A files left behind if Android stopped a microphone service before
 * it could register its recording with Room. Corrupt or unfinished media is NEVER deleted.
 * Only playable recordings are queued for archiving; the original stays on the phone.
 */
class InterruptedRecordingRecovery(
    private val context: Context,
    private val repository: RecordingRepository
) {
    suspend fun recover(): Int = withContext(Dispatchers.IO) {
        val folder = File(context.getExternalFilesDir(null), "manual_recordings")
        if (!folder.isDirectory) return@withContext 0

        var recovered = 0
        folder.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("REC_") && it.extension.equals("m4a", true) }
            .filter { it.length() > 0L && System.currentTimeMillis() - it.lastModified() > 10_000L }
            .forEach { file ->
                val uri = Uri.fromFile(file)
                if (repository.getByUri(uri.toString()) != null) return@forEach
                val retriever = MediaMetadataRetriever()
                val durationMs = try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } catch (_: Exception) {
                    null
                } finally {
                    runCatching { retriever.release() }
                }
                if (durationMs == null || durationMs < 3_000L) return@forEach

                val id = repository.insertRecording(
                    RecordingEntity(
                        uriString = uri.toString(),
                        fileName = file.name,
                        filePath = file.absolutePath,
                        source = RecordingSource.MANUAL_DISCUSSION,
                        durationSeconds = durationMs / 1_000L,
                        fileSizeBytes = file.length(),
                        recordedAt = file.lastModified(),
                        status = ProcessingStatus.WAITING_FOR_SYNC
                    )
                )
                if (id > 0L) recovered++
            }
        recovered
    }
}
