package com.voicegrowth.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.model.RecordingSource

@Entity(
    tableName = "recordings",
    indices = [Index(value = ["uriString"], unique = true)]
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uriString: String,
    val fileName: String,
    val filePath: String,
    val source: RecordingSource,
    val durationSeconds: Long,
    val fileSizeBytes: Long,
    val recordedAt: Long,
    val status: ProcessingStatus = ProcessingStatus.PENDING,
    val transcriptPath: String? = null,
    val driveFileId: String? = null,
    val driveAudioFileId: String? = null,
    val driveWebViewLink: String? = null,
    val errorMessage: String? = null,
    val detectedThemes: String = "",
    val retryCount: Int = 0,
    val processedAt: Long? = null
)
