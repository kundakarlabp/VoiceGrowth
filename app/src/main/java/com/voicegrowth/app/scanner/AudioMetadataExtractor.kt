package com.voicegrowth.app.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

data class AudioMetadata(
    val durationSeconds: Long,
    val recordedAt: Long,
    val fileSizeBytes: Long,
    val mimeType: String?
)

object AudioMetadataExtractor {
    fun extract(
        context: Context,
        uri: Uri,
        file: File? = null,
        fallbackRecordedAt: Long = 0L,
        fallbackSize: Long = 0L
    ): AudioMetadata {
        val retriever = MediaMetadataRetriever()
        var durationSec = 0L
        var recordedAt = fallbackRecordedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        var mimeType: String? = context.contentResolver.getType(uri)
        var fileSize = file?.length()?.takeIf { it > 0 } ?: fallbackSize

        try {
            if (file != null && file.exists()) {
                retriever.setDataSource(file.absolutePath)
                if (file.lastModified() > 0) recordedAt = file.lastModified()
            } else {
                retriever.setDataSource(context, uri)
            }

            durationSec = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L) / 1000L
            mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: mimeType
        } catch (_: Exception) {
            // Metadata is best-effort; scanner still records readable files.
        } finally {
            runCatching { retriever.release() }
        }

        return AudioMetadata(durationSec, recordedAt, fileSize, mimeType)
    }
}
