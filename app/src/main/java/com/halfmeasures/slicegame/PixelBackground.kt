package com.halfmeasures.slicegame

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A quiet field of pixel embers behind the play field.
 *
 * Deliberately restrained: a sparse drift of small unlit squares rising like
 * embers off a fire, plus a burst wherever the player touches or cuts. The only
 * other element is a soft glow along the bottom edge whose colour reports the
 * state of the run - it shifts with the difficulty stage, warms on a hot streak
 * and bleeds red as health drains.
 */
class PixelBackground(private val random: Random) {

    private class Ember(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val size: Float,
        val lifeSpan: Float,
        val swaySpeed: Float,
        var swayPhase: Float,
        /** Bursts die off; ambient embers recycle back to the bottom forever. */
        val ambient: Boolean,
        val bright: Boolean
    ) {
        var age = 0f
        val alive: Boolean get() = age < lifeSpan

        /** Fades in at birth and out at death so embers never pop. */
        val fade: Float
            get() {
                val t = (age / lifeSpan).coerceIn(0f, 1f)
                return when {
                    t < 0.15f -> t / 0.15f
                    t > 0.82f -> (1f - t) / 0.18f
                    else -> 1f
                }
            }
    }

    private var width = 0
    private var height = 0
    private var pixel = 4f

    private val embers = ArrayList<Ember>()

    private var time = 0f
    private var pulse = 0f

    // Eased so stage changes and health swings glide rather than snap.
    private var glowColor = STAGE_PALETTES[0][1]
    private var emberColor = STAGE_PALETTES[0][2]

    // Player-tunable, refreshed from settings each frame.
    private var density = 1f
    private var brightness = 1f
    private var sizeScale = 1f
    private var drift = 1f

    fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        width = w
        height = h
        pixel = (w / 90f).coerceAtLeast(3f)
        embers.clear()
    }

    fun reset() {
        embers.clear()
        pulse = 0f
    }

    /** A perfect cut brightens the whole field for a moment. */
    fun flash(amount: Float) {
        pulse = (pulse + amount * 0.35f).coerceAtMost(1f)
    }

    /** A puff of embers wherever the player touched or a shape was cut. */
    fun burst(x: Float, y: Float, strength: Float) {
        if (height <= 0) return
        val count = (7 * strength * density).roundToInt().coerceIn(1, 26)
        repeat(count) {
            if (embers.size > MAX_EMBERS) return@repeat
            val angle = random.nextFloat() * 6.2832f
            val speed = (35f + random.nextFloat() * 130f) * strength
            embers.add(
                Ember(
                    x = x + (random.nextFloat() - 0.5f) * pixel * 5f,
                    y = y + (random.nextFloat() - 0.5f) * pixel * 5f,
                    vx = kotlin.math.cos(angle) * speed,
                    // Biased upward, so a burst still reads as embers lifting off.
                    vy = kotlin.math.sin(angle) * speed - 60f * strength,
                    size = pixel * (0.7f + random.nextFloat() * 0.9f),
                    lifeSpan = 0.8f + random.nextFloat() * 1.1f,
                    swaySpeed = 1.4f + random.nextFloat() * 2.2f,
                    swayPhase = random.nextFloat() * 6.2832f,
                    ambient = false,
                    bright = random.nextFloat() < 0.4f
                )
            )
        }
    }

    /**
     * @param energy excitement from the effect system; lifts brightness a little.
     * @param healthFraction 0..1, drains the glow toward red as it falls.
     * @param stage difficulty stage, which picks the base palette.
     * @param warmth -1 on a cold streak, +1 on a hot one.
     */
    fun update(
        dt: Float,
        energy: Float,
        healthFraction: Float,
        stage: Int,
        warmth: Float,
        emberDensity: Float,
        emberBrightness: Float,
        emberSize: Float,
        driftSpeed: Float
    ) {
        density = emberDensity
        brightness = emberBrightness
        sizeScale = emberSize
        drift = driftSpeed

        time += dt
        pulse = (pulse - dt * 1.4f).coerceAtLeast(0f)

        easePaletteToward(stage, healthFraction, warmth)
        topUpAmbient()

        var i = embers.size - 1
        while (i >= 0) {
            val e = embers[i]
            e.age += dt
            e.swayPhase += e.swaySpeed * dt
            e.x += (e.vx + sin(e.swayPhase) * 14f) * dt * drift
            e.y += e.vy * dt * drift
            if (!e.ambient) {
                // Bursts slow down and settle; ambient embers keep their lazy rise.
                e.vx *= 0.97f
                e.vy = e.vy * 0.97f - 8f * dt
            }
            if (!e.alive || e.y < -pixel * 4f) embers.removeAt(i)
            i--
        }
    }

    /** Keeps a steady population of drifting embers without ever spawning a crowd. */
    private fun topUpAmbient() {
        if (width <= 0) return
        val target = (AMBIENT_BASE * density).roundToInt().coerceIn(0, MAX_EMBERS)
        var ambientCount = 0
        for (e in embers) if (e.ambient) ambientCount++
        if (ambientCount >= target) return

        // Add at most one per frame so the field fills in gently.
        embers.add(
            Ember(
                x = random.nextFloat() * width,
                // Start scattered up the screen on a cold start, then from the floor.
                y = if (ambientCount < target / 2) random.nextFloat() * height else height + pixel * 2f,
                vx = (random.nextFloat() - 0.5f) * 12f,
                vy = -(34f + random.nextFloat() * 62f),
                size = pixel * (0.5f + random.nextFloat() * 0.7f),
                lifeSpan = 11f + random.nextFloat() * 9f,
                swaySpeed = 0.5f + random.nextFloat() * 1.1f,
                swayPhase = random.nextFloat() * 6.2832f,
                ambient = true,
                bright = random.nextFloat() < 0.25f
            )
        )
    }

    private fun easePaletteToward(stage: Int, healthFraction: Float, warmth: Float) {
        // Cycles rather than clamps: stages now run to a hundred and the field
        // should keep moving through hues rather than parking on the last one.
        val palette = STAGE_PALETTES[((stage % STAGE_PALETTES.size) + STAGE_PALETTES.size) % STAGE_PALETTES.size]
        // Below a third health the field bleeds red; at death's door it is fully alarmed.
        val alarm = (1f - healthFraction / 0.35f).coerceIn(0f, 1f)

        var targetGlow = Theme.lerpColor(palette[1], DANGER_PALETTE[0], alarm)
        var targetEmber = Theme.lerpColor(palette[2], DANGER_PALETTE[1], alarm)

        // A hot streak warms the field toward gold, a cold one cools it toward grey.
        if (warmth > 0f) {
            targetGlow = Theme.lerpColor(targetGlow, HOT_GLOW, warmth * 0.55f)
            targetEmber = Theme.lerpColor(targetEmber, HOT_EMBER, warmth * 0.65f)
        } else if (warmth < 0f) {
            targetGlow = Theme.lerpColor(targetGlow, COLD_GLOW, -warmth * 0.5f)
            targetEmber = Theme.lerpColor(targetEmber, COLD_EMBER, -warmth * 0.5f)
        }

        val ease = 0.04f
        glowColor = Theme.lerpColor(glowColor, targetGlow, ease)
        emberColor = Theme.lerpColor(emberColor, targetEmber, ease)
    }

    /** The horizon glow whose colour reports the state of the run. */
    fun horizonColor(): Int = glowColor

    fun draw(canvas: Canvas, paint: Paint, energy: Float) {
        if (embers.isEmpty()) return
        paint.style = Paint.Style.FILL

        val lift = (1f + pulse * 0.8f + energy.coerceAtMost(1.5f) * 0.25f)
        val bright = Theme.lighten(emberColor, 0.35f)

        for (e in embers) {
            // Ambient embers sit well back; bursts read a touch stronger.
            val base = if (e.ambient) 0.30f else 0.55f
            val alpha = (base * e.fade * brightness * lift).coerceIn(0f, 0.85f)
            if (alpha <= 0.01f) continue

            paint.color = Theme.withAlpha(if (e.bright) bright else emberColor, alpha)
            // Snap to the pixel grid so every ember stays a crisp little square.
            val s = (e.size * sizeScale).coerceAtLeast(1.5f)
            val px = (e.x / pixel).roundToInt() * pixel
            val py = (e.y / pixel).roundToInt() * pixel
            canvas.drawRect(px - s, py - s, px + s, py + s, paint)
        }
    }

    companion object {
        private const val AMBIENT_BASE = 46
        private const val MAX_EMBERS = 260

        /** Base palettes, one per difficulty stage, cycling once the list runs out. */
        private val STAGE_PALETTES = arrayOf(
            // Stage one is near-black to match the backdrop: the embers are the only
            // colour on screen until the score starts pulling the scene toward a hue.
            intArrayOf(Color.rgb(0, 0, 0), Color.rgb(20, 30, 62), Color.rgb(74, 112, 178)),
            intArrayOf(Color.rgb(8, 12, 30), Color.rgb(34, 62, 130), Color.rgb(96, 150, 235)),
            intArrayOf(Color.rgb(10, 8, 32), Color.rgb(70, 40, 132), Color.rgb(160, 116, 240)),
            intArrayOf(Color.rgb(6, 20, 26), Color.rgb(24, 92, 96), Color.rgb(88, 210, 195)),
            intArrayOf(Color.rgb(26, 10, 26), Color.rgb(118, 40, 100), Color.rgb(232, 124, 186)),
            intArrayOf(Color.rgb(26, 18, 6), Color.rgb(122, 82, 26), Color.rgb(236, 180, 92)),
            intArrayOf(Color.rgb(4, 24, 14), Color.rgb(26, 100, 62), Color.rgb(112, 220, 148))
        )

        private val DANGER_PALETTE = intArrayOf(
            Color.rgb(112, 22, 38), Color.rgb(236, 86, 100)
        )

        private val HOT_GLOW = Color.rgb(140, 84, 20)
        private val HOT_EMBER = Color.rgb(255, 186, 88)
        private val COLD_GLOW = Color.rgb(38, 44, 58)
        private val COLD_EMBER = Color.rgb(120, 134, 156)

        fun paletteCount(): Int = STAGE_PALETTES.size
    }
}
