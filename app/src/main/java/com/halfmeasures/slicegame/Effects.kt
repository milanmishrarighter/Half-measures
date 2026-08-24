package com.halfmeasures.slicegame

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class ParticleShape { STREAK, DOT, SHARD }

/** A piece of debris thrown off by a cut. */
class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val size: Float,
    val lifeSpan: Float,
    val color: Int,
    val shape: ParticleShape,
    var rotation: Float,
    private val spin: Float,
    private val drag: Float
) {
    var age = 0f
    val alive: Boolean get() = age < lifeSpan
    /** 1 at birth, 0 at death. */
    val remaining: Float get() = (1f - age / lifeSpan).coerceIn(0f, 1f)

    fun update(dt: Float, gravity: Float) {
        age += dt
        vy += gravity * 0.32f * dt
        vx *= drag
        vy *= drag
        x += vx * dt
        y += vy * dt
        rotation += spin * dt
    }
}

/** An expanding ring, used to punctuate a strong cut. */
class Shockwave(
    val x: Float,
    val y: Float,
    val maxRadius: Float,
    val color: Int,
    private val lifeSpan: Float,
    val thickness: Float,
    val delay: Float
) {
    var age = 0f
    val alive: Boolean get() = age < lifeSpan + delay
    /** 0 until the ring's delay elapses, then 0..1 across its life. */
    val progress: Float get() = ((age - delay) / lifeSpan).coerceIn(0f, 1f)
    val started: Boolean get() = age >= delay

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
    /** Drives text size and how hard it pops in; 0 for a plain cut, 1 for a perfect. */
    val emphasis: Float
) {
    var age = 0f
    private val lifeSpan = 1.15f
    val alive: Boolean get() = age < lifeSpan
    val progress: Float get() = (age / lifeSpan).coerceIn(0f, 1f)

    fun update(dt: Float) {
        age += dt
    }
}

/**
 * Owns the transient flourishes: debris, rings, floating text, the screen flash,
 * the camera-shake impulse and the backdrop's excitement level. Kept apart from
 * [GameView] so the draw loop there stays about the game itself.
 */
class EffectSystem(private val random: Random) {

    val particles = ArrayList<Particle>()
    val shockwaves = ArrayList<Shockwave>()
    val popups = ArrayList<ScorePopup>()

    var shake = 0f
        private set

    /** Colour and intensity of the full-screen flash, decaying to nothing. */
    var flash = 0f
        private set
    var flashColor = 0
        private set

    /** Rises on good cuts and decays; the backdrop feeds off it. */
    var energy = 0f
        private set

    fun clear() {
        particles.clear()
        shockwaves.clear()
        popups.clear()
        shake = 0f
        flash = 0f
        energy = 0f
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
        shake = (shake - dt * 3.4f).coerceAtLeast(0f)
        flash = (flash - dt * 3.2f).coerceAtLeast(0f)
        energy = (energy - dt * 0.85f).coerceAtLeast(0f)
    }

    fun addShake(amount: Float) {
        shake = (shake + amount).coerceAtMost(2.2f)
    }

    fun addFlash(color: Int, amount: Float) {
        if (amount <= 0f) return
        flashColor = color
        flash = (flash + amount).coerceAtMost(1.4f)
    }

    fun addEnergy(amount: Float) {
        energy = (energy + amount).coerceAtMost(2.5f)
    }

    /**
     * Debris flung along the blade. [dirX]/[dirY] is the slice direction, so the
     * spray follows the cut rather than puffing out shapelessly.
     */
    fun burst(
        x: Float,
        y: Float,
        dirX: Float,
        dirY: Float,
        spread: Float,
        color: Int,
        count: Int,
        speed: Float,
        sizeScale: Float
    ) {
        repeat(count) {
            val along = (random.nextFloat() - 0.5f) * 2f
            val perpendicular = (random.nextFloat() - 0.5f) * 2f * 0.5f
            val magnitude = speed * (0.35f + random.nextFloat())
            particles.add(
                Particle(
                    x = x + (random.nextFloat() - 0.5f) * spread,
                    y = y + (random.nextFloat() - 0.5f) * spread,
                    vx = (dirX * along + -dirY * perpendicular) * magnitude,
                    vy = (dirY * along + dirX * perpendicular) * magnitude,
                    size = (7f + random.nextFloat() * 13f) * sizeScale,
                    lifeSpan = 0.45f + random.nextFloat() * 0.6f,
                    color = color,
                    shape = rollShape(),
                    rotation = random.nextFloat() * 6.2832f,
                    spin = (random.nextFloat() - 0.5f) * 16f,
                    drag = 0.988f
                )
            )
        }
    }

    /** A radial spray - the golden pop on a great or perfect cut. */
    fun radialBurst(
        x: Float,
        y: Float,
        color: Int,
        count: Int,
        speed: Float,
        sizeScale: Float
    ) {
        repeat(count) {
            val angle = random.nextFloat() * 6.2832f
            val magnitude = speed * (0.3f + random.nextFloat() * 1.1f)
            particles.add(
                Particle(
                    x = x,
                    y = y,
                    vx = cos(angle) * magnitude,
                    vy = sin(angle) * magnitude,
                    size = (8f + random.nextFloat() * 16f) * sizeScale,
                    lifeSpan = 0.6f + random.nextFloat() * 0.7f,
                    color = color,
                    shape = rollShape(),
                    rotation = random.nextFloat() * 6.2832f,
                    spin = (random.nextFloat() - 0.5f) * 20f,
                    drag = 0.985f
                )
            )
        }
    }

    private fun rollShape(): ParticleShape {
        val roll = random.nextFloat()
        return when {
            roll < 0.42f -> ParticleShape.STREAK
            roll < 0.78f -> ParticleShape.SHARD
            else -> ParticleShape.DOT
        }
    }

    fun shockwave(
        x: Float,
        y: Float,
        maxRadius: Float,
        color: Int,
        lifeSpan: Float = 0.5f,
        thickness: Float = 10f,
        delay: Float = 0f
    ) {
        shockwaves.add(Shockwave(x, y, maxRadius, color, lifeSpan, thickness, delay))
    }

    fun popup(headline: String, subline: String, x: Float, y: Float, color: Int, emphasis: Float) {
        popups.add(ScorePopup(headline, subline, x, y, color, emphasis))
    }

    /** Random shake offset for this frame, scaled by the player's shake setting. */
    fun shakeOffset(strength: Float): Pair<Float, Float> {
        if (shake <= 0f || strength <= 0f) return Pair(0f, 0f)
        val magnitude = shake * shake * 24f * strength
        return Pair(
            (random.nextFloat() - 0.5f) * 2f * magnitude,
            (random.nextFloat() - 0.5f) * 2f * magnitude
        )
    }
}
