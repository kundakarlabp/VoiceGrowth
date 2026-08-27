package com.voicegrowth.medscribe

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DriveFolderSync {
    suspend fun sync(context: Context, item: ScribeItem, settings: ScribeSettings): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = settings.driveFolderUri?.let(Uri::parse) ?: error("Drive folder is not linked")
                val root = DocumentFile.fromTreeUri(context, uri) ?: error("Linked Drive folder is unavailable")
                require(root.canWrite()) { "Linked Drive folder is no longer writable; select it again" }
                val transcriptPath = item.transcriptPath ?: error("Transcript is not ready")
                val transcript = File(transcriptPath)
                require(transcript.isFile) { "Local transcript file is missing" }

                val date = Date(item.recordedAt)
                val year = SimpleDateFormat("yyyy", Locale.US).format(date)
                val month = SimpleDateFormat("MM-MMM", Locale.US).format(date)
                val medScribe = root.folder("MedScribe")
                val transcriptDir = medScribe.folder("Transcripts").folder(year).folder(month)
                val safeBase = item.title.replace(Regex("[^A-Za-z0-9._ -]+"), "_").trim().take(60).ifBlank { item.id.take(8) }
                transcriptDir.writeFile(context, "${dateStamp(date)}_$safeBase.md", "text/markdown", transcript)

                if (settings.uploadAudio) {
                    val audio = File(item.audioPath)
                    if (audio.isFile) {
                        val audioDir = medScribe.folder("Audio").folder(year).folder(month)
                        val ext = audio.extension.ifBlank { "m4a" }
                        audioDir.writeFile(context, "${dateStamp(date)}_$safeBase.$ext", mimeForAudio(ext), audio)
                    }
                }
            }
        }

    private fun DocumentFile.folder(name: String): DocumentFile {
        val existing = findFile(name)
        if (existing != null && existing.isDirectory) return existing
        existing?.delete()
        return createDirectory(name) ?: error("Could not create Drive folder: $name")
    }

    private fun DocumentFile.writeFile(context: Context, name: String, mime: String, source: File) {
        findFile(name)?.delete()
        val target = createFile(mime, name) ?: error("Could not create Drive file: $name")
        context.contentResolver.openOutputStream(target.uri, "w").use { output ->
            requireNotNull(output) { "Could not open Drive file for writing" }
            source.inputStream().buffered(256 * 1024).use { input -> input.copyTo(output, 256 * 1024) }
        }
    }

    private fun dateStamp(date: Date): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)

    private fun mimeForAudio(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> "audio/mp4"
    }
}
