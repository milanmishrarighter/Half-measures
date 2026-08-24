package com.halfmeasures.slicegame

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class PointF2(val x: Float, val y: Float)

/**
 * The catalogue of sliceable shapes. Each kind supplies its outline in unit
 * space (roughly bounded by a radius-1 circle); [GameShape] scales, rotates and
 * positions it. [unlockScore] gates when a kind starts appearing, so a run opens
 * on easy round/blocky shapes and works up to spiky, concave ones that are much
 * harder to halve by eye.
 */
enum class ShapeKind(
    val displayName: String,
    val unlockScore: Int,
    private val builder: () -> List<PointF2>
) {
    CIRCLE("Circle", 0, { regular(36, 0f) }),
    SQUARE("Square", 0, { regular(4, (Math.PI / 4).toFloat()) }),
    CAPSULE("Capsule", 400, { capsule() }),
    HEXAGON("Hexagon", 900, { regular(6, 0f) }),
    DIAMOND("Diamond", 1500, { diamond() }),
    OCTAGON("Octagon", 2200, { regular(8, (Math.PI / 8).toFloat()) }),
    PENTAGON("Pentagon", 3000, { regular(5, (-Math.PI / 2).toFloat()) }),
    TRIANGLE("Triangle", 4000, { regular(3, (-Math.PI / 2).toFloat()) }),
    TRAPEZOID("Trapezoid", 5200, { trapezoid() }),
    STAR6("Six-Point Star", 6500, { star(6, 0.58f) }),
    CROSS("Cross", 8000, { cross() }),
    STAR5("Star", 9500, { star(5, 0.42f) });

    /** Outline in unit space, computed once per kind. */
    val unitVertices: List<PointF2> by lazy(LazyThreadSafetyMode.NONE) { builder() }

    /**
     * Where the perfect halving line sits in unit space, measured along the normal
     * of the shape's local +x axis. Because a shape rotates rigidly this is a
     * constant per kind, so the on-screen guide costs nothing per frame. It is zero
     * for centrally symmetric kinds and non-zero for the likes of a triangle, whose
     * bisector does not pass through the centroid.
     */
    val bisectorOffsetUnit: Float by lazy(LazyThreadSafetyMode.NONE) {
        SliceMath.bisectorOffset(unitVertices, 0f, 0f, 1f, 0f, 2f)
    }

    companion object {
        private fun regular(sides: Int, angleOffset: Float): List<PointF2> =
            (0 until sides).map { i ->
                val t = angleOffset + (2.0 * Math.PI * i / sides).toFloat()
                PointF2(cos(t), sin(t))
            }

        private fun star(points: Int, innerRatio: Float): List<PointF2> {
            val verts = ArrayList<PointF2>(points * 2)
            for (i in 0 until points * 2) {
                val r = if (i % 2 == 0) 1f else innerRatio
                val t = (-Math.PI / 2 + Math.PI * i / points).toFloat()
                verts.add(PointF2(r * cos(t), r * sin(t)))
            }
            return verts
        }

        /** A rounded bar: straight sides with semicircular caps. */
        private fun capsule(): List<PointF2> {
            val halfLength = 0.62f   // centre of each cap
            val capRadius = 0.46f
            val steps = 12
            val verts = ArrayList<PointF2>(steps * 2 + 2)
            for (i in 0..steps) { // right cap, -90deg -> +90deg
                val t = (-Math.PI / 2 + Math.PI * i / steps).toFloat()
                verts.add(PointF2(halfLength + capRadius * cos(t), capRadius * sin(t)))
            }
            for (i in 0..steps) { // left cap, +90deg -> +270deg
                val t = (Math.PI / 2 + Math.PI * i / steps).toFloat()
                verts.add(PointF2(-halfLength + capRadius * cos(t), capRadius * sin(t)))
            }
            return verts
        }

        private fun diamond(): List<PointF2> = listOf(
            PointF2(0f, -1f), PointF2(0.66f, 0f), PointF2(0f, 1f), PointF2(-0.66f, 0f)
        )

        private fun trapezoid(): List<PointF2> = listOf(
            PointF2(-0.52f, -0.62f), PointF2(0.52f, -0.62f),
            PointF2(0.95f, 0.62f), PointF2(-0.95f, 0.62f)
        )

        private fun cross(): List<PointF2> {
            val a = 0.34f // half-width of the arms
            val b = 1f    // arm reach
            return listOf(
                PointF2(-a, -b), PointF2(a, -b), PointF2(a, -a), PointF2(b, -a),
                PointF2(b, a), PointF2(a, a), PointF2(a, b), PointF2(-a, b),
                PointF2(-a, a), PointF2(-b, a), PointF2(-b, -a), PointF2(-a, -a)
            )
        }

        /** Kinds available at [score], honouring the player's unlock-pace setting. */
        fun unlockedAt(score: Int, pace: Float): List<ShapeKind> {
            val unlocked = values().filter { it.unlockScore * pace <= score }
            return if (unlocked.isEmpty()) listOf(CIRCLE, SQUARE) else unlocked
        }
    }
}

/**
 * A shape in flight. The outline is stored in unit space and transformed on
 * demand, so any polygon - convex or concave - works with the same slicing math.
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
    /** Index into [Theme.shapePalette]. */
    val paletteIndex: Int,
    val spawnTimeMs: Long
) {
    val lightColor: Int get() = Theme.shapePalette[paletteIndex][0]
    val deepColor: Int get() = Theme.shapePalette[paletteIndex][1]

    /** How long the shape has been alive, in seconds - drives the spawn pop-in. */
    var age = 0f
        private set

    /** Eases from 0 to 1 right after spawning so shapes scale in instead of appearing. */
    val spawnScale: Float
        get() {
            val t = (age / 0.22f).coerceIn(0f, 1f)
            val eased = 1f - (1f - t) * (1f - t)
            return 0.55f + 0.45f * eased
        }

    fun worldVertices(): List<PointF2> {
        val c = cos(rotation)
        val s = sin(rotation)
        val r = radius * spawnScale
        return kind.unitVertices.map { p ->
            PointF2(
                x + r * (p.x * c - p.y * s),
                y + r * (p.x * s + p.y * c)
            )
        }
    }

    fun update(dtSeconds: Float, gravity: Float) {
        age += dtSeconds
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
        const val BASE_GRAVITY = 1500f          // px/s^2 before gravityScale
        private const val BASE_LAUNCH_SPEED = 1600f
        private const val BASE_HORIZONTAL_DRIFT = 220f
        private const val BASE_SPIN = 5f

        fun spawnRandom(
            screenW: Int,
            screenH: Int,
            random: Random,
            nowMs: Long,
            score: Int,
            settings: GameSettings
        ): GameShape {
            val pool = ShapeKind.unlockedAt(score, settings.shapeUnlockPace)
            val kind = pool[random.nextInt(pool.size)]

            val radius = (((screenW * 0.06f) + random.nextFloat() * (screenW * 0.045f)) * settings.sizeScale)
                .coerceAtMost(screenW * 0.3f)
            val x = radius * 1.5f + random.nextFloat() * (screenW - radius * 3f)
            val y = screenH + radius

            val speed = settings.speedScale
            val vx = (random.nextFloat() - 0.5f) * 2f * BASE_HORIZONTAL_DRIFT * speed
            val vy = -BASE_LAUNCH_SPEED * speed * (0.85f + random.nextFloat() * 0.3f)
            val spin = (random.nextFloat() - 0.5f) * 2f * BASE_SPIN * settings.rotationScale

            return GameShape(
                kind, x, y, radius, vx, vy,
                random.nextFloat() * 6.28f, spin,
                random.nextInt(Theme.shapePalette.size), nowMs
            )
        }
    }
}
