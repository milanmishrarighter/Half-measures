package com.halfmeasures.slicegame

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Geometry for slicing a polygon with a straight line.
 *
 * Everything here works for concave outlines (stars, crosses) as well as convex
 * ones. Clipping uses Sutherland-Hodgman against a half-plane: for a concave
 * subject the result can be a single ring joined by degenerate edges that lie
 * *along* the cut line, but because those edges are collinear they contribute
 * nothing to the shoelace sum - so the measured area is exact, and the rendered
 * piece is visually correct since the seam has zero width.
 */
object SliceMath {

    /** Unsigned area of a polygon (shoelace). */
    fun polygonArea(pts: List<PointF2>): Float {
        if (pts.size < 3) return 0f
        var sum = 0f
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2f
    }

    /** >0 left of a->b, <0 right, 0 on the line. */
    private fun side(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float =
        (bx - ax) * (py - ay) - (by - ay) * (px - ax)

    /** Sutherland-Hodgman clip of [poly] to one side of the infinite line a->b. */
    private fun clipToHalfPlane(
        poly: List<PointF2>,
        ax: Float, ay: Float, bx: Float, by: Float,
        keepLeft: Boolean
    ): List<PointF2> {
        if (poly.size < 3) return emptyList()
        val out = ArrayList<PointF2>(poly.size + 4)
        val n = poly.size
        for (i in 0 until n) {
            val cur = poly[i]
            val nxt = poly[(i + 1) % n]
            val sCur = side(ax, ay, bx, by, cur.x, cur.y).let { if (keepLeft) it else -it }
            val sNxt = side(ax, ay, bx, by, nxt.x, nxt.y).let { if (keepLeft) it else -it }

            if (sCur >= 0f) out.add(cur)
            if ((sCur > 0f && sNxt < 0f) || (sCur < 0f && sNxt > 0f)) {
                val t = sCur / (sCur - sNxt)
                out.add(PointF2(cur.x + t * (nxt.x - cur.x), cur.y + t * (nxt.y - cur.y)))
            }
        }
        return out
    }

    /** Both halves of [poly] about the infinite line a->b. Either may be empty. */
    fun splitPolygon(
        poly: List<PointF2>,
        ax: Float, ay: Float, bx: Float, by: Float
    ): Pair<List<PointF2>, List<PointF2>> = Pair(
        clipToHalfPlane(poly, ax, ay, bx, by, keepLeft = true),
        clipToHalfPlane(poly, ax, ay, bx, by, keepLeft = false)
    )

    fun distancePointToLine(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.0001f) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        return abs(dx * (ay - py) - (ax - px) * dy) / len
    }

    /** Where p projects onto segment a->b: 0 at a, 1 at b, outside [0,1] beyond the ends. */
    fun projectionFraction(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 0.0001f) return 0f
        return ((px - ax) * dx + (py - ay) * dy) / lenSq
    }

    /**
     * True when the swipe segment genuinely cuts the shape: the infinite line must
     * put outline points on both sides (so it passes through material, not past it),
     * and the crossing has to fall near the sampled segment - with a margin, because
     * fast swipes are sampled coarsely.
     */
    fun segmentSlicesShape(shape: GameShape, ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        if (distancePointToLine(shape.x, shape.y, ax, ay, bx, by) > shape.radius * 1.2f) return false

        val frac = projectionFraction(shape.x, shape.y, ax, ay, bx, by)
        val margin = 0.35f
        if (frac < -margin || frac > 1f + margin) return false

        var sawPositive = false
        var sawNegative = false
        for (p in shape.worldVertices()) {
            val s = side(ax, ay, bx, by, p.x, p.y)
            if (s > 0.5f) sawPositive = true else if (s < -0.5f) sawNegative = true
            if (sawPositive && sawNegative) return true
        }
        return false
    }

    /**
     * Offset from the shape's centre, along the line's normal, at which a line in
     * direction (dirX, dirY) splits [poly] into exactly equal areas.
     *
     * Moving the line along +normal strictly shrinks the far side, so the areas are
     * monotonic in the offset and a bisection converges quickly. This is exact for
     * every outline - including a triangle or star, where the halving line does not
     * pass through the centroid.
     */
    fun bisectorOffset(
        poly: List<PointF2>,
        cx: Float, cy: Float,
        dirX: Float, dirY: Float,
        searchRadius: Float
    ): Float {
        val total = polygonArea(poly)
        if (total <= 0f) return 0f
        val half = total / 2f
        val nx = -dirY
        val ny = dirX

        var lo = -searchRadius
        var hi = searchRadius
        repeat(26) {
            val mid = (lo + hi) / 2f
            val px = cx + nx * mid
            val py = cy + ny * mid
            val area = polygonArea(clipToHalfPlane(poly, px, py, px + dirX, py + dirY, keepLeft = true))
            // Area on the kept side falls as the offset grows.
            if (area > half) lo = mid else hi = mid
        }
        return (lo + hi) / 2f
    }

    /** How far the bigger piece sits above a perfect 50%: 0 = flawless, 50 = total whiff. */
    /**
     * Whether a polygon crosses itself.
     *
     * A self-intersecting outline is not a shape this file can reason about: the
     * shoelace sum double-counts the overlap, so its area is wrong, so every
     * judgement built on that area is wrong too. Cheap to check once per kind and
     * worth having as a permanent gate on the catalogue.
     */
    fun selfIntersects(poly: List<PointF2>): Boolean {
        val n = poly.size
        if (n < 4) return false
        for (i in 0 until n) {
            val a1 = poly[i]
            val a2 = poly[(i + 1) % n]
            for (j in i + 1 until n) {
                // Neighbouring edges share an endpoint and always "touch".
                if (j == i || (j + 1) % n == i || (i + 1) % n == j) continue
                val b1 = poly[j]
                val b2 = poly[(j + 1) % n]
                val d1 = side(b1.x, b1.y, b2.x, b2.y, a1.x, a1.y)
                val d2 = side(b1.x, b1.y, b2.x, b2.y, a2.x, a2.y)
                val d3 = side(a1.x, a1.y, a2.x, a2.y, b1.x, b1.y)
                val d4 = side(a1.x, a1.y, a2.x, a2.y, b2.x, b2.y)
                // Strict: collinear touching is a tidy-up problem, not a crossing.
                if (d1 * d2 < 0f && d3 * d4 < 0f) return true
            }
        }
        return false
    }

    fun deviationPercent(areaA: Float, areaB: Float): Float {
        val total = areaA + areaB
        if (total <= 0f) return 50f
        return (abs(areaA - areaB) / total * 50f).coerceIn(0f, 50f)
    }
}
