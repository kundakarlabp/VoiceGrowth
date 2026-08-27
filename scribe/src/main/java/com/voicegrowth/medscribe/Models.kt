package com.voicegrowth.medscribe

import org.json.JSONArray
import org.json.JSONObject

enum class ItemStatus {
    RECORDED,
    PROCESSING,
    READY,
    NEEDS_MODEL,
    FAILED
}

data class ScribeItem(
    val id: String,
    val title: String,
    val audioPath: String,
    val recordedAt: Long,
    val durationSeconds: Long,
    val status: ItemStatus = ItemStatus.RECORDED,
    val transcriptPath: String? = null,
    val language: String = "auto",
    val speakerCount: Int = 0,
    val errorMessage: String? = null,
    val driveSyncedAt: Long? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("audioPath", audioPath)
        .put("recordedAt", recordedAt)
        .put("durationSeconds", durationSeconds)
        .put("status", status.name)
        .put("transcriptPath", transcriptPath)
        .put("language", language)
        .put("speakerCount", speakerCount)
        .put("errorMessage", errorMessage)
        .put("driveSyncedAt", driveSyncedAt)

    companion object {
        fun fromJson(o: JSONObject): ScribeItem = ScribeItem(
            id = o.getString("id"),
            title = o.optString("title", "Recording"),
            audioPath = o.getString("audioPath"),
            recordedAt = o.optLong("recordedAt", System.currentTimeMillis()),
            durationSeconds = o.optLong("durationSeconds", 0L),
            status = runCatching { ItemStatus.valueOf(o.optString("status", ItemStatus.RECORDED.name)) }
                .getOrDefault(ItemStatus.RECORDED),
            transcriptPath = o.optString("transcriptPath").takeIf { it.isNotBlank() && it != "null" },
            language = o.optString("language", "auto"),
            speakerCount = o.optInt("speakerCount", 0),
            errorMessage = o.optString("errorMessage").takeIf { it.isNotBlank() && it != "null" },
            driveSyncedAt = if (o.has("driveSyncedAt") && !o.isNull("driveSyncedAt")) o.optLong("driveSyncedAt") else null
        )
    }
}

data class ScribeSettings(
    val language: String = "auto",
    val whisperModel: String = "base",
    val diarizationEnabled: Boolean = true,
    val voiceRecognitionEnabled: Boolean = true,
    val autoTranscribe: Boolean = true,
    val autoSync: Boolean = true,
    val uploadAudio: Boolean = false,
    val driveFolderUri: String? = null,
    val driveFolderName: String? = null
)

data class ModelProgress(
    val label: String,
    val currentFile: String,
    val downloadedBytes: Long,
    val expectedBytes: Long
) {
    val percent: Int
        get() = if (expectedBytes <= 0L) 0
        else ((downloadedBytes * 100L) / expectedBytes).toInt().coerceIn(0, 100)
}

data class UiState(
    val items: List<ScribeItem> = emptyList(),
    val settings: ScribeSettings = ScribeSettings(),
    val isRecording: Boolean = false,
    val recordingStartedAt: Long = 0L,
    val modelInstalled: Boolean = false,
    val diarizationInstalled: Boolean = false,
    val voiceProfiles: List<VoiceProfile> = emptyList(),
    val modelProgress: ModelProgress? = null,
    val busyMessage: String? = null,
    val message: String? = null,
    val searchQuery: String = ""
)

internal fun itemsToJson(items: List<ScribeItem>): String {
    val a = JSONArray()
    items.forEach { a.put(it.toJson()) }
    return a.toString()
}

internal fun itemsFromJson(raw: String?): List<ScribeItem> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val a = JSONArray(raw)
        buildList {
            for (i in 0 until a.length()) add(ScribeItem.fromJson(a.getJSONObject(i)))
        }
    }.getOrDefault(emptyList())
}
