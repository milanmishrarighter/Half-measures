package com.halfmeasures.slicegame

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One place for the game's colours and typefaces, so the play surface and the
 * settings screen look like the same product.
 */
object Theme {

    // Backdrop
    val bgTop = Color.rgb(18, 22, 40)
    val bgBottom = Color.rgb(7, 9, 18)
    val bgGlow = Color.rgb(58, 82, 168)

    // Surfaces
    val card = Color.rgb(23, 28, 48)
    val cardRaised = Color.rgb(31, 38, 62)
    val hairline = Color.argb(38, 255, 255, 255)

    // Text
    val textPrimary = Color.rgb(238, 243, 255)
    val textSecondary = Color.argb(165, 214, 226, 255)
    val textFaint = Color.argb(105, 202, 216, 255)

    // Accents
    val accent = Color.rgb(94, 234, 212)      // teal - primary UI accent
    val accentDeep = Color.rgb(45, 190, 190)
    val gold = Color.rgb(255, 209, 102)       // perfect cuts
    val goldDeep = Color.rgb(255, 168, 46)
    val danger = Color.rgb(255, 89, 122)
    val good = Color.rgb(80, 226, 160)
    /** A third summary hue, so best, average and rank never share a colour. */
    val violet = Color.rgb(167, 139, 250)
    /** The cool end of the rank ladder; the warm end is [gold]. */
    val rankFar = Color.rgb(96, 165, 250)

    /** Shape fill palette - each entry is a (light, deep) pair used for the body gradient. */
    val shapePalette = arrayOf(
        intArrayOf(Color.rgb(255, 122, 158), Color.rgb(206, 43, 92)),
        intArrayOf(Color.rgb(255, 216, 122), Color.rgb(232, 145, 26)),
        intArrayOf(Color.rgb(120, 240, 200), Color.rgb(24, 168, 142)),
        intArrayOf(Color.rgb(120, 196, 255), Color.rgb(30, 118, 208)),
        intArrayOf(Color.rgb(188, 150, 255), Color.rgb(124, 66, 214)),
        intArrayOf(Color.rgb(255, 168, 128), Color.rgb(224, 96, 54))
    )

    /**
     * The score's colour, as a spiral rather than a line.
     *
     * The hue goes round and round the wheel; each lap comes back brighter than
     * the last, so the same blue arrives dull, then solid, then finally neon. The
     * first three thousand points hold still on blue and only lift in brightness,
     * because a colour that is already racing on the first cut has nowhere to go.
     * By fifteen thousand the run is on its third lap, and from twenty-three
     * thousand every colour is at full neon and only the hue keeps turning.
     *
     * The backdrop takes none of this - it is black, and stays black. This colour
     * is for the things the player is looking at anyway: the blade's streak, the
     * debris off a cut, the embers, and the shapes themselves.
     */
    private const val ENERGY_START_HUE = 220f
    private const val ENERGY_INTRO_END = 3_000f
    private const val ENERGY_LAP = 5_000f

    /** Brightness at the end of each lap; the last one is neon and holds there. */
    private val ENERGY_VALUES = floatArrayOf(0.60f, 0.72f, 0.84f, 0.93f, 0.98f, 1f)
    private const val ENERGY_INTRO_VALUE = 0.42f

    private fun energyHsv(score: Int): FloatArray {
        val s = score.toFloat().coerceAtLeast(0f)
        val hue: Float
        val value: Float
        if (s < ENERGY_INTRO_END) {
            val t = s / ENERGY_INTRO_END
            hue = ENERGY_START_HUE
            value = ENERGY_INTRO_VALUE + (ENERGY_VALUES[0] - ENERGY_INTRO_VALUE) * t
        } else {
            val since = s - ENERGY_INTRO_END
            val lap = (since / ENERGY_LAP).toInt()
            val t = (since - lap * ENERGY_LAP) / ENERGY_LAP
            hue = (ENERGY_START_HUE + 360f * t) % 360f
            val from = ENERGY_VALUES[lap.coerceAtMost(ENERGY_VALUES.size - 1)]
            val to = ENERGY_VALUES[(lap + 1).coerceAtMost(ENERGY_VALUES.size - 1)]
            value = from + (to - from) * t
        }
        // Saturation eases down as the value climbs: neon is a bright colour that
        // has let go of a little saturation, not a fully saturated dark one.
        return floatArrayOf(hue, 0.94f - 0.16f * value, value)
    }

    /** How far along the brightness climb [score] sits, 0 dull to 1 neon. */
    fun energyLevel(score: Int): Float {
        val v = energyHsv(score)[2]
        return ((v - ENERGY_INTRO_VALUE) / (1f - ENERGY_INTRO_VALUE)).coerceIn(0f, 1f)
    }

    /** The run's colour at [score] - blade, debris, embers, shape tint. */
    fun scoreEnergy(score: Int): Int = Color.HSVToColor(energyHsv(score))

    /** The same colour held back, for anything that sits behind the play. */
    fun scoreEnergyDim(score: Int): Int {
        val hsv = energyHsv(score)
        return Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], hsv[2] * 0.42f))
    }

    private var display: Typeface? = null
    private var displayBold: Typeface? = null
    private var ui: Typeface? = null
    private var uiBold: Typeface? = null

    /** Orbitron Black - big numbers, titles, the logo. */
    fun display(context: Context): Typeface {
        display?.let { return it }
        val t = load(context, "fonts/orbitron_black.ttf", Typeface.BOLD)
        display = t
        return t
    }

    /** Orbitron Bold - subheadings and score chips. */
    fun displayBold(context: Context): Typeface {
        displayBold?.let { return it }
        val t = load(context, "fonts/orbitron_bold.ttf", Typeface.BOLD)
        displayBold = t
        return t
    }

    /** Rajdhani SemiBold - body copy, slider labels. */
    fun ui(context: Context): Typeface {
        ui?.let { return it }
        val t = load(context, "fonts/rajdhani_semibold.ttf", Typeface.NORMAL)
        ui = t
        return t
    }

    /** Rajdhani Bold - buttons and emphasis. */
    fun uiBold(context: Context): Typeface {
        uiBold?.let { return it }
        val t = load(context, "fonts/rajdhani_bold.ttf", Typeface.BOLD)
        uiBold = t
        return t
    }

    private fun load(context: Context, path: String, fallbackStyle: Int): Typeface =
        try {
            Typeface.createFromAsset(context.assets, path)
        } catch (e: Exception) {
            Typeface.defaultFromStyle(fallbackStyle)
        }

    /**
     * The play surface is black and stays black. A backdrop that walked through
     * hues fought every bright thing drawn on top of it; the score's colour lives
     * in [scoreEnergy] now, on the blade and the debris where it can be seen.
     */
    fun scoreBackground(score: Int): Int = Color.BLACK

    /** The score's colour, lit, for the floor seam and anything echoing the run. */
    fun scoreAccent(score: Int): Int = scoreEnergy(score)


    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(
            (alpha.coerceIn(0f, 1f) * 255).toInt(),
            Color.red(color), Color.green(color), Color.blue(color)
        )

    /**
     * Straight RGB interpolation, [t] clamped to 0..1.
     *
     * The step is rounded, and forced to move at least one level when there is
     * anywhere left to go. Truncating instead is what kept the backdrop black:
     * the per-frame ease is a small fraction, and on a 120Hz screen every channel
     * moved less than a whole level per frame, truncated back to where it started,
     * and sat there for the entire run no matter what the score did.
     */
    fun lerpColor(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            stepChannel(Color.red(from), Color.red(to), k),
            stepChannel(Color.green(from), Color.green(to), k),
            stepChannel(Color.blue(from), Color.blue(to), k)
        )
    }

    private fun stepChannel(from: Int, to: Int, k: Float): Int {
        if (from == to) return from
        val moved = (from + (to - from) * k).roundToInt()
        // Never stall: a rounded step of zero still has to close the gap eventually.
        return if (moved == from) from + if (to > from) 1 else -1 else moved
    }

    fun lighten(color: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(color) + (255 - Color.red(color)) * a).toInt(),
            (Color.green(color) + (255 - Color.green(color)) * a).toInt(),
            (Color.blue(color) + (255 - Color.blue(color)) * a).toInt()
        )
    }

    fun darken(color: Int, amount: Float): Int {
        val a = 1f - amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(color) * a).toInt(),
            (Color.green(color) * a).toInt(),
            (Color.blue(color) * a).toInt()
        )
    }
}
