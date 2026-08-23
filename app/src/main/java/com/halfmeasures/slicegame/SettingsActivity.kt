package com.halfmeasures.slicegame

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

/**
 * Lets the player tune every knob behind the spawn algorithm - shape size, launch
 * speed, gravity, spin, starting concurrency, how much score it takes to allow one
 * more shape on screen, the concurrency ceiling, and the gap between spawns - so
 * they can dial in the feel themselves. Built with plain widgets (no XML layout)
 * to match the rest of the app, which is entirely programmatic.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: GameSettings

    private val sliderSteps = 1000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = GameSettings.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 20, 33))
            setPadding(48, 72, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Settings"
            textSize = 30f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 48)
        }
        root.addView(title)

        val section1 = sectionLabel("Shapes")
        root.addView(section1)

        addSlider(
            root, "Size",
            GameSettings.MIN_SIZE_SCALE, GameSettings.MAX_SIZE_SCALE, settings.sizeScale,
            format = { "%.1fx".format(it) },
            onChange = { settings.sizeScale = it }
        )

        addSlider(
            root, "Launch speed (how fast they fly off the bottom)",
            GameSettings.MIN_SPEED_SCALE, GameSettings.MAX_SPEED_SCALE, settings.speedScale,
            format = { "%.1fx".format(it) },
            onChange = { settings.speedScale = it }
        )

        addSlider(
            root, "Gravity (higher = falls faster AND peaks lower)",
            GameSettings.MIN_GRAVITY_SCALE, GameSettings.MAX_GRAVITY_SCALE, settings.gravityScale,
            format = { "%.1fx".format(it) },
            onChange = { settings.gravityScale = it }
        )

        addSlider(
            root, "Spin speed",
            GameSettings.MIN_ROTATION_SCALE, GameSettings.MAX_ROTATION_SCALE, settings.rotationScale,
            format = { "%.1fx".format(it) },
            onChange = { settings.rotationScale = it }
        )

        root.addView(sectionLabel("Spawning"))

        addSlider(
            root, "Shapes on screen at the start (score 0)",
            GameSettings.MIN_CONCURRENCY.toFloat(), GameSettings.MAX_CONCURRENCY_LIMIT.toFloat(),
            settings.startConcurrency.toFloat(),
            format = { "${it.roundToInt()}" },
            onChange = { settings.startConcurrency = it.roundToInt() }
        )

        addSlider(
            root, "Score needed to allow one more shape at once",
            GameSettings.MIN_CONCURRENCY_STEP_SCORE.toFloat(), GameSettings.MAX_CONCURRENCY_STEP_SCORE.toFloat(),
            settings.concurrencyStepScore.toFloat(),
            format = { "${it.roundToInt()} pts" },
            onChange = { settings.concurrencyStepScore = it.roundToInt() }
        )

        addSlider(
            root, "Most shapes ever allowed on screen at once",
            GameSettings.MIN_CONCURRENCY.toFloat(), GameSettings.MAX_CONCURRENCY_LIMIT.toFloat(),
            settings.maxConcurrency.toFloat(),
            format = { "${it.roundToInt()}" },
            onChange = { settings.maxConcurrency = it.roundToInt() }
        )

        addSlider(
            root, "Gap between spawns (while under the current cap)",
            GameSettings.MIN_SPAWN_GAP_MS.toFloat(), GameSettings.MAX_SPAWN_GAP_MS.toFloat(),
            settings.spawnGapMs.toFloat(),
            format = { "%.1fs".format(it / 1000f) },
            onChange = { settings.spawnGapMs = it.toLong() }
        )

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 48, 0, 0)
        }

        val resetButton = Button(this).apply {
            text = "Reset to defaults"
            setOnClickListener {
                settings = GameSettings()
                settings.saveTo(this@SettingsActivity)
                recreate()
            }
        }
        buttonRow.addView(resetButton)

        val doneButton = Button(this).apply {
            text = "Done"
            setOnClickListener { finish() }
        }
        buttonRow.addView(doneButton)

        root.addView(buttonRow)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    override fun onPause() {
        super.onPause()
        settings.saveTo(this)
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.argb(180, 255, 255, 255))
        textSize = 13f
        setPadding(0, 40, 0, 0)
    }

    private fun addSlider(
        parent: LinearLayout,
        label: String,
        minValue: Float,
        maxValue: Float,
        initial: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit
    ) {
        val labelView = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 32, 0, 8)
        }
        parent.addView(labelView)

        val valueView = TextView(this).apply {
            text = format(initial)
            setTextColor(Color.argb(210, 255, 255, 255))
            textSize = 14f
        }

        val seekBar = SeekBar(this).apply {
            max = sliderSteps
            progress = (((initial - minValue) / (maxValue - minValue)) * sliderSteps).roundToInt().coerceIn(0, sliderSteps)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = minValue + (maxValue - minValue) * (progress.toFloat() / sliderSteps)
                    valueView.text = format(value)
                    if (fromUser) onChange(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        parent.addView(seekBar)

        valueView.gravity = Gravity.END
        parent.addView(valueView)
    }
}
