package com.halfmeasures.slicegame

/**
 * A rank the player holds, earned by their best score and never lost.
 *
 * Each one borrows a shape from the catalogue, walking the same running order the
 * game itself uses - so the badge a player wears is a shape they have actually
 * been cutting, and climbing the ladder feels like the same journey twice.
 */
data class Rank(
    val number: Int,
    val title: String,
    val shape: ShapeKind,
    /** The highest score still inside this rank. The last one has no ceiling. */
    val ceiling: Int
)

object Ranks {

    /**
     * Thirteen rungs. The score attached to each is the top of its band, so a
     * player sitting on exactly that score holds that rank rather than the next
     * one - "till 3k is rank one" reads the way it sounds.
     */
    val all: List<Rank> = listOf(
        Rank(1, "Cookie Cutter", ShapeKind.CIRCLE, 3_000),
        Rank(2, "Square Slicer", ShapeKind.SQUARE, 6_000),
        Rank(3, "Hexagon Halver", ShapeKind.HEXAGON, 10_000),
        Rank(4, "Octagon Divider", ShapeKind.OCTAGON, 15_000),
        Rank(5, "Trapezoid Trimmer", ShapeKind.TRAPEZOID, 20_000),
        Rank(6, "Diamond Dissector", ShapeKind.DIAMOND, 25_000),
        Rank(7, "Triangle Splitter", ShapeKind.TRIANGLE, 30_000),
        Rank(8, "Droplet Parter", ShapeKind.DROP, 35_000),
        Rank(9, "Cross Carver", ShapeKind.CROSS, 45_000),
        Rank(10, "Starfall Severer", ShapeKind.STAR6, 50_000),
        Rank(11, "Bolt Bisector", ShapeKind.BOLT, 60_000),
        Rank(12, "Crown Cleaver", ShapeKind.CROWN, 75_000),
        Rank(13, "Crescent Surgeon", ShapeKind.MOON, Int.MAX_VALUE)
    )

    val count: Int get() = all.size

    /** The rank a best score of [score] holds. */
    fun forScore(score: Int): Rank = all.firstOrNull { score <= it.ceiling } ?: all.last()

    /** The rung above [rank], or null at the top of the ladder. */
    fun next(rank: Rank): Rank? = all.getOrNull(rank.number)

    /**
     * Points still needed to reach [target]. A rank begins one point above the
     * ceiling of the rank below it.
     */
    fun pointsTo(score: Int, target: Rank): Int {
        val entry = (all.getOrNull(target.number - 2)?.ceiling ?: -1) + 1
        return (entry - score).coerceAtLeast(0)
    }

    /** How far through the current band [score] sits, for a progress bar. */
    fun progressWithin(score: Int, rank: Rank): Float {
        val floor = (all.getOrNull(rank.number - 2)?.ceiling ?: -1) + 1
        if (rank.ceiling == Int.MAX_VALUE) return 1f
        val span = (rank.ceiling - floor).coerceAtLeast(1)
        return ((score - floor).toFloat() / span).coerceIn(0f, 1f)
    }
}
