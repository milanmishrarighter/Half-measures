package com.halfmeasures.slicegame

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

/**
 * Four questions, four answers. No cards, no rules, no separators - just a title
 * and a paragraph, four times over, so it reads like a page instead of a form.
 */
class InstructionsActivity : AppCompatActivity() {

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bgBottom)
            setPadding(dp(24f), dp(36f), dp(24f), dp(32f))
        }

        section(
            root, "MAIN OBJECTIVE",
            "All you have to do is cut all popping shapes into half, and make the " +
                "highest score.\n\nThat's it. Simple."
        )

        section(
            root, "HOW POINTS ARE CALCULATED",
            "Every cut is judged on how close the two halves are.\n\n" +
                "A dead 50/50 is PERFECT and pays the most. A 45/55 is close behind. " +
                "A 60/40 still scores, but barely. Anything wider than that pays " +
                "almost nothing.\n\n" +
                "Streaks multiply whatever the cut was worth."
        )

        section(
            root, "HOW GAMES END",
            "You start with 100 health.\n\n" +
                "Bad cuts cost health, and the worse the cut the steeper the cost - " +
                "a 60/40 stings, an 80/20 hurts badly. Letting a shape fall off the " +
                "screen ends the run on the spot.\n\n" +
                "Perfect cuts heal you back: 10 health for the first, 20 for two in a " +
                "row, and ten in a row heals you completely."
        )

        section(
            root, "HOW STREAKS WORK",
            "There are two streaks and you can only have one at a time.\n\n" +
                "PERFECT cuts in a row build a perfect streak - the biggest multiplier, " +
                "and the thing that heals you. Cuts inside 45/55 build a good streak " +
                "instead, worth less but easier to hold.\n\n" +
                "They cancel each other out. A perfect ends a good streak, a good cut " +
                "ends a perfect one, and anything wider ends both."
        )

        root.addView(TextView(this).apply {
            text = "GOT IT"
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
            ).apply { topMargin = dp(20f) }
            setOnClickListener { finish() }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Theme.bgBottom)
            isFillViewport = true
            addView(root)
        })
    }

    /** A title and the paragraph under it. That is the whole layout. */
    private fun section(parent: LinearLayout, title: String, body: String) {
        parent.addView(TextView(this).apply {
            text = title
            typeface = Theme.display(this@InstructionsActivity)
            setTextColor(Theme.textPrimary)
            textSize = 19f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (parent.childCount == 0) 0 else dp(34f) }
        })
        parent.addView(TextView(this).apply {
            text = body
            typeface = Theme.ui(this@InstructionsActivity)
            setTextColor(Theme.textSecondary)
            textSize = 16f
            setLineSpacing(dp(3f).toFloat(), 1f)
            setPadding(0, dp(10f), 0, 0)
        })
    }
}
