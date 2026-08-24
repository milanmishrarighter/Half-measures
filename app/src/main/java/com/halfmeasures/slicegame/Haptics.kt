package com.halfmeasures.slicegame

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Thin wrapper over the vibrator so the game can express three distinct feelings:
 * a crisp tick for an ordinary cut, a punchier double-tap for a perfect one, and
 * a heavy rumble when the run ends.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        null
    }

    private val available: Boolean = vibrator?.hasVibrator() == true

    /** Ordinary cut - scales with how good the cut was, so a near-miss feels duller. */
    fun cut(strength: Float, quality: Float) {
        val q = quality.coerceIn(0f, 1f)
        val amplitude = (110 + 145 * q) * strength
        oneShot(durationMs = (18 + 22 * q).toLong(), amplitude = amplitude.toInt())
    }

    /** A cut inside the great window: a firm double knock, short of the perfect fanfare. */
    fun great(strength: Float) {
        waveform(
            timings = longArrayOf(0, 30, 34, 58),
            amplitudes = intArrayOf(0, scale(190, strength), 0, scale(235, strength))
        )
    }

    /** The full celebration - a rising triple hit. */
    fun perfect(strength: Float) {
        waveform(
            timings = longArrayOf(0, 34, 26, 52, 26, 95),
            amplitudes = intArrayOf(
                0, scale(170, strength),
                0, scale(220, strength),
                0, scale(255, strength)
            )
        )
    }

    fun gameOver(strength: Float) {
        waveform(
            timings = longArrayOf(0, 90, 60, 240),
            amplitudes = intArrayOf(0, scale(200, strength), 0, scale(255, strength))
        )
    }

    fun tick(strength: Float) {
        oneShot(durationMs = 10, amplitude = scale(120, strength))
    }

    private fun scale(base: Int, strength: Float): Int =
        (base * strength.coerceIn(0f, 1f)).toInt().coerceIn(1, 255)

    private fun oneShot(durationMs: Long, amplitude: Int) {
        if (!available || durationMs <= 0) return
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // A device that refuses to buzz should never take the game down with it.
        }
    }

    private fun waveform(timings: LongArray, amplitudes: IntArray) {
        if (!available) return
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, -1)
            }
        } catch (e: Exception) {
            // Ignore - haptics are a nicety, not a requirement.
        }
    }
}
