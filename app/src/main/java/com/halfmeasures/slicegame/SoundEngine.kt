package com.halfmeasures.slicegame

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/** Every noise the game makes. */
enum class Sfx {
    BUTTON,
    SLICE,
    GOOD,
    GREAT,
    PERFECT,
    BAD,
    MISS,
    HEAL,
    LEVEL_UP,
    COUNTDOWN,
    GAME_OVER,
    BEST
}

/**
 * Chiptune sound effects, synthesised on the device rather than shipped as files.
 *
 * Nothing here is a recording: each effect is a handful of square, triangle, saw
 * and LFSR-noise voices with pitch sweeps and hard envelopes, deliberately
 * sample-and-hold crushed so it sounds like a sound chip rather than a synth. That
 * keeps the download at zero bytes of audio and means a tone can be retuned by
 * changing a number instead of sourcing a new asset.
 *
 * The rendered waves are written out as small WAV files on first run and handed to
 * a SoundPool, which is what gives overlapping playback, per-shot volume and rate
 * for free.
 */
class SoundEngine(context: Context) {

    private val appContext = context.applicationContext
    private val pool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = HashMap<Sfx, Int>()
    @Volatile private var loaded = false
    private var preparing = false

    var enabled = true
    var volume = 1f

    /**
     * Renders and loads every effect off the main thread. Cheap - a dozen short
     * mono waves - but not so cheap it belongs in a frame.
     */
    fun prepare() {
        if (loaded || preparing) return
        preparing = true
        Thread {
            try {
                val dir = File(appContext.cacheDir, "sfx").apply { mkdirs() }
                for (sfx in Sfx.values()) {
                    val file = File(dir, "${sfx.name.lowercase()}.wav")
                    // Rendered once and left in the cache; a cleared cache just
                    // means one more render.
                    if (!file.exists()) writeWav(file, render(sfx))
                    ids[sfx] = pool.load(file.absolutePath, 1)
                }
                loaded = true
            } catch (e: Exception) {
                // A device that will not give us audio is not a reason to take the
                // game down with it - play() simply becomes a no-op.
                loaded = false
            } finally {
                preparing = false
            }
        }.start()
    }

    /**
     * [rate] shifts the pitch, which is how one rendered wave covers a range of
     * moments - a cut that lands better is simply played higher.
     */
    fun play(sfx: Sfx, gain: Float = 1f, rate: Float = 1f) {
        if (!enabled || !loaded) return
        val id = ids[sfx] ?: return
        val v = (volume * gain).coerceIn(0f, 1f)
        if (v <= 0.001f) return
        pool.play(id, v, v, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun release() {
        pool.release()
        ids.clear()
        loaded = false
    }

    // -----------------------------------------------------------------
    // Synthesis
    // -----------------------------------------------------------------

    private fun render(sfx: Sfx): ShortArray = when (sfx) {
        // A dry tick. Short enough to fire on every tap without wearing thin.
        Sfx.BUTTON -> buffer(70) { buf ->
            tone(buf, 0, 55, 880f, 1180f, WAVE_SQUARE, 0.5f, duty = 0.25f, crush = 3)
        }

        // Blade noise with a falling body under it, so a cut reads as a cut.
        Sfx.SLICE -> buffer(110) { buf ->
            tone(buf, 0, 85, 0f, 0f, WAVE_NOISE, 0.34f, attackMs = 1, crush = 2)
            tone(buf, 0, 55, 1500f, 380f, WAVE_SAW, 0.28f, attackMs = 1, crush = 2)
        }

        // The three cut grades are one shape at three heights: a flat blip, a
        // two-note lift, a three-note flourish.
        Sfx.GOOD -> buffer(110) { buf ->
            tone(buf, 0, 90, 587f, 587f, WAVE_SQUARE, 0.42f, duty = 0.5f, crush = 3)
        }

        Sfx.GREAT -> buffer(180) { buf ->
            tone(buf, 0, 80, 659f, 659f, WAVE_SQUARE, 0.42f, duty = 0.5f, crush = 3)
            tone(buf, 70, 100, 988f, 988f, WAVE_SQUARE, 0.44f, duty = 0.5f, crush = 3)
        }

        Sfx.PERFECT -> buffer(320) { buf ->
            tone(buf, 0, 80, 784f, 784f, WAVE_SQUARE, 0.42f, duty = 0.5f, crush = 3)
            tone(buf, 65, 80, 1046f, 1046f, WAVE_SQUARE, 0.44f, duty = 0.5f, crush = 3)
            tone(buf, 130, 170, 1568f, 1568f, WAVE_SQUARE, 0.46f, duty = 0.25f, crush = 3)
            // A triangle shimmer over the last note, so a perfect glitters.
            tone(buf, 140, 160, 2093f, 3136f, WAVE_TRIANGLE, 0.20f, attackMs = 6)
        }

        // Harsh and low. Deliberately the least pleasant thing in the game.
        Sfx.BAD -> buffer(220) { buf ->
            tone(buf, 0, 190, 233f, 117f, WAVE_SQUARE, 0.40f, duty = 0.125f, crush = 5)
        }

        Sfx.MISS -> buffer(320) { buf ->
            tone(buf, 0, 280, 440f, 70f, WAVE_SAW, 0.42f, duty = 0.5f, crush = 4)
            tone(buf, 0, 120, 0f, 0f, WAVE_NOISE, 0.14f, attackMs = 2, crush = 3)
        }

        Sfx.HEAL -> buffer(240) { buf ->
            tone(buf, 0, 110, 880f, 1320f, WAVE_TRIANGLE, 0.34f, attackMs = 4)
            tone(buf, 80, 140, 1320f, 2093f, WAVE_TRIANGLE, 0.30f, attackMs = 4)
        }

        Sfx.LEVEL_UP -> buffer(340) { buf ->
            val notes = floatArrayOf(523f, 659f, 784f, 1046f)
            notes.forEachIndexed { i, hz ->
                tone(buf, i * 65, 90, hz, hz, WAVE_SQUARE, 0.40f, duty = 0.5f, crush = 3)
            }
        }

        Sfx.COUNTDOWN -> buffer(120) { buf ->
            tone(buf, 0, 95, 784f, 784f, WAVE_SQUARE, 0.44f, duty = 0.5f, crush = 3)
        }

        // Four notes walking down a minor chord. The run is over and it should
        // sound like it.
        Sfx.GAME_OVER -> buffer(720) { buf ->
            val notes = floatArrayOf(523f, 440f, 349f, 262f)
            notes.forEachIndexed { i, hz ->
                val last = i == notes.size - 1
                tone(
                    buf, i * 140, if (last) 300 else 150, hz, if (last) hz * 0.98f else hz,
                    WAVE_SQUARE, 0.40f, duty = if (last) 0.125f else 0.25f, crush = 4
                )
            }
        }

        // The only fanfare in the game, saved for beating your own record.
        Sfx.BEST -> buffer(900) { buf ->
            tone(buf, 0, 90, 784f, 784f, WAVE_SQUARE, 0.38f, duty = 0.5f, crush = 3)
            tone(buf, 100, 90, 784f, 784f, WAVE_SQUARE, 0.38f, duty = 0.5f, crush = 3)
            tone(buf, 200, 120, 1046f, 1046f, WAVE_SQUARE, 0.40f, duty = 0.5f, crush = 3)
            tone(buf, 330, 420, 1568f, 1568f, WAVE_SQUARE, 0.42f, duty = 0.25f, crush = 3)
            tone(buf, 340, 400, 2093f, 2093f, WAVE_TRIANGLE, 0.18f, attackMs = 10)
        }
    }

    private inline fun buffer(durationMs: Int, build: (FloatArray) -> Unit): ShortArray {
        val buf = FloatArray(samplesFor(durationMs))
        build(buf)
        return finish(buf)
    }

    /**
     * One voice. Frequency sweeps exponentially from [fromHz] to [toHz] - linear
     * sweeps sound wrong because pitch is logarithmic - under a linear attack and
     * a curved decay to silence. [crush] holds each computed sample for that many
     * samples, which is the sample-and-hold that gives the whole set its cheap,
     * eight-bit character.
     */
    private fun tone(
        buf: FloatArray,
        startMs: Int,
        durationMs: Int,
        fromHz: Float,
        toHz: Float,
        wave: Int,
        gain: Float,
        attackMs: Int = 3,
        duty: Float = 0.5f,
        crush: Int = 1
    ) {
        val start = samplesFor(startMs)
        val length = samplesFor(durationMs)
        if (start >= buf.size || length <= 0) return

        val attack = samplesFor(attackMs).coerceAtLeast(1)
        var phase = 0f
        var noise = 0x7FFF
        var held = 0f

        for (i in 0 until length) {
            val index = start + i
            if (index >= buf.size) break

            val progress = i.toFloat() / length
            val envelope = if (i < attack) {
                i.toFloat() / attack
            } else {
                val decay = (i - attack).toFloat() / (length - attack).coerceAtLeast(1)
                (1f - decay).pow(1.6f)
            }

            if (crush <= 1 || i % crush == 0) {
                held = when (wave) {
                    WAVE_NOISE -> {
                        // Linear-feedback shift register: the noise a sound chip
                        // makes, rather than white noise from a random number.
                        val bit = (noise xor (noise shr 1)) and 1
                        noise = (noise shr 1) or (bit shl 14)
                        if (noise and 1 == 1) 1f else -1f
                    }
                    WAVE_SQUARE -> if (phase % 1f < duty) 1f else -1f
                    WAVE_TRIANGLE -> {
                        val p = phase % 1f
                        if (p < 0.5f) 4f * p - 1f else 3f - 4f * p
                    }
                    else -> 2f * (phase % 1f) - 1f
                }
            }

            buf[index] += held * envelope * gain

            if (wave != WAVE_NOISE) {
                val hz = fromHz * (toHz / fromHz).pow(progress)
                phase += hz / RATE
            }
        }
    }

    /** Soft-limits rather than hard-clipping, so stacked voices do not crackle. */
    private fun finish(buf: FloatArray): ShortArray {
        val out = ShortArray(buf.size)
        for (i in buf.indices) {
            val x = buf[i].coerceIn(-1.4f, 1.4f)
            val limited = x - (x * x * x) / 6f
            out[i] = (limited * 26000f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        // A short fade at the tail: cutting a wave mid-cycle is an audible click.
        val fade = samplesFor(4).coerceAtMost(out.size)
        for (i in 0 until fade) {
            val k = 1f - i.toFloat() / fade
            out[out.size - 1 - i] = (out[out.size - 1 - i] * k).roundToInt().toShort()
        }
        return out
    }

    private fun samplesFor(ms: Int): Int = RATE * ms / 1000

    private fun writeWav(file: File, samples: ShortArray) {
        val dataSize = samples.size * 2
        val out = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray())
        out.putInt(36 + dataSize)
        out.put("WAVE".toByteArray())
        out.put("fmt ".toByteArray())
        out.putInt(16)          // subchunk size
        out.putShort(1)         // PCM
        out.putShort(1)         // mono
        out.putInt(RATE)
        out.putInt(RATE * 2)    // byte rate
        out.putShort(2)         // block align
        out.putShort(16)        // bits per sample
        out.put("data".toByteArray())
        out.putInt(dataSize)
        for (s in samples) out.putShort(s)
        file.writeBytes(out.array())
    }

    private companion object {
        /** Plenty for square waves and noise, and half the size of 44.1k. */
        const val RATE = 22050

        const val WAVE_SQUARE = 0
        const val WAVE_TRIANGLE = 1
        const val WAVE_SAW = 2
        const val WAVE_NOISE = 3
    }
}
