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

    /** Score at which the backdrop reaches full red. */
    const val RED_AT = 100_000f

    /**
     * The backdrop never gets brighter than this; it sits behind lit shapes, so
     * every stop below is a dark version of its hue rather than the hue itself.
     */
    private const val MAX_BACKDROP_VALUE = 0.44f

    /**
     * The sky the run is played under, as a list of stops: score, hue, and how far
     * up the brightness range that stop sits.
     *
     * This was a single curve from blue to red before, which meant the whole
     * middle of a run was one long violet and the first few thousand points were
     * indistinguishable from black. Stops let the walk be stated outright: black
     * off the line, a real dark blue by three thousand, violet by ten, green by
     * fifteen, yellow by twenty, then down through amber to red at a hundred
     * thousand. The hue doubles back once, between violet and green, which is
     * seen as the sky cooling through blue and teal on its way over.
     */
    private val BACKDROP_STOPS = arrayOf(
        floatArrayOf(0f, 230f, 0.00f),
        floatArrayOf(1_000f, 230f, 0.34f),
        floatArrayOf(3_000f, 226f, 0.60f),
        floatArrayOf(6_000f, 256f, 0.73f),
        floatArrayOf(10_000f, 288f, 0.83f),
        floatArrayOf(15_000f, 145f, 0.89f),
        floatArrayOf(20_000f, 52f, 0.94f),
        floatArrayOf(30_000f, 32f, 0.97f),
        floatArrayOf(50_000f, 16f, 1.00f),
        floatArrayOf(RED_AT, 0f, 1.00f)
    )

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

    /** Where [score] sits between two stops, as hue and brightness fraction. */
    private fun backdropAt(score: Int): FloatArray {
        val s = score.toFloat().coerceIn(0f, RED_AT)
        var i = 0
        while (i < BACKDROP_STOPS.size - 2 && s > BACKDROP_STOPS[i + 1][0]) i++
        val a = BACKDROP_STOPS[i]
        val b = BACKDROP_STOPS[i + 1]
        val span = (b[0] - a[0]).coerceAtLeast(1f)
        val t = ((s - a[0]) / span).coerceIn(0f, 1f)
        return floatArrayOf(a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
    }

    fun scoreBackground(score: Int): Int {
        val at = backdropAt(score)
        val level = at[1]
        // Saturation eases off as the sky brightens, or the top of the range reads
        // as a flat poster colour rather than as light.
        return Color.HSVToColor(
            floatArrayOf(at[0], 0.88f - 0.14f * level, MAX_BACKDROP_VALUE * level)
        )
    }

    /** The same hue, lit, for the floor seam and anything that echoes the sky. */
    fun scoreAccent(score: Int): Int =
        Color.HSVToColor(floatArrayOf(backdropAt(score)[0], 0.62f, 0.92f))

    /**
     * What the shapes are tinted toward: the sky's complement rather than the sky
     * itself, a third of the wheel away. Pulling them toward the backdrop's own
     * hue made a violet shape on a violet sky - the whole point of tinting them is
     * that they keep standing off it as it moves.
     */
    fun scoreShapeTint(score: Int): Int =
        Color.HSVToColor(floatArrayOf((backdropAt(score)[0] + 165f) % 360f, 0.58f, 0.98f))

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
