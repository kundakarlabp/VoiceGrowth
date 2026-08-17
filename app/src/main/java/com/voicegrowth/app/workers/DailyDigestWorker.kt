package com.voicegrowth.app.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voicegrowth.app.VoiceGrowthApplication
import com.voicegrowth.app.engine.knowledge.DailyDigestGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class DailyDigestWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as VoiceGrowthApplication
        val settings = app.container.recordingRepository.settingsFlow.first()
        if (!settings.dailyDigestEnabled || !settings.aiEnabled || settings.aiModelPath.isNullOrBlank()) return Result.success()
        return try {
            DailyDigestGenerator.generate(applicationContext).getOrThrow()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "VoiceGrowth_DailyDigest"
    }
}
