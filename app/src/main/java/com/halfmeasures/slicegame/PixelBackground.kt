package com.halfmeasures.slicegame

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A chunky pixel dance floor behind the play field.
 *
 * The grid is deliberately low resolution and colour-banded so it reads as
 * retro pixel art rather than a smooth gradient. Cell brightness comes from a
 * plasma field plus ripples kicked off by cuts and touches, and the palette
 * shifts with the player's stage and drains toward red as health falls - so the
 * backdrop is always reporting how the run is going.
 */
class PixelBackground(private val random: Random) {

    private class Ripple(val cx: Float, val cy: Float, val strength: Float) {
        var age = 0f
        val alive: Boolean get() = age < 1.6f
    }

    /** A pixel mote drifting up from the floor, spawned where the player touches. */
    private class Mote(
        var x: Float,
        var y: Float,
        var vy: Float,
        val size: Float,
        val lifeSpan: Float,
        val tint: Int
    ) {
        var age = 0f
        val alive: Boolean get() = age < lifeSpan
        val remaining: Float get() = (1f - age / lifeSpan).coerceIn(0f, 1f)
    }

    private var cols = 0
    private var rows = 0
    private var cellSize = 0f
    private var width = 0
    private var height = 0

    private val ripples = ArrayList<Ripple>()
    private val motes = ArrayList<Mote>()

    private var time = 0f
    private var pulse = 0f

    // Current palette, eased toward the target so stage changes glide rather than snap.
    private var lowColor = STAGE_PALETTES[0][0]
    private var midColor = STAGE_PALETTES[0][1]
    private var highColor = STAGE_PALETTES[0][2]

    fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        width = w
        height = h
        cols = 22
        cellSize = w.toFloat() / cols
        rows = (h / cellSize).toInt() + 1
        ripples.clear()
        motes.clear()
    }

    fun reset() {
        ripples.clear()
        motes.clear()
        pulse = 0f
    }

    /** A cut, a bounce or a tap sends a wave across the floor. */
    fun ripple(x: Float, y: Float, strength: Float) {
        if (ripples.size > 18) ripples.removeAt(0)
        ripples.add(Ripple(x, y, strength))
    }

    /** Whole-floor flash, for perfects and other big moments. */
    fun flash(amount: Float) {
        pulse = (pulse + amount).coerceAtMost(2.2f)
    }

    /**
     * Touching the floor kicks pixels up out of it. The closer to the bottom of
     * the screen, the more come up - the floor feels physical near its surface.
     */
    fun touch(x: Float, y: Float) {
        if (height <= 0) return
        ripple(x, y, 0.85f)

        val depth = (y / height).coerceIn(0f, 1f)
        // Only the lower part of the screen throws motes, densest right at the floor.
        if (depth < 0.55f) return
        val strength = ((depth - 0.55f) / 0.45f).coerceIn(0f, 1f)
        val count = (1 + strength * 4f).roundToInt()
        repeat(count) {
            if (motes.size > 160) return@repeat
            motes.add(
                Mote(
                    x = x + (random.nextFloat() - 0.5f) * cellSize * 3.2f,
                    y = height + random.nextFloat() * cellSize,
                    vy = -(90f + random.nextFloat() * 230f) * (0.5f + strength),
                    size = cellSize * (0.28f + random.nextFloat() * 0.42f),
                    lifeSpan = 0.7f + random.nextFloat() * 1.1f,
                    tint = if (random.nextFloat() < 0.35f) highColor else midColor
                )
            )
        }
    }

    /**
     * @param energy excitement level from the effect system, brightens everything.
     * @param healthFraction 0..1, drains the palette toward red as it falls.
     * @param stage difficulty stage, which picks the base palette.
     */
    fun update(dt: Float, energy: Float, healthFraction: Float, stage: Int) {
        time += dt * (0.55f + 0.35f * energy.coerceAtMost(1.5f))
        pulse = (pulse - dt * 2.6f).coerceAtLeast(0f)

        var i = ripples.size - 1
        while (i >= 0) {
            val r = ripples[i]
            r.age += dt
            if (!r.alive) ripples.removeAt(i)
            i--
        }

        i = motes.size - 1
        while (i >= 0) {
            val m = motes[i]
            m.age += dt
            m.y += m.vy * dt
            m.vy *= 0.985f
            if (!m.alive || m.y < -cellSize) motes.removeAt(i)
            i--
        }

        easePaletteToward(stage, healthFraction)
    }

    private fun easePaletteToward(stage: Int, healthFraction: Float) {
        val palette = STAGE_PALETTES[stage.coerceIn(0, STAGE_PALETTES.size - 1)]
        // Below a third health the floor bleeds red; at death's door it is fully alarmed.
        val alarm = (1f - healthFraction / 0.35f).coerceIn(0f, 1f)

        val targetLow = Theme.lerpColor(palette[0], DANGER_PALETTE[0], alarm)
        val targetMid = Theme.lerpColor(palette[1], DANGER_PALETTE[1], alarm)
        val targetHigh = Theme.lerpColor(palette[2], DANGER_PALETTE[2], alarm)

        val ease = 0.045f
        lowColor = Theme.lerpColor(lowColor, targetLow, ease)
        midColor = Theme.lerpColor(midColor, targetMid, ease)
        highColor = Theme.lerpColor(highColor, targetHigh, ease)
    }

    fun draw(canvas: Canvas, paint: Paint) {
        if (cols <= 0 || rows <= 0) return

        val gap = (cellSize * 0.13f).coerceAtLeast(1f)
        val drawSize = cellSize - gap
        paint.style = Paint.Style.FILL

        for (row in 0 until rows) {
            val cy = row * cellSize + cellSize * 0.5f
            val fy = row.toFloat() / rows
            for (col in 0 until cols) {
                val cx = col * cellSize + cellSize * 0.5f
                val fx = col.toFloat() / cols

                var v = plasma(fx, fy)
                v += rippleAt(cx, cy)
                // Brighter toward the floor, so the stage has a horizon.
                v += fy * fy * 0.30f
                v += pulse * 0.32f
                v = v.coerceIn(0f, 1f)

                // Quantise into bands - this is what makes it read as pixel art.
                val banded = (v * BANDS).roundToInt() / BANDS.toFloat()
                if (banded <= 0.001f) continue

                paint.color = colorFor(banded)
                canvas.drawRect(cx - drawSize / 2f, cy - drawSize / 2f,
                    cx + drawSize / 2f, cy + drawSize / 2f, paint)
            }
        }

        // Motes ride on top of the grid, snapped to the same pixel scale.
        for (m in motes) {
            val alpha = (m.remaining * 1.5f).coerceAtMost(1f)
            paint.color = Theme.withAlpha(m.tint, alpha * 0.85f)
            val s = m.size
            canvas.drawRect(m.x - s, m.y - s, m.x + s, m.y + s, paint)
        }
    }

    /** Three interfering waves, the classic demoscene plasma. */
    private fun plasma(fx: Float, fy: Float): Float {
        val a = sin(fx * 6.9f + time * 1.15f)
        val b = sin(fy * 5.3f - time * 0.87f)
        val c = sin((fx + fy) * 4.4f + time * 1.6f)
        val d = cos(sqrt((fx - 0.5f) * (fx - 0.5f) + (fy - 0.5f) * (fy - 0.5f)) * 11f - time * 1.9f)
        return ((a + b + c + d) / 4f) * 0.5f + 0.32f
    }

    private fun rippleAt(cx: Float, cy: Float): Float {
        if (ripples.isEmpty()) return 0f
        var total = 0f
        for (r in ripples) {
            val dx = cx - r.cx
            val dy = cy - r.cy
            val dist = sqrt(dx * dx + dy * dy)
            val front = r.age * 950f
            val band = abs(dist - front)
            if (band > 190f) continue
            val falloff = (1f - band / 190f)
            val decay = (1f - r.age / 1.6f).coerceAtLeast(0f)
            total += falloff * falloff * decay * r.strength * 0.85f
        }
        return total
    }

    private fun colorFor(v: Float): Int =
        if (v < 0.5f) Theme.lerpColor(lowColor, midColor, v * 2f)
        else Theme.lerpColor(midColor, highColor, (v - 0.5f) * 2f)

    companion object {
        private const val BANDS = 7

        /** Base palettes, one per difficulty stage, cycling once the list runs out. */
        private val STAGE_PALETTES = arrayOf(
            intArrayOf(Color.rgb(8, 12, 30), Color.rgb(28, 52, 120), Color.rgb(92, 156, 255)),
            intArrayOf(Color.rgb(10, 8, 32), Color.rgb(66, 34, 128), Color.rgb(168, 110, 255)),
            intArrayOf(Color.rgb(6, 20, 26), Color.rgb(18, 92, 96), Color.rgb(82, 226, 205)),
            intArrayOf(Color.rgb(26, 10, 26), Color.rgb(120, 30, 96), Color.rgb(255, 122, 196)),
            intArrayOf(Color.rgb(26, 18, 6), Color.rgb(128, 78, 18), Color.rgb(255, 190, 78)),
            intArrayOf(Color.rgb(4, 24, 14), Color.rgb(20, 106, 58), Color.rgb(110, 240, 150))
        )

        private val DANGER_PALETTE = intArrayOf(
            Color.rgb(30, 4, 8), Color.rgb(132, 18, 38), Color.rgb(255, 78, 96)
        )

        fun paletteCount(): Int = STAGE_PALETTES.size
    }
}
