package com.voicegrowth.app.sync

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DriveUploadResult(
    val fileId: String,
    val webViewLink: String?,
    val folderPath: String
)

class DriveSyncService(private val context: Context) {
    private val yearFormat = SimpleDateFormat("yyyy", Locale.US)
    private val monthFormat = SimpleDateFormat("MM-MMM", Locale.US)

    suspend fun uploadFile(
        account: GoogleSignInAccount,
        localFile: java.io.File,
        mimeType: String,
        recordedAtMillis: Long,
        baseHierarchy: String = "VoiceGrowth/Transcripts",
        description: String = "VoiceGrowth file"
    ): Result<DriveUploadResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(localFile.exists() && localFile.length() > 0) { "Local upload file is missing or empty" }
            val driveService = buildDriveService(account)
            val year = yearFormat.format(Date(recordedAtMillis))
            val month = monthFormat.format(Date(recordedAtMillis))
            val segments = sanitizeHierarchy(baseHierarchy) + listOf(year, month)

            var parentId = "root"
            for (segment in segments) parentId = getOrCreateFolder(driveService, segment, parentId)

            val metadata = File().apply {
                name = localFile.name
                parents = listOf(parentId)
                this.description = description
            }
            val uploaded = driveService.files()
                .create(metadata, FileContent(mimeType, localFile))
                .setFields("id, webViewLink, parents")
                .execute()

            DriveUploadResult(uploaded.id, uploaded.webViewLink, segments.joinToString("/"))
        }
    }

    private fun buildDriveService(account: GoogleSignInAccount): Drive {
        val selectedAccount = account.account ?: error("Google account is unavailable")
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DRIVE_FILE_SCOPE)
        ).setSelectedAccount(selectedAccount)

        return Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("VoiceGrowth").build()
    }

    private fun sanitizeHierarchy(path: String): List<String> {
        val segments = path.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { it.take(80) }
        return if (segments.isEmpty()) listOf("VoiceGrowth", "Transcripts") else segments
    }

    private fun getOrCreateFolder(drive: Drive, folderName: String, parentId: String): String {
        val safeName = folderName.replace("\\", "\\\\").replace("'", "\\'")
        val safeParent = parentId.replace("'", "\\'")
        val query = "mimeType = 'application/vnd.google-apps.folder' and name = '$safeName' and '$safeParent' in parents and trashed = false"
        val existing = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()
            .files
            ?.firstOrNull()
        if (existing != null) return existing.id

        val folder = File().apply {
            name = folderName
            mimeType = "application/vnd.google-apps.folder"
            parents = listOf(parentId)
        }
        return drive.files().create(folder).setFields("id").execute().id
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
