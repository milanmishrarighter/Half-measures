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

        /** Base, unscaled gravity; [GameView] applies GameSettings.gravityScale on top of this. */
        const val BASE_GRAVITY = 1500f // px/s^2

        private const val BASE_LAUNCH_SPEED = 1600f // px/s, straight up before speedScale
        private const val BASE_HORIZONTAL_DRIFT = 220f // px/s, max sideways speed before speedScale
        private const val BASE_SPIN = 5f // rad/s, max spin before rotationScale

        /**
         * Launch velocity is independent of gravity here: [speedScale] scales how fast a
         * shape leaves the bottom of the screen, while [GameView] separately scales gravity
         * via GameSettings.gravityScale - exactly like the settings screen describes it
         * ("higher gravity = faster fall AND a lower peak height").
         *
         * @param sizeScale multiplies the shape's radius (1.0 = base size).
         * @param speedScale multiplies launch speed, both vertical and horizontal drift.
         * @param rotationScale multiplies spin speed.
         */
        fun spawnRandom(
            screenW: Int,
            screenH: Int,
            random: Random,
            nowMs: Long,
            sizeScale: Float = 1f,
            speedScale: Float = 1f,
            rotationScale: Float = 1f
        ): GameShape {
            val kind = ShapeKind.values().random(random)
            val radius = (((screenW * 0.06f) + random.nextFloat() * (screenW * 0.045f)) * sizeScale)
                .coerceAtMost(screenW * 0.3f)
            val x = radius * 1.5f + random.nextFloat() * (screenW - radius * 3f)
            val y = screenH + radius

            val vx = (random.nextFloat() - 0.5f) * 2f * BASE_HORIZONTAL_DRIFT * speedScale
            val vy = -BASE_LAUNCH_SPEED * speedScale * (0.85f + random.nextFloat() * 0.3f)

            val angularVelocity = (random.nextFloat() - 0.5f) * 2f * BASE_SPIN * rotationScale
            val color = palette[random.nextInt(palette.size)]
            return GameShape(
                kind, x, y, radius,
                vx, vy,
                random.nextFloat() * 6.28f, angularVelocity,
                color, nowMs
            )
        }
    }
}

data class PointF2(val x: Float, val y: Float)
