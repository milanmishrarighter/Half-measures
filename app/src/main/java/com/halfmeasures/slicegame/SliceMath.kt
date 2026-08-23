package com.halfmeasures.slicegame

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Geometry helpers for slicing convex polygons with a straight line, in the
 * style of Fruit Ninja: the player's swipe defines a line, and any shape the
 * line passes through is split into two convex pieces.
 */
object SliceMath {

    /** Signed area (shoelace). Positive/negative depending on winding; magnitude is the area. */
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

    /** Which side of the line (a->b) point p is on. >0 left, <0 right, 0 on the line. */
    private fun side(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax)
    }

    /**
     * Splits a convex polygon by the infinite line through (ax,ay)-(bx,by).
     * Returns a pair of polygons (side1, side2). Either side may be empty if
     * the line does not actually cross the polygon.
     */
    fun splitPolygon(
        poly: List<PointF2>,
        ax: Float, ay: Float, bx: Float, by: Float
    ): Pair<List<PointF2>, List<PointF2>> {
        val left = ArrayList<PointF2>()
        val right = ArrayList<PointF2>()
        val n = poly.size
        for (i in 0 until n) {
            val cur = poly[i]
            val next = poly[(i + 1) % n]
            val curSide = side(ax, ay, bx, by, cur.x, cur.y)
            val nextSide = side(ax, ay, bx, by, next.x, next.y)

            if (curSide >= 0) left.add(cur)
            if (curSide <= 0) right.add(cur)

            // Edge crosses the line: add the intersection point to both sides.
            if (curSide != 0f && nextSide != 0f && (curSide > 0) != (nextSide > 0)) {
                val t = curSide / (curSide - nextSide)
                val ix = cur.x + t * (next.x - cur.x)
                val iy = cur.y + t * (next.y - cur.y)
                left.add(PointF2(ix, iy))
                right.add(PointF2(ix, iy))
            }
        }
        return Pair(left, right)
    }

    /** Shortest distance from point p to the infinite line through a-b. */
    fun distancePointToLine(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.0001f) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        return abs(dx * (ay - py) - (ax - px) * dy) / len
    }

    /**
     * Where does the perpendicular projection of p fall along segment a-b,
     * expressed as a fraction (0 = at a, 1 = at b, can be outside [0,1]).
     */
    fun projectionFraction(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 0.0001f) return 0f
        return ((px - ax) * dx + (py - ay) * dy) / lenSq
    }

    /**
     * A shape is considered sliced by a swipe segment (ax,ay)-(bx,by) if the
     * infinite line through the segment actually passes within the shape's
     * radius, AND that crossing point falls reasonably close to the segment
     * itself (with a small margin to forgive coarse touch sampling on fast swipes).
     */
    fun segmentSlicesShape(shape: GameShape, ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val dist = distancePointToLine(shape.x, shape.y, ax, ay, bx, by)
        if (dist > shape.radius) return false
        val frac = projectionFraction(shape.x, shape.y, ax, ay, bx, by)
        val margin = 0.35f // allow the crossing to fall a bit beyond the sampled segment endpoints
        return frac in (-margin)..(1f + margin)
    }

    /** Result of slicing one shape: the two resulting piece areas as a fraction of the whole. */
    data class SliceResult(val areaA: Float, val areaB: Float) {
        val total get() = areaA + areaB
        val imbalancePercent get() = if (total <= 0f) 100f else abs(areaA - areaB) / total * 100f
    }

    fun sliceShape(shape: GameShape, ax: Float, ay: Float, bx: Float, by: Float): SliceResult? {
        val poly = shape.worldVertices()
        val (left, right) = splitPolygon(poly, ax, ay, bx, by)
        if (left.size < 3 || right.size < 3) return null
        return SliceResult(polygonArea(left), polygonArea(right))
    }
}
