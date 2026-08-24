package com.halfmeasures.slicegame

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlin.math.roundToInt

/**
 * Every mechanic in the game, exposed as a slider or a switch so the feel can be
 * tuned in-app. Built programmatically to match the Canvas-drawn game surface -
 * same palette, same typefaces, no XML layouts anywhere in the project.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: GameSettings
    private val sliderSteps = 1000

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = GameSettings.load(this)
        setContentView(buildUi())
    }

    override fun onPause() {
        super.onPause()
        settings.saveTo(this)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bgBottom)
            setPadding(dp(20f), dp(28f), dp(20f), dp(28f))
        }

        root.addView(header())

        card("SHAPES").let { c ->
            root.addView(c.wrapper)
            slider(
                c.body, "Size", "How big the shapes are",
                GameSettings.MIN_SIZE_SCALE, GameSettings.MAX_SIZE_SCALE, settings.sizeScale,
                { "%.1fx".format(it) }, { settings.sizeScale = it }
            )
            slider(
                c.body, "Launch speed", "How fast they leave the bottom",
                GameSettings.MIN_SPEED_SCALE, GameSettings.MAX_SPEED_SCALE, settings.speedScale,
                { "%.1fx".format(it) }, { settings.speedScale = it }
            )
            slider(
                c.body, "Gravity", "Higher falls faster and peaks lower",
                GameSettings.MIN_GRAVITY_SCALE, GameSettings.MAX_GRAVITY_SCALE, settings.gravityScale,
                { "%.1fx".format(it) }, { settings.gravityScale = it }
            )
            slider(
                c.body, "Spin speed", "How fast shapes rotate in flight",
                GameSettings.MIN_ROTATION_SCALE, GameSettings.MAX_ROTATION_SCALE, settings.rotationScale,
                { "%.1fx".format(it) }, { settings.rotationScale = it }
            )
            slider(
                c.body, "Wall strength", "0% lets shapes drift off the sides, higher bounces them back",
                GameSettings.MIN_WALL_STRENGTH, GameSettings.MAX_WALL_STRENGTH, settings.wallStrength,
                { "${(it * 100).roundToInt()}%" }, { settings.wallStrength = it }
            )
            slider(
                c.body, "Shape unlock pace", "Lower brings the hard shapes out sooner",
                GameSettings.MIN_SHAPE_UNLOCK_PACE, GameSettings.MAX_SHAPE_UNLOCK_PACE, settings.shapeUnlockPace,
                { "%.1fx".format(it) }, { settings.shapeUnlockPace = it }
            )
        }

        card("SPAWNING").let { c ->
            root.addView(c.wrapper)
            slider(
                c.body, "Shapes at the start", "How many can share the screen at score 0",
                GameSettings.MIN_CONCURRENCY.toFloat(), GameSettings.MAX_CONCURRENCY_LIMIT.toFloat(),
                settings.startConcurrency.toFloat(),
                { "${it.roundToInt()}" }, { settings.startConcurrency = it.roundToInt() }
            )
            slider(
                c.body, "Score per extra shape", "Score needed to allow one more at once",
                GameSettings.MIN_CONCURRENCY_STEP_SCORE.toFloat(), GameSettings.MAX_CONCURRENCY_STEP_SCORE.toFloat(),
                settings.concurrencyStepScore.toFloat(),
                { "${it.roundToInt()} pts" }, { settings.concurrencyStepScore = it.roundToInt() }
            )
            slider(
                c.body, "Maximum at once", "Hard ceiling on shapes on screen",
                GameSettings.MIN_CONCURRENCY.toFloat(), GameSettings.MAX_CONCURRENCY_LIMIT.toFloat(),
                settings.maxConcurrency.toFloat(),
                { "${it.roundToInt()}" }, { settings.maxConcurrency = it.roundToInt() }
            )
            slider(
                c.body, "Gap between spawns", "Wait before the next shape appears",
                GameSettings.MIN_SPAWN_GAP_MS.toFloat(), GameSettings.MAX_SPAWN_GAP_MS.toFloat(),
                settings.spawnGapMs.toFloat(),
                { "%.1fs".format(it / 1000f) }, { settings.spawnGapMs = it.toLong() }
            )
        }

        card("SCORING & HEALTH").let { c ->
            root.addView(c.wrapper)
            slider(
                c.body, "Starting health", "Health you begin each run with",
                GameSettings.MIN_START_HEALTH.toFloat(), GameSettings.MAX_START_HEALTH.toFloat(),
                settings.startHealth.toFloat(),
                { "${it.roundToInt()}" }, { settings.startHealth = it.roundToInt() }
            )
            slider(
                c.body, "Perfect window", "How close to 50/50 still counts as perfect",
                GameSettings.MIN_PERFECT_THRESHOLD, GameSettings.MAX_PERFECT_THRESHOLD, settings.perfectThreshold,
                { "±%.1f%%".format(it) }, { settings.perfectThreshold = it }
            )
            slider(
                c.body, "Score penalty", "Points lost per 1% off a perfect half",
                GameSettings.MIN_SCORE_MISS_WEIGHT, GameSettings.MAX_SCORE_MISS_WEIGHT, settings.scoreMissWeight,
                { "%.1f pts".format(it) }, { settings.scoreMissWeight = it }
            )
            slider(
                c.body, "Health penalty", "Health lost per 1% off a perfect half",
                GameSettings.MIN_HEALTH_LOSS_PER_DEVIATION, GameSettings.MAX_HEALTH_LOSS_PER_DEVIATION,
                settings.healthLossPerDeviation,
                { "%.2f hp".format(it) }, { settings.healthLossPerDeviation = it }
            )
            toggle(
                c.body, "Perfect refills health", "A flawless cut restores the bar to full",
                settings.perfectRestoresHealth
            ) { settings.perfectRestoresHealth = it }
            slider(
                c.body, "Combo bonus", "Score multiplier gained per perfect in a row",
                GameSettings.MIN_COMBO_BONUS_PERCENT, GameSettings.MAX_COMBO_BONUS_PERCENT, settings.comboBonusPercent,
                { "+${it.roundToInt()}%" }, { settings.comboBonusPercent = it }
            )
            slider(
                c.body, "Combo ceiling", "Highest multiplier a streak can reach",
                GameSettings.MIN_MAX_COMBO_MULTIPLIER, GameSettings.MAX_MAX_COMBO_MULTIPLIER,
                settings.maxComboMultiplier,
                { "%.1fx".format(it) }, { settings.maxComboMultiplier = it }
            )
            toggle(
                c.body, "Missing ends the run", "Letting a shape fall off screen is game over",
                settings.missEndsRun
            ) { settings.missEndsRun = it }
        }

        card("FEEL & EFFECTS").let { c ->
            root.addView(c.wrapper)
            toggle(
                c.body, "Halving guide", "Dashed line showing the perfect cut",
                settings.guideLineEnabled
            ) { settings.guideLineEnabled = it }
            slider(
                c.body, "Guide visibility", "How strongly the guide shows",
                GameSettings.MIN_GUIDE_LINE_OPACITY, GameSettings.MAX_GUIDE_LINE_OPACITY, settings.guideLineOpacity,
                { "${(it * 100).roundToInt()}%" }, { settings.guideLineOpacity = it }
            )
            toggle(
                c.body, "Particles", "Sparks and debris on every cut",
                settings.particlesEnabled
            ) { settings.particlesEnabled = it }
            slider(
                c.body, "Particle amount", "How much debris a cut throws",
                GameSettings.MIN_PARTICLE_AMOUNT, GameSettings.MAX_PARTICLE_AMOUNT, settings.particleAmount,
                { "%.1fx".format(it) }, { settings.particleAmount = it }
            )
            slider(
                c.body, "Camera shake", "Screen kick on a good cut",
                GameSettings.MIN_CAMERA_SHAKE_STRENGTH, GameSettings.MAX_CAMERA_SHAKE_STRENGTH,
                settings.cameraShakeStrength,
                { if (it <= 0.01f) "Off" else "%.1fx".format(it) }, { settings.cameraShakeStrength = it }
            )
            toggle(
                c.body, "Vibration", "Haptic feedback on cuts and misses",
                settings.vibrationEnabled
            ) { settings.vibrationEnabled = it }
            slider(
                c.body, "Vibration strength", "How hard the phone buzzes",
                GameSettings.MIN_VIBRATION_STRENGTH, GameSettings.MAX_VIBRATION_STRENGTH, settings.vibrationStrength,
                { "${(it * 100).roundToInt()}%" }, { settings.vibrationStrength = it }
            )
            slider(
                c.body, "Blade thickness", "Width of the swipe trail",
                GameSettings.MIN_TRAIL_THICKNESS, GameSettings.MAX_TRAIL_THICKNESS, settings.trailThickness,
                { "%.1fx".format(it) }, { settings.trailThickness = it }
            )
        }

        root.addView(footer())

        return ScrollView(this).apply {
            setBackgroundColor(Theme.bgBottom)
            isFillViewport = true
            addView(root)
        }
    }

    // ---------------------------------------------------------------------
    // Building blocks
    // ---------------------------------------------------------------------

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4f), 0, dp(4f), dp(20f))

        addView(TextView(this@SettingsActivity).apply {
            text = "SETTINGS"
            typeface = Theme.display(this@SettingsActivity)
            setTextColor(Theme.textPrimary)
            textSize = 24f
        })
        addView(TextView(this@SettingsActivity).apply {
            text = "Tune the game to taste. Changes apply on your next run."
            typeface = Theme.ui(this@SettingsActivity)
            setTextColor(Theme.textFaint)
            textSize = 15f
            setPadding(0, dp(4f), 0, 0)
        })
    }

    private class Card(val wrapper: LinearLayout, val body: LinearLayout)

    private fun card(title: String): Card {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18f) }
        }

        wrapper.addView(TextView(this).apply {
            text = title
            typeface = Theme.uiBold(this@SettingsActivity)
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
            setPadding(dp(18f), dp(6f), dp(18f), dp(14f))
        }
        wrapper.addView(body)
        return Card(wrapper, body)
    }

    private fun rowLabel(title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

            addView(TextView(this@SettingsActivity).apply {
                text = title
                typeface = Theme.uiBold(this@SettingsActivity)
                setTextColor(Theme.textPrimary)
                textSize = 17f
            })
            if (subtitle.isNotEmpty()) {
                addView(TextView(this@SettingsActivity).apply {
                    text = subtitle
                    typeface = Theme.ui(this@SettingsActivity)
                    setTextColor(Theme.textFaint)
                    textSize = 13f
                })
            }
        }

    private fun slider(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        lowerBound: Float,
        upperBound: Float,
        initial: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12f), 0, 0)
        }
        row.addView(rowLabel(title, subtitle))

        val valueView = TextView(this).apply {
            text = format(initial)
            typeface = Theme.display(this@SettingsActivity)
            setTextColor(Theme.accent)
            textSize = 14f
            gravity = Gravity.END
            setPadding(dp(10f), 0, 0, 0)
        }
        row.addView(valueView)
        parent.addView(row)

        val bar = SeekBar(this).apply {
            max = sliderSteps
            progress = (((initial - lowerBound) / (upperBound - lowerBound)) * sliderSteps)
                .roundToInt().coerceIn(0, sliderSteps)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2f) }

            progressTintList = ColorStateList.valueOf(Theme.accent)
            thumbTintList = ColorStateList.valueOf(Theme.accent)
            progressBackgroundTintList = ColorStateList.valueOf(Theme.withAlpha(Color.WHITE, 0.16f))

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = lowerBound + (upperBound - lowerBound) * (progress.toFloat() / sliderSteps)
                    valueView.text = format(value)
                    if (fromUser) onChange(value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        parent.addView(bar)
    }

    private fun toggle(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14f), 0, dp(4f))
        }
        row.addView(rowLabel(title, subtitle))
        row.addView(SwitchCompat(this).apply {
            isChecked = initial
            thumbTintList = ColorStateList.valueOf(Theme.accent)
            trackTintList = ColorStateList.valueOf(Theme.withAlpha(Theme.accent, 0.4f))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        })
        parent.addView(row)
    }

    private fun footer(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4f), 0, 0)

        addView(pillButton("RESET", primary = false) {
            settings = GameSettings()
            settings.saveTo(this@SettingsActivity)
            recreate()
        }.also {
            (it.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12f)
        })

        addView(pillButton("DONE", primary = true) { finish() })
    }

    private fun pillButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            typeface = Theme.uiBold(this@SettingsActivity)
            textSize = 17f
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setTextColor(if (primary) Color.rgb(6, 20, 26) else Theme.textPrimary)
            setPadding(dp(18f), dp(15f), dp(18f), dp(15f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28f).toFloat()
                if (primary) {
                    setColor(Theme.accent)
                } else {
                    setColor(Theme.withAlpha(Color.WHITE, 0.07f))
                    setStroke(dp(1f), Theme.hairline)
                }
            }
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
}
