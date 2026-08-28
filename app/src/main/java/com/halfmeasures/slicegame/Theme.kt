package com.halfmeasures.slicegame

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import kotlin.math.pow

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

    /** Shape fill palette - each entry is a (light, deep) pair used for the body gradient. */
    val shapePalette = arrayOf(
        intArrayOf(Color.rgb(255, 122, 158), Color.rgb(206, 43, 92)),
        intArrayOf(Color.rgb(255, 216, 122), Color.rgb(232, 145, 26)),
        intArrayOf(Color.rgb(120, 240, 200), Color.rgb(24, 168, 142)),
        intArrayOf(Color.rgb(120, 196, 255), Color.rgb(30, 118, 208)),
        intArrayOf(Color.rgb(188, 150, 255), Color.rgb(124, 66, 214)),
        intArrayOf(Color.rgb(255, 168, 128), Color.rgb(224, 96, 54))
    )

    /** Score at which the backdrop reaches full red. */
    const val RED_AT = 100_000f
    private const val BLUE_HUE = 232f
    private const val RED_HUE = 360f

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
     * The backdrop is a function of the score rather than of the difficulty stage.
     *
     * A run starts at true black and walks up through dark blue, violet and
     * magenta to a bright red at [RED_AT] points. Value is raised on a square-root
     * curve so the first few thousand points visibly move the colour - a linear
     * ramp to 100,000 would look black for the first ten minutes - while the top
     * end still has somewhere left to go.
     */
    fun scoreProgress(score: Int): Float =
        (score.toFloat() / RED_AT).coerceIn(0f, 1f)

    fun scoreBackground(score: Int): Int {
        val t = scoreProgress(score)
        val hue = (BLUE_HUE + (RED_HUE - BLUE_HUE) * t) % 360f
        val value = 0.55f * t.pow(0.5f)
        val saturation = 0.95f - 0.12f * t
        return Color.HSVToColor(floatArrayOf(hue, saturation, value))
    }

    /** The same hue, lit, for tinting shapes and the floor seam. */
    fun scoreAccent(score: Int): Int {
        val t = scoreProgress(score)
        val hue = (BLUE_HUE + (RED_HUE - BLUE_HUE) * t) % 360f
        return Color.HSVToColor(floatArrayOf(hue, 0.62f, 0.92f))
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(
            (alpha.coerceIn(0f, 1f) * 255).toInt(),
            Color.red(color), Color.green(color), Color.blue(color)
        )

    /** Straight RGB interpolation, [t] clamped to 0..1. */
    fun lerpColor(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * k).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * k).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k).toInt()
        )
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
