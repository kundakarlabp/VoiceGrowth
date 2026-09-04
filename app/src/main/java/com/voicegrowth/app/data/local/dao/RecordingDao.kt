package com.voicegrowth.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(recording: RecordingEntity): Long

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE uriString = :uriString LIMIT 1")
    suspend fun getByUri(uriString: String): RecordingEntity?

    @Query("SELECT * FROM recordings ORDER BY recordedAt DESC")
    fun getAllRecordingsFlow(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE recordedAt >= :startMillis AND recordedAt < :endMillis AND transcriptPath IS NOT NULL ORDER BY recordedAt ASC")
    suspend fun getRecordingsBetween(startMillis: Long, endMillis: Long): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE status IN ('PENDING', 'TRANSCRIBING') ORDER BY recordedAt ASC")
    suspend fun getPendingRecordings(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE transcriptPath IS NULL AND status NOT IN ('SKIPPED_TOO_SHORT') ORDER BY recordedAt ASC LIMIT :limit")
    suspend fun getLocalTranscriptionCandidates(limit: Int = 3): List<RecordingEntity>

    // Include legacy processing states so an upgrade to v2 can upload recordings that were waiting
    // for local ASR in earlier versions.
    @Query("SELECT * FROM recordings WHERE status IN ('PENDING', 'TRANSCRIBING', 'LOCAL_READY', 'WAITING_FOR_SYNC', 'UPLOADED', 'FAILED') ORDER BY recordedAt ASC")
    suspend fun getSyncCandidates(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE status = 'UPLOADED' AND recordedAt < :olderThanTimestamp")
    suspend fun getCompletedOlderThan(olderThanTimestamp: Long): List<RecordingEntity>

    @Query("UPDATE recordings SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ProcessingStatus, error: String? = null)

    @Query("UPDATE recordings SET status = :status, errorMessage = :error, retryCount = 0 WHERE id = :id")
    suspend fun updateStatusResetRetry(id: Long, status: ProcessingStatus, error: String? = null)

    @Query("UPDATE recordings SET transcriptPath = :transcriptPath, detectedThemes = :themes, durationSeconds = :durationSeconds, status = :status, processedAt = :processedAt, errorMessage = NULL, retryCount = 0 WHERE id = :id")
    suspend fun updateTranscript(
        id: Long,
        transcriptPath: String,
        themes: String,
        durationSeconds: Long,
        status: ProcessingStatus,
        processedAt: Long
    )

    @Query("UPDATE recordings SET driveFileId = :driveFileId, driveWebViewLink = :driveWebViewLink WHERE id = :id")
    suspend fun updateTranscriptUpload(id: Long, driveFileId: String, driveWebViewLink: String?)

    @Query("UPDATE recordings SET driveAudioFileId = :driveAudioFileId WHERE id = :id")
    suspend fun updateAudioUpload(id: Long, driveAudioFileId: String)

    @Query("UPDATE recordings SET retryCount = retryCount + 1, status = :status, errorMessage = :error WHERE id = :id")
    suspend fun recordRetry(id: Long, status: ProcessingStatus, error: String?)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: Long)
}
