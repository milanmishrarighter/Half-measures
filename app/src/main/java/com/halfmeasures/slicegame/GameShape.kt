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


// ---- Letters. Blocky, and only the ones whose outline is a single loop: a
// counter like the one in an A or an O would be a hole, and a hole is not
// something a half-plane clip can represent.
private fun letterC() = outline(0f,0f, 1f,0f, 1f,0.22f, 0.28f,0.22f, 0.28f,0.78f, 1f,0.78f, 1f,1f, 0f,1f)
private fun letterE() = outline(0f,0f, 1f,0f, 1f,0.2f, 0.3f,0.2f, 0.3f,0.4f, 0.86f,0.4f, 0.86f,0.6f, 0.3f,0.6f, 0.3f,0.8f, 1f,0.8f, 1f,1f, 0f,1f)
private fun letterF() = outline(0f,0f, 1f,0f, 1f,0.2f, 0.3f,0.2f, 0.3f,0.42f, 0.86f,0.42f, 0.86f,0.62f, 0.3f,0.62f, 0.3f,1f, 0f,1f)
private fun letterG() = outline(0f,0f, 1f,0f, 1f,0.2f, 0.28f,0.2f, 0.28f,0.8f, 0.72f,0.8f, 0.72f,0.62f, 0.5f,0.62f, 0.5f,0.44f, 1f,0.44f, 1f,1f, 0f,1f)
private fun letterH() = outline(0f,0f, 0.28f,0f, 0.28f,0.4f, 0.72f,0.4f, 0.72f,0f, 1f,0f, 1f,1f, 0.72f,1f, 0.72f,0.6f, 0.28f,0.6f, 0.28f,1f, 0f,1f)
private fun letterI() = outline(0f,0f, 1f,0f, 1f,0.2f, 0.64f,0.2f, 0.64f,0.8f, 1f,0.8f, 1f,1f, 0f,1f, 0f,0.8f, 0.36f,0.8f, 0.36f,0.2f, 0f,0.2f)
private fun letterJ() = outline(0.65f,0f, 1f,0f, 1f,1f, 0f,1f, 0f,0.78f, 0.65f,0.78f)
private fun letterK() = outline(0f,0f, 0.28f,0f, 0.28f,0.4f, 0.7f,0f, 1f,0f, 0.56f,0.5f, 1f,1f, 0.7f,1f, 0.28f,0.6f, 0.28f,1f, 0f,1f)
private fun letterL() = outline(0f,0f, 0.35f,0f, 0.35f,0.78f, 1f,0.78f, 1f,1f, 0f,1f)
private fun letterM() = outline(0f,1f, 0f,0f, 0.25f,0f, 0.5f,0.46f, 0.75f,0f, 1f,0f, 1f,1f, 0.75f,1f, 0.75f,0.42f, 0.5f,0.82f, 0.25f,0.42f, 0.25f,1f)
private fun letterN() = outline(0f,1f, 0f,0f, 0.26f,0f, 0.74f,0.6f, 0.74f,0f, 1f,0f, 1f,1f, 0.74f,1f, 0.26f,0.4f, 0.26f,1f)
private fun letterS() = outline(0f,0f, 1f,0f, 1f,0.2f, 0.28f,0.2f, 0.28f,0.4f, 1f,0.4f, 1f,1f, 0f,1f, 0f,0.8f, 0.72f,0.8f, 0.72f,0.6f, 0f,0.6f)
private fun letterT() = outline(0f,0f, 1f,0f, 1f,0.22f, 0.65f,0.22f, 0.65f,1f, 0.35f,1f, 0.35f,0.22f, 0f,0.22f)
private fun letterU() = outline(0f,0f, 0.28f,0f, 0.28f,0.78f, 0.72f,0.78f, 0.72f,0f, 1f,0f, 1f,1f, 0f,1f)
private fun letterV() = outline(0f,0f, 0.26f,0f, 0.5f,0.72f, 0.74f,0f, 1f,0f, 0.62f,1f, 0.38f,1f)
private fun letterW() = outline(0f,0f, 0.2f,0f, 0.33f,0.62f, 0.5f,0.18f, 0.67f,0.62f, 0.8f,0f, 1f,0f, 0.8f,1f, 0.6f,1f, 0.5f,0.66f, 0.4f,1f, 0.2f,1f)
private fun letterX() = outline(0f,0f, 0.26f,0f, 0.5f,0.34f, 0.74f,0f, 1f,0f, 0.66f,0.5f, 1f,1f, 0.74f,1f, 0.5f,0.66f, 0.26f,1f, 0f,1f, 0.34f,0.5f)
private fun letterY() = outline(0f,0f, 0.26f,0f, 0.5f,0.42f, 0.74f,0f, 1f,0f, 0.65f,0.6f, 0.65f,1f, 0.35f,1f, 0.35f,0.6f)
private fun letterZ() = outline(0f,0f, 1f,0f, 1f,0.2f, 0.38f,0.8f, 1f,0.8f, 1f,1f, 0f,1f, 0f,0.8f, 0.62f,0.2f, 0f,0.2f)

// ---- Digits, same rule: no counters.
private fun digitOne() = outline(0.34f,0f, 0.66f,0f, 0.66f,0.8f, 1f,0.8f, 1f,1f, 0.08f,1f, 0.08f,0.8f, 0.34f,0.8f)
private fun digitTwo() = outline(0f,0f, 1f,0f, 1f,0.56f, 0.3f,0.56f, 0.3f,0.8f, 1f,0.8f, 1f,1f, 0f,1f, 0f,0.36f, 0.7f,0.36f, 0.7f,0.2f, 0f,0.2f)
private fun digitThree() = outline(0f,0f, 1f,0f, 1f,1f, 0f,1f, 0f,0.8f, 0.72f,0.8f, 0.72f,0.6f, 0.26f,0.6f, 0.26f,0.42f, 0.72f,0.42f, 0.72f,0.2f, 0f,0.2f)
private fun digitFive() = outline(0f,0f, 1f,0f, 1f,0.2f, 0.3f,0.2f, 0.3f,0.4f, 1f,0.4f, 1f,1f, 0f,1f, 0f,0.8f, 0.7f,0.8f, 0.7f,0.6f, 0f,0.6f)
private fun digitSeven() = outline(0f,0f, 1f,0f, 1f,0.2f, 0.56f,1f, 0.28f,1f, 0.72f,0.22f, 0f,0.22f)

// ---- Things. Rougher outlines than the letters, because a fish read at a
// glance is a silhouette, not a diagram.
/** Three clear tiers and a narrow trunk. Two shallow tiers on a wide trunk is an
 *  upload arrow, which is what the old one looked like. */
private fun treeOutline() = outline(
    0.50f,0.00f, 0.68f,0.26f, 0.58f,0.26f, 0.80f,0.55f, 0.70f,0.55f, 0.94f,0.84f,
    0.60f,0.84f, 0.60f,1.00f, 0.40f,1.00f, 0.40f,0.84f, 0.06f,0.84f, 0.30f,0.55f,
    0.20f,0.55f, 0.42f,0.26f, 0.32f,0.26f
)
private fun fishOutline() = built { v ->
    v.addAll(listOf(0.02f, 0.16f, 0.36f, 0.5f, 0.02f, 0.84f))
    arcInto(v, 0.6f, 0.5f, 0.42f, 0.4f, 150f, -150f, 14)
}
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

private fun appleOutline() = built { v ->
    arcInto(v, 0.5f, 0.58f, 0.46f, 0.42f, -80f, 260f, 22)
    v.add(0.55f); v.add(0.1f)
    v.add(0.45f); v.add(0.1f)
}
private fun arrowOutline() = outline(0.5f,0f, 1f,0.46f, 0.72f,0.46f, 0.72f,1f, 0.28f,1f, 0.28f,0.46f, 0f,0.46f)
private fun boltOutline() = outline(0.58f,0f, 0.14f,0.56f, 0.46f,0.56f, 0.34f,1f, 0.86f,0.42f, 0.54f,0.42f)
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

private fun cloudOutline() = built { v ->
    val bumps = arrayOf(
        floatArrayOf(0.24f, 0.56f, 0.22f),
        floatArrayOf(0.50f, 0.50f, 0.245f),
        floatArrayOf(0.76f, 0.56f, 0.22f)
    )
    val base = 0.86f
    var x0 = Float.MAX_VALUE
    var x1 = -Float.MAX_VALUE
    for (b in bumps) {
        x0 = min(x0, b[0] - b[2])
        x1 = max(x1, b[0] + b[2])
    }
    val steps = 54
    for (i in 0..steps) {
        val x = x0 + (x1 - x0) * i / steps
        var top = base
        for (b in bumps) {
            val dx = x - b[0]
            if (abs(dx) < b[2]) top = min(top, b[1] - sqrt(b[2] * b[2] - dx * dx))
        }
        v.add(x)
        v.add(top)
    }
    v.add(x1); v.add(base)
    v.add(x0); v.add(base)
}

private fun houseOutline() = outline(0.5f,0f, 1f,0.4f, 0.86f,0.4f, 0.86f,1f, 0.14f,1f, 0.14f,0.4f, 0f,0.4f)
private fun bottleOutline() = outline(0.4f,0f, 0.6f,0f, 0.6f,0.18f, 0.78f,0.36f, 0.78f,1f, 0.22f,1f, 0.22f,0.36f, 0.4f,0.18f)
private fun mushroomOutline() = built { v ->
    arcInto(v, 0.5f, 0.46f, 0.5f, 0.36f, 180f, 360f, 14)
    v.add(0.66f); v.add(0.46f)
    v.add(0.62f); v.add(1f)
    v.add(0.38f); v.add(1f)
    v.add(0.34f); v.add(0.46f)
}
private fun crownOutline() = outline(0f,1f, 0.08f,0.16f, 0.3f,0.56f, 0.5f,0.04f, 0.7f,0.56f, 0.92f,0.16f, 1f,1f)
private fun leafOutline() = built { v ->
    arcInto(v, 0.5f, 0.5f, 0.46f, 0.46f, 135f, -45f, 12)
    arcInto(v, 0.5f, 0.5f, 0.46f, 0.46f, -45f, -225f, 12)
}
private fun ghostOutline() = built { v ->
    arcInto(v, 0.5f, 0.42f, 0.44f, 0.42f, 180f, 360f, 14)
    v.addAll(listOf(0.94f, 1f, 0.78f, 0.86f, 0.62f, 1f, 0.46f, 0.86f, 0.3f, 1f, 0.14f, 0.86f, 0.06f, 1f))
}
private fun dropOutline() = built { v ->
    v.add(0.5f); v.add(0f)
    arcInto(v, 0.5f, 0.62f, 0.4f, 0.38f, -50f, 230f, 18)
}
private fun rocketOutline() = outline(
    0.5f,0f, 0.72f,0.32f, 0.72f,0.7f, 0.96f,0.9f, 0.72f,0.9f, 0.62f,1f,
    0.38f,1f, 0.28f,0.9f, 0.04f,0.9f, 0.28f,0.7f, 0.28f,0.32f
)
private fun cactusOutline() = outline(
    0.4f,0f, 0.6f,0f, 0.6f,0.34f, 0.84f,0.34f, 0.84f,0.66f, 0.6f,0.66f, 0.6f,1f,
    0.4f,1f, 0.4f,0.52f, 0.16f,0.52f, 0.16f,0.24f, 0.4f,0.24f
)
private fun sliceOutline() = built { v ->
    v.add(0.5f); v.add(0f)
    arcInto(v, 0.5f, 0.06f, 0.52f, 0.94f, 62f, 118f, 10)
}
/**
 * A thin shaft between four knuckles, built as the top edge and then the bottom
 * edge of their union. The old one placed the knuckle arcs at angles that folded
 * back over the shaft, and its shaft was as thick as the lobes were tall, which
 * is simply the letter H.
 */
private fun boneOutline() = built { v ->
    val lobes = arrayOf(
        floatArrayOf(0.18f, 0.30f, 0.185f), floatArrayOf(0.18f, 0.70f, 0.185f),
        floatArrayOf(0.82f, 0.30f, 0.185f), floatArrayOf(0.82f, 0.70f, 0.185f)
    )
    val shaftLeft = 0.18f
    val shaftRight = 0.82f
    val shaftTop = 0.455f
    val shaftBottom = 0.545f

    val steps = 44
    val top = ArrayList<Float>()
    val bottom = ArrayList<Float>()
    for (i in 0..steps) {
        val x = i.toFloat() / steps
        var t = Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        if (x >= shaftLeft && x <= shaftRight) {
            t = min(t, shaftTop)
            b = max(b, shaftBottom)
        }
        for (l in lobes) {
            val dx = x - l[0]
            if (abs(dx) < l[2]) {
                val h = sqrt(l[2] * l[2] - dx * dx)
                t = min(t, l[1] - h)
                b = max(b, l[1] + h)
            }
        }
        if (t == Float.MAX_VALUE) continue
        top.add(x); top.add(t)
        bottom.add(x); bottom.add(b)
    }
    v.addAll(top)
    for (i in bottom.size / 2 - 1 downTo 0) {
        v.add(bottom[i * 2]); v.add(bottom[i * 2 + 1])
    }
}

private fun keyOutline() = outline(
    0.5f,0f, 0.72f,0.12f, 0.72f,0.34f, 0.58f,0.44f, 0.58f,0.62f, 0.78f,0.62f,
    0.78f,0.76f, 0.58f,0.76f, 0.58f,0.88f, 0.74f,0.88f, 0.74f,1f, 0.42f,1f,
    0.42f,0.44f, 0.28f,0.34f, 0.28f,0.12f
)

/**
 * The catalogue of sliceable shapes. Each kind supplies its outline in unit
 * space (bounded by a radius-1 circle); [GameShape] scales, rotates and positions
 * it.
 *
 * Declaration order here is arbitrary. The unlock schedule instead walks
 * [ShapeKind.byDifficulty], which is measured rather than guessed - see
 * [ShapeKind.difficulty] - so adding a shape to this list is all it takes to slot
 * it into the right stage.
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
    STAR5("Star", { starOutline(5, 0.42f) }),

    // Things.
    HOUSE("House", { houseOutline() }),
    ARROW("Arrow", { arrowOutline() }),
    DROP("Drop", { dropOutline() }),
    LEAF("Leaf", { leafOutline() }),
    HEART("Heart", { heartOutline() }),
    APPLE("Apple", { appleOutline() }),
    MOON("Moon", { moonOutline() }),
    CLOUD("Cloud", { cloudOutline() }),
    TREE("Tree", { treeOutline() }),
    FISH("Fish", { fishOutline() }),
    BOTTLE("Bottle", { bottleOutline() }),
    MUSHROOM("Mushroom", { mushroomOutline() }),
    CROWN("Crown", { crownOutline() }),
    GHOST("Ghost", { ghostOutline() }),
    ROCKET("Rocket", { rocketOutline() }),
    CACTUS("Cactus", { cactusOutline() }),
    PIZZA("Pizza Slice", { sliceOutline() }),
    BOLT("Bolt", { boltOutline() }),
    BONE("Bone", { boneOutline() }),
    KEY("Key", { keyOutline() }),

    // Digits.
    ONE("One", { digitOne() }),
    TWO("Two", { digitTwo() }),
    THREE("Three", { digitThree() }),
    FIVE("Five", { digitFive() }),
    SEVEN("Seven", { digitSeven() }),

    // Letters.
    LETTER_C("C", { letterC() }),
    LETTER_E("E", { letterE() }),
    LETTER_F("F", { letterF() }),
    LETTER_G("G", { letterG() }),
    LETTER_H("H", { letterH() }),
    LETTER_I("I", { letterI() }),
    LETTER_J("J", { letterJ() }),
    LETTER_K("K", { letterK() }),
    LETTER_L("L", { letterL() }),
    LETTER_M("M", { letterM() }),
    LETTER_N("N", { letterN() }),
    LETTER_S("S", { letterS() }),
    LETTER_T("T", { letterT() }),
    LETTER_U("U", { letterU() }),
    LETTER_V("V", { letterV() }),
    LETTER_W("W", { letterW() }),
    LETTER_X("X", { letterX() }),
    LETTER_Y("Y", { letterY() }),
    LETTER_Z("Z", { letterZ() });

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

    /**
     * How hard this shape is to halve by eye, measured rather than judged.
     *
     * Three things make a shape hard, and all three are computed by cutting it for
     * real at sixteen angles:
     *
     *  - **Sensitivity.** How much of the area a fixed small aiming error costs.
     *    A long thin shape cut across its length forgives nothing; a circle
     *    barely notices. This is the dominant term, because it is what actually
     *    turns a near-miss into a bad score.
     *  - **Eccentricity.** How far the halving line sits from the centroid. The
     *    eye aims at the middle of a shape, so a shape whose true bisector is not
     *    there - a triangle, a pizza slice - is quietly misleading.
     *  - **Concavity.** How much of its own bounding hull the shape fails to
     *    fill. Spiky, notched outlines are harder to read at a glance and harder
     *    to imagine a line through.
     */
    val difficulty: Float by lazy(LazyThreadSafetyMode.NONE) {
        val poly = unitVertices
        val total = SliceMath.polygonArea(poly)
        if (total <= 0f) return@lazy 0f

        val samples = 16
        // A fixed aiming error, in the same unit space the outline lives in.
        val error = 0.06f
        var sensitivity = 0f
        var eccentricity = 0f

        for (i in 0 until samples) {
            val angle = (Math.PI * i / samples).toFloat()
            val dx = cos(angle)
            val dy = sin(angle)
            val offset = SliceMath.bisectorOffset(poly, 0f, 0f, dx, dy, 2.5f)
            eccentricity += kotlin.math.abs(offset)

            // Cut it off-centre by the error and see what that costs.
            val nx = -dy
            val ny = dx
            val px = nx * (offset + error)
            val py = ny * (offset + error)
            val (a, b) = SliceMath.splitPolygon(poly, px, py, px + dx, py + dy)
            val areaA = SliceMath.polygonArea(a)
            val areaB = SliceMath.polygonArea(b)
            if (areaA + areaB > 0f) sensitivity += SliceMath.deviationPercent(areaA, areaB)
        }

        sensitivity /= samples
        eccentricity /= samples

        // Concavity, from the ratio of the outline's area to its convex hull's.
        val hull = SliceMath.polygonArea(convexHull(poly)).coerceAtLeast(0.0001f)
        val concavity = (1f - total / hull).coerceIn(0f, 1f)

        sensitivity + eccentricity * 22f + concavity * 26f
    }

    companion object {
        /**
         * The catalogue in the order a player meets it, easiest first. Computed
         * once from the measured [difficulty], so a shape added to the enum lands
         * at the right stage without anyone deciding where it belongs.
         */
        val byDifficulty: List<ShapeKind> by lazy(LazyThreadSafetyMode.NONE) {
            values().filter { it.isSimple }.sortedBy { it.difficulty }
        }

        /**
         * How many kinds are in play at [stage]: the starting set, plus a fixed
         * number of new shapes each stage, capped at the full catalogue.
         */
        fun unlockedCount(stage: Int, startingShapes: Int, shapesPerStage: Int): Int =
            (startingShapes + stage * shapesPerStage).coerceIn(1, values().size)

        /** The stage at which the shape at [index] of [byDifficulty] first appears. */
        fun stageFor(index: Int, startingShapes: Int, shapesPerStage: Int): Int {
            if (index < startingShapes) return 0
            val step = shapesPerStage.coerceAtLeast(1)
            return (index - startingShapes) / step + 1
        }

        /**
         * The score at which a kind starts appearing: the player's override if they
         * set one, otherwise its measured place in the unlock order turned into a
         * score. Overriding one shape therefore leaves every other one alone.
         */
        fun unlockScore(kind: ShapeKind, settings: GameSettings): Int {
            settings.shapeUnlockScores[kind.name]?.let { return it }
            val index = byDifficulty.indexOf(kind)
            val stage = stageFor(index, settings.startingShapeCount, settings.shapesPerStage)
            return stage * settings.stageScoreInterval
        }

        /**
         * Chooses a kind the player has both switched on and reached the score for.
         *
         * If nothing qualifies - every unlocked kind switched off, or every unlock
         * score set above the current score - the search falls back to whatever is
         * merely switched on, and finally to the catalogue. An empty sky is not a
         * setting anyone meant to choose.
         */
        fun pick(score: Int, settings: GameSettings, random: Random): ShapeKind {
            val enabled = byDifficulty.filter { it.name !in settings.disabledShapes }

            val reached = enabled.filter { score >= unlockScore(it, settings) }
            if (reached.isNotEmpty()) return reached[random.nextInt(reached.size)]

            if (enabled.isNotEmpty()) {
                // Nothing has unlocked yet: hand over the earliest one that will.
                return enabled.minByOrNull { unlockScore(it, settings) } ?: enabled[0]
            }
            return byDifficulty[0]
        }

        /** Andrew's monotone chain, for the concavity term of [difficulty]. */
        private fun convexHull(points: List<PointF2>): List<PointF2> {
            if (points.size < 3) return points
            val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
            val hull = ArrayList<PointF2>(sorted.size * 2)

            fun cross(o: PointF2, a: PointF2, b: PointF2): Float =
                (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

            for (p in sorted) {
                while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0f) {
                    hull.removeAt(hull.size - 1)
                }
                hull.add(p)
            }
            val lower = hull.size + 1
            for (i in sorted.indices.reversed()) {
                val p = sorted[i]
                while (hull.size >= lower && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0f) {
                    hull.removeAt(hull.size - 1)
                }
                hull.add(p)
            }
            hull.removeAt(hull.size - 1)
            return hull
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
