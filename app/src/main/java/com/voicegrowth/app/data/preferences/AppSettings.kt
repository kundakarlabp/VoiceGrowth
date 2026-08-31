package com.voicegrowth.app.data.preferences

data class AppSettings(
    // Periodic OEM call-folder scanning remains useful; consult recordings themselves always queue.
    val autoProcessing: Boolean = true,
    val wifiOnly: Boolean = false,

    // Legacy v1 fields are retained for preference/database compatibility but are no longer used by
    // the v2 capture pipeline.
    val onlyProcessOver30Sec: Boolean = false,
    val uploadAudio: Boolean = true,
    val uploadTranscript: Boolean = false,
    val deleteSourceAudioEnabled: Boolean = false,
    val deleteLocalAudioDays: Int = 7,
    val transcriptionLanguage: String = "auto",
    val driveFolderHierarchy: String = "VoiceGrowth/Audio",
    val driveTreeUri: String? = null,
    val driveTreeDisplayName: String? = null,
    val clinicalPrivacyMode: Boolean = true,
    val aiEnabled: Boolean = false,
    val aiModelPath: String? = null,
    val aiModelDisplayName: String? = null,
    val aiPreferredBackend: String = "gpu",
    val dailyDigestEnabled: Boolean = false,
    val selectedFolderUri: String? = null,
    val selectedFolderDisplayName: String? = null,
    val googleAccountEmail: String? = null
)
