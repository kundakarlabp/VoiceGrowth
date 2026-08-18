package com.voicegrowth.app.data.preferences

data class AppSettings(
    val autoProcessing: Boolean = true,
    val wifiOnly: Boolean = false,
    val onlyProcessOver30Sec: Boolean = true,
    val uploadAudio: Boolean = false,
    val uploadTranscript: Boolean = true,
    val deleteSourceAudioEnabled: Boolean = false,
    val deleteLocalAudioDays: Int = 7,
    val transcriptionLanguage: String = "auto",
    val driveFolderHierarchy: String = "VoiceGrowth/Transcripts",
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
