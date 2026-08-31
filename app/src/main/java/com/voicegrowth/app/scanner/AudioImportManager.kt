package com.voicegrowth.app.scanner

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.model.RecordingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object AudioImportManager {
    private const val MAX_IMPORTS_PER_ACTION = 20

    suspend fun importUris(context: Context, uris: List<Uri>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext as VoiceGrowthApplication
            val repository = app.container.recordingRepository
            val settings = repository.settingsFlow.first()
            var imported = 0

            uris.distinctBy(Uri::toString).take(MAX_IMPORTS_PER_ACTION).forEach { uri ->
                val displayName = queryDisplayName(context, uri)
                val extension = resolveExtension(context, uri, displayName)
                val directory = File(context.getExternalFilesDir(null), "imported_audio").apply { mkdirs() }
                val destination = File(directory, "IMP_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$extension")
                try {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Unable to open shared audio: $displayName")
                    input.use { source ->
                        destination.outputStream().buffered().use { output -> source.copyTo(output) }
                    }
                    require(destination.length() > 0L) { "Shared audio is empty: $displayName" }

                    val localUri = Uri.fromFile(destination)
                    val metadata = AudioMetadataExtractor.extract(
                        context = context,
                        uri = localUri,
                        file = destination,
                        fallbackRecordedAt = System.currentTimeMillis(),
                        fallbackSize = destination.length()
                    )

                    val id = repository.insertRecording(
                        RecordingEntity(
                            uriString = localUri.toString(),
                            fileName = displayName.take(180),
                            filePath = destination.absolutePath,
                            source = RecordingSource.IMPORTED_AUDIO,
                            durationSeconds = metadata.durationSeconds,
                            fileSizeBytes = destination.length(),
                            recordedAt = metadata.recordedAt,
                            status = ProcessingStatus.WAITING_FOR_SYNC
                        )
                    )
                    if (id > 0L) imported++ else destination.delete()
                } catch (error: CancellationException) {
                    destination.delete()
                    throw error
                } catch (error: Exception) {
                    destination.delete()
                    throw error
                }
            }

            if (imported > 0) app.enqueueDriveSync(settings.wifiOnly)
            Result.success(imported)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        return name?.takeIf(String::isNotBlank) ?: "Imported audio"
    }

    private fun resolveExtension(context: Context, uri: Uri, displayName: String): String {
        val fromName = displayName.substringAfterLast('.', "").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        if (fromName != null) return fromName
        return MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(context.contentResolver.getType(uri))
            ?.lowercase() ?: "m4a"
    }
}
