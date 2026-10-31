package com.voicegrowth.app.data.repository

import com.voicegrowth.app.data.local.dao.RecordingDao
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.preferences.AppSettings
import com.voicegrowth.app.data.preferences.SettingsDataStore
import kotlinx.coroutines.flow.Flow

class RecordingRepository(
    private val dao: RecordingDao,
    private val settingsDataStore: SettingsDataStore
) {
    val recordingsFlow: Flow<List<RecordingEntity>> = dao.getAllRecordingsFlow()
    val settingsFlow: Flow<AppSettings> = settingsDataStore.settingsFlow

    suspend fun insertRecording(recording: RecordingEntity): Long = dao.insert(recording)
    suspend fun getByUri(uri: String): RecordingEntity? = dao.getByUri(uri)
    suspend fun getById(id: Long): RecordingEntity? = dao.getById(id)
    suspend fun getRecordingsBetween(startMillis: Long, endMillis: Long): List<RecordingEntity> =
        dao.getRecordingsBetween(startMillis, endMillis)
    suspend fun getPendingRecordings(): List<RecordingEntity> = dao.getPendingRecordings()
    suspend fun getSyncCandidates(): List<RecordingEntity> = dao.getSyncCandidates()
    suspend fun getCompletedOlderThan(timestamp: Long): List<RecordingEntity> = dao.getCompletedOlderThan(timestamp)

    suspend fun updateStatus(id: Long, status: ProcessingStatus, error: String? = null) =
        dao.updateStatus(id, status, error)

    suspend fun updateStatusResetRetry(id: Long, status: ProcessingStatus, error: String? = null) =
        dao.updateStatusResetRetry(id, status, error)

    suspend fun updateAudioUpload(id: Long, driveAudioFileId: String) =
        dao.updateAudioUpload(id, driveAudioFileId)

    suspend fun recordRetry(id: Long, status: ProcessingStatus, error: String?) =
        dao.recordRetry(id, status, error)

    suspend fun deleteRecording(id: Long) = dao.deleteById(id)
}
