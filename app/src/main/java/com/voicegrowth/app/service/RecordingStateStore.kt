package com.voicegrowth.app.service

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService

object RecordingStateStore {
    private const val PREFS = "voicegrowth_recording_state"
    private const val KEY_RECORDING = "recording"

    fun isRecording(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RECORDING, false)

    fun setRecording(context: Context, recording: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RECORDING, recording)
            .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, QuickCaptureTileService::class.java)
                )
            }
        }
    }
}
