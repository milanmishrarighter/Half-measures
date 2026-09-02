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

/**
 * Builds an outline from x,y pairs given in a convenient 0..1 box with y running
 * down, then re-centres it on its own centroid and scales it so its farthest
 * vertex sits at radius 1.
 *
 * Every shape below is authored this way, which means a new one only has to be
 * drawn roughly - the normalisation makes it the same visual size as everything
 * else, and the halving maths works in the same unit space regardless.
 */
private fun outline(vararg v: Float): List<PointF2> {
    val n = v.size / 2
    var cx = 0f
    var cy = 0f
    for (i in 0 until n) {
        cx += v[i * 2]
        cy += v[i * 2 + 1]
    }
    cx /= n
    cy /= n

    var maxR = 0.0001f
    for (i in 0 until n) {
        val dx = v[i * 2] - cx
        val dy = v[i * 2 + 1] - cy
        maxR = kotlin.math.max(maxR, sqrt(dx * dx + dy * dy))
    }

    val scaled = (0 until n).map { i ->
        PointF2((v[i * 2] - cx) / maxR, (v[i * 2 + 1] - cy) / maxR)
    }
    return tidy(scaled)
}

/**
 * Drops repeated points and points that sit on the straight line between their
 * neighbours. Both are harmless to look at and a nuisance to reason about: a
 * zero-length edge has no side to be on, which is enough to make a self-
 * intersection test report crossings that are not there.
 */
private fun tidy(poly: List<PointF2>): List<PointF2> {
    val eps = 1e-4f
    val once = ArrayList<PointF2>(poly.size)
    for (p in poly) {
        val last = once.lastOrNull()
        if (last == null || abs(p.x - last.x) > eps || abs(p.y - last.y) > eps) once.add(p)
    }
    while (once.size > 1 &&
        abs(once.first().x - once.last().x) < eps && abs(once.first().y - once.last().y) < eps
    ) {
        once.removeAt(once.size - 1)
    }
    if (once.size < 3) return once

    val kept = ArrayList<PointF2>(once.size)
    for (i in once.indices) {
        val a = once[(i - 1 + once.size) % once.size]
        val b = once[i]
        val c = once[(i + 1) % once.size]
        val cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        if (abs(cross) > 1e-6f) kept.add(b)
    }
    return if (kept.size >= 3) kept else once
}

/** A run of points along a circular arc, appended to a builder. */
private fun sweepInto(
    out: ArrayList<Float>, cx: Float, cy: Float, r: Float,
    from: Float, to: Float, steps: Int, forward: Boolean
) {
    var end = to
    val turn = (2.0 * Math.PI).toFloat()
    if (forward) while (end < from) end += turn else while (end > from) end -= turn
    for (i in 0..steps) {
        val a = from + (end - from) * i / steps
        out.add(cx + r * cos(a))
        out.add(cy + r * sin(a))
    }
}

/** An arc of points, for the shapes that need a curve rather than a corner. */
private fun arcInto(
    out: ArrayList<Float>, cx: Float, cy: Float, rx: Float, ry: Float,
    fromDeg: Float, toDeg: Float, steps: Int
) {
    for (i in 0..steps) {
        val a = Math.toRadians((fromDeg + (toDeg - fromDeg) * i / steps).toDouble())
        out.add(cx + rx * cos(a.toFloat()))
        out.add(cy + ry * sin(a.toFloat()))
    }
}

private fun built(build: (ArrayList<Float>) -> Unit): List<PointF2> {
    val v = ArrayList<Float>()
    build(v)
    return outline(*v.toFloatArray())
}

private fun crossOutline(): List<PointF2> {
    val a = 0.34f // half-width of the arms
    val b = 1f    // arm reach
    return listOf(
        PointF2(-a, -b), PointF2(a, -b), PointF2(a, -a), PointF2(b, -a),
        PointF2(b, a), PointF2(a, a), PointF2(a, b), PointF2(-a, b),
        PointF2(-a, a), PointF2(-b, a), PointF2(-b, -a), PointF2(-a, -a)
    )
}

/** Three clear tiers and a narrow trunk. Two shallow tiers on a wide trunk is an
 *  upload arrow, which is what the old one looked like. */
private fun treeOutline() = outline(
    0.50f,0.00f, 0.68f,0.26f, 0.58f,0.26f, 0.80f,0.55f, 0.70f,0.55f, 0.94f,0.84f,
    0.60f,0.84f, 0.60f,1.00f, 0.40f,1.00f, 0.40f,0.84f, 0.06f,0.84f, 0.30f,0.55f,
    0.20f,0.55f, 0.42f,0.26f, 0.32f,0.26f
)

/**
 * The classic parametric heart. Two arcs meeting at a notch is easy to get wrong
 * - the old one crossed itself - and a closed parametric curve simply cannot.
 */
private fun heartOutline() = built { v ->
    val steps = 44
    for (i in 0 until steps) {
        val t = (2.0 * Math.PI * i / steps).toFloat()
        val s1 = sin(t)
        v.add(16f * s1 * s1 * s1)
        // Negated: the canvas runs y downward, and the curve is written y up.
        v.add(-(13f * cos(t) - 5f * cos(2f * t) - 2f * cos(3f * t) - cos(4f * t)))
    }
}

private fun arrowOutline() = outline(0.5f,0f, 1f,0.46f, 0.72f,0.46f, 0.72f,1f, 0.28f,1f, 0.28f,0.46f, 0f,0.46f)

private fun boltOutline() = outline(0.58f,0f, 0.14f,0.56f, 0.46f,0.56f, 0.34f,1f, 0.86f,0.42f, 0.54f,0.42f)

private fun rectangleOutline(): List<PointF2> = listOf(
    PointF2(-1f, -0.55f), PointF2(1f, -0.55f), PointF2(1f, 0.55f), PointF2(-1f, 0.55f)
)

/** A rectangle pushed over: equal opposite sides, no right angles anywhere. */
private fun parallelogramOutline(): List<PointF2> = listOf(
    PointF2(-1f, -0.5f), PointF2(0.55f, -0.5f), PointF2(1f, 0.5f), PointF2(-0.55f, 0.5f)
)

private fun ellipseOutline(): List<PointF2> = (0 until 36).map { i ->
    val t = (2.0 * Math.PI * i / 36).toFloat()
    PointF2(cos(t), 0.60f * sin(t))
}

/** Half a disc, flat edge down. Its halving line is nowhere near its centre. */
private fun semicircleOutline(): List<PointF2> {
    val v = ArrayList<PointF2>(28)
    for (i in 0..26) {
        val t = (Math.PI + Math.PI * i / 26).toFloat()
        v.add(PointF2(cos(t), sin(t)))
    }
    return v
}

/** Six petals, as a polar rose sampled finely enough to keep them smooth. */
private fun flowerOutline(): List<PointF2> = (0 until 72).map { i ->
    val t = (2.0 * Math.PI * i / 72).toFloat()
    val r = 0.58f + 0.42f * abs(cos(3f * t))
    PointF2(r * cos(t), r * sin(t))
}

/**
 * A ring, as far as a ring can be had here.
 *
 * The game cuts simple polygons - one closed loop, no holes - so a true annulus
 * cannot be expressed: there is nowhere to put the inner boundary. This is the
 * standard way round it, a keyhole: out along one radius, round the outside, back
 * in, and round the inside the other way, leaving a two-degree slit at the
 * bottom. Everything downstream - the area, the halving line, the clipping -
 * then works on it like any other outline.
 */
private const val TORUS_INNER = 0.58f

/** The two circles the ring is drawn from - see [ShapeKind.renderContours]. */
private fun torusContours(): List<List<PointF2>> = listOf(
    (0 until 48).map {
        val t = (2.0 * Math.PI * it / 48).toFloat(); PointF2(cos(t), sin(t))
    },
    (0 until 36).map {
        val t = (2.0 * Math.PI * it / 36).toFloat()
        PointF2(TORUS_INNER * cos(t), TORUS_INNER * sin(t))
    }
)

private fun torusOutline(): List<PointF2> {
    val slit = Math.toRadians(2.0).toFloat()
    val start = (Math.PI / 2).toFloat() + slit
    val end = (Math.PI / 2).toFloat() + (2.0 * Math.PI).toFloat() - slit
    val v = ArrayList<PointF2>(84)
    for (i in 0..46) {
        val t = start + (end - start) * i / 46f
        v.add(PointF2(cos(t), sin(t)))
    }
    for (i in 0..34) {
        val t = end + (start - end) * i / 34f
        v.add(PointF2(TORUS_INNER * cos(t), TORUS_INNER * sin(t)))
    }
    return v
}

/** The same ring with a mouth cut out of it, which needs no keyhole at all. */
private fun halfTorusOutline(): List<PointF2> {
    val a0 = Math.toRadians(40.0).toFloat()
    val a1 = Math.toRadians(320.0).toFloat()
    val v = ArrayList<PointF2>(74)
    for (i in 0..40) {
        val t = a0 + (a1 - a0) * i / 40f
        v.add(PointF2(cos(t), sin(t)))
    }
    for (i in 0..30) {
        val t = a1 + (a0 - a1) * i / 30f
        v.add(PointF2(0.55f * cos(t), 0.55f * sin(t)))
    }
    return v
}

/**
 * Two circle arcs meeting at their true intersection points, worked out rather
 * than guessed. The old one used round angles that did not actually meet, so the
 * tips crossed and left a wedge.
 */
private fun moonOutline() = built { v ->
    val x1 = 0.5f; val y1 = 0.5f; val r1 = 0.5f
    val x2 = 0.64f; val y2 = 0.5f; val r2 = 0.44f

    val d = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
    val a = (r1 * r1 - r2 * r2 + d * d) / (2f * d)
    val h = sqrt(max(r1 * r1 - a * a, 0f))
    val px = x1 + a * (x2 - x1) / d
    val py = y1 + a * (y2 - y1) / d
    val ux = -(y2 - y1) / d
    val uy = (x2 - x1) / d

    val ax = px + h * ux; val ay = py + h * uy
    val bx = px - h * ux; val by = py - h * uy

    // The long way round the outside, then back along the bite.
    sweepInto(v, x1, y1, r1, atan2(ay - y1, ax - x1), atan2(by - y1, bx - x1), 26, true)
    sweepInto(v, x2, y2, r2, atan2(by - y2, bx - x2), atan2(ay - y2, ax - x2), 20, false)
}

private fun crownOutline() = outline(0f,1f, 0.08f,0.16f, 0.3f,0.56f, 0.5f,0.04f, 0.7f,0.56f, 0.92f,0.16f, 1f,1f)

private fun dropOutline() = built { v ->
    v.add(0.5f); v.add(0f)
    arcInto(v, 0.5f, 0.62f, 0.4f, 0.38f, -50f, 230f, 18)
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
    MOON("Moon", 13000, { moonOutline() }),
    RECTANGLE("Rectangle", 500, { rectangleOutline() }),
    ELLIPSE("Ellipse", 1500, { ellipseOutline() }),
    PARALLELOGRAM("Parallelogram", 2500, { parallelogramOutline() }),
    SEMICIRCLE("Semicircle", 5500, { semicircleOutline() }),
    FLOWER("Flower", 14000, { flowerOutline() }),
    HALF_TORUS("Half Torus", 15000, { halfTorusOutline() }),
    TORUS("Torus", 16000, { torusOutline() });

    /** Outline in unit space, computed once per kind. */
    val unitVertices: List<PointF2> by lazy(LazyThreadSafetyMode.NONE) { builder() }

    /**
     * What to draw, when that is not the same as what to cut.
     *
     * The ring is the only shape where those differ. Cutting needs one closed loop
     * with no holes, so [unitVertices] runs it as a keyhole - out along a radius,
     * round the outside, back in, round the inside - which is correct for the area
     * and the halving line but leaves a hairline slit for the outline to trace.
     * Drawing takes these two separate circles instead, filled even-odd, so the
     * ring is a ring and the seam is nowhere.
     */
    val renderContours: List<List<PointF2>>? by lazy(LazyThreadSafetyMode.NONE) {
        if (this == TORUS) torusContours() else null
    }

    /**
     * The same outline again, but sized and centred for a badge rather than for
     * play.
     *
     * [unitVertices] scales every kind so its farthest vertex sits at radius 1,
     * which is right for a thrown shape - they all sweep the same circle - and
     * badly wrong for a row of icons: a circle fills that disc completely while a
     * square only touches it at four corners, so side by side the circle looked
     * enormous and the square shrunken.
     *
     * Here every kind is given the same *area* as the unit circle instead, which
     * is what the eye actually reads as "the same size", and is re-centred on its
     * bounding box so it hangs in the middle of the space it is given. A few long
     * kinds would still overhang after that, so anything wider or taller than
     * [GLYPH_BOUND] is pulled back to fit.
     */
    val glyphVertices: List<PointF2> by lazy(LazyThreadSafetyMode.NONE) {
        val v = unitVertices
        var twice = 0f
        for (i in v.indices) {
            val a = v[i]
            val b = v[(i + 1) % v.size]
            twice += a.x * b.y - b.x * a.y
        }
        val area = abs(twice) * 0.5f
        var scale = if (area > 1e-5f) sqrt(UNIT_CIRCLE_AREA / area) else 1f

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in v) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val midX = (minX + maxX) * 0.5f
        val midY = (minY + maxY) * 0.5f

        val reach = max(maxX - midX, maxY - midY) * scale
        if (reach > GLYPH_BOUND) scale *= GLYPH_BOUND / reach

        v.map { PointF2((it.x - midX) * scale, (it.y - midY) * scale) }
    }

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

        /** Area of the unit circle - the size every badge is matched to. */
        private const val UNIT_CIRCLE_AREA = 3.14159265f

        /** How far a badge may reach from its centre before it is pulled back in. */
        private const val GLYPH_BOUND = 1.12f

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
    /** Which of the run-colour slots this shape wears - see Theme.shapeLight. */
    val paletteIndex: Int,
    val spawnTimeMs: Long
) {

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

    /** [ShapeKind.renderContours] carried into world space, or null. */
    fun worldContours(): List<List<PointF2>>? {
        val contours = kind.renderContours ?: return null
        val c = cos(rotation)
        val s = sin(rotation)
        val r = radius * spawnScale
        return contours.map { loop ->
            loop.map { p ->
                PointF2(x + r * (p.x * c - p.y * s), y + r * (p.x * s + p.y * c))
            }
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
                random.nextInt(Theme.shapeSlots), nowMs
            )
        }
    }
}
