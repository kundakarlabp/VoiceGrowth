package com.voicegrowth.app.engine.ai

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.File


data class AiModelImportProgress(
    val copiedBytes: Long,
    val totalBytes: Long?
) {
    val percent: Int? = totalBytes?.takeIf { it > 0L }?.let { total ->
        ((copiedBytes * 100L) / total).coerceIn(0L, 100L).toInt()
    }
}

object AiModelManager {
    private const val MODEL_DIR = "ai_models"
    private const val MODEL_FILE = "voicegrowth-model.litertlm"
    private const val COPY_BUFFER_BYTES = 1024 * 1024
    private const val PROGRESS_GRANULARITY_BYTES = 8L * 1024 * 1024
    private const val FREE_SPACE_RESERVE_BYTES = 256L * 1024 * 1024

    fun importedModelFile(context: Context): File = File(File(context.filesDir, MODEL_DIR), MODEL_FILE)

    fun displayName(context: Context, uri: Uri): String {
        return queryMetadata(context, uri).first
            ?: uri.lastPathSegment
            ?: "LiteRT-LM model"
    }

    fun selectedSizeBytes(context: Context, uri: Uri): Long? = queryMetadata(context, uri).second

    fun availablePrivateStorageBytes(context: Context): Long = StatFs(context.filesDir.absolutePath).availableBytes

    fun importModel(
        context: Context,
        uri: Uri,
        onProgress: (AiModelImportProgress) -> Unit = {}
    ): File {
        val directory = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
        val destination = File(directory, MODEL_FILE)
        val temporary = File(directory, "$MODEL_FILE.partial")
        val backup = File(directory, "$MODEL_FILE.backup")
        temporary.delete()
        backup.delete()

        val expectedSize = selectedSizeBytes(context, uri)?.takeIf { it > 0L }
        if (expectedSize != null) {
            val required = expectedSize + FREE_SPACE_RESERVE_BYTES
            val available = availablePrivateStorageBytes(context)
            require(available >= required) {
                "Not enough free storage. Model needs about ${formatMb(expectedSize)} MB plus 256 MB working space; only ${formatMb(available)} MB is available."
            }
        }

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Unable to open the selected LiteRT-LM model")
            var copied = 0L
            var nextProgress = 0L
            input.use { source ->
                temporary.outputStream().buffered(COPY_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied >= nextProgress) {
                            onProgress(AiModelImportProgress(copied, expectedSize))
                            nextProgress = copied + PROGRESS_GRANULARITY_BYTES
                        }
                    }
                    output.flush()
                }
            }
            require(temporary.length() > 0L) { "The selected AI model is empty" }
            if (expectedSize != null) {
                require(temporary.length() == expectedSize) {
                    "Model copy was incomplete (${formatMb(temporary.length())}/${formatMb(expectedSize)} MB)."
                }
            }
            onProgress(AiModelImportProgress(temporary.length(), expectedSize ?: temporary.length()))

            if (destination.exists()) {
                check(destination.renameTo(backup)) { "Unable to preserve the existing AI model before replacement" }
            }
            try {
                check(temporary.renameTo(destination)) { "Unable to finalize the imported AI model" }
                backup.delete()
            } catch (error: Exception) {
                destination.delete()
                if (backup.exists()) backup.renameTo(destination)
                throw error
            }
            return destination
        } finally {
            if (temporary.exists()) temporary.delete()
            // A backup is only left behind if restoration itself failed; keep it rather than
            // deleting the user's previously working model bytes.
        }
    }

    fun removeModel(context: Context) {
        importedModelFile(context).delete()
        val directory = File(context.filesDir, MODEL_DIR)
        File(directory, "$MODEL_FILE.partial").delete()
        File(directory, "$MODEL_FILE.backup").delete()
    }

    private fun queryMetadata(context: Context, uri: Uri): Pair<String?, Long?> {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                name to size
            }
        }.getOrNull() ?: (null to null)
    }

    private fun formatMb(bytes: Long): Long = (bytes + 1024 * 1024 - 1) / (1024 * 1024)
}
