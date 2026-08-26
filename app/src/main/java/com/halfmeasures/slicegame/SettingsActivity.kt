package com.halfmeasures.slicegame

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlin.math.roundToInt

/**
 * What a player actually needs: sound, vibration, and a way to see what they are
 * running. Everything that shapes how the game plays lives in the dev panel, which
 * has no button of its own - ten taps on the build line opens it.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: GameSettings
    private var buildTaps = 0
    private var lastTapAt = 0L

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
            setPadding(dp(24f), dp(36f), dp(24f), dp(32f))
        }

        root.addView(TextView(this).apply {
            text = "SETTINGS"
            typeface = Theme.display(this@SettingsActivity)
            setTextColor(Theme.textPrimary)
            textSize = 24f
            setPadding(0, 0, 0, dp(24f))
        })

        toggle(
            root, "Sound", "Arcade blips, cut noises and jingles.",
            settings.soundEnabled
        ) { settings.soundEnabled = it }

        toggle(
            root, "Vibration", "A buzz on every cut, stronger the better it lands.",
            settings.vibrationEnabled
        ) { settings.vibrationEnabled = it }

        root.addView(buildInfo())

        root.addView(TextView(this).apply {
            text = "DONE"
            typeface = Theme.uiBold(this@SettingsActivity)
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
            ).apply { topMargin = dp(28f) }
            setOnClickListener { finish() }
        })

        return ScrollView(this).apply {
            setBackgroundColor(Theme.bgBottom)
            isFillViewport = true
            addView(root)
        }
    }

    /** A labelled switch on a card, matching the game's surfaces. */
    private fun toggle(
        parent: LinearLayout,
        label: String,
        detail: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18f).toFloat()
                setColor(Theme.card)
                setStroke(dp(1f), Theme.hairline)
            }
            setPadding(dp(18f), dp(16f), dp(14f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14f) }
        }

        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(this).apply {
            this.text = label
            typeface = Theme.uiBold(this@SettingsActivity)
            setTextColor(Theme.textPrimary)
            textSize = 18f
        })
        text.addView(TextView(this).apply {
            this.text = detail
            typeface = Theme.ui(this@SettingsActivity)
            setTextColor(Theme.textFaint)
            textSize = 14f
            setPadding(0, dp(2f), 0, 0)
        })
        row.addView(text)

        val switch = SwitchCompat(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(switch)

        // The whole row is the target, not just the switch itself.
        row.isClickable = true
        row.setOnClickListener { switch.toggle() }

        parent.addView(row)
    }

    /**
     * Build and package details, and the way into the dev panel. Ten taps, with the
     * count resetting if they slow down, so nobody arrives here by fidgeting.
     */
    private fun buildInfo(): View {
        val info = TextView(this).apply {
            text = infoText()
            typeface = Theme.ui(this@SettingsActivity)
            setTextColor(Theme.textFaint)
            textSize = 13f
            gravity = Gravity.CENTER
            setLineSpacing(dp(2f).toFloat(), 1f)
            setPadding(dp(12f), dp(24f), dp(12f), dp(8f))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16f) }
        }

        info.setOnClickListener {
            val now = System.currentTimeMillis()
            buildTaps = if (now - lastTapAt > TAP_WINDOW_MS) 1 else buildTaps + 1
            lastTapAt = now

            when {
                buildTaps >= TAPS_TO_UNLOCK -> {
                    buildTaps = 0
                    settings.saveTo(this)
                    startActivity(Intent(this, DevSettingsActivity::class.java))
                }
                // Only starts counting out loud near the end, so the door stays shut
                // to anyone not already looking for it.
                buildTaps >= TAPS_TO_UNLOCK - 3 -> {
                    val left = TAPS_TO_UNLOCK - buildTaps
                    Toast.makeText(this, "$left more...", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return info
    }

    private fun infoText(): String {
        val version = try {
            val pkg = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            val code = pkg.versionCode
            "v${pkg.versionName} (build $code)"
        } catch (e: PackageManager.NameNotFoundException) {
            "version unknown"
        }
        return buildString {
            appendLine(getString(R.string.app_name))
            appendLine(version)
            appendLine(packageName)
            append("Android ${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}")
        }
    }

    private companion object {
        const val TAPS_TO_UNLOCK = 10
        /** Taps further apart than this start the count again. */
        const val TAP_WINDOW_MS = 1200L
    }
}
