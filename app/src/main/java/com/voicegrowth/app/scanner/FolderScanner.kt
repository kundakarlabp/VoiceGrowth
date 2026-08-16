package com.voicegrowth.app.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.model.RecordingSource
import com.voicegrowth.app.data.preferences.AppSettings
import com.voicegrowth.app.data.repository.RecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FolderScanner(
    private val context: Context,
    private val repository: RecordingRepository
) {
    suspend fun scanFolder(treeUri: Uri, settings: AppSettings): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext 0
        val now = System.currentTimeMillis()
        var newRecordings = 0

        for (doc in root.listFiles()) {
            if (!doc.isFile || !isAudioFile(doc.name.orEmpty())) continue

            val uriString = doc.uri.toString()
            if (repository.getByUri(uriString) != null) continue

            val lastModified = doc.lastModified()
            // Avoid picking up an OEM file while the call recorder is still writing it.
            if (lastModified > 0 && now - lastModified < FILE_STABILITY_WINDOW_MS) continue
            val size = doc.length()
            if (size <= 0L) continue

            val metadata = AudioMetadataExtractor.extract(
                context = context,
                uri = doc.uri,
                fallbackRecordedAt = lastModified,
                fallbackSize = size
            )

            val status = if (
                settings.onlyProcessOver30Sec && metadata.durationSeconds in 1 until MIN_DURATION_SECONDS
            ) {
                ProcessingStatus.SKIPPED_TOO_SHORT
            } else {
                ProcessingStatus.PENDING
            }

            val inserted = repository.insertRecording(
                RecordingEntity(
                    uriString = uriString,
                    fileName = doc.name ?: "recording_${now}",
                    filePath = doc.uri.toString(),
                    source = RecordingSource.CALL_RECORDING,
                    durationSeconds = metadata.durationSeconds,
                    fileSizeBytes = size,
                    recordedAt = metadata.recordedAt,
                    status = status
                )
            )
            if (inserted > 0 && status == ProcessingStatus.PENDING) newRecordings++
        }
        newRecordings
    }

    private fun isAudioFile(name: String): Boolean {
        val lower = name.lowercase()
        return SUPPORTED_EXTENSIONS.any(lower::endsWith)
    }

    companion object {
        private const val MIN_DURATION_SECONDS = 30L
        private const val FILE_STABILITY_WINDOW_MS = 10_000L
        private val SUPPORTED_EXTENSIONS = listOf(".m4a", ".mp3", ".wav", ".aac", ".3gp", ".amr", ".ogg", ".opus")
    }
}
