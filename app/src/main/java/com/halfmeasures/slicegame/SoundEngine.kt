package com.halfmeasures.slicegame

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** One-off noises that happen rarely enough not to need variety. */
enum class Sfx { BUTTON, MISS, HEAL, LEVEL_UP, COUNTDOWN, GAME_OVER, BEST }

/**
 * The four things that happen constantly. Each is a bank of ten distinct
 * recipes rather than one sound, and each shot is pitched, so the ear never
 * settles on a pattern.
 */
enum class SfxBank { SLICE, GOOD, PERFECT, BAD }

/**
 * Chiptune audio, synthesised on the device rather than shipped as files.
 *
 * Nothing here is a recording: every sound is a handful of square, triangle, saw,
 * sine and LFSR-noise voices with exponential pitch sweeps and hard envelopes,
 * sample-and-hold crushed so it sounds like a sound chip rather than a synth. The
 * download carries no audio at all, and retuning anything is changing a number.
 *
 * Repetition is handled twice over: ten separately written variants per bank, and
 * a musical pitch shift on every shot. Ten recipes and seven ratios is seventy
 * distinct results for a cut, and consecutive repeats of a variant are refused
 * outright - the ear catches an immediate repeat far more easily than it catches
 * a sound it heard six cuts ago.
 */
class SoundEngine(context: Context) {

    private val appContext = context.applicationContext
    private val random = Random(System.nanoTime())

    private val pool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val singles = HashMap<Sfx, Int>()
    private val banks = HashMap<SfxBank, IntArray>()
    private val lastPlayed = HashMap<SfxBank, Int>()

    private var music: MediaPlayer? = null
    private var musicFile: File? = null
    private var musicWanted = false

    @Volatile private var loaded = false
    private var preparing = false

    var enabled = true
    var volume = 1f
    var musicEnabled = true
        set(value) {
            field = value
            if (!value) stopMusic() else if (musicWanted) startMusic()
        }
    var musicVolume = 0.45f
        set(value) {
            field = value
            music?.setVolume(value, value)
        }

    /**
     * Renders and loads everything off the main thread: fifty-odd short waves and
     * one long loop. Cheap enough - a few hundred thousand samples - but not so
     * cheap it belongs in a frame.
     */
    fun prepare() {
        if (loaded || preparing) return
        preparing = true
        Thread {
            try {
                // Versioned, so a build that retunes the set does not keep playing
                // whatever the last one left in the cache.
                val dir = File(appContext.cacheDir, "sfx_v$RENDER_VERSION").apply { mkdirs() }

                for (sfx in Sfx.values()) {
                    val file = File(dir, "one_${sfx.name.lowercase()}.wav")
                    if (!file.exists()) writeWav(file, renderSingle(sfx))
                    singles[sfx] = pool.load(file.absolutePath, 1)
                }

                for (bank in SfxBank.values()) {
                    val ids = IntArray(BANK_SIZE)
                    for (i in 0 until BANK_SIZE) {
                        val file = File(dir, "${bank.name.lowercase()}_$i.wav")
                        if (!file.exists()) writeWav(file, renderVariant(bank, i))
                        ids[i] = pool.load(file.absolutePath, 1)
                    }
                    banks[bank] = ids
                }

                val loop = File(dir, "music.wav")
                if (!loop.exists()) writeWav(loop, renderMusic())
                musicFile = loop

                loaded = true
                if (musicWanted) startMusic()
            } catch (e: Exception) {
                // A device that will not give us audio is not a reason to take the
                // game down with it - playback simply becomes a no-op.
                loaded = false
            } finally {
                preparing = false
            }
        }.start()
    }

    fun play(sfx: Sfx, gain: Float = 1f, rate: Float = 1f) {
        if (!enabled || !loaded) return
        shoot(singles[sfx] ?: return, gain, rate)
    }

    /**
     * Picks a variant the bank did not just play and pitches it. [gain] and [spread]
     * let a caller lean on the same bank differently - a cut that barely counted
     * gets the same recipes quieter and lower.
     */
    fun play(bank: SfxBank, gain: Float = 1f, spread: Int = PITCH_STEPS.size) {
        if (!enabled || !loaded) return
        val ids = banks[bank] ?: return

        var index = random.nextInt(ids.size)
        if (ids.size > 1 && index == lastPlayed[bank]) index = (index + 1 + random.nextInt(ids.size - 1)) % ids.size
        lastPlayed[bank] = index

        val range = spread.coerceIn(1, PITCH_STEPS.size)
        val offset = (PITCH_STEPS.size - range) / 2
        shoot(ids[index], gain, PITCH_STEPS[offset + random.nextInt(range)])
    }

    private fun shoot(id: Int, gain: Float, rate: Float) {
        val v = (volume * gain).coerceIn(0f, 1f)
        if (v <= 0.001f) return
        pool.play(id, v, v, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    // -----------------------------------------------------------------
    // Music
    // -----------------------------------------------------------------

    fun startMusic() {
        musicWanted = true
        if (!musicEnabled) return
        val file = musicFile ?: return
        if (music != null) {
            resumeMusic()
            return
        }
        music = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(file.absolutePath)
                isLooping = true
                setVolume(musicVolume, musicVolume)
                prepare()
                start()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun stopMusic() {
        musicWanted = false
        music?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // Already stopped; releasing is all that is left to do.
            }
            it.release()
        }
        music = null
    }

    /** Holds the track where it is - for a pause, or the app going to the back. */
    fun pauseMusic() {
        music?.let { if (it.isPlaying) it.pause() }
    }

    fun resumeMusic() {
        if (!musicEnabled || !musicWanted) return
        music?.let { if (!it.isPlaying) it.start() }
    }

    fun release() {
        stopMusic()
        pool.release()
        singles.clear()
        banks.clear()
        loaded = false
    }

    // -----------------------------------------------------------------
    // The single-shot set
    // -----------------------------------------------------------------

    private fun renderSingle(sfx: Sfx): ShortArray = when (sfx) {
        Sfx.BUTTON -> buffer(70) { b ->
            tone(b, 0, 55, 880f, 1180f, SQUARE, 0.5f, duty = 0.25f, crush = 3)
        }

        Sfx.MISS -> buffer(340) { b ->
            tone(b, 0, 290, 440f, 70f, SAW, 0.42f, crush = 4)
            tone(b, 0, 120, 0f, 0f, NOISE, 0.14f, attackMs = 2, crush = 3)
        }

        Sfx.HEAL -> buffer(240) { b ->
            tone(b, 0, 110, 880f, 1320f, TRIANGLE, 0.34f, attackMs = 4)
            tone(b, 80, 140, 1320f, 2093f, TRIANGLE, 0.30f, attackMs = 4)
        }

        Sfx.LEVEL_UP -> buffer(340) { b ->
            floatArrayOf(523f, 659f, 784f, 1046f).forEachIndexed { i, hz ->
                tone(b, i * 65, 90, hz, hz, SQUARE, 0.40f, crush = 3)
            }
        }

        Sfx.COUNTDOWN -> buffer(120) { b ->
            tone(b, 0, 95, 784f, 784f, SQUARE, 0.44f, crush = 3)
        }

        // Four notes walking down a minor chord. The run is over and it should
        // sound like it.
        Sfx.GAME_OVER -> buffer(740) { b ->
            val notes = floatArrayOf(523f, 440f, 349f, 262f)
            notes.forEachIndexed { i, hz ->
                val last = i == notes.size - 1
                tone(
                    b, i * 140, if (last) 300 else 150, hz, if (last) hz * 0.97f else hz,
                    SQUARE, 0.40f, duty = if (last) 0.125f else 0.25f, crush = 4
                )
            }
            tone(b, 420, 300, 65f, 55f, SINE, 0.5f, attackMs = 6)
        }

        // The only fanfare in the game, saved for beating your own record.
        Sfx.BEST -> buffer(900) { b ->
            tone(b, 0, 90, 784f, 784f, SQUARE, 0.36f, crush = 3)
            tone(b, 100, 90, 784f, 784f, SQUARE, 0.36f, crush = 3)
            tone(b, 200, 120, 1046f, 1046f, SQUARE, 0.38f, crush = 3)
            tone(b, 330, 420, 1568f, 1568f, SQUARE, 0.40f, duty = 0.25f, crush = 3)
            tone(b, 340, 400, 2093f, 2093f, TRIANGLE, 0.18f, attackMs = 10)
            tone(b, 330, 380, 98f, 98f, SINE, 0.4f)
        }
    }

    // -----------------------------------------------------------------
    // The banks - ten separately written recipes each
    // -----------------------------------------------------------------

    private fun renderVariant(bank: SfxBank, index: Int): ShortArray = when (bank) {
        SfxBank.SLICE -> renderSlice(index)
        SfxBank.GOOD -> renderGood(index)
        SfxBank.PERFECT -> renderPerfect(index)
        SfxBank.BAD -> renderBad(index)
    }

    /** Ten blades: swishes, ticks, zaps and one that just crunches. */
    private fun renderSlice(i: Int): ShortArray = when (i) {
        0 -> buffer(110) { b ->
            tone(b, 0, 85, 0f, 0f, NOISE, 0.34f, attackMs = 1, crush = 2)
            tone(b, 0, 55, 1500f, 380f, SAW, 0.26f, attackMs = 1, crush = 2)
        }
        1 -> buffer(90) { b ->
            tone(b, 0, 30, 2400f, 1600f, SQUARE, 0.30f, attackMs = 1, duty = 0.125f)
            tone(b, 10, 70, 0f, 0f, NOISE, 0.26f, attackMs = 1, crush = 2)
        }
        2 -> buffer(180) { b ->
            // A long airy sweep: the sound of a slow, deliberate cut.
            tone(b, 0, 165, 0f, 0f, NOISE, 0.24f, attackMs = 30, crush = 4)
            tone(b, 20, 120, 900f, 240f, TRIANGLE, 0.20f, attackMs = 8)
        }
        3 -> buffer(120) { b ->
            tone(b, 0, 26, 1800f, 1800f, SQUARE, 0.30f, attackMs = 1, duty = 0.25f)
            tone(b, 34, 26, 2400f, 2400f, SQUARE, 0.28f, attackMs = 1, duty = 0.25f)
            tone(b, 0, 90, 0f, 0f, NOISE, 0.16f, attackMs = 1, crush = 3)
        }
        4 -> buffer(160) { b ->
            tone(b, 0, 140, 0f, 0f, NOISE, 0.34f, attackMs = 12, crush = 6)
            tone(b, 0, 90, 320f, 90f, SINE, 0.32f, attackMs = 3)
        }
        5 -> buffer(100) { b ->
            tone(b, 0, 80, 2600f, 300f, SQUARE, 0.34f, attackMs = 1, duty = 0.125f, crush = 2)
        }
        6 -> buffer(95) { b ->
            tone(b, 0, 70, 0f, 0f, NOISE, 0.38f, attackMs = 1, crush = 1)
            tone(b, 0, 14, 3000f, 2200f, SQUARE, 0.22f, attackMs = 1, duty = 0.5f)
        }
        7 -> buffer(130) { b ->
            tone(b, 0, 110, 3200f, 420f, SAW, 0.30f, attackMs = 1, crush = 3)
        }
        8 -> buffer(140) { b ->
            tone(b, 0, 120, 0f, 0f, NOISE, 0.40f, attackMs = 2, crush = 9)
            tone(b, 0, 60, 180f, 70f, SQUARE, 0.24f, duty = 0.125f, crush = 4)
        }
        else -> buffer(150) { b ->
            tone(b, 0, 130, 1200f, 380f, TRIANGLE, 0.32f, attackMs = 5)
            tone(b, 0, 55, 0f, 0f, NOISE, 0.14f, attackMs = 1, crush = 3)
        }
    }

    /** Ten small approvals. Short, mid-register, never triumphant. */
    private fun renderGood(i: Int): ShortArray = when (i) {
        0 -> buffer(110) { b -> tone(b, 0, 90, 587f, 587f, SQUARE, 0.42f, crush = 3) }
        1 -> buffer(160) { b ->
            tone(b, 0, 60, 523f, 523f, SQUARE, 0.40f, crush = 3)
            tone(b, 55, 90, 659f, 659f, SQUARE, 0.42f, crush = 3)
        }
        2 -> buffer(150) { b ->
            tone(b, 0, 55, 587f, 587f, SQUARE, 0.40f, duty = 0.25f, crush = 3)
            tone(b, 50, 85, 698f, 698f, SQUARE, 0.42f, duty = 0.25f, crush = 3)
        }
        3 -> buffer(140) { b -> tone(b, 0, 120, 659f, 659f, TRIANGLE, 0.44f, attackMs = 4) }
        4 -> buffer(130) { b ->
            // A short trill: the same note wobbled rather than a second note.
            tone(b, 0, 40, 622f, 660f, SQUARE, 0.40f, crush = 3)
            tone(b, 38, 40, 660f, 622f, SQUARE, 0.38f, crush = 3)
            tone(b, 76, 45, 622f, 622f, SQUARE, 0.36f, crush = 3)
        }
        5 -> buffer(170) { b ->
            tone(b, 0, 55, 494f, 494f, SQUARE, 0.38f, crush = 4)
            tone(b, 50, 100, 587f, 587f, SQUARE, 0.42f, crush = 4)
        }
        6 -> buffer(120) { b -> tone(b, 0, 100, 440f, 466f, SQUARE, 0.42f, duty = 0.125f, crush = 4) }
        7 -> buffer(100) { b -> tone(b, 0, 80, 784f, 784f, SQUARE, 0.40f, duty = 0.25f, crush = 2) }
        8 -> buffer(150) { b ->
            tone(b, 0, 130, 622f, 740f, TRIANGLE, 0.40f, attackMs = 3)
            tone(b, 0, 40, 1244f, 1244f, SQUARE, 0.12f, duty = 0.125f)
        }
        else -> buffer(180) { b ->
            tone(b, 0, 50, 659f, 659f, SQUARE, 0.38f, crush = 3)
            tone(b, 45, 45, 523f, 523f, SQUARE, 0.34f, crush = 3)
            tone(b, 88, 80, 659f, 659f, SQUARE, 0.42f, crush = 3)
        }
    }

    /**
     * Ten arcade flourishes. Every one is a run of notes going up - that shape is
     * what a machine sounds like when it is pleased with you - varied by interval,
     * length and timbre rather than by being different ideas.
     */
    private fun renderPerfect(i: Int): ShortArray = when (i) {
        // Major triad, the plain one.
        0 -> buffer(330) { b ->
            arp(b, floatArrayOf(784f, 1046f, 1568f), 65, 90, 0.44f, duty = 0.25f)
            tone(b, 140, 170, 2093f, 3136f, TRIANGLE, 0.18f, attackMs = 6)
        }
        // Octave run.
        1 -> buffer(360) { b ->
            arp(b, floatArrayOf(523f, 784f, 1046f, 1568f), 60, 90, 0.42f)
        }
        // Pentatonic, five quick notes.
        2 -> buffer(360) { b ->
            arp(b, floatArrayOf(587f, 698f, 880f, 1046f, 1319f), 52, 80, 0.40f, duty = 0.25f)
        }
        // Stacked fifths, wide and bright.
        3 -> buffer(340) { b ->
            arp(b, floatArrayOf(659f, 988f, 1319f, 1976f), 58, 95, 0.42f, duty = 0.125f)
        }
        // A fast chromatic scramble upward.
        4 -> buffer(320) { b ->
            arp(b, floatArrayOf(880f, 932f, 988f, 1046f, 1109f, 1568f), 38, 70, 0.38f)
        }
        // Two up, one further up, held.
        5 -> buffer(400) { b ->
            arp(b, floatArrayOf(698f, 880f), 60, 80, 0.40f)
            tone(b, 130, 240, 1397f, 1397f, SQUARE, 0.44f, duty = 0.25f, crush = 3)
            tone(b, 140, 220, 2794f, 2794f, TRIANGLE, 0.14f, attackMs = 8)
        }
        // Bell-like: triangle only, no square at all.
        6 -> buffer(420) { b ->
            arp(b, floatArrayOf(1046f, 1319f, 1568f, 2093f), 70, 160, 0.34f, wave = TRIANGLE, attackMs = 4)
        }
        // Rising with a trill on top.
        7 -> buffer(400) { b ->
            arp(b, floatArrayOf(784f, 1046f), 70, 90, 0.40f)
            tone(b, 150, 45, 1568f, 1568f, SQUARE, 0.42f, crush = 3)
            tone(b, 192, 45, 1760f, 1760f, SQUARE, 0.40f, crush = 3)
            tone(b, 234, 120, 1568f, 1568f, SQUARE, 0.44f, crush = 3)
        }
        // Sweep instead of steps.
        8 -> buffer(330) { b ->
            tone(b, 0, 220, 523f, 2093f, SQUARE, 0.38f, duty = 0.25f, crush = 3, attackMs = 6)
            tone(b, 190, 130, 2093f, 2093f, SQUARE, 0.42f, duty = 0.125f, crush = 3)
        }
        // The big one: six notes and a bass thump under them.
        else -> buffer(460) { b ->
            arp(b, floatArrayOf(523f, 659f, 784f, 1046f, 1319f, 1568f), 55, 85, 0.40f)
            tone(b, 0, 180, 131f, 131f, SINE, 0.34f)
            tone(b, 280, 160, 2093f, 2637f, TRIANGLE, 0.16f, attackMs = 8)
        }
    }

    /** Ten refusals: buzzes, thuds, glitches. Deliberately unpleasant. */
    private fun renderBad(i: Int): ShortArray = when (i) {
        0 -> buffer(230) { b -> tone(b, 0, 190, 233f, 117f, SQUARE, 0.40f, duty = 0.125f, crush = 5) }
        // The classic wrong-answer double buzz.
        1 -> buffer(300) { b ->
            tone(b, 0, 110, 196f, 196f, SQUARE, 0.42f, duty = 0.125f, crush = 6)
            tone(b, 140, 130, 185f, 185f, SQUARE, 0.42f, duty = 0.125f, crush = 6)
        }
        2 -> buffer(200) { b ->
            tone(b, 0, 160, 0f, 0f, NOISE, 0.36f, attackMs = 2, crush = 8)
            tone(b, 0, 120, 150f, 60f, SINE, 0.40f, attackMs = 2)
        }
        3 -> buffer(260) { b -> tone(b, 0, 230, 165f, 98f, SAW, 0.38f, crush = 6) }
        // Two notes a semitone apart, beating against each other.
        4 -> buffer(280) { b ->
            tone(b, 0, 240, 220f, 220f, SQUARE, 0.30f, duty = 0.25f, crush = 4)
            tone(b, 0, 240, 233f, 233f, SQUARE, 0.30f, duty = 0.25f, crush = 4)
        }
        5 -> buffer(150) { b ->
            tone(b, 0, 40, 440f, 110f, SQUARE, 0.40f, attackMs = 1, duty = 0.125f, crush = 7)
            tone(b, 45, 90, 0f, 0f, NOISE, 0.22f, attackMs = 1, crush = 10)
        }
        6 -> buffer(240) { b ->
            tone(b, 0, 210, 0f, 0f, NOISE, 0.30f, attackMs = 4, crush = 12)
            tone(b, 0, 170, 175f, 131f, SQUARE, 0.32f, duty = 0.125f, crush = 5)
        }
        // A warble on the way down.
        7 -> buffer(300) { b ->
            tone(b, 0, 70, 262f, 220f, SQUARE, 0.36f, duty = 0.25f, crush = 5)
            tone(b, 65, 70, 233f, 196f, SQUARE, 0.36f, duty = 0.25f, crush = 5)
            tone(b, 130, 140, 196f, 147f, SQUARE, 0.38f, duty = 0.25f, crush = 5)
        }
        // A dead thunk with almost no pitch to it.
        8 -> buffer(180) { b ->
            tone(b, 0, 150, 110f, 70f, SINE, 0.52f, attackMs = 1)
            tone(b, 0, 60, 0f, 0f, NOISE, 0.18f, attackMs = 1, crush = 6)
        }
        else -> buffer(320) { b ->
            arp(b, floatArrayOf(294f, 277f, 262f, 247f), 70, 90, 0.34f, duty = 0.125f, crush = 5)
        }
    }

    /** A run of notes at a fixed spacing - the shape most of these sounds share. */
    private fun arp(
        buf: FloatArray,
        notes: FloatArray,
        stepMs: Int,
        holdMs: Int,
        gain: Float,
        wave: Int = SQUARE,
        duty: Float = 0.5f,
        crush: Int = 3,
        attackMs: Int = 3
    ) {
        notes.forEachIndexed { i, hz ->
            tone(buf, i * stepMs, holdMs, hz, hz, wave, gain, attackMs, duty, crush)
        }
    }

    // -----------------------------------------------------------------
    // The loop
    // -----------------------------------------------------------------

    /**
     * Eight bars of bass-led chip music at 108 BPM, in A minor.
     *
     * The weight is deliberately all at the bottom: a sine sub doubling a square
     * bass, a kick that drops a couple of octaves in ninety milliseconds, and only
     * a thin arpeggio up top so the cut sounds always sit above the track rather
     * than fighting it.
     */
    private fun renderMusic(): ShortArray {
        val stepMs = (60_000f / BPM / 2f).roundToInt()   // an eighth note
        val bars = 8
        val steps = bars * 8
        val buf = FloatArray(samplesFor(steps * stepMs + 400))

        // A minor - A A F G A A F E, two bars a chord would be too slow at this
        // tempo, so one bar each and round twice.
        val roots = floatArrayOf(55f, 55f, 43.65f, 49f, 55f, 55f, 43.65f, 41.2f)
        // Root, root, root, fifth, root, octave, fifth, root: enough movement to
        // carry eight bars without becoming a melody you notice.
        val pattern = floatArrayOf(1f, 1f, 1f, 1.5f, 1f, 2f, 1.5f, 1f)

        for (bar in 0 until bars) {
            val root = roots[bar]
            for (eighth in 0 until 8) {
                val at = (bar * 8 + eighth) * stepMs

                val hz = root * pattern[eighth]
                tone(buf, at, (stepMs * 0.92f).toInt(), hz, hz, SQUARE, 0.26f, attackMs = 4, duty = 0.25f, crush = 4)
                // The sub is what makes it bassy rather than merely low.
                tone(buf, at, (stepMs * 0.95f).toInt(), hz, hz, SINE, 0.34f, attackMs = 6)

                // Kick on one and three.
                if (eighth == 0 || eighth == 4) {
                    tone(buf, at, 95, 150f, 45f, SINE, 0.55f, attackMs = 1)
                    tone(buf, at, 22, 0f, 0f, NOISE, 0.10f, attackMs = 1, crush = 4)
                }
                // Hat on the offbeats, quiet enough to be felt more than heard.
                if (eighth % 2 == 1) {
                    tone(buf, at, 26, 0f, 0f, NOISE, 0.07f, attackMs = 1, crush = 2)
                }
                // A thin arpeggio, only on the second half of each bar.
                if (eighth >= 4) {
                    val lead = root * 8f * pattern[(eighth + 2) % 8]
                    tone(buf, at, (stepMs * 0.6f).toInt(), lead, lead, TRIANGLE, 0.09f, attackMs = 8)
                }
            }
        }
        return finish(buf, peak = 21000f, fadeMs = 0)
    }

    // -----------------------------------------------------------------
    // Synthesis primitives
    // -----------------------------------------------------------------

    private inline fun buffer(durationMs: Int, build: (FloatArray) -> Unit): ShortArray {
        val buf = FloatArray(samplesFor(durationMs))
        build(buf)
        return finish(buf)
    }

    /**
     * One voice. Frequency sweeps exponentially from [fromHz] to [toHz] - a linear
     * sweep sounds wrong because pitch is logarithmic - under a linear attack and a
     * curved decay to silence. [crush] holds each computed sample for that many
     * samples, the sample-and-hold that gives the whole set its eight-bit grain.
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
                    NOISE -> {
                        // Linear-feedback shift register: the noise a sound chip
                        // makes, rather than white noise from a random number.
                        val bit = (noise xor (noise shr 1)) and 1
                        noise = (noise shr 1) or (bit shl 14)
                        if (noise and 1 == 1) 1f else -1f
                    }
                    SQUARE -> if (phase % 1f < duty) 1f else -1f
                    TRIANGLE -> {
                        val p = phase % 1f
                        if (p < 0.5f) 4f * p - 1f else 3f - 4f * p
                    }
                    SINE -> sin(2.0 * PI * phase).toFloat()
                    else -> 2f * (phase % 1f) - 1f
                }
            }

            buf[index] += held * envelope * gain

            if (wave != NOISE) {
                val hz = fromHz * (toHz / fromHz).pow(progress)
                phase += hz / RATE
            }
        }
    }

    /** Soft-limits rather than hard-clipping, so stacked voices do not crackle. */
    private fun finish(buf: FloatArray, peak: Float = 26000f, fadeMs: Int = 4): ShortArray {
        val out = ShortArray(buf.size)
        for (i in buf.indices) {
            val x = buf[i].coerceIn(-1.4f, 1.4f)
            val limited = x - (x * x * x) / 6f
            out[i] = (limited * peak).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        // A short fade at the tail: cutting a wave mid-cycle is an audible click.
        // The loop skips it, or every bar would come with a dip.
        val fade = samplesFor(fadeMs).coerceAtMost(out.size)
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
        /** Bumped whenever the set is retuned, so the cache cannot serve stale waves. */
        const val RENDER_VERSION = 2

        const val BANK_SIZE = 10
        const val RATE = 22050
        const val BPM = 108f

        const val SQUARE = 0
        const val TRIANGLE = 1
        const val SAW = 2
        const val NOISE = 3
        const val SINE = 4

        /**
         * Semitone-ish ratios either side of unity. Musical rather than random, so
         * a shifted sound still sounds like it belongs to the same instrument.
         */
        val PITCH_STEPS = floatArrayOf(0.84f, 0.89f, 0.94f, 1.0f, 1.06f, 1.12f, 1.19f)
    }
}
