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
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Android could not open the selected call-recording folder")
        require(root.exists() && root.isDirectory && root.canRead()) {
            "Selected call-recording folder is no longer readable. Re-select it in Settings."
        }
        require(FolderAccessManager.hasPersistedReadPermission(context, treeUri)) {
            "Persistent folder access was lost. Re-select the call-recording folder in Settings."
        }

        val now = System.currentTimeMillis()
        var newRecordings = 0
        var visitedNodes = 0
        val visitedDirectories = HashSet<String>()

        suspend fun processFile(doc: DocumentFile) {
            val uriString = doc.uri.toString()
            if (repository.getByUri(uriString) != null) return

            val lastModified = doc.lastModified()
            if (lastModified > 0 && now - lastModified < FILE_STABILITY_WINDOW_MS) return
            val size = doc.length()
            if (size <= 0L) return

            val metadata = AudioMetadataExtractor.extract(
                context = context,
                uri = doc.uri,
                fallbackRecordedAt = lastModified,
                fallbackSize = size
            )

            val inserted = repository.insertRecording(
                RecordingEntity(
                    uriString = uriString,
                    fileName = doc.name ?: "recording_${now}",
                    filePath = doc.uri.toString(),
                    source = RecordingSource.CALL_RECORDING,
                    durationSeconds = metadata.durationSeconds,
                    fileSizeBytes = size,
                    recordedAt = metadata.recordedAt,
                    status = ProcessingStatus.WAITING_FOR_SYNC
                )
            )
            if (inserted > 0) newRecordings++
        }

        suspend fun walk(directory: DocumentFile, depth: Int) {
            if (depth > MAX_DEPTH || visitedNodes >= MAX_NODES) return
            if (!visitedDirectories.add(directory.uri.toString())) return
            for (doc in directory.listFiles()) {
                if (visitedNodes++ >= MAX_NODES) break
                when {
                    doc.isDirectory -> walk(doc, depth + 1)
                    doc.isFile && FolderAccessManager.isSupportedAudio(doc.name.orEmpty()) -> processFile(doc)
                }
            }
        }

        walk(root, 0)
        newRecordings
    }

    companion object {
        private const val FILE_STABILITY_WINDOW_MS = 10_000L
        private const val MAX_DEPTH = 6
        private const val MAX_NODES = 4_000
    }
}
