package com.voicegrowth.app.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


data class DriveTreeStatus(
    val usable: Boolean,
    val persistedReadPermission: Boolean,
    val persistedWritePermission: Boolean,
    val displayName: String,
    val providerAuthority: String?,
    val message: String
)

data class DriveUploadResult(
    val fileId: String,
    val webViewLink: String?,
    val folderPath: String
)

class DriveTreeSyncService(private val context: Context) {
    private val yearFormat = SimpleDateFormat("yyyy", Locale.US)
    private val monthFormat = SimpleDateFormat("MM-MMM", Locale.US)

    fun persistPermission(uri: Uri): Result<Unit> = runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val status = inspect(uri)
        require(status.persistedReadPermission && status.persistedWritePermission) {
            "Android did not retain read/write access to the selected cloud folder"
        }
    }

    fun releasePermission(uri: Uri) {
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        var flags = 0
        if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (flags != 0) runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    fun inspect(uri: Uri): DriveTreeStatus {
        val persisted = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        val read = persisted?.isReadPermission == true
        val write = persisted?.isWritePermission == true
        return try {
            val root = DocumentFile.fromTreeUri(context, uri)
                ?: return DriveTreeStatus(false, read, write, fallbackName(uri), uri.authority, "Android could not open the selected cloud folder")
            val name = root.name?.takeIf(String::isNotBlank) ?: fallbackName(uri)
            val readable = root.exists() && root.isDirectory && root.canRead()
            val writable = readable && root.canWrite()
            val usable = readable && writable && read && write
            val message = when {
                !read || !write -> "Persistent read/write access was not retained. Choose the folder again and tap Use this folder / Allow."
                !readable -> "The selected cloud folder is no longer readable. Re-select it in Android Files."
                !writable -> "The selected cloud folder is read-only. Choose a folder where VoiceGrowth can create files."
                else -> "Cloud folder access is healthy. VoiceGrowth can sync without an OAuth client."
            }
            DriveTreeStatus(usable, read, write, name, uri.authority, message)
        } catch (error: SecurityException) {
            DriveTreeStatus(false, read, write, fallbackName(uri), uri.authority, "Cloud-folder permission was lost. Re-select the folder.")
        } catch (error: Exception) {
            DriveTreeStatus(false, read, write, fallbackName(uri), uri.authority, "Cloud-folder check failed: ${(error.message ?: error::class.java.simpleName).take(160)}")
        }
    }

    suspend fun verifyWrite(uri: Uri): Result<DriveTreeStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val before = inspect(uri)
            require(before.usable) { before.message }
            val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) { "Cloud folder is unavailable" }
            val testName = "VoiceGrowth connection test ${System.currentTimeMillis()}.txt"
            val test = root.createFile("text/plain", testName)
                ?: error("The provider did not allow VoiceGrowth to create a test file")
            try {
                context.contentResolver.openOutputStream(test.uri, "wt")?.use { out ->
                    out.write("VoiceGrowth cloud-folder write test".toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: error("The provider did not allow VoiceGrowth to write the test file")
            } finally {
                runCatching { test.delete() }
            }
            inspect(uri).copy(message = "Cloud folder verified: read/write test succeeded.")
        }
    }

    suspend fun uploadFile(
        treeUri: Uri,
        localFile: File,
        mimeType: String,
        recordedAtMillis: Long,
        baseHierarchy: String = "VoiceGrowth/Audio"
    ): Result<DriveUploadResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(localFile.exists() && localFile.length() > 0L) { "Local upload file is missing or empty" }
            val status = inspect(treeUri)
            require(status.usable) { status.message }
            val root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)) { "Selected cloud folder is unavailable" }

            val year = yearFormat.format(Date(recordedAtMillis))
            val month = monthFormat.format(Date(recordedAtMillis))
            val segments = normalizeHierarchyForSelectedRoot(status.displayName, sanitizeHierarchy(baseHierarchy)) + listOf(year, month)
            var parent = root
            for (segment in segments) parent = getOrCreateDirectory(parent, segment)

            val existing = parent.findFile(localFile.name)?.takeIf { it.isFile }
            val target = existing ?: parent.createFile(mimeType, localFile.name)
                ?: error("Cloud provider could not create ${localFile.name}")

            val written = runCatching {
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                    localFile.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                    output.flush()
                } ?: error("Cloud provider refused the output stream")
            }.recoverCatching {
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    localFile.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                    output.flush()
                } ?: error("Cloud provider refused the output stream")
            }
            written.getOrThrow()

            DriveUploadResult(
                fileId = target.uri.toString(),
                webViewLink = null,
                folderPath = buildString {
                    append(status.displayName)
                    if (segments.isNotEmpty()) append('/').append(segments.joinToString("/"))
                }
            )
        }
    }

    private fun normalizeHierarchyForSelectedRoot(rootName: String, segments: List<String>): List<String> {
        if (segments.isEmpty()) return segments
        val first = segments.first()
        return if (rootName.equals(first, ignoreCase = true)) segments.drop(1) else segments
    }

    private fun getOrCreateDirectory(parent: DocumentFile, rawName: String): DocumentFile {
        val name = sanitizeSegment(rawName)
        val existing = parent.findFile(name)
        if (existing != null) {
            require(existing.isDirectory) { "A file named '$name' blocks creation of the Drive folder hierarchy" }
            return existing
        }
        return parent.createDirectory(name) ?: error("Cloud provider could not create folder '$name'")
    }

    private fun sanitizeHierarchy(path: String): List<String> {
        val segments = path.split('/')
            .map(String::trim)
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map(::sanitizeSegment)
        return if (segments.isEmpty()) listOf("VoiceGrowth", "Audio") else segments
    }

    private fun sanitizeSegment(value: String): String = value
        .replace('/', '_')
        .replace('\\', '_')
        .trim()
        .take(80)
        .ifBlank { "VoiceGrowth" }

    private fun fallbackName(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val decoded = id?.let(Uri::decode).orEmpty()
        return decoded.substringAfterLast('/').substringAfterLast(':').takeIf(String::isNotBlank)
            ?: "Selected cloud folder"
    }
}
