package com.voicegrowth.app.integration

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Explicit, user-initiated handoff only.
 *
 * Google AI Edge Gallery exposes a VIEW deep link to tasks, but not a public
 * transcription service, result callback, or cross-app model-file provider.
 * Never attach clinical source audio or assume a result was returned.
 */
object GalleryHandoff {
    const val GALLERY_PACKAGE = "com.google.ai.edge.gallery"
    const val AUDIO_SCRIBE_DEEP_LINK = "com.google.ai.edge.gallery://llm_ask_audio"
    const val MODEL_MANAGER_DEEP_LINK = "com.google.ai.edge.gallery://global_model_manager"
    const val BENCHMARK_PACKAGE = "com.voicegrowth.app.gemma"
    const val BENCHMARK_ACTIVITY =
        "com.voicegrowth.app.gemmabench.GemmaBenchmarkActivity"

    fun openAudioScribe(context: Context): Boolean = openGallery(context, AUDIO_SCRIBE_DEEP_LINK)

    fun openGalleryModels(context: Context): Boolean =
        openGallery(context, MODEL_MANAGER_DEEP_LINK)

    fun openVoiceGrowthBenchmark(context: Context): Boolean = try {
        val intent = Intent().setClassName(BENCHMARK_PACKAGE, BENCHMARK_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun openGallery(context: Context, deepLink: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(GALLERY_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Only the expected package may handle the deep link; no audio or IDs sent.
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
