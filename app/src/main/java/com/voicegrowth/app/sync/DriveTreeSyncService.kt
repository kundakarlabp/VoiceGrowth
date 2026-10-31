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

    // Reuse a resolved provider directory across uploads in this process. Some cloud
    // DocumentsProviders delay making freshly created children visible to listFiles().
    private val directoryCache = mutableMapOf<String, DocumentFile>()

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
            val supportedRoot = DriveDestinationPolicy.isSupportedRoot(name)
            val usable = readable && writable && read && write && supportedRoot
            val message = when {
                !read || !write -> "Persistent read/write access was not retained. Choose the folder again and tap Use this folder / Allow."
                !supportedRoot -> "Select the existing VoiceGrowth/Audio folder in Google Drive, or its immediate VoiceGrowth parent. Do not select My Drive or Transcripts."
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
        remoteFileName: String
    ): Result<DriveUploadResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(localFile.exists() && localFile.length() > 0L) { "Local upload file is missing or empty" }
            val status = inspect(treeUri)
            require(status.usable) { status.message }
            val root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)) { "Selected cloud folder is unavailable" }

            val year = yearFormat.format(Date(recordedAtMillis))
            val month = monthFormat.format(Date(recordedAtMillis))
            val segments = DriveDestinationPolicy.relativeSegments(status.displayName, listOf("VoiceGrowth", "Audio")) + listOf(year, month)
            var parent = root
            for (segment in segments) parent = getOrCreateDirectory(parent, segment)

            require(remoteFileName.isNotBlank() && !remoteFileName.contains('/') && !remoteFileName.contains('\\')) {
                "Unsafe recording filename"
            }
            // Stable source-identity filename: retries update one object, not a new random cache name.

            // Match the immutable source bytes before deciding whether to create/write.
            // Retried uploads must never overwrite unrelated audio with a colliding name.
            val localHash = sha256(localFile.inputStream())
            val sameName = parent.listFiles()
                .filter { it.isFile && it.name == remoteFileName }
                .sortedBy { it.uri.toString() }

            val emptyHash = sha256(java.io.ByteArrayInputStream(byteArrayOf()))
            var hasDifferentNonemptyAudio = false
            for (candidate in sameName) {
                val existingHash = context.contentResolver.openInputStream(candidate.uri)
                    ?.let(::sha256) ?: error("Cannot inspect existing Drive-provider audio")
                if (!existingHash.contentEquals(emptyHash)) hasDifferentNonemptyAudio = true
                if (localHash.contentEquals(existingHash)) {
                    return@runCatching DriveUploadResult(
                        fileId = candidate.uri.toString(),
                        webViewLink = null,
                        folderPath = archivePath(status.displayName, segments)
                    )
                }
            }
            // A zero-byte object from an interrupted create can be safely resumed.
            // A non-empty different object is NEVER overwritten.
            require(!hasDifferentNonemptyAudio) {
                "Filename collision in selected Drive folder; existing recording was preserved"
            }
            require(sameName.size <= 1) {
                "Multiple partial files have the same name; resolve duplicates in Drive"
            }
            val target = sameName.firstOrNull()
                ?: parent.createFile(mimeType, remoteFileName)
                ?: error("Cloud provider could not create ${remoteFileName}")

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
            // This checks the bytes exposed by Android's provider, not remote Google
            // Drive synchronization. Never treat a content:// URI as a Google file ID.
            val readBack = context.contentResolver.openInputStream(target.uri)
                ?: error("Drive provider did not allow read-back of saved recording")
            require(localHash.contentEquals(sha256(readBack))) {
                "Drive provider read-back did not match original audio; will retry"
            }

            DriveUploadResult(
                fileId = target.uri.toString(),
                webViewLink = null,
                folderPath = archivePath(status.displayName, segments)
            )
        }
    }


    private fun archivePath(rootName: String, segments: List<String>): String =
        if (segments.isEmpty()) rootName else "$rootName/${segments.joinToString("/")}"

    private fun sha256(input: java.io.InputStream): ByteArray = input.use { stream ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest()
    }

    private fun getOrCreateDirectory(parent: DocumentFile, rawName: String): DocumentFile {
        val name = sanitizeSegment(rawName)
        val key = "${parent.uri}/$name"
        // A just-created cloud folder may temporarily report exists() == false.
        // Reuse its URI instead of creating a duplicate during provider index lag.
        directoryCache[key]?.let { return it }

        fun children() = parent.listFiles().filter { it.name.equals(name, ignoreCase = true) }
        var matches = children()
        // A provider may take a moment to list a directory created by an earlier worker.
        if (matches.isEmpty()) {
            Thread.sleep(300)
            matches = children()
        }
        require(matches.all { it.isDirectory }) {
            "A file named '$name' blocks creation of the Drive folder hierarchy"
        }
        // A duplicate sibling makes the target ambiguous. Never choose an arbitrary one.
        require(matches.size <= 1) {
            "Duplicate $name folders exist here. Select the existing VoiceGrowth/Audio folder directly."
        }
        val resolved = matches.firstOrNull()
            ?: parent.createDirectory(name)
            ?: error("Cloud provider could not create folder '$name'")
        directoryCache[key] = resolved
        return resolved
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
