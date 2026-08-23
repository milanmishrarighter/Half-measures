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
 * Lets the player tune shape size, flight speed, how often shapes spawn, and how
 * quickly spawning ramps up with score. Built with plain widgets (no XML layout)
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

        addSlider(
            root, "Shape size",
            GameSettings.MIN_SIZE_SCALE, GameSettings.MAX_SIZE_SCALE, settings.sizeScale,
            format = { "%.1fx".format(it) },
            onChange = { settings.sizeScale = it }
        )

        addSlider(
            root, "Shape speed (how fast they fly)",
            GameSettings.MIN_SPEED_SCALE, GameSettings.MAX_SPEED_SCALE, settings.speedScale,
            format = { "%.1fx".format(it) },
            onChange = { settings.speedScale = it }
        )

        addSlider(
            root, "Spawn frequency (time between shapes at the start)",
            GameSettings.MIN_SPAWN_INTERVAL_START_MS.toFloat(), GameSettings.MAX_SPAWN_INTERVAL_START_MS.toFloat(),
            settings.spawnIntervalStartMs.toFloat(),
            format = { "%.1fs".format(it / 1000f) },
            onChange = { settings.spawnIntervalStartMs = it.toLong() }
        )

        addSlider(
            root, "Difficulty ramp (score needed to reach max spawn rate)",
            GameSettings.MIN_DIFFICULTY_RAMP_SCORE.toFloat(), GameSettings.MAX_DIFFICULTY_RAMP_SCORE.toFloat(),
            settings.difficultyRampScore.toFloat(),
            format = { "${it.roundToInt()} pts" },
            onChange = { settings.difficultyRampScore = it.roundToInt() }
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
