package com.voicegrowth.app.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Never delete originals on the strength of a provider-local read-back alone. */
class CleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = Result.success()

    companion object {
        const val WORK_NAME = "VoiceGrowth_Cleanup"
    }
}
