package com.halfmeasures.slicegame

import android.content.Context

/**
 * Everything the game remembers across runs, beyond the handful of records the
 * score card already shows.
 *
 * Two tallies are kept: how every cut in the player's history landed, and how
 * every cut landed *per shape*. The second is the interesting one - it is what
 * makes it possible to say "you halve a circle better than anything else, and a
 * bolt has never once gone your way".
 *
 * Counts are held in memory during a run and written once when it ends. A cut
 * lands two or three times a second at speed, and a write to disk on each one is
 * a stutter for a number nobody reads until the run is over.
 */
class LifetimeStats private constructor(
    /** Cuts by grade over every run, indexed the way [GRADES] is. */
    val bands: IntArray,
    /** Per shape, the same six counts, by enum name. */
    val perShape: MutableMap<String, IntArray>
) {

    /** One shape's history, with the arithmetic the stats screen wants. */
    class ShapeRecord(val kind: ShapeKind, val counts: IntArray) {
        val perfect: Int get() = counts[0]
        /** Great and good together: the cuts that kept a run alive. */
        val good: Int get() = counts[1] + counts[2]
        /** Everything from a 70/30 down, which is what actually costs health. */
        val bad: Int get() = counts[3] + counts[4] + counts[5]
        val total: Int get() = counts.sum()
        val perfectRate: Float get() = if (total == 0) 0f else perfect.toFloat() / total
        val badRate: Float get() = if (total == 0) 0f else bad.toFloat() / total
    }

    fun record(kind: ShapeKind, gradeOrdinal: Int) {
        if (gradeOrdinal !in bands.indices) return
        bands[gradeOrdinal]++
        perShape.getOrPut(kind.name) { IntArray(GRADES) }[gradeOrdinal]++
    }

    val totalCuts: Int get() = bands.sum()

    /**
     * Shapes ranked by how often they are halved outright, best first.
     *
     * A shape needs [MIN_SAMPLE] cuts behind it to be ranked at all: one lucky
     * perfect on a shape seen twice is not a strength, and without the floor the
     * table fills up with whatever was cut least.
     */
    fun ranked(): List<ShapeRecord> = perShape.entries
        .mapNotNull { (name, counts) ->
            val kind = ShapeKind.values().firstOrNull { it.name == name } ?: return@mapNotNull null
            ShapeRecord(kind, counts)
        }
        .filter { it.total >= MIN_SAMPLE }

    /**
     * The shapes halved best. A shape with no perfect cut at all has not earned a
     * place here however tidy the rest of its cuts were - "best" with a zero
     * beside it reads as a mistake, and it was one.
     */
    fun best(limit: Int = 5): List<ShapeRecord> = ranked()
        .filter { it.perfect > 0 }
        .sortedWith(compareByDescending<ShapeRecord> { it.perfectRate }
            .thenByDescending { it.perfect })
        .take(limit)

    /**
     * The shapes that cost the most, never one already listed as a best.
     *
     * A shape can genuinely lead on both rates - plenty of perfects and plenty of
     * disasters - but a table that names the same shape as the player's best and
     * their worst is not telling them anything they can act on.
     */
    fun worst(limit: Int = 5, excluding: List<ShapeRecord> = emptyList()): List<ShapeRecord> {
        val skip = excluding.map { it.kind }.toSet()
        return ranked()
            .filter { it.bad > 0 && it.kind !in skip }
            .sortedWith(compareByDescending<ShapeRecord> { it.badRate }
                .thenByDescending { it.bad })
            .take(limit)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lifetime_bands", bands.joinToString(","))
            .putString("lifetime_shapes", encode(perShape))
            .apply()
    }

    companion object {
        /** How many grades a cut can land in - PERFECT down to MISS. */
        const val GRADES = 6
        /** Cuts a shape needs before it is worth ranking. */
        const val MIN_SAMPLE = 8

        private const val PREFS = "half_measures_scores"

        fun load(context: Context): LifetimeStats {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val bands = IntArray(GRADES)
            p.getString("lifetime_bands", "")?.split(",")?.forEachIndexed { i, s ->
                if (i < GRADES) bands[i] = s.toIntOrNull() ?: 0
            }
            return LifetimeStats(bands, decode(p.getString("lifetime_shapes", "")))
        }

        /** "NAME=a,b,c,d,e,f;NAME=..." - one line, and readable if it is ever dumped. */
        private fun encode(map: Map<String, IntArray>): String =
            map.entries.joinToString(";") { (name, counts) ->
                "$name=${counts.joinToString(",")}"
            }

        private fun decode(raw: String?): MutableMap<String, IntArray> {
            val out = HashMap<String, IntArray>()
            if (raw.isNullOrBlank()) return out
            for (entry in raw.split(";")) {
                val name = entry.substringBefore('=', "")
                if (name.isEmpty()) continue
                val counts = IntArray(GRADES)
                entry.substringAfter('=', "").split(",").forEachIndexed { i, s ->
                    if (i < GRADES) counts[i] = s.toIntOrNull() ?: 0
                }
                out[name] = counts
            }
            return out
        }
    }
}
