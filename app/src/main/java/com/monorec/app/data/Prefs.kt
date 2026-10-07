package com.monorec.app.data

import android.content.Context
import com.monorec.app.recorder.AudioMode
import com.monorec.app.recorder.QualityPreset

/** Menyimpan pilihan terakhir pengguna. */
object Prefs {
    private const val FILE = "monorec_prefs"
    private const val KEY_QUALITY = "quality"
    private const val KEY_AUDIO = "audio"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun quality(c: Context): QualityPreset =
        runCatching { QualityPreset.valueOf(sp(c).getString(KEY_QUALITY, "") ?: "") }
            .getOrDefault(QualityPreset.HD)

    fun audio(c: Context): AudioMode =
        runCatching { AudioMode.valueOf(sp(c).getString(KEY_AUDIO, "") ?: "") }
            .getOrDefault(AudioMode.DEVICE)

    fun save(c: Context, quality: QualityPreset, audio: AudioMode) {
        sp(c).edit()
            .putString(KEY_QUALITY, quality.name)
            .putString(KEY_AUDIO, audio.name)
            .apply()
    }
}
