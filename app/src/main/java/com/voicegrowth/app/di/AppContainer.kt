package com.voicegrowth.app.di

import android.content.Context
import com.voicegrowth.app.data.local.AppDatabase
import com.voicegrowth.app.data.preferences.SettingsDataStore
import com.voicegrowth.app.data.repository.RecordingRepository

class AppContainer(context: Context) {
    val database = AppDatabase.getInstance(context)
    val settingsDataStore = SettingsDataStore(context)
    val recordingRepository = RecordingRepository(database.recordingDao(), settingsDataStore)
}
