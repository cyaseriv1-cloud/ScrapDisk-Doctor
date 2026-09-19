package com.scrapdisk.doctor

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class HapticFeedbackHelper(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun vibrateSuccess() {
        vibratePattern(longArrayOf(0, 90, 80, 110), intArrayOf(0, 200, 0, 255))
    }

    fun vibrateWarning() {
        vibratePattern(longArrayOf(0, 150, 100, 150, 100, 150), intArrayOf(0, 180, 0, 180, 0, 180))
    }

    fun vibrateFailure() {
        vibratePattern(longArrayOf(0, 600), intArrayOf(0, 255))
    }

    private fun vibratePattern(timings: LongArray, amplitudes: IntArray) {
        if (vibrator == null || !vibrator.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (_: Exception) {}
    }
}
