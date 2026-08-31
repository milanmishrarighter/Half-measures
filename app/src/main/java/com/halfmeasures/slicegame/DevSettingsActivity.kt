package com.halfmeasures.slicegame

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.SwitchCompat
import kotlin.math.roundToInt

/**
 * Every mechanic in the game, exposed as a slider or a switch so the feel can be
 * tuned in-app. Built programmatically to match the Canvas-drawn game surface -
 * same palette, same typefaces, no XML layouts anywhere in the project.
 *
 * Not reachable from the game's own settings screen by any labelled route: it is
 * behind ten taps on the build-info line. Thirty-odd knobs are a development tool,
 * and handing them to a player is handing them a way to make the game worse.
 */
class DevSettingsActivity : AppCompatActivity() {

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
        save()
    }

    /**
     * Written on every change rather than only on the way out. A screen that holds
     * its edits until onPause loses them to anything that kills the process, and
     * makes it far too easy for another screen to overwrite them in the meantime.
     */
    private fun save() {
        settings.saveTo(this)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bgBottom)
            setPadding(dp(20f), dp(28f), dp(20f), dp(28f))
        }

        root.addView(header())

        card("MAIN GAMEPLAY").let { c ->
            root.addView(c.wrapper)
            numberField(
                c.body, "Start the game at this score",
                "The level, the backdrop colour and which shapes are in play all follow from " +
                    "the score, so this drops you straight into a run as it would be there.",
                settings.startingScore
            ) { settings.startingScore = it }
            slider(
                c.body, "Points per level", "Score this many points and the game moves up a level.",
                GameSettings.MIN_STAGE_SCORE_INTERVAL.toFloat(), GameSettings.MAX_STAGE_SCORE_INTERVAL.toFloat(),
                settings.stageScoreInterval.toFloat(),
                { "${it.roundToInt()}" }, { settings.stageScoreInterval = it.roundToInt() }
            )
            slider(
                c.body, "Shapes in the air at level 1", "How many can be up at the same time when you start.",
                GameSettings.MIN_CONCURRENCY.toFloat(), GameSettings.MAX_CONCURRENCY_LIMIT.toFloat(),
                settings.startConcurrency.toFloat(),
                { "${it.roundToInt()}" }, { settings.startConcurrency = it.roundToInt() }
            )
            slider(
                c.body, "Extra shapes each level", "How many more are allowed in the air when you level up.",
                GameSettings.MIN_CONCURRENCY_PER_STAGE.toFloat(), GameSettings.MAX_CONCURRENCY_PER_STAGE.toFloat(),
                settings.concurrencyPerStage.toFloat(),
                { "+${it.roundToInt()}" }, { settings.concurrencyPerStage = it.roundToInt() }
            )
            slider(
                c.body, "Never more than", "However good you get, never more shapes than this at once.",
                GameSettings.MIN_CONCURRENCY.toFloat(), GameSettings.MAX_CONCURRENCY_LIMIT.toFloat(),
                settings.maxConcurrency.toFloat(),
                { "${it.roundToInt()}" }, { settings.maxConcurrency = it.roundToInt() }
            )
            slider(
                c.body, "Wait between shapes", "How long the game waits before throwing up the next one.",
                GameSettings.MIN_SPAWN_GAP_MS.toFloat(), GameSettings.MAX_SPAWN_GAP_MS.toFloat(),
                settings.spawnGapMs.toFloat(),
                { "%.1fs".format(it / 1000f) }, { settings.spawnGapMs = it.toLong() }
            )
            slider(
                c.body, "They come faster each level",
                "How much shorter that wait gets every level. There is a floor, so it can never " +
                    "become a solid wall of shapes.",
                GameSettings.MIN_SPAWN_SPEED_UP_PERCENT, GameSettings.MAX_SPAWN_SPEED_UP_PERCENT,
                settings.spawnSpeedUpPercent,
                { if (it < 0.5f) "Off" else "-${it.roundToInt()}% / level" },
                { settings.spawnSpeedUpPercent = it }
            )
            slider(
                c.body, "Spinning", "How much the shapes twirl. Twirly shapes are harder to cut in half.",
                GameSettings.MIN_ROTATION_SCALE, GameSettings.MAX_ROTATION_SCALE, settings.rotationScale,
                { "%.1fx".format(it) }, { settings.rotationScale = it }
            )
            slider(
                c.body, "Extra spin each level", "How much twirlier they get when you level up.",
                GameSettings.MIN_ROTATION_PER_STAGE_PERCENT, GameSettings.MAX_ROTATION_PER_STAGE_PERCENT,
                settings.rotationPerStagePercent,
                { "+${it.roundToInt()}%" }, { settings.rotationPerStagePercent = it }
            )
            slider(
                c.body, "Throw-in spread each level",
                "Shapes are thrown in from the sides. This is how much further toward the " +
                    "middle they can start each time you level up.",
                GameSettings.MIN_LAUNCH_CENTRE_CREEP, GameSettings.MAX_LAUNCH_CENTRE_CREEP,
                settings.launchCentreCreep,
                { if (it <= 0.002f) "Sides only" else "+${(it * 100).roundToInt()}%" },
                { settings.launchCentreCreep = it }
            )
        }

        card("THE SHAPES").let { c ->
            root.addView(c.wrapper)
            slider(
                c.body, "Size", "Bigger number = bigger shapes to cut.",
                GameSettings.MIN_SIZE_SCALE, GameSettings.MAX_SIZE_SCALE, settings.sizeScale,
                { "%.1fx".format(it) }, { settings.sizeScale = it }
            )
            slider(
                c.body, "How high they fly", "How far up the screen they go before falling back down.",
                GameSettings.MIN_FLIGHT_HEIGHT, GameSettings.MAX_FLIGHT_HEIGHT, settings.flightHeight,
                { "${(it * 100).roundToInt()}%" }, { settings.flightHeight = it }
            )
            slider(
                c.body, "Gravity", "Bigger number = they drop back down faster. Smaller = they float.",
                GameSettings.MIN_GRAVITY_SCALE, GameSettings.MAX_GRAVITY_SCALE, settings.gravityScale,
                { "%.1fx".format(it) }, { settings.gravityScale = it }
            )
            slider(
                c.body, "Bouncy walls", "0% and shapes fly off the sides. Higher and they bounce back in.",
                GameSettings.MIN_WALL_STRENGTH, GameSettings.MAX_WALL_STRENGTH, settings.wallStrength,
                { if (it <= 0.01f) "Off" else "${(it * 100).roundToInt()}%" }, { settings.wallStrength = it }
            )
        }

        card("POINTS & HEALTH").let { c ->
            root.addView(c.wrapper)
            slider(
                c.body, "Starting health", "How much health you begin with. It runs out, the game ends.",
                GameSettings.MIN_START_HEALTH.toFloat(), GameSettings.MAX_START_HEALTH.toFloat(),
                settings.startHealth.toFloat(),
                { "${it.roundToInt()}" }, { settings.startHealth = it.roundToInt() }
            )
            slider(
                c.body, "How close counts as PERFECT", "Cut this close to dead centre and it counts as perfect.",
                GameSettings.MIN_PERFECT_THRESHOLD, GameSettings.MAX_PERFECT_THRESHOLD, settings.perfectThreshold,
                { "±%.1f%%".format(it) }, { settings.perfectThreshold = it }
            )
            slider(
                c.body, "How close counts as GREAT", "10% means a 60/40 cut still gets a well done.",
                GameSettings.MIN_GREAT_THRESHOLD, GameSettings.MAX_GREAT_THRESHOLD, settings.greatThreshold,
                { "±%.0f%%".format(it) }, { settings.greatThreshold = it }
            )
            slider(
                c.body, "Points lost for being off", "How many points you drop for each 1% you miss by.",
                GameSettings.MIN_SCORE_MISS_WEIGHT, GameSettings.MAX_SCORE_MISS_WEIGHT, settings.scoreMissWeight,
                { "%.1f pts".format(it) }, { settings.scoreMissWeight = it }
            )
            slider(
                c.body, "Bonus for being neat", "Extra points for really tidy cuts. The tidier, the bigger the bonus.",
                GameSettings.MIN_GREAT_BONUS_PERCENT, GameSettings.MAX_GREAT_BONUS_PERCENT,
                settings.greatBonusPercent,
                { "+${it.roundToInt()}%" }, { settings.greatBonusPercent = it }
            )
            slider(
                c.body, "Health lost on a 60/40 cut", "How much health an okay-but-not-great cut costs you.",
                GameSettings.MIN_HEALTH_LOSS_AT_SIXTY_FORTY, GameSettings.MAX_HEALTH_LOSS_AT_SIXTY_FORTY,
                settings.healthLossAtSixtyForty,
                { "%.1f hp".format(it) }, { settings.healthLossAtSixtyForty = it }
            )
            slider(
                c.body, "Punish bad cuts extra", "Turn this up and really messy cuts hurt way more than small mistakes.",
                GameSettings.MIN_HEALTH_LOSS_CURVE, GameSettings.MAX_HEALTH_LOSS_CURVE,
                settings.healthLossCurve,
                { "%.1f".format(it) }, { settings.healthLossCurve = it }
            )
            toggle(
                c.body, "Perfect cuts heal you", "Dead centre cuts give you health back.",
                settings.perfectRestoresHealth
            ) { settings.perfectRestoresHealth = it }
            slider(
                c.body, "Healing per perfect in a row",
                "First perfect heals this much, second heals twice, and so on. At 10 hp, ten perfects in a row refill the whole bar.",
                GameSettings.MIN_PERFECT_HEAL_PER_STREAK, GameSettings.MAX_PERFECT_HEAL_PER_STREAK,
                settings.perfectHealPerStreak,
                { "%.0f hp".format(it) }, { settings.perfectHealPerStreak = it }
            )
            toggle(
                c.body, "Missing a shape ends the game", "Let one fall off the screen without cutting it and you lose.",
                settings.missEndsRun
            ) { settings.missEndsRun = it }
        }

        card("STREAKS").let { c ->
            root.addView(c.wrapper)
            slider(
                c.body, "Good streak bonus", "Good cut after good cut (60/40 or better)? Each one adds this much extra score.",
                GameSettings.MIN_COMBO_BONUS_PERCENT, GameSettings.MAX_COMBO_BONUS_PERCENT, settings.comboBonusPercent,
                { "+${it.roundToInt()}%" }, { settings.comboBonusPercent = it }
            )
            slider(
                c.body, "Perfect streak bonus",
                "Dead centre cut after dead centre cut? Each one adds this much on top of the good streak bonus.",
                GameSettings.MIN_PERFECT_STREAK_BONUS_PERCENT, GameSettings.MAX_PERFECT_STREAK_BONUS_PERCENT,
                settings.perfectStreakBonusPercent,
                { "+${it.roundToInt()}%" }, { settings.perfectStreakBonusPercent = it }
            )
            slider(
                c.body, "Biggest hot streak bonus", "The most your streak bonus can ever grow to.",
                GameSettings.MIN_MAX_COMBO_MULTIPLIER, GameSettings.MAX_MAX_COMBO_MULTIPLIER,
                settings.maxComboMultiplier,
                { "%.1fx".format(it) }, { settings.maxComboMultiplier = it }
            )
            slider(
                c.body, "Cold streak punishment", "Bad cut after bad cut? Each one takes this much off your score.",
                GameSettings.MIN_COLD_STREAK_PENALTY_PERCENT, GameSettings.MAX_COLD_STREAK_PENALTY_PERCENT,
                settings.coldStreakPenaltyPercent,
                { "-${it.roundToInt()}%" }, { settings.coldStreakPenaltyPercent = it }
            )
        }

        card("SLOW MOTION").let { c ->
            root.addView(c.wrapper)
            toggle(
                c.body, "Slow-mo on a perfect cut", "Time crawls for a moment so you can enjoy a perfect cut.",
                settings.slowMoOnPerfect
            ) { settings.slowMoOnPerfect = it }
            slider(
                c.body, "How slow it goes", "Smaller number = slower. 20% means everything moves at a fifth speed.",
                GameSettings.MIN_SLOW_MO_INTENSITY, GameSettings.MAX_SLOW_MO_INTENSITY, settings.slowMoIntensity,
                { "${(it * 100).roundToInt()}%" }, { settings.slowMoIntensity = it }
            )
            slider(
                c.body, "How long the slow-mo lasts", "How many seconds before the game speeds back up.",
                GameSettings.MIN_SLOW_MO_DURATION, GameSettings.MAX_SLOW_MO_DURATION, settings.slowMoDuration,
                { "%.1fs".format(it) }, { settings.slowMoDuration = it }
            )
            toggle(
                c.body, "Warning when health is low", "Time slows and counts 3, 2, 1 so you know you are nearly out.",
                settings.lowHealthSlowMo
            ) { settings.lowHealthSlowMo = it }
            slider(
                c.body, "When to warn you", "Health drops below this much and the warning kicks in.",
                GameSettings.MIN_LOW_HEALTH_AT.toFloat(), GameSettings.MAX_LOW_HEALTH_AT.toFloat(),
                settings.lowHealthAt.toFloat(),
                { "${it.roundToInt()} hp" }, { settings.lowHealthAt = it.roundToInt() }
            )
        }

        card("LOOK & FEEL").let { c ->
            root.addView(c.wrapper)
            toggle(
                c.body, "Show the dotted helper line", "A row of faint dots showing exactly where to cut.",
                settings.guideLineEnabled
            ) { settings.guideLineEnabled = it }
            slider(
                c.body, "How clearly you see it", "Turn it up to make the helper dots easier to spot.",
                GameSettings.MIN_GUIDE_LINE_OPACITY, GameSettings.MAX_GUIDE_LINE_OPACITY, settings.guideLineOpacity,
                { "${(it * 100).roundToInt()}%" }, { settings.guideLineOpacity = it }
            )
            toggle(
                c.body, "Sparks and bits", "Little bits fly everywhere when you cut something.",
                settings.particlesEnabled
            ) { settings.particlesEnabled = it }
            slider(
                c.body, "How many bits fly", "More bits = messier, more exciting explosions.",
                GameSettings.MIN_PARTICLE_AMOUNT, GameSettings.MAX_PARTICLE_AMOUNT, settings.particleAmount,
                { "%.1fx".format(it) }, { settings.particleAmount = it }
            )
            slider(
                c.body, "Screen shake", "How hard the whole screen jolts when you land a good cut.",
                GameSettings.MIN_CAMERA_SHAKE_STRENGTH, GameSettings.MAX_CAMERA_SHAKE_STRENGTH,
                settings.cameraShakeStrength,
                { if (it <= 0.01f) "Off" else "%.1fx".format(it) }, { settings.cameraShakeStrength = it }
            )
            slider(
                c.body, "Colour flash", "The whole screen lights up for a split second on a great cut.",
                GameSettings.MIN_SCREEN_FLASH_STRENGTH, GameSettings.MAX_SCREEN_FLASH_STRENGTH,
                settings.screenFlashStrength,
                { if (it <= 0.01f) "Off" else "%.1fx".format(it) }, { settings.screenFlashStrength = it }
            )
            slider(
                c.body, "Ember count", "How many little sparks float in the background.",
                GameSettings.MIN_EMBER_DENSITY, GameSettings.MAX_EMBER_DENSITY, settings.emberDensity,
                { if (it <= 0.01f) "None" else "%.1fx".format(it) }, { settings.emberDensity = it }
            )
            slider(
                c.body, "Ember glow", "How bright those background sparks are.",
                GameSettings.MIN_EMBER_BRIGHTNESS, GameSettings.MAX_EMBER_BRIGHTNESS, settings.emberBrightness,
                { if (it <= 0.01f) "Off" else "%.1fx".format(it) }, { settings.emberBrightness = it }
            )
            slider(
                c.body, "Ember size", "How big each little spark square is.",
                GameSettings.MIN_EMBER_SIZE, GameSettings.MAX_EMBER_SIZE, settings.emberSize,
                { "%.1fx".format(it) }, { settings.emberSize = it }
            )
            slider(
                c.body, "Ember drift speed", "How fast the sparks float upward.",
                GameSettings.MIN_BACKGROUND_MOTION, GameSettings.MAX_BACKGROUND_MOTION, settings.backgroundMotion,
                { if (it <= 0.01f) "Still" else "%.1fx".format(it) }, { settings.backgroundMotion = it }
            )
            slider(
                c.body, "Knife trail thickness", "How fat the glowing line under your finger is.",
                GameSettings.MIN_TRAIL_THICKNESS, GameSettings.MAX_TRAIL_THICKNESS, settings.trailThickness,
                { "%.1fx".format(it) }, { settings.trailThickness = it }
            )
            slider(
                c.body, "Score text size", "How big the points that pop up after a cut are.",
                GameSettings.MIN_POPUP_TEXT_SCALE, GameSettings.MAX_POPUP_TEXT_SCALE, settings.popupTextScale,
                { "%.1fx".format(it) }, { settings.popupTextScale = it }
            )
            toggle(
                c.body, "Buzzing", "The phone buzzes in your hand when you cut something.",
                settings.vibrationEnabled
            ) { settings.vibrationEnabled = it }
            slider(
                c.body, "How hard it buzzes", "Turn it down for a gentle tap, up for a proper thump.",
                GameSettings.MIN_VIBRATION_STRENGTH, GameSettings.MAX_VIBRATION_STRENGTH, settings.vibrationStrength,
                { "${(it * 100).roundToInt()}%" }, { settings.vibrationStrength = it }
            )
        }

        card("SOUND & GLOW").let { c ->
            root.addView(c.wrapper)
            toggle(
                c.body, "Sound", "All the arcade blips and jingles.",
                settings.soundEnabled
            ) { settings.soundEnabled = it }
            slider(
                c.body, "How loud", "Volume of everything the game plays.",
                GameSettings.MIN_SOUND_VOLUME, GameSettings.MAX_SOUND_VOLUME, settings.soundVolume,
                { "${(it * 100).roundToInt()}%" }, { settings.soundVolume = it }
            )
            toggle(
                c.body, "Announcer", "The shouted line on a perfect cut.",
                settings.voiceEnabled
            ) { settings.voiceEnabled = it }
            toggle(
                c.body, "Music", "The bass loop under a run.",
                settings.musicEnabled
            ) { settings.musicEnabled = it }
            slider(
                c.body, "Music volume", "How loud the loop is. Held under the effects by default.",
                GameSettings.MIN_MUSIC_VOLUME, GameSettings.MAX_MUSIC_VOLUME, settings.musicVolume,
                { "${(it * 100).roundToInt()}%" }, { settings.musicVolume = it }
            )
            slider(
                c.body, "Neon glow",
                "How hard the outline burns around each shape. 0 goes back to a plain edge.",
                GameSettings.MIN_NEON_GLOW, GameSettings.MAX_NEON_GLOW, settings.neonGlow,
                { if (it <= 0.01f) "Off" else "%.1fx".format(it) }, { settings.neonGlow = it }
            )
        }

        card("SHAPES").let { c ->
            root.addView(c.wrapper)
            shapePicker(c.body)
        }

        card("ADS").let { c ->
            root.addView(c.wrapper)
            toggle(
                c.body, "Offer a continue when you die",
                "Watch an ad and carry on with the same score instead of going to the scorecard.",
                settings.continuesEnabled
            ) { settings.continuesEnabled = it }
            slider(
                c.body, "Continues per run",
                "How many times one run can be bought back. Leave it at no limit and a good run can go on as long as you keep watching.",
                GameSettings.MIN_CONTINUES_PER_RUN.toFloat(), GameSettings.MAX_CONTINUES_PER_RUN.toFloat(),
                settings.continuesPerRun.toFloat(),
                { if (it.roundToInt() == 0) "No limit" else "${it.roundToInt()}" },
                { settings.continuesPerRun = it.roundToInt() }
            )
            slider(
                c.body, "Health you come back with",
                "How full the bar is when an ad brings you back.",
                GameSettings.MIN_CONTINUE_HEALTH_FRACTION, GameSettings.MAX_CONTINUE_HEALTH_FRACTION,
                settings.continueHealthFraction,
                { "${(it * 100).roundToInt()}%" }, { settings.continueHealthFraction = it }
            )
            slider(
                c.body, "Ad before every Nth game",
                "Every this many games in one sitting, the next one needs an ad watched before it starts. Closing the app resets the count.",
                GameSettings.MIN_AD_GATE_EVERY.toFloat(), GameSettings.MAX_AD_GATE_EVERY.toFloat(),
                settings.adGateEvery.toFloat(),
                { if (it.roundToInt() == 0) "Off" else "Every ${it.roundToInt()}" },
                { settings.adGateEvery = it.roundToInt() }
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

        addView(TextView(this@DevSettingsActivity).apply {
            text = "SETTINGS"
            typeface = Theme.display(this@DevSettingsActivity)
            setTextColor(Theme.textPrimary)
            textSize = 24f
        })
        addView(TextView(this@DevSettingsActivity).apply {
            text = "Tune the game to taste. Changes apply on your next run."
            typeface = Theme.ui(this@DevSettingsActivity)
            setTextColor(Theme.textFaint)
            textSize = 15f
            setPadding(0, dp(4f), 0, 0)
        })
    }

    private class Card(val wrapper: LinearLayout, val body: LinearLayout)

    /**
     * A labelled number you type rather than drag. Some values are not a range to
     * feel your way along - a starting score of 47,000 is a specific thing to ask
     * for, and no slider is going to land on it.
     */
    private fun numberField(
        parent: LinearLayout,
        label: String,
        detail: String,
        initial: Int,
        onChange: (Int) -> Unit
    ) {
        parent.addView(TextView(this).apply {
            text = label
            typeface = Theme.uiBold(this@DevSettingsActivity)
            setTextColor(Theme.textPrimary)
            textSize = 17f
            setPadding(0, dp(12f), 0, 0)
        })
        parent.addView(TextView(this).apply {
            text = detail
            typeface = Theme.ui(this@DevSettingsActivity)
            setTextColor(Theme.textFaint)
            textSize = 14f
            setPadding(0, dp(2f), 0, dp(6f))
        })
        parent.addView(EditText(this).apply {
            setText(initial.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            typeface = Theme.uiBold(this@DevSettingsActivity)
            setTextColor(Theme.textPrimary)
            textSize = 17f
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10f).toFloat()
                setColor(Theme.withAlpha(Color.WHITE, 0.05f))
                setStroke(dp(1f), Theme.hairline)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    // An empty box is someone mid-edit, not a zero.
                    val typed = s?.toString()?.trim().orEmpty()
                    if (typed.isEmpty()) return
                    typed.toIntOrNull()?.let {
                        onChange(it.coerceIn(GameSettings.MIN_STARTING_SCORE, GameSettings.MAX_STARTING_SCORE))
                        save()
                    }
                }
            })
        })
    }

    /**
     * Every shape in the catalogue, in the order the game unlocks them.
     *
     * Each row carries a tick and the score it starts appearing at. The score is
     * an override: a shape with an empty box uses its measured place in the unlock
     * order, so changing one shape leaves every other one exactly where it was.
     */
    private fun shapePicker(body: LinearLayout) {
        body.addView(TextView(this).apply {
            text = "Untick a shape to keep it out of the game. The number is the score it " +
                "starts appearing at - type over it to move a shape earlier or later, or " +
                "clear it to put it back where the game puts it. The list is in running " +
                "order - use SORT to redo it after changing a score."
            typeface = Theme.ui(this@DevSettingsActivity)
            setTextColor(Theme.textFaint)
            textSize = 14f
            setPadding(0, dp(8f), 0, dp(12f))
        })

        val boxes = ArrayList<AppCompatCheckBox>()
        val fields = ArrayList<EditText>()
        val kinds = ShapeKind.ordered(settings)

        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pillButton("ALL ON", primary = false) {
                settings.disabledShapes.clear()
                boxes.forEach { it.isChecked = true }
                save()
            }.also { (it.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8f) })
            addView(pillButton("ALL OFF", primary = false) {
                settings.disabledShapes.addAll(kinds.map { k -> k.name })
                boxes.forEach { it.isChecked = false }
                save()
            }.also { (it.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8f) })
            addView(pillButton("RESET SCORES", primary = false) {
                settings.shapeUnlockScores.clear()
                save()
                fields.forEachIndexed { i, f ->
                    f.setText(ShapeKind.unlockScore(kinds[i], settings).toString())
                }
            }.also { (it.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8f) })
            // Rebuilding as you type would take the keyboard with it, so the
            // re-sort is a button rather than something that happens underneath.
            addView(pillButton("SORT", primary = false) {
                save()
                recreate()
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        })

        // Column headings, so the number in each row is not a mystery.
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@DevSettingsActivity).apply {
                text = "SHAPE"
                typeface = Theme.uiBold(this@DevSettingsActivity)
                setTextColor(Theme.textFaint)
                textSize = 11f
                letterSpacing = 0.14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@DevSettingsActivity).apply {
                text = "FROM SCORE"
                typeface = Theme.uiBold(this@DevSettingsActivity)
                setTextColor(Theme.textFaint)
                textSize = 11f
                letterSpacing = 0.14f
                setPadding(0, 0, dp(38f), 0)
            })
            setPadding(0, 0, 0, dp(6f))
        })

        kinds.forEach { kind ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(1f), 0, dp(1f))
            }

            row.addView(TextView(this@DevSettingsActivity).apply {
                text = kind.displayName
                typeface = Theme.uiBold(this@DevSettingsActivity)
                setTextColor(Theme.textPrimary)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })

            val field = EditText(this).apply {
                setText(ShapeKind.unlockScore(kind, settings).toString())
                inputType = InputType.TYPE_CLASS_NUMBER
                typeface = Theme.ui(this@DevSettingsActivity)
                setTextColor(Theme.textPrimary)
                setHintTextColor(Theme.textFaint)
                textSize = 15f
                gravity = Gravity.END
                setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8f).toFloat()
                    setColor(Theme.withAlpha(Color.WHITE, 0.05f))
                    setStroke(dp(1f), Theme.hairline)
                }
                layoutParams = LinearLayout.LayoutParams(dp(84f), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = dp(8f) }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val typed = s?.toString()?.trim().orEmpty()
                        // An empty box means "wherever difficulty puts it", which is
                        // different from a zero and has to stay different.
                        if (typed.isEmpty()) settings.shapeUnlockScores.remove(kind.name)
                        else typed.toIntOrNull()?.let {
                            settings.shapeUnlockScores[kind.name] = it.coerceAtLeast(0)
                        }
                        save()
                    }
                })
            }
            fields.add(field)
            row.addView(field)

            val box = AppCompatCheckBox(this).apply {
                isChecked = kind.name !in settings.disabledShapes
                setOnCheckedChangeListener { _, checked ->
                    if (checked) settings.disabledShapes.remove(kind.name)
                    else settings.disabledShapes.add(kind.name)
                    save()
                }
            }
            boxes.add(box)
            row.addView(box)
            body.addView(row)
        }
    }

    private fun card(title: String): Card {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18f) }
        }

        wrapper.addView(TextView(this).apply {
            text = title
            typeface = Theme.uiBold(this@DevSettingsActivity)
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

            addView(TextView(this@DevSettingsActivity).apply {
                text = title
                typeface = Theme.uiBold(this@DevSettingsActivity)
                setTextColor(Theme.textPrimary)
                textSize = 17f
            })
            if (subtitle.isNotEmpty()) {
                addView(TextView(this@DevSettingsActivity).apply {
                    text = subtitle
                    typeface = Theme.ui(this@DevSettingsActivity)
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
            typeface = Theme.display(this@DevSettingsActivity)
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
                // Written once the finger lifts rather than on every pixel of the
                // drag: a thousand commits per sweep would be absurd.
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    save()
                }
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
            setOnCheckedChangeListener { _, checked ->
                onChange(checked)
                save()
            }
        })
        parent.addView(row)
    }

    private fun footer(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4f), 0, 0)

        addView(pillButton("COPY ALL SETTINGS", primary = false) {
            copySettingsToClipboard()
        }.also {
            // A weighted, zero-width pill only works inside a row; in this column
            // it needs a real width or it collapses and disappears.
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12f) }
        })

        addView(LinearLayout(this@DevSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pillButton("RESET", primary = false) {
                settings = GameSettings()
                save()
                recreate()
            }.also {
                (it.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12f)
            })
            addView(pillButton("DONE", primary = true) { finish() })
        })
    }

    /** Dumps every current value as plain text, ready to paste back for hardcoding. */
    private fun copySettingsToClipboard() {
        settings.saveTo(this)
        val text = buildString {
            appendLine("Half Measures settings")
            appendLine()
            appendLine("MAIN GAMEPLAY")
            appendLine("  Start the game at this score: ${settings.startingScore}")
            appendLine("  Points per level: ${settings.stageScoreInterval}")
            appendLine("  Shapes in the air at level 1: ${settings.startConcurrency}")
            appendLine("  Extra shapes each level: ${settings.concurrencyPerStage}")
            appendLine("  Never more than: ${settings.maxConcurrency}")
            appendLine("  Wait between shapes: %.1fs".format(settings.spawnGapMs / 1000f))
            appendLine("  They come faster each level: ${settings.spawnSpeedUpPercent.roundToInt()}%")
            appendLine("  Spinning: %.2fx".format(settings.rotationScale))
            appendLine("  Extra spin each level: ${settings.rotationPerStagePercent.roundToInt()}%")
            appendLine("  Throw-in spread each level: ${(settings.launchCentreCreep * 100).roundToInt()}%")
            appendLine()
            appendLine("THE SHAPES")
            appendLine("  Size: %.2fx".format(settings.sizeScale))
            appendLine("  How high they fly: ${(settings.flightHeight * 100).roundToInt()}%")
            appendLine("  Gravity: %.2fx".format(settings.gravityScale))
            appendLine("  Bouncy walls: ${(settings.wallStrength * 100).roundToInt()}%")
            appendLine()
            appendLine("POINTS & HEALTH")
            appendLine("  Starting health: ${settings.startHealth}")
            appendLine("  Counts as PERFECT: ±%.1f%%".format(settings.perfectThreshold))
            appendLine("  Counts as GREAT: ±%.0f%%".format(settings.greatThreshold))
            appendLine("  Points lost for being off: %.1f pts".format(settings.scoreMissWeight))
            appendLine("  Bonus for being neat: ${settings.greatBonusPercent.roundToInt()}%")
            appendLine("  Health lost on a 60/40 cut: %.1f hp".format(settings.healthLossAtSixtyForty))
            appendLine("  Punish bad cuts extra: %.1f".format(settings.healthLossCurve))
            appendLine("  Perfect cuts heal you: ${onOff(settings.perfectRestoresHealth)}")
            appendLine("  Healing per perfect in a row: %.0f hp".format(settings.perfectHealPerStreak))
            appendLine("  Missing a shape ends the game: ${onOff(settings.missEndsRun)}")
            appendLine()
            appendLine("STREAKS")
            appendLine("  Good streak bonus: ${settings.comboBonusPercent.roundToInt()}%")
            appendLine("  Perfect streak bonus: ${settings.perfectStreakBonusPercent.roundToInt()}%")
            appendLine("  Biggest hot streak bonus: %.1fx".format(settings.maxComboMultiplier))
            appendLine("  Cold streak punishment: ${settings.coldStreakPenaltyPercent.roundToInt()}%")
            appendLine()
            appendLine("SLOW MOTION")
            appendLine("  Slow-mo on a perfect cut: ${onOff(settings.slowMoOnPerfect)}")
            appendLine("  How slow it goes: ${(settings.slowMoIntensity * 100).roundToInt()}%")
            appendLine("  How long it lasts: %.1fs".format(settings.slowMoDuration))
            appendLine("  Warning when health is low: ${onOff(settings.lowHealthSlowMo)}")
            appendLine("  When to warn you: ${settings.lowHealthAt} hp")
            appendLine()
            appendLine("LOOK & FEEL")
            appendLine("  Dotted helper line: ${onOff(settings.guideLineEnabled)}")
            appendLine("  How clearly you see it: ${(settings.guideLineOpacity * 100).roundToInt()}%")
            appendLine("  Sparks and bits: ${onOff(settings.particlesEnabled)}")
            appendLine("  How many bits fly: %.2fx".format(settings.particleAmount))
            appendLine("  Screen shake: %.2fx".format(settings.cameraShakeStrength))
            appendLine("  Colour flash: %.2fx".format(settings.screenFlashStrength))
            appendLine("  Ember count: %.2fx".format(settings.emberDensity))
            appendLine("  Ember glow: %.2fx".format(settings.emberBrightness))
            appendLine("  Ember size: %.2fx".format(settings.emberSize))
            appendLine("  Ember drift speed: %.2fx".format(settings.backgroundMotion))
            appendLine("  Knife trail thickness: %.2fx".format(settings.trailThickness))
            appendLine("  Score text size: %.2fx".format(settings.popupTextScale))
            appendLine("  Buzzing: ${onOff(settings.vibrationEnabled)}")
            appendLine("  How hard it buzzes: ${(settings.vibrationStrength * 100).roundToInt()}%")
            appendLine()
            appendLine("SOUND & GLOW")
            appendLine("  Sound: ${onOff(settings.soundEnabled)}")
            appendLine("  How loud: ${(settings.soundVolume * 100).roundToInt()}%")
            appendLine("  Announcer: ${onOff(settings.voiceEnabled)}")
            appendLine("  Music: ${onOff(settings.musicEnabled)}")
            appendLine("  Music volume: ${(settings.musicVolume * 100).roundToInt()}%")
            appendLine("  Neon glow: %.2fx".format(settings.neonGlow))
            appendLine()
            appendLine("SHAPES")
            appendLine("  In play: ${ShapeKind.values().size - settings.disabledShapes.size}" +
                " of ${ShapeKind.values().size}")
            ShapeKind.ordered(settings)
                .filter { it.name !in settings.disabledShapes }
                .forEach {
                    appendLine("    ${it.displayName}: from ${ShapeKind.unlockScore(it, settings)}")
                }
            if (settings.disabledShapes.isNotEmpty()) {
                val off = ShapeKind.ordered(settings)
                    .filter { it.name in settings.disabledShapes }
                    .joinToString(", ") { it.displayName }
                appendLine("  Switched off: $off")
            }
            appendLine()
            appendLine("ADS")
            appendLine("  Offer a continue when you die: ${onOff(settings.continuesEnabled)}")
            appendLine("  Continues per run: ${if (settings.continuesPerRun == 0) "No limit" else "${settings.continuesPerRun}"}")
            appendLine("  Health you come back with: ${(settings.continueHealthFraction * 100).roundToInt()}%")
            appendLine("  Ad before every Nth game: ${settings.adGateEvery}")
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Half Measures settings", text))
        Toast.makeText(this, "Settings copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun onOff(value: Boolean): String = if (value) "ON" else "OFF"

    private fun pillButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            typeface = Theme.uiBold(this@DevSettingsActivity)
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
