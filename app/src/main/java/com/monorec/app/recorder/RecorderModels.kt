package com.monorec.app.recorder

import kotlinx.coroutines.flow.MutableStateFlow

enum class AudioMode(val label: String, val caption: String) {
    NONE("Senyap", "Tanpa audio"),
    DEVICE("HP", "Audio internal saja"),
    DEVICE_MIC("HP + Mic", "Audio internal + mikrofon")
}

enum class QualityPreset(
    val label: String,
    val shortSide: Int,
    val fps: Int,
    val videoBitrate: Int
) {
    SD("540p", 540, 30, 3_000_000),
    HD("720p", 720, 30, 5_000_000),
    FHD("1080p", 1080, 30, 8_000_000)
}

data class RecorderConfig(
    val quality: QualityPreset,
    val audioMode: AudioMode
)

data class RecState(
    val recording: Boolean = false,
    val elapsedMs: Long = 0L,
    val bytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val videoBitrate: Int = 0,
    val audioMode: AudioMode = AudioMode.NONE,
    val savedCount: Int = 0,          // naik 1 setiap rekaman selesai, memicu refresh riwayat
    val error: String? = null
)

/** Status rekaman yang dibagi antara Service dan UI. */
object RecorderState {
    val state = MutableStateFlow(RecState())
}
