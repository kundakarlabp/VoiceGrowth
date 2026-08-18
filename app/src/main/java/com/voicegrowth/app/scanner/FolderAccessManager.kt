package com.voicegrowth.app.scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile


data class FolderAccessStatus(
    val accessible: Boolean,
    val persistedReadPermission: Boolean,
    val displayName: String,
    val audioFileCount: Int,
    val visitedDirectoryCount: Int,
    val message: String
)

/** Small, bounded SAF diagnostics used before persisting a call-recording folder. */
object FolderAccessManager {
    private const val MAX_DEPTH = 6
    private const val MAX_NODES = 4_000

    fun persistReadPermission(context: Context, uri: Uri): Result<Unit> = runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun hasPersistedReadPermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }

    fun inspect(context: Context, uri: Uri): FolderAccessStatus {
        val persisted = hasPersistedReadPermission(context, uri)
        return try {
            val root = DocumentFile.fromTreeUri(context, uri)
                ?: return FolderAccessStatus(false, persisted, fallbackName(uri), 0, 0, "Android could not open this folder")
            val name = root.name?.takeIf(String::isNotBlank) ?: fallbackName(uri)
            if (!root.exists() || !root.isDirectory || !root.canRead()) {
                return FolderAccessStatus(false, persisted, name, 0, 0, "Folder is not readable. Re-select it and grant access.")
            }

            var audioFiles = 0
            var directories = 0
            var nodes = 0
            val visited = HashSet<String>()

            fun walk(directory: DocumentFile, depth: Int) {
                if (depth > MAX_DEPTH || nodes >= MAX_NODES) return
                val key = directory.uri.toString()
                if (!visited.add(key)) return
                directories++
                val children = directory.listFiles()
                for (child in children) {
                    if (nodes++ >= MAX_NODES) break
                    when {
                        child.isDirectory -> walk(child, depth + 1)
                        child.isFile && isSupportedAudio(child.name.orEmpty()) -> audioFiles++
                    }
                }
            }

            walk(root, 0)
            val message = when {
                !persisted -> "Folder is readable now, but persistent read access was not retained. Re-select it."
                audioFiles > 0 -> "Folder access is healthy. Found $audioFiles audio file(s), including subfolders."
                else -> "Folder access is healthy, but no supported audio files are currently visible."
            }
            FolderAccessStatus(true, persisted, name, audioFiles, directories, message)
        } catch (error: SecurityException) {
            FolderAccessStatus(false, persisted, fallbackName(uri), 0, 0, "Folder permission was lost. Re-select the call-recording folder.")
        } catch (error: Exception) {
            FolderAccessStatus(
                false,
                persisted,
                fallbackName(uri),
                0,
                0,
                "Folder check failed: ${(error.message ?: error::class.java.simpleName).take(160)}"
            )
        }
    }

    fun isSupportedAudio(name: String): Boolean {
        val lower = name.lowercase()
        return SUPPORTED_EXTENSIONS.any(lower::endsWith)
    }

    private fun fallbackName(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val decoded = id?.let(Uri::decode).orEmpty()
        return decoded.substringAfterLast('/').substringAfterLast(':').takeIf(String::isNotBlank)
            ?: "Call recordings"
    }

    private val SUPPORTED_EXTENSIONS = listOf(
        ".m4a", ".mp3", ".wav", ".aac", ".3gp", ".amr", ".ogg", ".opus", ".mp4"
    )
}
