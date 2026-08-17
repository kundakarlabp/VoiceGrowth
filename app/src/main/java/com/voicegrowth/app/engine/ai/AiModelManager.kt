package com.voicegrowth.app.engine.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object AiModelManager {
    private const val MODEL_DIR = "ai_models"
    private const val MODEL_FILE = "voicegrowth-model.litertlm"

    fun importedModelFile(context: Context): File = File(File(context.filesDir, MODEL_DIR), MODEL_FILE)

    fun displayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "LiteRT-LM model"
    }

    fun importModel(context: Context, uri: Uri): File {
        val directory = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
        val destination = File(directory, MODEL_FILE)
        val temporary = File(directory, "$MODEL_FILE.partial")
        temporary.delete()

        val input = context.contentResolver.openInputStream(uri)
            ?: error("Unable to open the selected LiteRT-LM model")
        input.use { source ->
            temporary.outputStream().buffered(1024 * 1024).use { output ->
                source.copyTo(output, bufferSize = 1024 * 1024)
            }
        }
        require(temporary.length() > 0L) { "The selected AI model is empty" }

        destination.delete()
        check(temporary.renameTo(destination)) { "Unable to finalize the imported AI model" }
        return destination
    }

    fun removeModel(context: Context) {
        importedModelFile(context).delete()
        File(File(context.filesDir, MODEL_DIR), "$MODEL_FILE.partial").delete()
    }
}
