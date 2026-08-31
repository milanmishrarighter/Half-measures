package com.halfmeasures.slicegame

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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



/**
 * The catalogue of sliceable shapes. Each kind supplies its outline in unit
 * space (bounded by a radius-1 circle); [GameShape] scales, rotates and positions
 * it.
 *
 * Kinds are declared in the order a player meets them, and each carries the score
 * it starts appearing at. The catalogue was measured for difficulty and then
 * hand-picked down to the shapes that read cleanly at speed, so the running order
 * is now a design decision rather than something derived.
 */
enum class ShapeKind(
    val displayName: String,
    /**
     * The score this kind starts appearing at unless the player overrides it.
     * Written here rather than derived, because the running order is a design
     * decision now: the catalogue was measured and then hand-picked, and the
     * result is this list.
     */
    val defaultUnlockScore: Int,
    private val builder: () -> List<PointF2>
) {
    CIRCLE("Circle", 0, { regularOutline(36, 0f) }),
    OCTAGON("Octagon", 0, { regularOutline(8, (Math.PI / 8).toFloat()) }),
    HEXAGON("Hexagon", 0, { regularOutline(6, 0f) }),
    PENTAGON("Pentagon", 0, { regularOutline(5, (-Math.PI / 2).toFloat()) }),
    SQUARE("Square", 0, { regularOutline(4, (Math.PI / 4).toFloat()) }),
    CAPSULE("Capsule", 0, { capsuleOutline() }),
    TRAPEZOID("Trapezoid", 1000, { trapezoidOutline() }),
    DIAMOND("Diamond", 2000, { diamondOutline() }),
    TRIANGLE("Triangle", 3000, { regularOutline(3, (-Math.PI / 2).toFloat()) }),
    DROP("Drop", 4000, { dropOutline() }),
    CROSS("Cross", 5000, { crossOutline() }),
    STAR6("Six-Point Star", 6000, { starOutline(6, 0.58f) }),
    STAR5("Star", 7000, { starOutline(5, 0.42f) }),
    ARROW("Arrow", 8000, { arrowOutline() }),
    BOLT("Bolt", 9000, { boltOutline() }),
    CROWN("Crown", 10000, { crownOutline() }),
    TREE("Tree", 11000, { treeOutline() }),
    HEART("Heart", 12000, { heartOutline() }),
    MOON("Moon", 13000, { moonOutline() });

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

    /**
     * Whether this outline is a polygon the game can actually work with. Checked
     * once per kind, and used to keep a malformed shape out of the catalogue
     * entirely rather than letting it reach a player - which is how a heart, a
     * bone and a cloud that all crossed themselves stayed in for several builds.
     */
    val isSimple: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        !SliceMath.selfIntersects(unitVertices)
    }

    companion object {

        /** Every kind the game will actually use, malformed outlines excluded. */
        val usable: List<ShapeKind> by lazy(LazyThreadSafetyMode.NONE) {
            values().filter { it.isSimple }
        }

        /** The score [kind] starts appearing at: the player's override, or its own. */
        fun unlockScore(kind: ShapeKind, settings: GameSettings): Int =
            settings.shapeUnlockScores[kind.name] ?: kind.defaultUnlockScore

        /**
         * The catalogue in the order a player meets it. Ties keep the declaration
         * order, so a group that all arrive at once still reads consistently.
         */
        fun ordered(settings: GameSettings): List<ShapeKind> =
            usable.sortedWith(compareBy({ unlockScore(it, settings) }, { it.ordinal }))

        /**
         * Chooses a kind the player has both switched on and reached the score for.
         *
         * If nothing qualifies - everything switched off, or every unlock score set
         * above the current score - the pick falls back to whatever is merely
         * switched on, and finally to the catalogue. An empty sky is not a setting
         * anyone meant to choose.
         */
        fun pick(score: Int, settings: GameSettings, random: Random): ShapeKind {
            val enabled = usable.filter { it.name !in settings.disabledShapes }

            val reached = enabled.filter { score >= unlockScore(it, settings) }
            if (reached.isNotEmpty()) return reached[random.nextInt(reached.size)]

            if (enabled.isNotEmpty()) {
                return enabled.minByOrNull { unlockScore(it, settings) } ?: enabled[0]
            }
            return usable.first()
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
            score: Int,
            settings: GameSettings
        ): GameShape {
            val kind = ShapeKind.pick(score, settings, random)

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
