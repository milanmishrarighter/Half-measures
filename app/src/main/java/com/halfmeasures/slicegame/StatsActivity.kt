package com.halfmeasures.slicegame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The player's whole history, read top to bottom as a story rather than a table.
 *
 * The score card is a list of numbers for one run and it should look like one.
 * This is the opposite job: a few big things worth looking at, and the shapes
 * turned into something closer to a trophy cabinet than a spreadsheet. So the
 * rank is an emblem rather than a row, precision is one bar rather than six, and
 * the shapes are cards with their outlines drawn large.
 *
 * Every figure says outright whether it is a lifetime total, a best-ever, or an
 * average, because "perfect cuts: 40" answers neither question on its own.
 */
class StatsActivity : AppCompatActivity() {

    private lateinit var stats: LifetimeStats

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sounds.arm(this, GameSettings.load(this))
        stats = LifetimeStats.load(this)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val scores = getSharedPreferences("half_measures_scores", Context.MODE_PRIVATE)
        val runs = scores.getInt("runs_finished", 0)
        val best = scores.getInt("best_score", 0)
        val total = scores.getLong("score_total", 0L)
        val average = if (runs <= 0) 0 else (total / runs).toInt()
        val rank = Ranks.forScore(average)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bgBottom)
            setPadding(dp(GUTTER), dp(GUTTER), dp(GUTTER), dp(GUTTER))
        }

        if (runs < MIN_RUNS) {
            root.addView(locked(runs))
            root.addView(doneButton())
            return scroll(root)
        }

        // ---- the emblem: the rank, drawn big, over the ladder it sits on ----
        root.addView(emblem(rank, average))

        // ---- the two scores, stated as what they are ----
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bigTile("HIGHEST SCORE", format(best), Theme.accent))
            addView(spacer())
            addView(bigTile("AVERAGE SCORE", format(average), Theme.violet))
            layoutParams = rowParams()
        })

        // ---- records, all per-game, right under the two scores they belong with ----
        root.addView(sectionLabel("BEST IN A SINGLE GAME"))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(smallTile("MOST\nCUTS", scores.getInt("best_cuts", 0).toString()))
            addView(spacer())
            addView(smallTile("MOST\nPERFECTS", scores.getInt("best_perfect_cuts", 0).toString()))
            layoutParams = rowParams()
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(smallTile("LONGEST PERFECT\nSTREAK", "${scores.getInt("best_perfect_streak", 0)}x"))
            addView(spacer())
            addView(smallTile("LONGEST GOOD\nSTREAK", "${scores.getInt("best_good_streak", 0)}x"))
            layoutParams = rowParams()
        })

        // ---- and now everything counted over every run there has ever been ----
        val groups = groupedBands()
        root.addView(sectionLabel("ALL TIME"))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(smallTile("PERFECT\nCUTS", format(groups[0])))
            addView(spacer())
            addView(smallTile("GOOD\nCUTS", format(groups[1])))
            addView(spacer())
            addView(smallTile("GAMES\nPLAYED", format(runs)))
            layoutParams = rowParams()
        })
        root.addView(precisionPanel())

        // ---- the shapes ----
        val bestShapes = stats.best()
        if (bestShapes.isEmpty()) {
            root.addView(sectionLabel("YOUR SHAPES"))
            root.addView(TextView(this).apply {
                text = "A shape is ranked once you have cut it ${LifetimeStats.MIN_SAMPLE} times. " +
                    "Keep playing."
                typeface = Theme.ui(this@StatsActivity)
                setTextColor(Theme.textFaint)
                textSize = 14f
                setPadding(0, 0, 0, dp(GAP))
            })
        } else {
            root.addView(sectionLabel("YOUR BEST SHAPES", Theme.gold))
            root.addView(sectionNote("Ranked by how often you halve them perfectly."))
            bestShapes.forEachIndexed { i, r -> root.addView(shapeCard(i + 1, r, best = true)) }

            // A shape cannot be both, so the bests are held out of the worsts.
            val worstShapes = stats.worst(excluding = bestShapes)
            if (worstShapes.isNotEmpty()) {
                root.addView(sectionLabel("YOUR WORST SHAPES", Theme.danger))
                root.addView(sectionNote("Ranked by how often you butcher them."))
                worstShapes.forEachIndexed { i, r -> root.addView(shapeCard(i + 1, r, best = false)) }
            }
        }

        root.addView(doneButton())
        return scroll(root)
    }

    // -----------------------------------------------------------------
    // Blocks
    // -----------------------------------------------------------------

    /**
     * The rank, as the badge it is: the shape drawn large behind its own name,
     * with the thirteen rungs under it and the next one spelled out.
     */
    private fun emblem(rank: Rank, average: Int): View {
        val tint = rungColour(rank.number)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22f).toFloat()
                colors = intArrayOf(Theme.withAlpha(tint, 0.20f), Theme.withAlpha(tint, 0.04f))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(dp(1f), Theme.withAlpha(tint, 0.35f))
            }
            setPadding(dp(GUTTER), dp(GUTTER), dp(GUTTER), dp(GUTTER))
            layoutParams = rowParams()

            addView(TextView(this@StatsActivity).apply {
                text = "RANK ${rank.number} OF ${Ranks.count}"
                typeface = Theme.uiBold(this@StatsActivity)
                setTextColor(Theme.textFaint)
                textSize = 11f
                letterSpacing = 0.20f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            addView(GlyphView(this@StatsActivity, rank.shape, tint).apply {
                layoutParams = LinearLayout.LayoutParams(dp(84f), dp(84f))
                    .apply { topMargin = dp(10f); bottomMargin = dp(8f) }
            })
            addView(TextView(this@StatsActivity).apply {
                text = rank.title.uppercase()
                typeface = Theme.display(this@StatsActivity)
                setTextColor(tint)
                textSize = 19f
                letterSpacing = 0.04f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            addView(LadderView(this@StatsActivity, rank.number).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(14f)
                ).apply { topMargin = dp(12f) }
            })
            addView(TextView(this@StatsActivity).apply {
                val next = Ranks.next(rank)
                text = if (next == null) "TOP RANK"
                    else "Average ${format(rank.ceiling + 1)} to reach ${next.title}"
                typeface = Theme.ui(this@StatsActivity)
                setTextColor(Theme.textFaint)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(10f), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        }
    }

    /**
     * Precision, left aligned all the way down: the bar, then the perfect rate
     * as the headline, then one plain sentence per group with the split it means
     * out on the right. The counts used to sit there, which made every line read
     * as a tally when what it is is a rate.
     */
    private fun precisionPanel(): View {
        val cuts = stats.totalCuts
        val groups = groupedBands()
        fun pct(v: Int): Int = if (cuts == 0) 0 else (v * 100f / cuts).roundToInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18f).toFloat()
                setColor(Theme.card)
                setStroke(dp(1f), Theme.hairline)
            }
            setPadding(dp(GUTTER), dp(GAP), dp(GUTTER), dp(GAP))
            layoutParams = rowParams()

            addView(TextView(this@StatsActivity).apply {
                text = "PRECISION  \u00b7  ALL TIME"
                typeface = Theme.uiBold(this@StatsActivity)
                setTextColor(Theme.accent)
                textSize = 12f
                letterSpacing = 0.16f
            })
            addView(StackView(this@StatsActivity, groups).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(16f)
                ).apply { topMargin = dp(12f); bottomMargin = dp(12f) }
            })
            addView(TextView(this@StatsActivity).apply {
                text = "${pct(groups[0])}%"
                typeface = Theme.display(this@StatsActivity)
                setTextColor(Theme.gold)
                textSize = 30f
            })
            addView(TextView(this@StatsActivity).apply {
                text = "of every cut you have made was perfect"
                typeface = Theme.ui(this@StatsActivity)
                setTextColor(Theme.textFaint)
                textSize = 12f
                setPadding(0, dp(2f), 0, dp(12f))
            })
            for (i in groups.indices) {
                addView(breakdownRow(pct(groups[i]), GROUP_LABELS[i], GROUP_RANGES[i], groupColour(i)))
            }
        }
    }

    /** "33% of your cuts are perfect." with the split it means on the right. */
    private fun breakdownRow(percent: Int, label: String, range: String, colour: Int) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5f), 0, dp(5f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(View(this@StatsActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colour)
                }
                layoutParams = LinearLayout.LayoutParams(dp(9f), dp(9f))
                    .apply { rightMargin = dp(10f) }
            })
            addView(TextView(this@StatsActivity).apply {
                text = "$percent% of your cuts are $label."
                typeface = Theme.ui(this@StatsActivity)
                setTextColor(Theme.textSecondary)
                textSize = 13.5f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@StatsActivity).apply {
                text = range
                typeface = Theme.uiBold(this@StatsActivity)
                setTextColor(colour)
                textSize = 12f
                gravity = Gravity.END
            })
        }

    /**
     * The six recorded bands folded into the four the player thinks in. The bar
     * and the lines below it read off the same four numbers, so a colour in the
     * bar always has a sentence to go with it.
     */
    private fun groupedBands(): IntArray {
        val b = stats.bands
        return intArrayOf(b[0], b[1] + b[2], b[3], b[4] + b[5])
    }

    /** "33% are good cuts - 45/55 or better - 412 of them". */
    private fun breakdownRow(label: String, range: String, percent: Int, count: Int, colour: Int) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(View(this@StatsActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colour)
                }
                layoutParams = LinearLayout.LayoutParams(dp(9f), dp(9f))
                    .apply { rightMargin = dp(10f) }
            })
            addView(TextView(this@StatsActivity).apply {
                text = "$percent%"
                typeface = Theme.display(this@StatsActivity)
                setTextColor(colour)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(dp(46f), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(this@StatsActivity).apply {
                text = "$label  ·  $range"
                typeface = Theme.ui(this@StatsActivity)
                setTextColor(Theme.textSecondary)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@StatsActivity).apply {
                text = format(count)
                typeface = Theme.ui(this@StatsActivity)
                setTextColor(Theme.textFaint)
                textSize = 13f
                gravity = Gravity.END
            })
        }

    /**
     * What the screen shows before it has anything worth showing. Ten runs is the
     * point where a rate stops being one lucky cut and starts being a habit.
     */
    private fun locked(runs: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22f).toFloat()
            setColor(Theme.card)
            setStroke(dp(1f), Theme.hairline)
        }
        setPadding(dp(GUTTER), dp(30f), dp(GUTTER), dp(30f))
        layoutParams = rowParams()

        addView(TextView(this@StatsActivity).apply {
            text = "STATS LOCKED"
            typeface = Theme.uiBold(this@StatsActivity)
            setTextColor(Theme.textFaint)
            textSize = 11f
            letterSpacing = 0.20f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        addView(TextView(this@StatsActivity).apply {
            text = "$runs / $MIN_RUNS"
            typeface = Theme.display(this@StatsActivity)
            setTextColor(Theme.accent)
            textSize = 46f
            gravity = Gravity.CENTER
            setPadding(0, dp(10f), 0, dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        addView(TextView(this@StatsActivity).apply {
            val left = MIN_RUNS - runs
            text = if (left == 1) "Play 1 more game and your stats appear."
                else "Play $MIN_RUNS games first and your stats appear. $left to go."
            typeface = Theme.ui(this@StatsActivity)
            setTextColor(Theme.textSecondary)
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        addView(ProgressPips(this@StatsActivity, runs).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(16f)
            ).apply { topMargin = dp(18f) }
        })
    }

    /**
     * One shape's card: its outline drawn large in its medal colour, its name,
     * the split of how it has gone, and the rate it is ranked on.
     */
    private fun shapeCard(place: Int, r: LifetimeStats.ShapeRecord, best: Boolean): View {
        val accent = if (best) placeColour(place) else Theme.danger
        val rate = if (best) r.perfectRate else r.badRate
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16f).toFloat()
                setColor(Theme.withAlpha(accent, 0.08f))
                setStroke(dp(1f), Theme.withAlpha(accent, 0.28f))
            }
            setPadding(dp(14f), dp(12f), dp(16f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }

            addView(TextView(this@StatsActivity).apply {
                text = place.toString()
                typeface = Theme.display(this@StatsActivity)
                setTextColor(accent)
                textSize = 15f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(20f), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(GlyphView(this@StatsActivity, r.kind, accent).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
                    .apply { leftMargin = dp(8f); rightMargin = dp(14f) }
            })
            addView(LinearLayout(this@StatsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@StatsActivity).apply {
                    text = r.kind.displayName.uppercase()
                    typeface = Theme.uiBold(this@StatsActivity)
                    setTextColor(Theme.textPrimary)
                    textSize = 15f
                    letterSpacing = 0.04f
                })
                addView(TextView(this@StatsActivity).apply {
                    val hits = if (best) r.perfect else r.bad
                    val word = if (best) "perfect" else "bad"
                    text = "$hits of your ${r.total} cuts were $word."
                    typeface = Theme.ui(this@StatsActivity)
                    setTextColor(Theme.textFaint)
                    textSize = 12.5f
                    setPadding(0, dp(3f), 0, 0)
                })
            })
            addView(TextView(this@StatsActivity).apply {
                text = "${(rate * 100).roundToInt()}%"
                typeface = Theme.display(this@StatsActivity)
                setTextColor(accent)
                textSize = 20f
                gravity = Gravity.END
            })
        }
    }

    // -----------------------------------------------------------------
    // Small parts
    // -----------------------------------------------------------------

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(GAP) }

    private fun scroll(content: View) = ScrollView(this).apply {
        setBackgroundColor(Theme.bgBottom)
        isFillViewport = true
        addView(content)
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(10f), 1)
    }

    private fun sectionLabel(text: String, colour: Int = Theme.textFaint) = TextView(this).apply {
        this.text = text
        typeface = Theme.uiBold(this@StatsActivity)
        setTextColor(colour)
        textSize = if (colour == Theme.textFaint) 11f else 13f
        letterSpacing = 0.18f
        setPadding(dp(4f), dp(4f), 0, if (colour == Theme.textFaint) dp(10f) else dp(3f))
    }

    /** One line under a section heading saying what it is sorted on. */
    private fun sectionNote(text: String) = TextView(this).apply {
        this.text = text
        typeface = Theme.ui(this@StatsActivity)
        setTextColor(Theme.textFaint)
        textSize = 12f
        setPadding(dp(4f), 0, 0, dp(10f))
    }

    /** A big number under a caption that says exactly what it is. */
    private fun bigTile(label: String, value: String, accent: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18f).toFloat()
            setColor(Theme.withAlpha(accent, 0.10f))
            setStroke(dp(1f), Theme.withAlpha(accent, 0.30f))
        }
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(this@StatsActivity).apply {
            text = label
            typeface = Theme.uiBold(this@StatsActivity)
            setTextColor(Theme.textFaint)
            textSize = 10f
            letterSpacing = 0.18f
        })
        addView(TextView(this@StatsActivity).apply {
            text = value
            typeface = Theme.display(this@StatsActivity)
            setTextColor(accent)
            textSize = 26f
            setPadding(0, dp(6f), 0, 0)
        })
    }

    private fun smallTile(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14f).toFloat()
            setColor(Theme.card)
            setStroke(dp(1f), Theme.hairline)
        }
        setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(this@StatsActivity).apply {
            text = value
            typeface = Theme.display(this@StatsActivity)
            setTextColor(Theme.textPrimary)
            textSize = 20f
            gravity = Gravity.CENTER
        })
        addView(TextView(this@StatsActivity).apply {
            text = label
            typeface = Theme.uiBold(this@StatsActivity)
            setTextColor(Theme.textFaint)
            textSize = 9.5f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setLineSpacing(dp(1f).toFloat(), 1f)
            setPadding(0, dp(6f), 0, 0)
        })
    }

    private fun doneButton() = TextView(this).apply {
        text = "DONE"
        typeface = Theme.uiBold(this@StatsActivity)
        textSize = 17f
        gravity = Gravity.CENTER
        letterSpacing = 0.08f
        setTextColor(Color.rgb(6, 20, 26))
        setPadding(dp(18f), dp(15f), dp(18f), dp(15f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28f).toFloat()
            setColor(Theme.accent)
        }
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6f) }
        setOnClickListener { Sounds.click(this@StatsActivity); finish() }
    }

    private fun placeColour(place: Int): Int = when (place) {
        1 -> Theme.gold
        2 -> Color.rgb(205, 214, 226)
        3 -> Color.rgb(205, 145, 96)
        else -> Theme.accent
    }

    /** Perfect, good, mid range, bad - in the bar and in the lines below it. */
    private fun groupColour(index: Int): Int = when (index) {
        0 -> Theme.gold
        1 -> Theme.good
        2 -> Color.rgb(255, 176, 84)
        else -> Theme.danger
    }

    /** The ladder's colour at a rung: gold at the bottom, indigo at the top. */
    private fun rungColour(number: Int): Int {
        val t = (number - 1) / (Ranks.count - 1f)
        return Color.HSVToColor(floatArrayOf(44f + (252f - 44f) * t, 0.58f + 0.30f * t, 1f - 0.10f * t))
    }

    private fun format(value: Int): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val out = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
            out.append(c)
        }
        return out.toString()
    }

    // -----------------------------------------------------------------
    // Drawn parts
    // -----------------------------------------------------------------

    /** A shape's outline, from the same equal-area geometry the badges use. */
    private class GlyphView(context: Context, private val kind: ShapeKind, private val tint: Int) :
        View(context) {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val verts = kind.glyphVertices
            if (verts.isEmpty() || width == 0 || height == 0) return

            // Fit from the outline's own reach, never from a guessed fraction of
            // the view. A glyph can extend past the unit circle - the capsule
            // does - and a fixed 0.94 clipped it. Half the stroke is taken off
            // first because a stroke straddles the path it follows.
            var reachX = 0f
            var reachY = 0f
            for (p in verts) {
                if (kotlin.math.abs(p.x) > reachX) reachX = kotlin.math.abs(p.x)
                if (kotlin.math.abs(p.y) > reachY) reachY = kotlin.math.abs(p.y)
            }
            if (reachX <= 0f || reachY <= 0f) return

            val box = min(width, height) / 2f
            val weight = kotlin.math.max(2f, box * 0.09f)
            val room = box - weight / 2f - 1f
            if (room <= 0f) return
            val r = min(room / reachX, room / reachY)

            val cx = width / 2f
            val cy = height / 2f
            path.rewind()
            verts.forEachIndexed { i, p ->
                val x = cx + p.x * r
                val y = cy + p.y * r
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            fill.color = Theme.withAlpha(tint, 0.20f)
            canvas.drawPath(path, fill)
            stroke.strokeWidth = weight
            stroke.color = Theme.withAlpha(tint, 0.95f)
            canvas.drawPath(path, stroke)
        }
    }

    /** Ten pips, filled as the required runs are played. */
    private class ProgressPips(context: Context, private val done: Int) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val gap = 16f * resources.displayMetrics.density
            setMeasuredDimension((gap * MIN_RUNS).toInt(), resolveSize(0, heightSpec))
        }

        override fun onDraw(canvas: Canvas) {
            val gap = 16f * resources.displayMetrics.density
            val pip = 4.5f * resources.displayMetrics.density
            var x = gap / 2f
            val cy = height / 2f
            for (i in 1..MIN_RUNS) {
                paint.color = if (i <= done) Theme.accent else Theme.withAlpha(Color.WHITE, 0.14f)
                canvas.drawCircle(x, cy, pip, paint)
                x += gap
            }
        }
    }

    /** Thirteen pips, warm to cool, the ones earned filled. */
    private class LadderView(context: Context, private val rank: Int) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val gap = (9f * resources.displayMetrics.density)
            setMeasuredDimension(
                (gap * Ranks.count).toInt(),
                resolveSize(0, heightSpec)
            )
        }

        override fun onDraw(canvas: Canvas) {
            val gap = 9f * resources.displayMetrics.density
            val pip = 3.2f * resources.displayMetrics.density
            var x = gap / 2f
            val cy = height / 2f
            for (i in 1..Ranks.count) {
                val earned = i <= rank
                val t = (i - 1) / (Ranks.count - 1f)
                val colour = Color.HSVToColor(
                    floatArrayOf(44f + (252f - 44f) * t, 0.58f + 0.30f * t, 1f - 0.10f * t)
                )
                paint.color = Theme.withAlpha(
                    if (earned) colour else Color.WHITE, if (earned) 0.95f else 0.16f
                )
                canvas.drawCircle(x, cy, if (i == rank) pip * 1.35f else pip, paint)
                x += gap
            }
        }
    }

    /**
     * The four groups as one bar, each segment as wide as its share. Rounded at the
     * two ends only, so it reads as a single bar that has been divided rather than
     * six bars pushed together.
     */
    private class StackView(context: Context, private val bands: IntArray) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rect = RectF()

        private fun colourOf(index: Int): Int = when (index) {
            0 -> Theme.gold
            1 -> Theme.good
            2 -> Color.rgb(255, 176, 84)
            else -> Theme.danger
        }

        override fun onDraw(canvas: Canvas) {
            val total = bands.sum()
            val r = height / 2f
            if (total == 0) {
                paint.color = Theme.withAlpha(Color.WHITE, 0.07f)
                canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
                return
            }
            // Clipped to a capsule, then filled straight across: the ends are round
            // and every division inside is a clean edge.
            val clip = Path().apply {
                addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clip)
            var x = 0f
            for (i in bands.indices) {
                if (bands[i] == 0) continue
                val w = width * bands[i].toFloat() / total
                paint.color = colourOf(i)
                rect.set(x, 0f, x + w, height.toFloat())
                canvas.drawRect(rect, paint)
                x += w
            }
            canvas.restore()
        }
    }

    private companion object {
        const val GAP = 14f
        const val GUTTER = 20f

        /** Below this many finished runs the screen has nothing honest to say. */
        const val MIN_RUNS = 10

        val GROUP_LABELS = arrayOf("perfect", "good", "mid range", "bad")
        val GROUP_RANGES = arrayOf("= 50/50", "45/55 \u2013 60/40", "70/30", "80/20 or worse")
    }
}
