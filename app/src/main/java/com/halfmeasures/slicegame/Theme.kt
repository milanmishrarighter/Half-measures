package com.halfmeasures.slicegame

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface

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
     * The colour the whole scene drifts through as the player levels up. It starts
     * at true black - a fresh run is as dark as the screen goes - and only picks up
     * a hue as the score climbs, so the colour itself is a read on how far you got.
     * Used flat as the backdrop and blended into the shapes, so a level change reads
     * as a shift in the light rather than as a banner.
     */
    private val stageRamp = intArrayOf(
        Color.rgb(0, 0, 0),      // black - where every run starts
        Color.rgb(12, 16, 38),   // indigo
        Color.rgb(26, 16, 48),   // violet
        Color.rgb(40, 14, 46),   // plum
        Color.rgb(44, 16, 34),   // magenta-ish
        Color.rgb(30, 26, 22),   // warm neutral
        Color.rgb(12, 32, 34),   // teal
        Color.rgb(10, 34, 24)    // green
    )

    /** Accent hues matching [stageRamp], for tinting the shapes and the horizon. */
    private val stageAccents = intArrayOf(
        Color.rgb(72, 100, 210),
        Color.rgb(96, 132, 255),
        Color.rgb(150, 110, 255),
        Color.rgb(206, 104, 226),
        Color.rgb(238, 104, 168),
        Color.rgb(226, 176, 96),
        Color.rgb(88, 216, 208),
        Color.rgb(104, 224, 148)
    )

    /**
     * Walks the ramp one entry per level, then turns around and walks back, so a
     * long run keeps drifting through hues instead of parking on the last one.
     * The gradual part is handled by the caller easing toward this target.
     */
    private fun sampleRamp(ramp: IntArray, stage: Int): Int {
        if (stage <= 0) return ramp[0]
        val period = (ramp.size - 1) * 2
        val position = stage % period
        val index = if (position < ramp.size) position else period - position
        return ramp[index.coerceIn(0, ramp.size - 1)]
    }

    fun stageBackground(stage: Int): Int = sampleRamp(stageRamp, stage)

    fun stageAccent(stage: Int): Int = sampleRamp(stageAccents, stage)

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
