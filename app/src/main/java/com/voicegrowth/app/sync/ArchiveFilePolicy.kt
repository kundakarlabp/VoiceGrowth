package com.voicegrowth.app.sync

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deterministic destination name keyed by the original local recording row.
 * A retry never changes this filename merely because an upload cache file was recreated.
 */
internal object ArchiveFilePolicy {
    fun stableName(
        id: Long,
        recordedAt: Long,
        source: String,
        originalName: String
    ): String {
        require(id > 0) { "An archived recording must have a persistent local row ID" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(recordedAt))
        val extension = originalName.substringAfterLast('.', "m4a")
            .lowercase(Locale.US)
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "m4a"
        val safeSource = source.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_]"), "_")
            .take(40)
        return "VG_${timestamp}_${id}_${safeSource}.${extension}"
    }
}
