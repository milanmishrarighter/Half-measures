package com.halfmeasures.slicegame

import android.graphics.Color
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
 * The rules and the tactics, in short plain sentences. Reached from the title
 * screen and again from the game-over card, where the player has just been given
 * a reason to want it.
 */
class InstructionsActivity : AppCompatActivity() {

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bgBottom)
            setPadding(dp(20f), dp(28f), dp(20f), dp(28f))
        }

        root.addView(heading("HOW TO PLAY"))
        root.addView(
            subheading("Shapes fly up. Cut each one exactly in half.")
        )

        card(root, "THE BASICS", listOf(
            "Swipe across a shape to slice it." to
                "Your finger is the blade.",
            "Aim for a dead 50/50 split." to
                "The closer to half, the better everything goes.",
            "Never let a shape get away." to
                "One shape off the screen ends the run."
        ))

        card(root, "HOW A CUT IS SCORED", listOf(
            "PERFECT is a dead 50/50." to
                "Worth the most points and heals you.",
            "45/55 is a great cut." to
                "Keeps your good streak alive.",
            "60/40 is just okay." to
                "It scores, but it breaks your streak.",
            "70/30 and worse hurts." to
                "The wider the miss, the more health it costs."
        ))

        card(root, "HEALTH", listOf(
            "You start with 100 health." to
                "Hit zero and the run is over.",
            "Bad cuts cost health." to
                "A 60/40 costs a little. A 80/20 costs a lot more.",
            "Perfect cuts heal you." to
                "First perfect heals 10. Two in a row heals 20. Ten in a row heals you fully.",
            "Miss a perfect and the healing resets." to
                "Your next perfect heals 10 again."
        ))

        card(root, "STREAKS", listOf(
            "Great cuts in a row build a good streak." to
                "Each one adds to your score multiplier.",
            "Perfect cuts build their own streak." to
                "It pays more, and it is tracked separately.",
            "The two streaks do not mix." to
                "A perfect ends a good streak and starts a perfect one.",
            "Sloppy cuts in a row cost you." to
                "Keep missing and you start losing points."
        ))

        card(root, "AS YOU GET BETTER", listOf(
            "The game levels up with your score." to
                "The colours of everything shift each level.",
            "More shapes share the screen." to
                "And they spin faster.",
            "Harder shapes arrive." to
                "Stars and crosses are much harder to halve by eye."
        ))

        card(root, "HOW TO GET BETTER", listOf(
            "Watch the shape, not your finger." to
                "Pick your line before you swipe.",
            "Cut at the top of the arc." to
                "The shape is slowest there and easiest to read.",
            "Cut through the middle, not the edge." to
                "A cut near an edge is always a bad split.",
            "For a spinning shape, wait." to
                "Let it turn to a flat angle you can read.",
            "Turn on the dotted guide while learning." to
                "Settings shows a faint line through the perfect cut.",
            "Slow the game down." to
                "Settings can shrink the speed and spin until it clicks."
        ))

        root.addView(pillButton("GOT IT") { finish() })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Theme.bgBottom)
            isFillViewport = true
            addView(root)
        })
    }

    private fun heading(text: String): View = TextView(this).apply {
        this.text = text
        typeface = Theme.display(this@InstructionsActivity)
        setTextColor(Theme.textPrimary)
        textSize = 24f
    }

    private fun subheading(text: String): View = TextView(this).apply {
        this.text = text
        typeface = Theme.ui(this@InstructionsActivity)
        setTextColor(Theme.textFaint)
        textSize = 15f
        setPadding(0, dp(4f), 0, dp(20f))
    }

    /** A titled card of short rule/detail pairs. */
    private fun card(parent: LinearLayout, title: String, rules: List<Pair<String, String>>) {
        parent.addView(TextView(this).apply {
            text = title
            typeface = Theme.uiBold(this@InstructionsActivity)
            setTextColor(Theme.accent)
            textSize = 13f
            letterSpacing = 0.16f
            setPadding(dp(6f), 0, 0, dp(8f))
        })

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18f).toFloat()
                setColor(Theme.card)
                setStroke(dp(1f), Theme.hairline)
            }
            setPadding(dp(18f), dp(14f), dp(18f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18f) }
        }

        rules.forEachIndexed { index, (rule, detail) ->
            if (index > 0) {
                // A hairline between rules so each one reads as its own point.
                body.addView(View(this).apply {
                    setBackgroundColor(Theme.hairline)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)
                    ).apply {
                        topMargin = dp(12f)
                        bottomMargin = dp(12f)
                    }
                })
            }
            body.addView(TextView(this).apply {
                text = rule
                typeface = Theme.uiBold(this@InstructionsActivity)
                setTextColor(Theme.textPrimary)
                textSize = 17f
            })
            body.addView(TextView(this).apply {
                text = detail
                typeface = Theme.ui(this@InstructionsActivity)
                setTextColor(Theme.textFaint)
                textSize = 14f
                setPadding(0, dp(2f), 0, 0)
            })
        }
        parent.addView(body)
    }

    private fun pillButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            typeface = Theme.uiBold(this@InstructionsActivity)
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
            ).apply { topMargin = dp(4f) }
            setOnClickListener { onClick() }
        }
}
