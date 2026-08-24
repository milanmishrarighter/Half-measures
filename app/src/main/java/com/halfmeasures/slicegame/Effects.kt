package com.halfmeasures.slicegame

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** A single spark thrown off by a cut. */
class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val size: Float,
    val lifeSpan: Float,
    val color: Int,
    val streak: Boolean
) {
    var age = 0f
    val alive: Boolean get() = age < lifeSpan
    /** 1 at birth, 0 at death. */
    val remaining: Float get() = (1f - age / lifeSpan).coerceIn(0f, 1f)

    fun update(dt: Float, gravity: Float) {
        age += dt
        vy += gravity * 0.35f * dt
        vx *= 0.985f
        x += vx * dt
        y += vy * dt
    }
}

/** An expanding ring, used to punctuate a perfect cut. */
class Shockwave(val x: Float, val y: Float, val maxRadius: Float, val color: Int) {
    var age = 0f
    private val lifeSpan = 0.45f
    val alive: Boolean get() = age < lifeSpan
    val progress: Float get() = (age / lifeSpan).coerceIn(0f, 1f)

    fun update(dt: Float) {
        age += dt
    }
}

/** Floating score text that rises and fades after a cut. */
class ScorePopup(
    val headline: String,
    val subline: String,
    val x: Float,
    val y: Float,
    val color: Int,
    val emphatic: Boolean
) {
    var age = 0f
    private val lifeSpan = 1.05f
    val alive: Boolean get() = age < lifeSpan
    val progress: Float get() = (age / lifeSpan).coerceIn(0f, 1f)

    fun update(dt: Float) {
        age += dt
    }
}

/**
 * Owns the transient visual flourishes: sparks, rings, floating score text and
 * the camera shake impulse. Kept separate from [GameView] so the draw loop there
 * stays about the game itself.
 */
class EffectSystem(private val random: Random) {

    val particles = ArrayList<Particle>()
    val shockwaves = ArrayList<Shockwave>()
    val popups = ArrayList<ScorePopup>()

    var shake = 0f
        private set

    fun clear() {
        particles.clear()
        shockwaves.clear()
        popups.clear()
        shake = 0f
    }

    fun update(dt: Float, gravity: Float) {
        var i = particles.size - 1
        while (i >= 0) {
            val p = particles[i]
            p.update(dt, gravity)
            if (!p.alive) particles.removeAt(i)
            i--
        }
        i = shockwaves.size - 1
        while (i >= 0) {
            val s = shockwaves[i]
            s.update(dt)
            if (!s.alive) shockwaves.removeAt(i)
            i--
        }
        i = popups.size - 1
        while (i >= 0) {
            val p = popups[i]
            p.update(dt)
            if (!p.alive) popups.removeAt(i)
            i--
        }
        shake = (shake - dt * 4.2f).coerceAtLeast(0f)
    }

    fun addShake(amount: Float) {
        shake = (shake + amount).coerceAtMost(1.6f)
    }

    /**
     * Sparks flung out along the cut. [dirX]/[dirY] is the slice direction, so
     * debris sprays along the blade rather than in a shapeless puff.
     */
    fun burst(
        x: Float,
        y: Float,
        dirX: Float,
        dirY: Float,
        spread: Float,
        color: Int,
        count: Int,
        speed: Float
    ) {
        repeat(count) {
            val along = (random.nextFloat() - 0.5f) * 2f
            val perpendicular = (random.nextFloat() - 0.5f) * 2f * 0.55f
            val vx = (dirX * along + -dirY * perpendicular) * speed * (0.4f + random.nextFloat())
            val vy = (dirY * along + dirX * perpendicular) * speed * (0.4f + random.nextFloat())
            particles.add(
                Particle(
                    x = x + (random.nextFloat() - 0.5f) * spread,
                    y = y + (random.nextFloat() - 0.5f) * spread,
                    vx = vx,
                    vy = vy,
                    size = 3f + random.nextFloat() * 6f,
                    lifeSpan = 0.35f + random.nextFloat() * 0.5f,
                    color = color,
                    streak = random.nextFloat() < 0.45f
                )
            )
        }
    }

    /** A radial spray, used for the golden pop on a perfect cut. */
    fun radialBurst(x: Float, y: Float, color: Int, count: Int, speed: Float) {
        repeat(count) {
            val angle = random.nextFloat() * 6.2832f
            val magnitude = speed * (0.35f + random.nextFloat())
            particles.add(
                Particle(
                    x = x,
                    y = y,
                    vx = cos(angle) * magnitude,
                    vy = sin(angle) * magnitude,
                    size = 4f + random.nextFloat() * 7f,
                    lifeSpan = 0.5f + random.nextFloat() * 0.55f,
                    color = color,
                    streak = random.nextFloat() < 0.6f
                )
            )
        }
    }

    fun shockwave(x: Float, y: Float, maxRadius: Float, color: Int) {
        shockwaves.add(Shockwave(x, y, maxRadius, color))
    }

    fun popup(headline: String, subline: String, x: Float, y: Float, color: Int, emphatic: Boolean) {
        popups.add(ScorePopup(headline, subline, x, y, color, emphatic))
    }

    /** Random shake offset for this frame, scaled by the player's shake setting. */
    fun shakeOffset(strength: Float): Pair<Float, Float> {
        if (shake <= 0f || strength <= 0f) return Pair(0f, 0f)
        val magnitude = shake * shake * 26f * strength
        return Pair(
            (random.nextFloat() - 0.5f) * 2f * magnitude,
            (random.nextFloat() - 0.5f) * 2f * magnitude
        )
    }
}
