package com.halfmeasures.slicegame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

/**
 * Everything the game has watched the player do, in one screen.
 *
 * Three bands, in the order a player asks the questions: what have I done at my
 * best, how do my cuts land in general, and which shapes am I good and bad at.
 * The last is the point of the screen - the rest is on the score card already.
 *
 * Laid out programmatically like every other screen here, on the same palette and
 * typefaces as the Canvas the game is drawn on, with one spacing unit used
 * throughout so nothing has to be eyeballed.
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
            setPadding(dp(GUTTER), dp(GUTTER + 12f), dp(GUTTER), dp(GUTTER))
        }

        root.addView(TextView(this).apply {
            text = "STATS"
            typeface = Theme.display(this@StatsActivity)
            setTextColor(Theme.textPrimary)
            textSize = 24f
            setPadding(0, 0, 0, dp(GAP))
        })

        if (runs == 0) {
            root.addView(note("Play a run and this fills up."))
            root.addView(doneButton())
            return scroll(root)
        }

        // ---- the headline three, as the pills the rest of the game uses ----
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("BEST", format(best), Theme.accent, 1f))
            addView(spacer())
            addView(pill("AVG", format(average), Theme.violet, 1f))
        })
        root.addView(gap())
        root.addView(rankPill(rank))
        root.addView(gap())

        // ---- records ----
        card("YOUR RECORDS").also { c ->
            statRow(c, "Games played", runs.toString(), Theme.textPrimary)
            statRow(c, "Cuts survived", scores.getInt("best_cuts", 0).toString(), Theme.accent)
            statRow(c, "Perfect cuts", scores.getInt("best_perfect_cuts", 0).toString(), Theme.gold)
            statRow(c, "Perfect streak", "${scores.getInt("best_perfect_streak", 0)}x", Theme.gold)
            statRow(c, "Good streak", "${scores.getInt("best_good_streak", 0)}x", Theme.good)
            statRow(c, "Cuts made", stats.totalCuts.toString(), Theme.textSecondary)
            root.addView(c)
        }

        // ---- where the cuts land, over everything ever played ----
        card("HOW YOUR CUTS LAND").also { c ->
            val peak = stats.bands.maxOrNull() ?: 0
            BAND_LABELS.forEachIndexed { i, label ->
                bandRow(c, label, stats.bands[i], peak, bandColour(i))
            }
            root.addView(c)
        }

        // ---- the shapes, best and worst ----
        val bestShapes = stats.best()
        val worstShapes = stats.worst()
        if (bestShapes.isEmpty()) {
            card("YOUR SHAPES").also { c ->
                c.addView(note(
                    "Cut a shape ${LifetimeStats.MIN_SAMPLE} times and it starts being ranked."
                ))
                root.addView(c)
            }
        } else {
            card("SHARPEST ON").also { c ->
                bestShapes.forEachIndexed { i, r -> shapeRow(c, i + 1, r, best = true) }
                root.addView(c)
            }
            card("WORST ON").also { c ->
                worstShapes.forEachIndexed { i, r -> shapeRow(c, i + 1, r, best = false) }
                root.addView(c)
            }
        }

        root.addView(doneButton())
        return scroll(root)
    }

    // -----------------------------------------------------------------
    // Pieces
    // -----------------------------------------------------------------

    private fun scroll(content: View) = ScrollView(this).apply {
        setBackgroundColor(Theme.bgBottom)
        isFillViewport = true
        addView(content)
    }

    private fun gap() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(GAP))
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(GAP), 1)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        typeface = Theme.ui(this@StatsActivity)
        setTextColor(Theme.textFaint)
        textSize = 14f
        setPadding(0, dp(4f), 0, dp(4f))
    }

    /** A capsule with a quiet caption and a bright figure, like the score card's. */
    private fun pill(label: String, value: String, accent: Int, weight: Float) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(PILL_HEIGHT / 2f).toFloat()
                setColor(Theme.withAlpha(accent, 0.14f))
            }
            setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            addView(TextView(this@StatsActivity).apply {
                this.text = "$label:"
                typeface = Theme.uiBold(this@StatsActivity)
                setTextColor(Theme.textFaint)
                textSize = 15f
                letterSpacing = 0.06f
            })
            addView(TextView(this@StatsActivity).apply {
                this.text = " $value"
                typeface = Theme.uiBold(this@StatsActivity)
                setTextColor(accent)
                textSize = 15f
            })
        }

    /** The rank, in its own colour, with its shape beside it. */
    private fun rankPill(rank: Rank) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(PILL_HEIGHT / 2f).toFloat()
            setColor(Theme.withAlpha(Theme.gold, 0.16f))
        }
        setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(TextView(this@StatsActivity).apply {
            text = "RANK:"
            typeface = Theme.uiBold(this@StatsActivity)
            setTextColor(Theme.textFaint)
            textSize = 15f
            letterSpacing = 0.06f
        })
        addView(GlyphView(this@StatsActivity, rank.shape, Theme.gold).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f))
                .apply { leftMargin = dp(10f); rightMargin = dp(10f) }
        })
        addView(TextView(this@StatsActivity).apply {
            text = rank.title.uppercase()
            typeface = Theme.uiBold(this@StatsActivity)
            setTextColor(Theme.gold)
            textSize = 15f
            letterSpacing = 0.06f
        })
    }

    private fun card(title: String): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18f).toFloat()
                setColor(Theme.card)
                setStroke(dp(1f), Theme.hairline)
            }
            setPadding(dp(GUTTER), dp(GAP), dp(GUTTER), dp(GAP))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(GAP) }
        }
        body.addView(TextView(this).apply {
            text = title
            typeface = Theme.uiBold(this@StatsActivity)
            setTextColor(Theme.accent)
            textSize = 12f
            letterSpacing = 0.16f
            setPadding(0, 0, 0, dp(10f))
        })
        return body
    }

    /** A label on the left, a figure hard right. */
    private fun statRow(parent: LinearLayout, label: String, value: String, colour: Int) {
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5f), 0, dp(5f))
            addView(TextView(this@StatsActivity).apply {
                text = label
                typeface = Theme.ui(this@StatsActivity)
                setTextColor(Theme.textSecondary)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@StatsActivity).apply {
                text = value
                typeface = Theme.uiBold(this@StatsActivity)
                setTextColor(colour)
                textSize = 16f
            })
        })
    }

    /** A band of cuts: its name, a bar as long as its share, and the count. */
    private fun bandRow(parent: LinearLayout, label: String, count: Int, peak: Int, colour: Int) {
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
            addView(TextView(this@StatsActivity).apply {
                text = label
                typeface = Theme.uiBold(this@StatsActivity)
                setTextColor(Theme.textSecondary)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(dp(62f), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(BarView(this@StatsActivity, if (peak <= 0) 0f else count.toFloat() / peak, colour).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(10f), 1f)
                    .apply { rightMargin = dp(12f) }
            })
            addView(TextView(this@StatsActivity).apply {
                text = count.toString()
                typeface = Theme.uiBold(this@StatsActivity)
                setTextColor(if (count == 0) Theme.textFaint else colour)
                textSize = 14f
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(48f), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        })
    }

    /**
     * One shape's standing: its place, its outline, its name, and the split of
     * how its cuts have gone. The headline figure is the rate rather than the
     * count - a shape thrown twice as often would otherwise win on volume alone.
     */
    private fun shapeRow(parent: LinearLayout, place: Int, r: LifetimeStats.ShapeRecord, best: Boolean) {
        val accent = if (best) placeColour(place) else Theme.danger
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, dp(6f))

            addView(TextView(this@StatsActivity).apply {
                text = place.toString()
                typeface = Theme.uiBold(this@StatsActivity)
                setTextColor(accent)
                textSize = 15f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(22f), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(GlyphView(this@StatsActivity, r.kind, accent).apply {
                layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f))
                    .apply { leftMargin = dp(6f); rightMargin = dp(12f) }
            })
            addView(LinearLayout(this@StatsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@StatsActivity).apply {
                    text = r.kind.displayName
                    typeface = Theme.uiBold(this@StatsActivity)
                    setTextColor(Theme.textPrimary)
                    textSize = 15f
                })
                addView(TextView(this@StatsActivity).apply {
                    text = "${r.perfect} perfect  ·  ${r.good} good  ·  ${r.bad} bad"
                    typeface = Theme.ui(this@StatsActivity)
                    setTextColor(Theme.textFaint)
                    textSize = 12f
                    setPadding(0, dp(1f), 0, 0)
                })
            })
            addView(TextView(this@StatsActivity).apply {
                val rate = if (best) r.perfectRate else r.badRate
                text = "${(rate * 100).roundToInt()}%"
                typeface = Theme.uiBold(this@StatsActivity)
                setTextColor(accent)
                textSize = 17f
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(52f), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
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
        ).apply { topMargin = dp(GAP) }
        setOnClickListener { Sounds.click(this@StatsActivity); finish() }
    }

    /** Gold, silver, bronze, then the house colour. */
    private fun placeColour(place: Int): Int = when (place) {
        1 -> Theme.gold
        2 -> Color.rgb(205, 214, 226)
        3 -> Color.rgb(205, 145, 96)
        else -> Theme.accent
    }

    private fun bandColour(index: Int): Int = when (index) {
        0 -> Theme.gold
        1 -> Theme.good
        2 -> Theme.accent
        3 -> Color.rgb(255, 190, 90)
        4 -> Color.rgb(255, 140, 80)
        else -> Theme.danger
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

    /** A shape's outline, drawn from the same equal-area geometry the badges use. */
    private class GlyphView(context: Context, private val kind: ShapeKind, private val tint: Int) :
        View(context) {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val r = kotlin.math.min(width, height) / 2f * 0.92f
            val cx = width / 2f
            val cy = height / 2f
            path.rewind()
            kind.glyphVertices.forEachIndexed { i, p ->
                val x = cx + p.x * r
                val y = cy + p.y * r
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            fill.color = Theme.withAlpha(tint, 0.22f)
            canvas.drawPath(path, fill)
            stroke.strokeWidth = kotlin.math.max(1.5f, r * 0.12f)
            stroke.color = Theme.withAlpha(tint, 0.95f)
            canvas.drawPath(path, stroke)
        }
    }

    /** A rounded bar on a rounded track, filled to a fraction. */
    private class BarView(context: Context, private val fraction: Float, private val tint: Int) :
        View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = Theme.withAlpha(Color.WHITE, 0.07f)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            if (fraction <= 0f) return
            // A bar this short would vanish; a stub says "some" rather than "none".
            val w = kotlin.math.max(height.toFloat(), width * fraction.coerceIn(0f, 1f))
            paint.color = tint
            canvas.drawRoundRect(0f, 0f, w, height.toFloat(), r, r, paint)
        }
    }

    private companion object {
        /** One spacing unit for the whole screen, and one inset from the edges. */
        const val GAP = 14f
        const val GUTTER = 20f
        const val PILL_HEIGHT = 38f

        val BAND_LABELS = arrayOf("PERFECT", "45/55", "60/40", "70/30", "80/20", "90/10")
    }
}
