package com.voicegrowth.app.integration

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryHandoffTest {
    @Test fun routesToDocumentedAudioScribe() {
        assertEquals("com.google.ai.edge.gallery", GalleryHandoff.GALLERY_PACKAGE)
        assertEquals("com.google.ai.edge.gallery://llm_ask_audio", GalleryHandoff.AUDIO_SCRIBE_DEEP_LINK)
        assertEquals("com.google.ai.edge.gallery://global_model_manager", GalleryHandoff.MODEL_MANAGER_DEEP_LINK)
    }
    @Test fun keepsBenchmarkPackageSeparate() {
        assertEquals("com.voicegrowth.app.gemma", GalleryHandoff.BENCHMARK_PACKAGE)
    }
}
