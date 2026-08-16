package com.voicegrowth.app.data.model

enum class ProcessingStatus {
    PENDING,
    TRANSCRIBING,
    LOCAL_READY,
    WAITING_FOR_SYNC,
    UPLOADED,
    FAILED,
    SKIPPED_TOO_SHORT
}
