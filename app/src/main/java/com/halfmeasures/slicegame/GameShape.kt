package com.halfmeasures.slicegame

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class PointF2(val x: Float, val y: Float)

// Outline builders live at file scope rather than in ShapeKind's companion: enum
// entry constructors run before the companion is initialised, so they cannot call
// into it.

private fun regularOutline(sides: Int, angleOffset: Float): List<PointF2> =
    (0 until sides).map { i ->
        val t = angleOffset + (2.0 * Math.PI * i / sides).toFloat()
        PointF2(cos(t), sin(t))
    }

private fun starOutline(points: Int, innerRatio: Float): List<PointF2> {
    val verts = ArrayList<PointF2>(points * 2)
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) 1f else innerRatio
        val t = (-Math.PI / 2 + Math.PI * i / points).toFloat()
        verts.add(PointF2(r * cos(t), r * sin(t)))
    }
    return verts
}

/** A rounded bar: straight sides with semicircular caps. */
private fun capsuleOutline(): List<PointF2> {
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

private fun diamondOutline(): List<PointF2> = listOf(
    PointF2(0f, -1f), PointF2(0.66f, 0f), PointF2(0f, 1f), PointF2(-0.66f, 0f)
)

private fun trapezoidOutline(): List<PointF2> = listOf(
    PointF2(-0.52f, -0.62f), PointF2(0.52f, -0.62f),
    PointF2(0.95f, 0.62f), PointF2(-0.95f, 0.62f)
)

private fun crossOutline(): List<PointF2> {
    val a = 0.34f // half-width of the arms
    val b = 1f    // arm reach
    return listOf(
        PointF2(-a, -b), PointF2(a, -b), PointF2(a, -a), PointF2(b, -a),
        PointF2(b, a), PointF2(a, a), PointF2(a, b), PointF2(-a, b),
        PointF2(-a, a), PointF2(-b, a), PointF2(-b, -a), PointF2(-a, -a)
    )
}

/**
 * The catalogue of sliceable shapes. Each kind supplies its outline in unit
 * space (roughly bounded by a radius-1 circle); [GameShape] scales, rotates and
 * positions it. Kinds are declared easiest-first and unlocked by difficulty stage,
 * so a run opens on easy round/blocky shapes and works up to spiky, concave ones
 * that are much harder to halve by eye.
 */
enum class ShapeKind(
    val displayName: String,
    private val builder: () -> List<PointF2>
) {
    // Declared easiest-to-halve first: the unlock schedule walks down this list,
    // so a run opens on symmetric blobs and ends on spiky, concave outlines.
    CIRCLE("Circle", { regularOutline(36, 0f) }),
    SQUARE("Square", { regularOutline(4, (Math.PI / 4).toFloat()) }),
    CAPSULE("Capsule", { capsuleOutline() }),
    DIAMOND("Diamond", { diamondOutline() }),
    HEXAGON("Hexagon", { regularOutline(6, 0f) }),
    OCTAGON("Octagon", { regularOutline(8, (Math.PI / 8).toFloat()) }),
    TRAPEZOID("Trapezoid", { trapezoidOutline() }),
    PENTAGON("Pentagon", { regularOutline(5, (-Math.PI / 2).toFloat()) }),
    TRIANGLE("Triangle", { regularOutline(3, (-Math.PI / 2).toFloat()) }),
    STAR6("Six-Point Star", { starOutline(6, 0.58f) }),
    CROSS("Cross", { crossOutline() }),
    STAR5("Star", { starOutline(5, 0.42f) });

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
        /**
         * How many kinds are in play at [stage]: the starting set, plus a fixed
         * number of new shapes each stage, capped at the full catalogue.
         */
        fun unlockedCount(stage: Int, startingShapes: Int, shapesPerStage: Int): Int =
            (startingShapes + stage * shapesPerStage).coerceIn(1, values().size)
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
        /** Ceiling on sideways speed, so a shape thrown from the wing never streaks across. */
        private const val MAX_HORIZONTAL_SPEED = 420f
        private const val BASE_SPIN = 3.4f

        /**
         * Launches a shape for the given difficulty [stage].
         *
         * The upward velocity is solved from the *desired apex height* rather than
         * set directly, so however the player tunes gravity a shape always tops out
         * at the same fraction of the screen and never sails off out of reach.
         */
        fun spawnRandom(
            screenW: Int,
            screenH: Int,
            random: Random,
            nowMs: Long,
            stage: Int,
            settings: GameSettings
        ): GameShape {
            val poolSize = ShapeKind.unlockedCount(
                stage, settings.startingShapeCount, settings.shapesPerStage
            )
            val kind = ShapeKind.values()[random.nextInt(poolSize)]

            val radius = (((screenW * 0.06f) + random.nextFloat() * (screenW * 0.045f)) * settings.sizeScale)
                .coerceAtMost(screenW * 0.3f)

            val gravity = BASE_GRAVITY * settings.gravityScale
            val apex = screenH * settings.flightHeight * (0.92f + random.nextFloat() * 0.16f)
            val vy = -sqrt(2f * gravity * apex)

            /*
             * Shapes are thrown in from the wings and arc toward the middle, so the
             * player works across the screen rather than straight up. Early stages
             * launch from the very edges; each stage lets the launch band creep
             * further inward, until late runs can come from almost anywhere.
             */
            val fromLeft = random.nextBoolean()
            val creep = (stage * settings.launchCentreCreep).coerceIn(0f, 0.42f)
            val edge = radius * 1.2f
            val bandStart = screenW * creep
            val bandWidth = screenW * (0.12f + 0.10f * random.nextFloat())
            val x = if (fromLeft) {
                (edge + bandStart + random.nextFloat() * bandWidth)
            } else {
                (screenW - edge - bandStart - random.nextFloat() * bandWidth)
            }.coerceIn(edge, screenW - edge)
            val y = screenH + radius

            // Aim the arc so it tops out near the middle, with a little scatter.
            val timeToApex = -vy / gravity
            val targetX = screenW * (0.5f + (random.nextFloat() - 0.5f) * 0.30f)
            val vx = ((targetX - x) / timeToApex)
                .coerceIn(-MAX_HORIZONTAL_SPEED, MAX_HORIZONTAL_SPEED)
            // Shapes tumble faster every stage, so late runs are harder to read.
            val spinScale = settings.rotationScale *
                (1f + settings.rotationPerStagePercent / 100f * stage)
            val spin = (random.nextFloat() - 0.5f) * 2f * BASE_SPIN * spinScale

            return GameShape(
                kind, x, y, radius, vx, vy,
                random.nextFloat() * 6.28f, spin,
                random.nextInt(Theme.shapePalette.size), nowMs
            )
        }
    }
}
