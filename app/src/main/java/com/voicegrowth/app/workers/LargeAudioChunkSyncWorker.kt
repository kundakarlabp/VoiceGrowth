package com.voicegrowth.app.workers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.sync.ArchiveFilePolicy
import com.voicegrowth.app.sync.DriveTreeSyncService
import com.voicegrowth.app.sync.LongAudioChunker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Extra on-device transport for long recorded AAC files. It never performs ASR, and it never
 * changes the source recording's uploaded status, location or bytes. Part uploads are placed
 * in Audio/YYYY/MM-MMM/__transcription_parts, not in the original-audio directory.
 *
 * The JSON manifest is uploaded LAST; a partial upload must not advertise a complete set.
 * A durable preferences flag prevents repeated work across app restarts.
 */
class LargeAudioChunkSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(INPUT_RECORDING_ID, -1L)
        if (id <= 0L) return Result.failure()
        val settingsKey = "completed_${id}"
        val prefs = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(settingsKey, false)) return Result.success()
        val app = applicationContext as VoiceGrowthApplication
        val repository = app.container.recordingRepository
        val row = repository.getById(id) ?: return Result.success()
        if (row.fileSizeBytes < LongAudioChunker.MAX_CHUNK_BYTES) return Result.success()
        if (row.driveAudioFileId.isNullOrBlank()) return Result.retry()
        val ext = row.fileName.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("m4a", "mp4")) {
            Log.i(TAG, "Large audio ${id} uses ${ext}; lossless AAC segmentation unsupported")
            return Result.success()
        }

        var copied: File? = null
        var prepared: LongAudioChunker.Bundle? = null
        try {
            val settings = repository.settingsFlow.first()
            val tree = settings.driveTreeUri?.let(Uri::parse) ?: return Result.retry()
            val service = DriveTreeSyncService(applicationContext)
            require(service.inspect(tree).usable) { "Private Drive folder is not writable" }
            copied = File.createTempFile("vg_part_input_${id}_", ".m4a", applicationContext.cacheDir)
            val source = Uri.parse(row.uriString)
            applicationContext.contentResolver.openInputStream(source)?.use { input ->
                copied.outputStream().use(input::copyTo)
            } ?: error("Source audio no longer available on device")
            require(copied.length() == row.fileSizeBytes) {
                "Local source bytes changed; refusing to generate parts"
            }

            val stableName = ArchiveFilePolicy.stableName(
                row.id, row.recordedAt, row.source.name, row.fileName)
            prepared = LongAudioChunker.prepare(copied, stableName, id, applicationContext.cacheDir)
                ?: return Result.success()

            for (part in prepared.parts) {
                service.uploadFile(
                    treeUri = tree,
                    localFile = part.file,
                    mimeType = "audio/mp4",
                    recordedAtMillis = row.recordedAt,
                    remoteFileName = part.file.name,
                    childFolderWithinMonth = LongAudioChunker.CHUNK_FOLDER_NAME
                ).getOrThrow()
            }
            // Only publish a manifest after every playable part was uploaded/read back.
            service.uploadFile(
                treeUri = tree,
                localFile = prepared.manifest,
                mimeType = "application/json",
                recordedAtMillis = row.recordedAt,
                remoteFileName = prepared.manifest.name,
                childFolderWithinMonth = LongAudioChunker.CHUNK_FOLDER_NAME
            ).getOrThrow()
            prefs.edit().putBoolean(settingsKey, true).apply()
            Log.i(TAG, "Uploaded ${prepared.parts.size} private audio parts for row ${id}")
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Chunk transport failed for ${id}; original recording preserved", e)
            return if (runAttemptCount < 5) Result.retry() else Result.failure()
        } finally {
            prepared?.directory?.deleteRecursively()
            copied?.delete()
        }
    }

    companion object {
        private const val TAG = "VoiceGrowthChunkSync"
        private const val PREFERENCES_NAME = "voicegrowth_chunk_transport"
        private const val INPUT_RECORDING_ID = "recording_id"

        fun enqueue(context: Context, id: Long, wifiOnly: Boolean) {
            val request = OneTimeWorkRequestBuilder<LargeAudioChunkSyncWorker>()
                .setInputData(workDataOf(INPUT_RECORDING_ID to id))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "VoiceGrowth_DerivedAudioParts_${id}",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
