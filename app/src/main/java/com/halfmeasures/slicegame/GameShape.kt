package com.halfmeasures.slicegame

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class ShapeKind(val sides: Int, val displayName: String) {
    TRIANGLE(3, "Triangle"),
    SQUARE(4, "Square"),
    PENTAGON(5, "Pentagon"),
    HEXAGON(6, "Hexagon"),
    CIRCLE(28, "Circle") // high side-count regular polygon reads as a circle
}

/**
 * A convex shape flying through the air. Represented as a regular polygon so every
 * shape can be split by a slice line with the same generic clipping math.
 */
class GameShape(
    val kind: ShapeKind,
    var x: Float,
    var y: Float,
    val radius: Float,
    var vx: Float,
    var vy: Float,
    var rotation: Float,
    val angularVelocity: Float,
    val color: Int,
    val spawnTimeMs: Long
) {
    var sliced = false

    /** Vertices in world space, in order, forming a convex polygon. */
    fun worldVertices(): List<PointF2> {
        val n = kind.sides
        val verts = ArrayList<PointF2>(n)
        for (i in 0 until n) {
            val theta = rotation + (2 * Math.PI * i / n).toFloat()
            verts.add(PointF2(x + radius * cos(theta), y + radius * sin(theta)))
        }
        return verts
    }

    fun update(dtSeconds: Float, gravity: Float) {
        vy += gravity * dtSeconds
        x += vx * dtSeconds
        y += vy * dtSeconds
        rotation += angularVelocity * dtSeconds
    }

    fun isOffScreen(screenW: Int, screenH: Int): Boolean {
        val margin = radius * 2.5f
        return y - margin > screenH || x < -margin || x > screenW + margin
    }

    companion object {
        private val palette = intArrayOf(
            0xFFEF476F.toInt(),
            0xFFFFD166.toInt(),
            0xFF06D6A0.toInt(),
            0xFF118AB2.toInt(),
            0xFF9B5DE5.toInt(),
            0xFFFF9F1C.toInt()
        )

        /** Base, unscaled gravity used to derive a launch arc; must match [GameView]'s effective gravity math. */
        const val BASE_GRAVITY = 1500f // px/s^2

        /**
         * @param sizeScale multiplies the shape's radius (1.0 = base size).
         * @param speedScale uniformly slows or speeds up the whole flight: velocities are
         *   scaled by this factor and (by the caller, via [BASE_GRAVITY] * speedScale^2)
         *   gravity is scaled by its square, which reproduces the exact same arc traced
         *   out over 1/speedScale as much time - i.e. true slow motion, not a squashed path.
         */
        fun spawnRandom(
            screenW: Int,
            screenH: Int,
            random: Random,
            nowMs: Long,
            sizeScale: Float = 1f,
            speedScale: Float = 1f
        ): GameShape {
            val kind = ShapeKind.values().random(random)
            val radius = (((screenW * 0.06f) + random.nextFloat() * (screenW * 0.045f)) * sizeScale)
                .coerceAtMost(screenW * 0.3f)
            val x = radius * 1.5f + random.nextFloat() * (screenW - radius * 3f)
            val y = screenH + radius

            // Aim roughly toward the upper-middle area of the screen so shapes stay reachable.
            val targetX = screenW * (0.25f + random.nextFloat() * 0.5f)
            val targetY = screenH * (0.18f + random.nextFloat() * 0.22f)
            val flightSeconds = 1.15f + random.nextFloat() * 0.35f

            val vx = (targetX - x) / flightSeconds
            // From y = y0 + vy*t + 0.5*g*t^2 solved for vy so the apex lands near targetY.
            val vy = (targetY - y - 0.5f * BASE_GRAVITY * flightSeconds * flightSeconds) / flightSeconds

            val angularVelocity = (random.nextFloat() - 0.5f) * 6f
            val color = palette[random.nextInt(palette.size)]
            return GameShape(
                kind, x, y, radius,
                vx * speedScale, vy * speedScale,
                random.nextFloat() * 6.28f, angularVelocity * speedScale,
                color, nowMs
            )
        }
    }
}

data class PointF2(val x: Float, val y: Float)
