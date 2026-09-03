package com.halfmeasures.slicegame

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** One-off noises that happen rarely enough not to need variety. */
enum class Sfx { BUTTON, MISS, HEAL, LEVEL_UP, COUNTDOWN, GAME_OVER, BEST, RANK_UP, RANK_DOWN }

/**
 * The events that happen constantly. Each is a bank of ten separately written
 * recipes, and each shot is pitched, so the ear never settles on a pattern.
 *
 * Tonality is deliberate and consistent: [SWIPE] and [SLICE] are noise, so they
 * have no key; [GOOD] and [PERFECT] are major triads, major pentatonic and plucked
 * major chords; [BAD] and [DANGER] are minor, diminished and chromatic. A player
 * should be able to tell how a cut landed with their eyes shut.
 */
enum class SfxBank { SWIPE, SLICE, GOOD, PERFECT, BAD, DANGER, VOICE, UI }

/**
 * All of the game's audio, synthesised on the device rather than shipped as files.
 *
 * Effects are square, triangle, saw, sine and LFSR-noise voices with exponential
 * pitch sweeps and hard envelopes, sample-and-hold crushed so they sound like a
 * sound chip. Music is ten generated tracks, streamed through an AudioTrack the
 * engine feeds itself so the loop is sample-exact. The one exception is the
 * announcer, which is recorded takes loaded from the assets - a synthesised shout
 * was only ever going to sound like an arcade cabinet, and these are voices.
 */
class SoundEngine(context: Context) {

    private val appContext = context.applicationContext
    private val random = Random(System.nanoTime())

    private val pool = SoundPool.Builder()
        .setMaxStreams(14)
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

    private var cacheDir: File? = null
    private val tracks = arrayOfNulls<ShortArray>(TRACK_COUNT)
    private var music: MusicStream? = null
    private var musicWanted = false
    private var musicHeld = false
    private var currentTrack = -1
    private var musicSpeed = 1f

    @Volatile private var loaded = false
    private var preparing = false

    var enabled = true
    var volume = 1f
    var voiceEnabled = true
    /** Scales the announcer alone, on top of [volume]. */
    var voiceVolume = 1f
    var musicEnabled = true
        set(value) {
            field = value
            if (!value) stopMusic() else if (musicWanted) startMusic()
        }
    /** Starts where the settings say, so nothing plays at a level nobody chose. */
    var musicVolume = GameSettings.DEFAULT_MUSIC_VOLUME
        set(value) {
            field = value
            music?.setVolume(value)
        }

    fun prepare() {
        if (loaded || preparing) return
        preparing = true
        Thread {
            try {
                // Versioned, so a build that retunes the set cannot keep playing
                // whatever the last one left in the cache.
                val dir = File(appContext.cacheDir, "sfx_v$RENDER_VERSION").apply { mkdirs() }
                cacheDir = dir

                for (sfx in Sfx.values()) {
                    val file = File(dir, "one_${sfx.name.lowercase()}.wav")
                    if (!file.exists()) writeWav(file, renderSingle(sfx))
                    singles[sfx] = pool.load(file.absolutePath, 1)
                }

                for (bank in SfxBank.values()) {
                    if (bank == SfxBank.VOICE) continue
                    val ids = IntArray(BANK_SIZE)
                    for (i in 0 until BANK_SIZE) {
                        val file = File(dir, "${bank.name.lowercase()}_$i.wav")
                        if (!file.exists()) writeWav(file, renderVariant(bank, i))
                        ids[i] = pool.load(file.absolutePath, 1)
                    }
                    banks[bank] = ids
                }

                // The announcer is recorded, not synthesised: real takes, straight
                // out of the assets, so nothing is cached or rendered for them.
                val voice = IntArray(VOICE_FILES.size)
                for (i in VOICE_FILES.indices) {
                    appContext.assets.openFd("voice/${VOICE_FILES[i]}").use { fd ->
                        voice[i] = pool.load(fd, 1)
                    }
                }
                banks[SfxBank.VOICE] = voice

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
     * Picks a variant the bank did not just play and pitches it. [spread] narrows
     * the pitch range for the sounds that are melodies rather than noises - a
     * wildly detuned flourish sounds like a mistake, a detuned swish does not.
     */
    fun play(bank: SfxBank, gain: Float = 1f, spread: Int = PITCH_STEPS.size) {
        if (!enabled || !loaded) return
        if (bank == SfxBank.VOICE && !voiceEnabled) return
        val ids = banks[bank] ?: return
        if (ids.isEmpty()) return

        var index = random.nextInt(ids.size)
        if (ids.size > 1 && index == lastPlayed[bank]) {
            index = (index + 1 + random.nextInt(ids.size - 1)) % ids.size
        }
        lastPlayed[bank] = index

        val range = spread.coerceIn(1, PITCH_STEPS.size)
        val offset = (PITCH_STEPS.size - range) / 2
        val level = if (bank == SfxBank.VOICE) gain * voiceVolume else gain
        shoot(ids[index], level, PITCH_STEPS[offset + random.nextInt(range)])
    }

    private fun shoot(id: Int, gain: Float, rate: Float) {
        val v = (volume * gain).coerceIn(0f, 1f)
        if (v <= 0.001f) return
        pool.play(id, v, v, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    // -----------------------------------------------------------------
    // Music
    // -----------------------------------------------------------------

    /** Starts a random track. Rendered on demand, so a launch waits for one. */
    fun startMusic() {
        musicWanted = true
        if (!musicEnabled || !loaded) return
        if (music != null) {
            resumeMusic()
            return
        }
        val pick = if (TRACK_COUNT > 1) {
            var p = random.nextInt(TRACK_COUNT)
            if (p == currentTrack) p = (p + 1) % TRACK_COUNT
            p
        } else 0
        currentTrack = pick

        Thread {
            try {
                val data = tracks[pick] ?: renderTrack(pick).also { tracks[pick] = it }
                if (!musicWanted || !musicEnabled) return@Thread
                synchronized(this) {
                    if (music != null) return@Thread
                    music = MusicStream(data).apply {
                        setVolume(musicVolume)
                        setSpeed(musicSpeed)
                        start()
                    }
                }
            } catch (e: Exception) {
                // No music is survivable.
            }
        }.start()
    }

    fun stopMusic() {
        musicWanted = false
        synchronized(this) {
            music?.stop()
            music = null
        }
    }

    fun pauseMusic() {
        music?.pause()
    }

    /**
     * A hold the game puts on the music, distinct from the app losing the screen.
     *
     * Without it, coming back from settings would start the track under a paused
     * run: the app-level watcher sees a screen come forward and resumes, and it
     * has no idea the game behind it is sitting on its pause card.
     */
    fun holdMusic() {
        musicHeld = true
        music?.pause()
    }

    fun releaseMusicHold() {
        musicHeld = false
        resumeMusic()
    }

    fun resumeMusic() {
        if (!musicEnabled || !musicWanted || musicHeld) return
        music?.resume()
    }

    /**
     * Speeds the track up without pitching it - AudioTrack's playback params
     * time-stretch rather than resample, so a faster run does not turn the bass
     * into a chipmunk.
     */
    fun setMusicSpeed(speed: Float) {
        val clamped = speed.coerceIn(1f, MAX_MUSIC_SPEED)
        if (kotlin.math.abs(clamped - musicSpeed) < 0.005f) return
        musicSpeed = clamped
        music?.setSpeed(clamped)
    }

    /**
     * Drops the music stream. The sample pool stays: it belongs to the process
     * rather than to any one screen, and the settings and instructions screens
     * click through the same engine the game plays through.
     */
    fun releaseMusic() {
        stopMusic()
    }



    /**
     * A looping stream the engine feeds itself, one chunk at a time, wrapping at
     * the end of the buffer. MediaPlayer's own looping leaves an audible gap on
     * many devices - it tears the file down and sets it up again - whereas this
     * simply keeps writing, so the loop point is sample-exact and inaudible.
     */
    private inner class MusicStream(private val data: ShortArray) {

        private var track: AudioTrack? = null
        private var thread: Thread? = null
        @Volatile private var running = false
        @Volatile private var paused = false

        fun start() {
            val minBuffer = AudioTrack.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuffer * 2, RATE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            t.play()

            running = true
            thread = Thread {
                var pos = 0
                while (running) {
                    if (paused) {
                        Thread.sleep(20)
                        continue
                    }
                    val take = minOf(CHUNK, data.size - pos)
                    // Non-blocking, so stopping never has to wait on a full buffer.
                    val written = t.write(data, pos, take, AudioTrack.WRITE_NON_BLOCKING)
                    if (written > 0) {
                        pos = (pos + written) % data.size
                    } else {
                        Thread.sleep(8)
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        fun stop() {
            running = false
            thread?.join(300)
            thread = null
            try {
                track?.pause()
                track?.flush()
                track?.stop()
            } catch (e: IllegalStateException) {
                // Already down; releasing is all that is left.
            }
            track?.release()
            track = null
        }

        fun pause() {
            paused = true
            try {
                track?.pause()
            } catch (e: IllegalStateException) {
                // Nothing to hold.
            }
        }

        fun resume() {
            if (!paused) return
            paused = false
            try {
                track?.play()
            } catch (e: IllegalStateException) {
                // Nothing to resume.
            }
        }

        fun setVolume(v: Float) {
            track?.setVolume(v.coerceIn(0f, 1f))
        }

        fun setSpeed(speed: Float) {
            val t = track ?: return
            try {
                t.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
            } catch (e: Exception) {
                // A device that will not time-stretch just plays at tempo.
            }
        }
    }

    // -----------------------------------------------------------------
    // Single shots
    // -----------------------------------------------------------------

    private fun renderSingle(sfx: Sfx): ShortArray = when (sfx) {
        Sfx.BUTTON -> buffer(70) { b ->
            tone(b, 0, 55, 880f, 1180f, SQUARE, 0.5f, duty = 0.25f, crush = 3)
        }

        Sfx.MISS -> buffer(340) { b ->
            tone(b, 0, 290, 440f, 70f, SAW, 0.42f, crush = 4)
            tone(b, 0, 120, 0f, 0f, NOISE, 0.14f, attackMs = 2, crush = 3)
        }

        // Major, because healing is good news.
        Sfx.HEAL -> buffer(240) { b ->
            tone(b, 0, 110, 880f, 1109f, TRIANGLE, 0.34f, attackMs = 4)
            tone(b, 80, 140, 1319f, 1760f, TRIANGLE, 0.30f, attackMs = 4)
        }

        Sfx.LEVEL_UP -> buffer(340) { b ->
            arp(b, majorRun(523f), 65, 90, 0.40f)
        }

        Sfx.COUNTDOWN -> buffer(120) { b ->
            tone(b, 0, 95, 784f, 784f, SQUARE, 0.44f, crush = 3)
        }

        // Minor, walking down. The run is over and it should sound like it.
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

        /**
         * Promotion. A rising major arpeggio played twice, the second an octave up
         * and answered by a held fifth over a bass note - bigger than the record
         * fanfare and clearly a different event, because they can land together.
         */
        Sfx.RANK_UP -> buffer(1200) { b ->
            val root = 392f
            arp(b, floatArrayOf(root, root * MAJOR_THIRD, root * FIFTH), 80, 100, 0.34f, crush = 3)
            arp2(b, 260, floatArrayOf(root * 2f, root * 2f * MAJOR_THIRD, root * 2f * FIFTH), 80, 110, 0.32f)
            tone(b, 520, 520, root * 4f, root * 4f, SQUARE, 0.30f, attackMs = 6, duty = 0.25f, crush = 3)
            tone(b, 520, 500, root * 3f, root * 3f, TRIANGLE, 0.22f, attackMs = 12)
            tone(b, 500, 460, root / 4f, root / 4f, SINE, 0.46f, attackMs = 8)
            // A last flick upward, so it ends on the way up rather than settling.
            tone(b, 900, 240, root * 4f, root * 6f, TRIANGLE, 0.20f, attackMs = 6)
        }

        /**
         * Demotion. The same shape upside down: a minor arpeggio walking down,
         * detuning as it goes, over a bass note that sags a semitone.
         */
        Sfx.RANK_DOWN -> buffer(1000) { b ->
            val root = 330f
            val notes = floatArrayOf(root * 2f, root * FIFTH, root * MINOR_THIRD, root)
            notes.forEachIndexed { i, hz ->
                val last = i == notes.size - 1
                tone(
                    b, i * 150, if (last) 380 else 160, hz, if (last) hz * 0.94f else hz * 0.99f,
                    SQUARE, 0.34f, attackMs = 4, duty = if (last) 0.125f else 0.25f, crush = 4
                )
            }
            tone(b, 450, 420, root / 4f, root / 4f * 0.94f, SINE, 0.44f, attackMs = 10)
            tone(b, 600, 300, 0f, 0f, NOISE, 0.05f, attackMs = 40, crush = 6)
        }
    }

    /**
     * Ten button blips.
     *
     * One click on every press was the most-heard sound in the app by a distance,
     * and a sound heard that often has to have somewhere to go. These are short
     * and quiet by design - a menu is not a place for a flourish - but no two are
     * built the same way, and the bank pitches each shot on top of that.
     */
    private fun renderUi(i: Int): ShortArray = when (i) {
        // A plain chip blip, up.
        0 -> buffer(70) { b -> tone(b, 0, 55, 880f, 1180f, SQUARE, 0.44f, duty = 0.25f, crush = 3) }
        // The same, down - a press and a release read differently.
        1 -> buffer(70) { b -> tone(b, 0, 58, 1180f, 820f, SQUARE, 0.42f, duty = 0.25f, crush = 3) }
        // Two-step, a fourth apart.
        2 -> buffer(110) { b ->
            tone(b, 0, 38, 784f, 784f, SQUARE, 0.36f, duty = 0.5f, crush = 3)
            tone(b, 34, 55, 1046f, 1046f, SQUARE, 0.36f, duty = 0.25f, crush = 3)
        }
        // Soft triangle, no edge to it.
        3 -> buffer(90) { b -> tone(b, 0, 78, 988f, 1245f, TRIANGLE, 0.40f, attackMs = 5) }
        // A tick with a breath of noise on the front, like a key.
        4 -> buffer(80) { b ->
            tone(b, 0, 18, 0f, 0f, NOISE, 0.16f, attackMs = 1, crush = 4)
            tone(b, 6, 52, 1046f, 1318f, SQUARE, 0.36f, duty = 0.125f, crush = 3)
        }
        // Narrow duty: thin and glassy.
        5 -> buffer(75) { b -> tone(b, 0, 62, 1318f, 1568f, SQUARE, 0.30f, duty = 0.125f, crush = 2) }
        // A fifth below, fatter.
        6 -> buffer(95) { b ->
            tone(b, 0, 70, 587f, 784f, SQUARE, 0.40f, duty = 0.375f, crush = 3)
            tone(b, 0, 40, 1174f, 1568f, TRIANGLE, 0.14f, attackMs = 3)
        }
        // Two-step down, for anything that reads as backing out.
        7 -> buffer(110) { b ->
            tone(b, 0, 40, 1046f, 1046f, SQUARE, 0.34f, duty = 0.25f, crush = 3)
            tone(b, 36, 60, 784f, 740f, SQUARE, 0.36f, duty = 0.25f, crush = 4)
        }
        // Saw, with a bite to the attack.
        8 -> buffer(80) { b -> tone(b, 0, 66, 660f, 990f, SAW, 0.30f, attackMs = 2, crush = 4) }
        // A sine pip under a square one: the roundest of the set.
        else -> buffer(100) { b ->
            tone(b, 0, 46, 1568f, 1568f, SQUARE, 0.26f, duty = 0.25f, crush = 2)
            tone(b, 0, 88, 392f, 392f, SINE, 0.30f, attackMs = 6)
        }
    }

    private fun renderVariant(bank: SfxBank, index: Int): ShortArray = when (bank) {
        SfxBank.SWIPE -> renderSwipe(index)
        SfxBank.SLICE -> renderSlice(index)
        SfxBank.GOOD -> renderGood(index)
        SfxBank.PERFECT -> renderPerfect(index)
        SfxBank.BAD -> renderBad(index)
        SfxBank.DANGER -> renderDanger(index)
        SfxBank.UI -> renderUi(index)
        // Recorded, not rendered - loaded straight from the assets in prepare.
        SfxBank.VOICE -> ShortArray(0)
    }

    // -----------------------------------------------------------------
    // Banks - noise
    // -----------------------------------------------------------------

    /**
     * The blade moving, before it has hit anything. Air rather than impact: quiet,
     * mostly noise, no pitch to speak of, so it can fire on every swipe without
     * becoming the loudest thing in the game.
     */
    private fun renderSwipe(i: Int): ShortArray = when (i) {
        0 -> buffer(150) { b -> tone(b, 0, 130, 0f, 0f, NOISE, 0.20f, attackMs = 30, crush = 5) }
        1 -> buffer(120) { b ->
            tone(b, 0, 100, 0f, 0f, NOISE, 0.18f, attackMs = 18, crush = 3)
            tone(b, 0, 80, 1100f, 500f, TRIANGLE, 0.09f, attackMs = 12)
        }
        2 -> buffer(180) { b -> tone(b, 0, 160, 0f, 0f, NOISE, 0.17f, attackMs = 50, crush = 7) }
        3 -> buffer(110) { b ->
            tone(b, 0, 95, 0f, 0f, NOISE, 0.22f, attackMs = 14, crush = 2)
        }
        4 -> buffer(160) { b ->
            tone(b, 0, 140, 0f, 0f, NOISE, 0.16f, attackMs = 40, crush = 9)
            tone(b, 20, 90, 320f, 180f, SINE, 0.12f, attackMs = 20)
        }
        5 -> buffer(130) { b -> tone(b, 0, 115, 0f, 0f, NOISE, 0.19f, attackMs = 25, crush = 4) }
        6 -> buffer(200) { b ->
            tone(b, 0, 180, 0f, 0f, NOISE, 0.15f, attackMs = 70, crush = 6)
        }
        7 -> buffer(100) { b ->
            tone(b, 0, 85, 0f, 0f, NOISE, 0.21f, attackMs = 10, crush = 3)
            tone(b, 0, 40, 1800f, 900f, TRIANGLE, 0.07f, attackMs = 8)
        }
        8 -> buffer(170) { b -> tone(b, 0, 150, 0f, 0f, NOISE, 0.18f, attackMs = 45, crush = 11) }
        else -> buffer(140) { b ->
            tone(b, 0, 120, 0f, 0f, NOISE, 0.17f, attackMs = 22, crush = 5)
            tone(b, 0, 60, 600f, 260f, TRIANGLE, 0.08f, attackMs = 14)
        }
    }

    /** Ten blades landing: swishes, ticks, zaps and one that just crunches. */
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
            tone(b, 0, 70, 0f, 0f, NOISE, 0.38f, attackMs = 1)
            tone(b, 0, 14, 3000f, 2200f, SQUARE, 0.22f, attackMs = 1)
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

    // -----------------------------------------------------------------
    // Banks - major. Everything here resolves upward and consonant.
    // -----------------------------------------------------------------

    /** Ten small approvals. Major thirds, fifths and octaves only. */
    private fun renderGood(i: Int): ShortArray = when (i) {
        0 -> buffer(110) { b -> tone(b, 0, 90, 587f, 587f, SQUARE, 0.42f, crush = 3) }
        // Root then major third.
        1 -> buffer(160) { b ->
            tone(b, 0, 60, 523f, 523f, SQUARE, 0.40f, crush = 3)
            tone(b, 55, 90, 659f, 659f, SQUARE, 0.42f, crush = 3)
        }
        // Root then fifth.
        2 -> buffer(150) { b ->
            tone(b, 0, 55, 587f, 587f, SQUARE, 0.40f, duty = 0.25f, crush = 3)
            tone(b, 50, 85, 880f, 880f, SQUARE, 0.42f, duty = 0.25f, crush = 3)
        }
        3 -> buffer(140) { b -> tone(b, 0, 120, 659f, 659f, TRIANGLE, 0.44f, attackMs = 4) }
        // A whole-tone trill - no semitones, so nothing sours.
        4 -> buffer(130) { b ->
            tone(b, 0, 40, 587f, 587f, SQUARE, 0.40f, crush = 3)
            tone(b, 38, 40, 659f, 659f, SQUARE, 0.38f, crush = 3)
            tone(b, 76, 45, 587f, 587f, SQUARE, 0.36f, crush = 3)
        }
        // A plucked major chord: all three notes at once, triangle, soft attack.
        5 -> buffer(230) { b ->
            tone(b, 0, 200, 523f, 523f, TRIANGLE, 0.26f, attackMs = 3)
            tone(b, 0, 200, 659f, 659f, TRIANGLE, 0.22f, attackMs = 5)
            tone(b, 0, 200, 784f, 784f, TRIANGLE, 0.20f, attackMs = 7)
        }
        6 -> buffer(120) { b -> tone(b, 0, 100, 440f, 440f, SQUARE, 0.42f, duty = 0.125f, crush = 4) }
        7 -> buffer(100) { b -> tone(b, 0, 80, 784f, 784f, SQUARE, 0.40f, duty = 0.25f, crush = 2) }
        // Rising a major third across one note.
        8 -> buffer(150) { b ->
            tone(b, 0, 130, 622f, 784f, TRIANGLE, 0.40f, attackMs = 3)
            tone(b, 0, 40, 1568f, 1568f, SQUARE, 0.10f, duty = 0.125f)
        }
        // Up, down, up: the major third answered and restated.
        else -> buffer(180) { b ->
            tone(b, 0, 50, 659f, 659f, SQUARE, 0.38f, crush = 3)
            tone(b, 45, 45, 523f, 523f, SQUARE, 0.34f, crush = 3)
            tone(b, 88, 80, 784f, 784f, SQUARE, 0.42f, crush = 3)
        }
    }

    /**
     * Ten arcade flourishes, every one built from a major triad, a major pentatonic
     * or a plucked major chord. They all rise, because that shape is what a machine
     * sounds like when it is pleased with you.
     */
    private fun renderPerfect(i: Int): ShortArray = when (i) {
        // C major triad and up an octave.
        0 -> buffer(330) { b ->
            arp(b, floatArrayOf(523f, 659f, 784f, 1046f), 65, 90, 0.42f, duty = 0.25f)
            tone(b, 200, 170, 2093f, 2637f, TRIANGLE, 0.18f, attackMs = 6)
        }
        // G major, wide octave leap at the end.
        1 -> buffer(360) { b ->
            arp(b, floatArrayOf(392f, 587f, 784f, 1568f), 60, 95, 0.42f)
        }
        // Major pentatonic, five quick notes.
        2 -> buffer(360) { b ->
            arp(b, floatArrayOf(523f, 587f, 659f, 784f, 880f), 52, 85, 0.40f, duty = 0.25f)
        }
        // Stacked fifths: no third at all, so it cannot be anything but open.
        3 -> buffer(340) { b ->
            arp(b, floatArrayOf(587f, 880f, 1319f, 1976f), 58, 95, 0.42f, duty = 0.125f)
        }
        // A major arpeggio taken twice, second time an octave up.
        4 -> buffer(380) { b ->
            arp(b, floatArrayOf(440f, 554f, 659f, 880f, 1109f, 1319f), 52, 80, 0.38f)
        }
        // Two up, then the octave held.
        5 -> buffer(400) { b ->
            arp(b, floatArrayOf(698f, 880f), 60, 80, 0.40f)
            tone(b, 130, 240, 1397f, 1397f, SQUARE, 0.44f, duty = 0.25f, crush = 3)
            tone(b, 140, 220, 2794f, 2794f, TRIANGLE, 0.14f, attackMs = 8)
        }
        // Bell-like: triangle only, F major.
        6 -> buffer(420) { b ->
            arp(b, floatArrayOf(698f, 880f, 1046f, 1397f), 70, 160, 0.34f, wave = TRIANGLE, attackMs = 4)
        }
        // Rising, with a major-second trill on top.
        7 -> buffer(400) { b ->
            arp(b, floatArrayOf(784f, 1046f), 70, 90, 0.40f)
            tone(b, 150, 45, 1568f, 1568f, SQUARE, 0.42f, crush = 3)
            tone(b, 192, 45, 1760f, 1760f, SQUARE, 0.40f, crush = 3)
            tone(b, 234, 120, 1568f, 1568f, SQUARE, 0.44f, crush = 3)
        }
        // A plucked major chord, struck and left to ring, then the octave over it.
        8 -> buffer(430) { b ->
            tone(b, 0, 400, 523f, 523f, TRIANGLE, 0.26f, attackMs = 3)
            tone(b, 12, 388, 659f, 659f, TRIANGLE, 0.22f, attackMs = 4)
            tone(b, 24, 376, 784f, 784f, TRIANGLE, 0.20f, attackMs = 5)
            tone(b, 36, 364, 1046f, 1046f, TRIANGLE, 0.16f, attackMs = 6)
            tone(b, 210, 200, 1568f, 1568f, SQUARE, 0.22f, duty = 0.125f, crush = 3)
        }
        // The big one: a full major scale run with a bass thump under it.
        else -> buffer(470) { b ->
            arp(b, floatArrayOf(523f, 587f, 659f, 698f, 784f, 880f, 988f, 1046f), 48, 75, 0.38f)
            tone(b, 0, 180, 131f, 131f, SINE, 0.34f)
            tone(b, 300, 170, 1568f, 2093f, TRIANGLE, 0.16f, attackMs = 8)
        }
    }

    /** A major triad plus the octave, from any root. */
    private fun majorRun(root: Float): FloatArray =
        floatArrayOf(root, root * MAJOR_THIRD, root * FIFTH, root * 2f)

    // -----------------------------------------------------------------
    // Banks - minor. Everything here sags.
    // -----------------------------------------------------------------

    /** Ten refusals: minor, diminished and chromatic. Deliberately unpleasant. */
    private fun renderBad(i: Int): ShortArray = when (i) {
        0 -> buffer(230) { b -> tone(b, 0, 190, 233f, 117f, SQUARE, 0.40f, duty = 0.125f, crush = 5) }
        // The classic wrong-answer double buzz, a semitone apart.
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
        // A minor triad falling, warbling as it goes.
        7 -> buffer(300) { b ->
            tone(b, 0, 70, 349f, 330f, SQUARE, 0.36f, duty = 0.25f, crush = 5)
            tone(b, 65, 70, 294f, 277f, SQUARE, 0.36f, duty = 0.25f, crush = 5)
            tone(b, 130, 140, 220f, 175f, SQUARE, 0.38f, duty = 0.25f, crush = 5)
        }
        // A dead thunk with almost no pitch to it.
        8 -> buffer(180) { b ->
            tone(b, 0, 150, 110f, 70f, SINE, 0.52f, attackMs = 1)
            tone(b, 0, 60, 0f, 0f, NOISE, 0.18f, attackMs = 1, crush = 6)
        }
        // A diminished chord, all minor thirds, which is the sourest chord there is.
        else -> buffer(320) { b ->
            tone(b, 0, 280, 262f, 262f, SQUARE, 0.24f, duty = 0.125f, crush = 5)
            tone(b, 0, 280, 311f, 311f, SQUARE, 0.24f, duty = 0.125f, crush = 5)
            tone(b, 0, 280, 370f, 370f, SQUARE, 0.24f, duty = 0.125f, crush = 5)
        }
    }

    /**
     * Ten ways of saying you are about to die: falling minor figures with the
     * shape of the tune that plays when a plumber runs out of time. Longer and
     * more melodic than a bad cut, because this is an announcement, not a verdict.
     */
    private fun renderDanger(i: Int): ShortArray = when (i) {
        // A minor triad falling, then a semitone lower than it should land.
        0 -> buffer(620) { b ->
            arp(b, floatArrayOf(523f, 415f, 349f, 262f), 130, 170, 0.36f, duty = 0.25f)
            tone(b, 400, 220, 123f, 116f, SINE, 0.40f, attackMs = 8)
        }
        // Chromatic slide down, the whole thing sliding under itself.
        1 -> buffer(560) { b ->
            tone(b, 0, 520, 440f, 220f, SQUARE, 0.32f, duty = 0.125f, crush = 6, attackMs = 10)
            tone(b, 0, 520, 220f, 110f, SINE, 0.34f, attackMs = 20)
        }
        // Two minor thirds falling in pairs.
        2 -> buffer(600) { b ->
            arp(b, floatArrayOf(466f, 392f), 120, 150, 0.34f)
            arp2(b, 260, floatArrayOf(392f, 330f), 120, 150, 0.34f)
            tone(b, 380, 200, 110f, 98f, SINE, 0.38f, attackMs = 10)
        }
        // A held minor second, the most anxious interval available.
        3 -> buffer(560) { b ->
            tone(b, 0, 520, 330f, 330f, SQUARE, 0.24f, duty = 0.25f, crush = 5, attackMs = 20)
            tone(b, 0, 520, 311f, 311f, SQUARE, 0.24f, duty = 0.25f, crush = 5, attackMs = 20)
            tone(b, 300, 240, 98f, 82f, SINE, 0.36f, attackMs = 12)
        }
        // Descending minor scale, quick and panicked.
        4 -> buffer(560) { b ->
            arp(b, floatArrayOf(523f, 466f, 415f, 392f, 349f, 311f), 80, 110, 0.32f, duty = 0.125f)
        }
        // Alarm: the same two notes alternating, a tritone apart.
        5 -> buffer(640) { b ->
            for (n in 0 until 4) {
                val hz = if (n % 2 == 0) 466f else 330f
                tone(b, n * 150, 130, hz, hz, SQUARE, 0.32f, duty = 0.25f, crush = 5)
            }
            tone(b, 0, 600, 82f, 78f, SINE, 0.30f, attackMs = 40)
        }
        // A minor arpeggio down with a wobble on the last note.
        6 -> buffer(620) { b ->
            arp(b, floatArrayOf(440f, 349f, 294f), 140, 170, 0.34f)
            tone(b, 420, 190, 220f, 196f, SQUARE, 0.34f, duty = 0.125f, crush = 6)
        }
        // Everything sagging together: a whole diminished chord sliding down.
        7 -> buffer(600) { b ->
            tone(b, 0, 560, 349f, 262f, SQUARE, 0.22f, duty = 0.25f, crush = 6, attackMs = 20)
            tone(b, 0, 560, 415f, 311f, SQUARE, 0.22f, duty = 0.25f, crush = 6, attackMs = 20)
            tone(b, 0, 560, 494f, 370f, SQUARE, 0.22f, duty = 0.25f, crush = 6, attackMs = 20)
        }
        // Slow, heavy, funereal.
        8 -> buffer(700) { b ->
            tone(b, 0, 300, 262f, 262f, TRIANGLE, 0.34f, attackMs = 20)
            tone(b, 320, 360, 220f, 208f, TRIANGLE, 0.34f, attackMs = 20)
            tone(b, 0, 660, 65f, 62f, SINE, 0.40f, attackMs = 30)
        }
        // A siren that never resolves.
        else -> buffer(660) { b ->
            tone(b, 0, 320, 392f, 294f, TRIANGLE, 0.30f, attackMs = 30)
            tone(b, 300, 340, 370f, 277f, TRIANGLE, 0.30f, attackMs = 30)
            tone(b, 0, 620, 98f, 92f, SINE, 0.34f, attackMs = 40)
        }
    }

    /** An arp that starts somewhere other than zero. */
    private fun arp2(
        buf: FloatArray, startMs: Int, notes: FloatArray, stepMs: Int, holdMs: Int, gain: Float
    ) {
        notes.forEachIndexed { i, hz ->
            tone(buf, startMs + i * stepMs, holdMs, hz, hz, SQUARE, gain, 3, 0.5f, 3)
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
    // Music generation
    // -----------------------------------------------------------------

    /**
     * Sixteen bars of bass-led chip music, generated from a per-track spec: a key,
     * a chord progression, a bass figure, a drum pattern and whether a lead plays.
     *
     * The weight is deliberately all at the bottom - a sine sub doubling a square
     * bass, and a kick that drops two octaves in ninety milliseconds - so the cut
     * sounds sit above the track rather than fight it.
     *
     * Every voice is written with wrap-around, so a note struck in the last bar
     * rings on across the loop point into the first. That is what makes the seam
     * inaudible: the buffer is a circle, not a clip.
     */
    private fun renderTrack(index: Int): ShortArray {
        val spec = TRACKS[index.coerceIn(0, TRACKS.size - 1)]
        val stepMs = (60_000f / BASE_BPM / 2f).roundToInt()      // an eighth note
        val bars = 16
        val steps = bars * 8
        val buf = FloatArray(samplesFor(steps * stepMs))

        val bassFigure = BASS_FIGURES[spec.bass]
        val kickFigure = KICK_FIGURES[spec.kick]
        val third = if (spec.minor) MINOR_THIRD else MAJOR_THIRD

        for (bar in 0 until bars) {
            // Two bars per chord, so sixteen bars is an eight-chord progression.
            val root = spec.root * semitone(spec.progression[(bar / 2) % spec.progression.size])
            // The second half of the loop lifts an octave in the lead and drops a
            // note from the bass: enough that thirty-five seconds does not feel
            // like the same four bars eight times.
            val secondHalf = bar >= 8

            for (eighth in 0 until 8) {
                val at = (bar * 8 + eighth) * stepMs
                val hold = (stepMs * 0.92f).toInt()

                val mult = bassFigure[eighth]
                if (mult > 0f && !(secondHalf && eighth == 6)) {
                    val hz = root * mult
                    tone(buf, at, hold, hz, hz, SQUARE, 0.24f, attackMs = 4, duty = 0.25f, crush = 4, wrap = true)
                    // The sub is what makes it bassy rather than merely low.
                    tone(buf, at, (stepMs * 0.95f).toInt(), hz, hz, SINE, 0.34f, attackMs = 6, wrap = true)
                }

                if (kickFigure[eighth]) {
                    tone(buf, at, 95, 150f, 45f, SINE, 0.55f, attackMs = 1, wrap = true)
                    tone(buf, at, 22, 0f, 0f, NOISE, 0.10f, attackMs = 1, crush = 4, wrap = true)
                }

                // Hats on the offbeats, quiet enough to be felt more than heard.
                if (eighth % 2 == 1) {
                    tone(buf, at, 26, 0f, 0f, NOISE, 0.07f, attackMs = 1, crush = 2, wrap = true)
                }
                // A snare-ish crack on the backbeat.
                if (eighth == 4) {
                    tone(buf, at, 70, 0f, 0f, NOISE, 0.16f, attackMs = 2, crush = 3, wrap = true)
                    tone(buf, at, 60, 220f, 170f, TRIANGLE, 0.10f, attackMs = 2, wrap = true)
                }

                if (spec.lead && eighth >= 4) {
                    // Root, third, fifth, octave of the current chord, an octave
                    // higher in the back half.
                    val degrees = floatArrayOf(1f, third, FIFTH, 2f)
                    val lead = root * 8f * degrees[eighth % 4] * (if (secondHalf) 2f else 1f)
                    tone(buf, at, (stepMs * 0.6f).toInt(), lead, lead, TRIANGLE, 0.085f, attackMs = 8, wrap = true)
                }
            }
        }
        // No tail fade: a fade would be an audible dip once a bar.
        return finish(buf, peak = 21000f, fadeMs = 0)
    }

    private fun semitone(steps: Int): Float = 2f.pow(steps / 12f)

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
     * [wrap] sends anything past the end of the buffer back to the beginning, which
     * is what lets a loop's last note ring across its own seam.
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
        crush: Int = 1,
        wrap: Boolean = false
    ) {
        val start = samplesFor(startMs)
        val length = samplesFor(durationMs)
        if (buf.isEmpty() || length <= 0) return
        if (!wrap && start >= buf.size) return

        val attack = samplesFor(attackMs).coerceAtLeast(1)
        var phase = 0f
        var noise = 0x7FFF
        var held = 0f

        for (i in 0 until length) {
            val raw = start + i
            if (!wrap && raw >= buf.size) break
            val index = if (wrap) raw % buf.size else raw

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
        // Cutting a wave mid-cycle is an audible click, so one-shots fade out. A
        // loop must not: the fade would become a dip once every pass.
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
        out.putInt(16)
        out.putShort(1)         // PCM
        out.putShort(1)         // mono
        out.putInt(RATE)
        out.putInt(RATE * 2)
        out.putShort(2)
        out.putShort(16)
        out.put("data".toByteArray())
        out.putInt(dataSize)
        for (s in samples) out.putShort(s)
        file.writeBytes(out.array())
    }

    /** One sound of a spoken line. */

    private class TrackSpec(
        val root: Float,
        val minor: Boolean,
        /** Semitone offsets from the root, one per two bars. */
        val progression: IntArray,
        val bass: Int,
        val kick: Int,
        val lead: Boolean
    )

    private companion object {
        /** Bumped whenever the set is retuned, so the cache cannot serve stale waves. */
        const val RENDER_VERSION = 5

        const val BANK_SIZE = 10
        const val RATE = 22050
        const val BASE_BPM = 108f
        const val CHUNK = 4096
        const val TRACK_COUNT = 10

        /** Roughly +10 BPM per thousand points, capped before it becomes silly. */
        const val MAX_MUSIC_SPEED = 1.75f

        const val SQUARE = 0
        const val TRIANGLE = 1
        const val SAW = 2
        const val NOISE = 3
        const val SINE = 4

        const val MINOR_THIRD = 1.1892f
        const val MAJOR_THIRD = 1.2599f
        const val FIFTH = 1.4983f

        val PITCH_STEPS = floatArrayOf(0.84f, 0.89f, 0.94f, 1.0f, 1.06f, 1.12f, 1.19f)

        /**
         * The announcer, as recorded takes in the assets. The bank picks one at
         * random and never the one it just used, so a run of perfect cuts is a run
         * of different lines.
         */
        val VOICE_FILES = arrayOf(
            "that_is_perfect.mp3",
            "thats_perfect.mp3",
            "splitacular.mp3",
            "dead_center.mp3",
            "wow.mp3",
            "holy_moly.mp3",
            "my_goodness.mp3",
            "perfection.mp3"
        )

        /** Eighth-note bass figures. Zero is a rest. */
        val BASS_FIGURES = arrayOf(
            floatArrayOf(1f, 1f, 1f, FIFTH, 1f, 1f, 2f, FIFTH),
            floatArrayOf(1f, 0f, 1f, 1f, 0f, FIFTH, 1f, 0f),
            floatArrayOf(1f, 1f, 2f, 1f, FIFTH, 1f, 2f, FIFTH),
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            floatArrayOf(1f, 0f, FIFTH, 0f, 2f, 0f, FIFTH, 0f),
            floatArrayOf(1f, 1f, 0f, FIFTH, 0f, 1f, 2f, 0f)
        )

        val KICK_FIGURES = arrayOf(
            booleanArrayOf(true, false, false, false, true, false, false, false),
            booleanArrayOf(true, false, false, true, false, false, true, false),
            booleanArrayOf(true, false, true, false, true, false, true, false),
            booleanArrayOf(true, false, false, false, true, false, false, true)
        )

        /**
         * Ten tracks. All bass-led and low, but no two share a key, a progression,
         * a bass figure and a drum pattern, so a long session does not settle.
         */
        val TRACKS = arrayOf(
            TrackSpec(55.0f, true, intArrayOf(0, 0, -4, -2, 0, 0, -4, -5), 0, 0, true),
            TrackSpec(49.0f, true, intArrayOf(0, 3, -2, 0, 0, 3, 5, 3), 2, 1, true),
            TrackSpec(43.65f, false, intArrayOf(0, 5, 7, 5, 0, 5, 7, 2), 1, 2, false),
            TrackSpec(58.27f, true, intArrayOf(0, -3, -5, -3, 0, -3, 2, 0), 3, 3, true),
            TrackSpec(51.91f, true, intArrayOf(0, 0, 5, 3, 0, 0, -2, -4), 4, 0, false),
            TrackSpec(61.74f, false, intArrayOf(0, 7, 5, 2, 0, 7, 3, 5), 0, 2, true),
            TrackSpec(46.25f, true, intArrayOf(0, 2, 3, 5, 0, 2, 3, 7), 5, 1, true),
            TrackSpec(65.41f, true, intArrayOf(0, -5, -3, -1, 0, -5, -7, -5), 2, 3, false),
            TrackSpec(41.20f, false, intArrayOf(0, 4, 7, 4, 0, 9, 7, 5), 3, 0, true),
            TrackSpec(69.30f, true, intArrayOf(0, 0, -2, -4, -5, -4, -2, 0), 1, 2, true)
        )
    }
}

/**
 * The one engine, shared by every screen.
 *
 * A settings screen that built its own would load a second copy of the whole
 * sample set to play one click, and would not know the volume the game is running
 * at. This hands all of them the same one.
 */
object Sounds {

    @Volatile private var instance: SoundEngine? = null

    fun of(context: Context): SoundEngine =
        instance ?: synchronized(this) {
            instance ?: SoundEngine(context).also { instance = it }
        }

    /**
     * Readies the engine and matches it to the player's preferences. Called on the
     * way into any screen with buttons, since loading is asynchronous and a first
     * tap should not be the thing that starts it.
     */
    fun arm(context: Context, settings: GameSettings) {
        of(context).apply {
            enabled = settings.soundEnabled
            volume = settings.soundVolume
            voiceEnabled = settings.voiceEnabled
            voiceVolume = settings.voiceVolume
            musicVolume = settings.musicVolume
            prepare()
        }
    }

    /** The click every button in the app makes - one of ten, pitched. */
    fun click(context: Context) {
        of(context).play(SfxBank.UI)
    }
}
