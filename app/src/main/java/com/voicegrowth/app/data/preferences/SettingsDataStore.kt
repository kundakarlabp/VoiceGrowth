package com.voicegrowth.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "voicegrowth_settings")

class SettingsDataStore(private val context: Context) {

    private object PreferencesKeys {
        val AUTO_PROCESSING = booleanPreferencesKey("auto_processing")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val ONLY_PROCESS_OVER_30_SEC = booleanPreferencesKey("only_process_over_30_sec")
        val UPLOAD_AUDIO = booleanPreferencesKey("upload_audio")
        val UPLOAD_TRANSCRIPT = booleanPreferencesKey("upload_transcript")
        val DELETE_SOURCE_AUDIO_ENABLED = booleanPreferencesKey("delete_source_audio_enabled")
        val DELETE_LOCAL_AUDIO_DAYS = intPreferencesKey("delete_local_audio_days")
        val TRANSCRIPTION_LANGUAGE = stringPreferencesKey("transcription_language")
        val DRIVE_FOLDER_HIERARCHY = stringPreferencesKey("drive_folder_hierarchy")
        val DRIVE_TREE_URI = stringPreferencesKey("drive_tree_uri")
        val DRIVE_TREE_DISPLAY_NAME = stringPreferencesKey("drive_tree_display_name")
        val CLINICAL_PRIVACY_MODE = booleanPreferencesKey("clinical_privacy_mode")
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val AI_MODEL_PATH = stringPreferencesKey("ai_model_path")
        val AI_MODEL_DISPLAY_NAME = stringPreferencesKey("ai_model_display_name")
        val AI_PREFERRED_BACKEND = stringPreferencesKey("ai_preferred_backend")
        val DAILY_DIGEST_ENABLED = booleanPreferencesKey("daily_digest_enabled")
        val SELECTED_FOLDER_URI = stringPreferencesKey("selected_folder_uri")
        val SELECTED_FOLDER_NAME = stringPreferencesKey("selected_folder_name")
        val GOOGLE_ACCOUNT_EMAIL = stringPreferencesKey("google_account_email")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data
        .catch { exception -> if (exception is IOException) emit(emptyPreferences()) else throw exception }
        .map { preferences ->
            AppSettings(
                autoProcessing = preferences[PreferencesKeys.AUTO_PROCESSING] ?: true,
                wifiOnly = preferences[PreferencesKeys.WIFI_ONLY] ?: false,
                onlyProcessOver30Sec = preferences[PreferencesKeys.ONLY_PROCESS_OVER_30_SEC] ?: true,
                uploadAudio = preferences[PreferencesKeys.UPLOAD_AUDIO] ?: false,
                uploadTranscript = preferences[PreferencesKeys.UPLOAD_TRANSCRIPT] ?: true,
                deleteSourceAudioEnabled = preferences[PreferencesKeys.DELETE_SOURCE_AUDIO_ENABLED] ?: false,
                deleteLocalAudioDays = preferences[PreferencesKeys.DELETE_LOCAL_AUDIO_DAYS] ?: 7,
                transcriptionLanguage = preferences[PreferencesKeys.TRANSCRIPTION_LANGUAGE] ?: "auto",
                driveFolderHierarchy = preferences[PreferencesKeys.DRIVE_FOLDER_HIERARCHY] ?: "VoiceGrowth/Transcripts",
                driveTreeUri = preferences[PreferencesKeys.DRIVE_TREE_URI],
                driveTreeDisplayName = preferences[PreferencesKeys.DRIVE_TREE_DISPLAY_NAME],
                clinicalPrivacyMode = preferences[PreferencesKeys.CLINICAL_PRIVACY_MODE] ?: true,
                aiEnabled = preferences[PreferencesKeys.AI_ENABLED] ?: false,
                aiModelPath = preferences[PreferencesKeys.AI_MODEL_PATH],
                aiModelDisplayName = preferences[PreferencesKeys.AI_MODEL_DISPLAY_NAME],
                aiPreferredBackend = preferences[PreferencesKeys.AI_PREFERRED_BACKEND] ?: "gpu",
                dailyDigestEnabled = preferences[PreferencesKeys.DAILY_DIGEST_ENABLED] ?: false,
                selectedFolderUri = preferences[PreferencesKeys.SELECTED_FOLDER_URI],
                selectedFolderDisplayName = preferences[PreferencesKeys.SELECTED_FOLDER_NAME],
                googleAccountEmail = preferences[PreferencesKeys.GOOGLE_ACCOUNT_EMAIL]
            )
        }

    suspend fun setAutoProcessing(enabled: Boolean) = update(PreferencesKeys.AUTO_PROCESSING, enabled)
    suspend fun setWifiOnly(enabled: Boolean) = update(PreferencesKeys.WIFI_ONLY, enabled)
    suspend fun setOnlyProcessOver30Sec(enabled: Boolean) = update(PreferencesKeys.ONLY_PROCESS_OVER_30_SEC, enabled)
    suspend fun setUploadAudio(enabled: Boolean) = update(PreferencesKeys.UPLOAD_AUDIO, enabled)
    suspend fun setUploadTranscript(enabled: Boolean) = update(PreferencesKeys.UPLOAD_TRANSCRIPT, enabled)
    suspend fun setDeleteSourceAudioEnabled(enabled: Boolean) = update(PreferencesKeys.DELETE_SOURCE_AUDIO_ENABLED, enabled)
    suspend fun setDeleteLocalAudioDays(days: Int) = update(PreferencesKeys.DELETE_LOCAL_AUDIO_DAYS, days)
    suspend fun setTranscriptionLanguage(lang: String) = update(PreferencesKeys.TRANSCRIPTION_LANGUAGE, lang)
    suspend fun setDriveFolderHierarchy(path: String) = update(PreferencesKeys.DRIVE_FOLDER_HIERARCHY, path)
    suspend fun setClinicalPrivacyMode(enabled: Boolean) = update(PreferencesKeys.CLINICAL_PRIVACY_MODE, enabled)
    suspend fun setAiEnabled(enabled: Boolean) = update(PreferencesKeys.AI_ENABLED, enabled)
    suspend fun setAiPreferredBackend(backend: String) = update(PreferencesKeys.AI_PREFERRED_BACKEND, backend)
    suspend fun setDailyDigestEnabled(enabled: Boolean) = update(PreferencesKeys.DAILY_DIGEST_ENABLED, enabled)

    suspend fun setAiModel(path: String?, displayName: String?) {
        context.dataStore.edit { prefs ->
            if (path.isNullOrBlank()) prefs.remove(PreferencesKeys.AI_MODEL_PATH) else prefs[PreferencesKeys.AI_MODEL_PATH] = path
            if (displayName.isNullOrBlank()) prefs.remove(PreferencesKeys.AI_MODEL_DISPLAY_NAME) else prefs[PreferencesKeys.AI_MODEL_DISPLAY_NAME] = displayName
        }
    }

    suspend fun setSelectedFolder(uri: String, name: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.SELECTED_FOLDER_URI] = uri
            prefs[PreferencesKeys.SELECTED_FOLDER_NAME] = name
        }
    }

    suspend fun setDriveTree(uri: String?, displayName: String?) {
        context.dataStore.edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(PreferencesKeys.DRIVE_TREE_URI) else prefs[PreferencesKeys.DRIVE_TREE_URI] = uri
            if (displayName.isNullOrBlank()) prefs.remove(PreferencesKeys.DRIVE_TREE_DISPLAY_NAME) else prefs[PreferencesKeys.DRIVE_TREE_DISPLAY_NAME] = displayName
        }
    }

    suspend fun setGoogleAccountEmail(email: String?) {
        context.dataStore.edit { prefs ->
            if (email != null) prefs[PreferencesKeys.GOOGLE_ACCOUNT_EMAIL] = email else prefs.remove(PreferencesKeys.GOOGLE_ACCOUNT_EMAIL)
        }
    }

    private suspend fun <T> update(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { preferences -> preferences[key] = value }
    }
}
