package com.scrapdisk.doctor

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SoundFeedbackHelper(context: Context) {

    private val prefs = context.getSharedPreferences("scrap_disk_settings", Context.MODE_PRIVATE)
    private var toneGenerator: ToneGenerator? = null

    var isSoundEnabled: Boolean
        get() = prefs.getBoolean("sound_enabled", true)
        set(value) = prefs.edit().putBoolean("sound_enabled", value).apply()

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (_: Exception) {}
    }

    fun playSuccess() {
        if (!isSoundEnabled) return
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Doble tono agudo alegre (80ms + pausa + 120ms)
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                delay(100)
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
            } catch (_: Exception) {}
        }
    }

    fun playWarning() {
        if (!isSoundEnabled) return
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Tono medio de advertencia
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 180)
            } catch (_: Exception) {}
        }
    }

    fun playFailure() {
        if (!isSoundEnabled) return
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Tono grave de error
                toneGenerator?.startTone(ToneGenerator.TONE_SUP_ERROR, 350)
            } catch (_: Exception) {}
        }
    }

    fun playClick() {
        if (!isSoundEnabled) return
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_PROMPT, 40)
        } catch (_: Exception) {}
    }

    fun release() {
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}
    }
}
