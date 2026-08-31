package com.halfmeasures.slicegame

import android.content.Context
import android.media.AudioAttributes
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

    /**
     * Every effect is played with an explicit game usage.
     *
     * Without attributes the system files a vibration under USAGE_UNKNOWN, and an
     * unknown-usage vibration is the first thing dropped when the device is in a
     * state that suppresses haptics. That is the likeliest reason a buzz went
     * missing here and there while its neighbours worked.
     */
    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

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

    /**
     * The full celebration: three rising knocks that run straight into a long hard
     * hold. A perfect cut is the rarest thing a player does, so it gets the only
     * sustained buzz in the game rather than another pattern of taps.
     */
    fun perfect(strength: Float) {
        waveform(
            // Held for 170ms rather than 260: two perfects in a row can land
            // inside half a second, and an effect that outlasts the gap eats the
            // one that should follow it.
            timings = longArrayOf(0, 30, 18, 44, 18, 70, 0, 170, 70),
            amplitudes = intArrayOf(
                0, scale(180, strength),
                0, scale(220, strength),
                0, scale(255, strength),
                0, scale(255, strength),
                // Tailing off rather than stopping dead, so it releases rather
                // than cuts out.
                scale(120, strength)
            )
        )
    }

    /**
     * Low health: a heartbeat rather than a hit. Six pulses over about a second and
     * a half, quickening and hardening as they go, so the warning is felt as
     * something closing in rather than as one more buzz.
     */
    fun lowHealth(strength: Float) {
        waveform(
            timings = longArrayOf(
                0, 70, 190,
                0, 70, 170,
                0, 75, 150,
                0, 80, 130,
                0, 90, 110,
                0, 110
            ),
            amplitudes = intArrayOf(
                0, scale(150, strength), 0,
                0, scale(170, strength), 0,
                0, scale(195, strength), 0,
                0, scale(215, strength), 0,
                0, scale(235, strength), 0,
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
                play(v, VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
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
                play(v, VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, -1)
            }
        } catch (e: Exception) {
            // Ignore - haptics are a nicety, not a requirement.
        }
    }

    /**
     * Stops whatever is playing before starting the next effect.
     *
     * A new vibration is supposed to replace the one in progress, but in practice
     * a request that arrives while the motor is still running its previous pattern
     * can be swallowed - which is what a second perfect landing inside the first
     * one's tail looks like. Cancelling first makes the hand-off explicit.
     */
    private fun play(v: Vibrator, effect: VibrationEffect) {
        v.cancel()
        v.vibrate(effect, attributes)
    }
}
