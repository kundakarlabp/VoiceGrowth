package com.voicegrowth.app.data.preferences

data class AppSettings(
    val autoProcessing: Boolean = true,
    val wifiOnly: Boolean = false,
    val onlyProcessOver30Sec: Boolean = true,
    val uploadAudio: Boolean = false,
    val uploadTranscript: Boolean = true,
    val deleteLocalAudioDays: Int = 7,
    val transcriptionLanguage: String = "auto",
    val driveFolderHierarchy: String = "VoiceGrowth/Transcripts",
    val clinicalPrivacyMode: Boolean = true,
    val selectedFolderUri: String? = null,
    val selectedFolderDisplayName: String? = null,
    val googleAccountEmail: String? = null
)
