package com.halfmeasures.slicegame

import android.content.Context

/**
 * Every tunable knob in the game, persisted to SharedPreferences so the whole
 * feel of the game can be dialled in from the in-app Settings screen.
 *
 * Difficulty runs on stages: every [stageScoreInterval] points the run advances a
 * stage, which allows [concurrencyPerStage] more shapes on screen (never past
 * [maxConcurrency]), introduces [shapesPerStage] new shape kinds, and adds
 * [rotationPerStagePercent] more spin. Whenever fewer shapes are alive than the
 * current cap, a new one spawns every [spawnGapMs].
 *
 * Scoring works off "deviation": half the area imbalance, i.e. how many
 * percentage points the bigger piece sits above a perfect 50. A 60/40 cut has a
 * deviation of 10, a perfect cut 0, a total whiff 50.
 */
data class GameSettings(
    // ---- Shapes ----
    var sizeScale: Float = DEFAULT_SIZE_SCALE,
    /** How high shapes rise, as a fraction of screen height. Reachability is guaranteed. */
    var flightHeight: Float = DEFAULT_FLIGHT_HEIGHT,
    var gravityScale: Float = DEFAULT_GRAVITY_SCALE,
    var rotationScale: Float = DEFAULT_ROTATION_SCALE,
    /** 0 = sides are transparent and shapes drift off; 1 = fully elastic bounce. */
    var wallStrength: Float = DEFAULT_WALL_STRENGTH,

    // ---- Difficulty stages ----
    /** Score needed to advance one stage. Each stage adds shapes, spin and traffic. */
    var stageScoreInterval: Int = DEFAULT_STAGE_SCORE_INTERVAL,
    /** Shape kinds in play at stage 0. */
    var startingShapeCount: Int = DEFAULT_STARTING_SHAPE_COUNT,
    /** New shape kinds introduced each stage. */
    var shapesPerStage: Int = DEFAULT_SHAPES_PER_STAGE,
    /** Extra shapes allowed on screen per stage. */
    var concurrencyPerStage: Int = DEFAULT_CONCURRENCY_PER_STAGE,
    /** Spin speed added per stage, as a percentage. */
    var rotationPerStagePercent: Float = DEFAULT_ROTATION_PER_STAGE_PERCENT,

    // ---- Spawning ----
    var startConcurrency: Int = DEFAULT_START_CONCURRENCY,
    var maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    var spawnGapMs: Long = DEFAULT_SPAWN_GAP_MS,

    // ---- Scoring & health ----
    var startHealth: Int = DEFAULT_START_HEALTH,
    /** Deviation at or below this counts as a PERFECT cut. */
    var perfectThreshold: Float = DEFAULT_PERFECT_THRESHOLD,
    /**
     * Deviation at or below this counts as a GREAT cut and keeps a good streak
     * alive - 5 means a 45/55 split or better. Kept below the 60/40 band so that
     * band always has room to exist.
     */
    var greatThreshold: Float = DEFAULT_GREAT_THRESHOLD,
    /** Points lost per point of deviation, from a 100-point perfect cut. */
    var scoreMissWeight: Float = DEFAULT_SCORE_MISS_WEIGHT,
    /** Extra score, as a percentage, for a cut inside the great window. Scales to full at a perfect. */
    var greatBonusPercent: Float = DEFAULT_GREAT_BONUS_PERCENT,
    /** Health lost by a 60/40 cut. The curve below scales every other cut off this anchor. */
    var healthLossAtSixtyForty: Float = DEFAULT_HEALTH_LOSS_AT_SIXTY_FORTY,
    /**
     * Exponent on the health penalty. 1 is linear; above that a bad cut hurts
     * disproportionately more than a near miss.
     */
    var healthLossCurve: Float = DEFAULT_HEALTH_LOSS_CURVE,
    /** Perfect cuts heal. */
    var perfectRestoresHealth: Boolean = DEFAULT_PERFECT_RESTORES_HEALTH,
    /**
     * Health restored per perfect in a row: the first heals this much, the second
     * twice this, and so on, so a ten-perfect run refills a full bar.
     */
    var perfectHealPerStreak: Float = DEFAULT_PERFECT_HEAL_PER_STREAK,
    /** Score bonus per consecutive great-or-better cut, as a percentage. */
    var comboBonusPercent: Float = DEFAULT_COMBO_BONUS_PERCENT,
    /** Score bonus per consecutive *perfect* cut - stacks on top of the good-streak bonus. */
    var perfectStreakBonusPercent: Float = DEFAULT_PERFECT_STREAK_BONUS_PERCENT,
    var maxComboMultiplier: Float = DEFAULT_MAX_COMBO_MULTIPLIER,
    /** Score cut per consecutive sloppy cut, as a percentage - a cold streak bites back. */
    var coldStreakPenaltyPercent: Float = DEFAULT_COLD_STREAK_PENALTY_PERCENT,
    /** Letting a shape fall off screen uncut ends the run. */
    var missEndsRun: Boolean = DEFAULT_MISS_ENDS_RUN,

    // ---- Feel & FX ----
    /** Dashed guide showing exactly where a perfect 50/50 cut would land. */
    var guideLineEnabled: Boolean = DEFAULT_GUIDE_LINE_ENABLED,
    var guideLineOpacity: Float = DEFAULT_GUIDE_LINE_OPACITY,
    var particlesEnabled: Boolean = DEFAULT_PARTICLES_ENABLED,
    var particleAmount: Float = DEFAULT_PARTICLE_AMOUNT,
    var cameraShakeStrength: Float = DEFAULT_CAMERA_SHAKE_STRENGTH,
    var vibrationEnabled: Boolean = DEFAULT_VIBRATION_ENABLED,
    var vibrationStrength: Float = DEFAULT_VIBRATION_STRENGTH,
    var trailThickness: Float = DEFAULT_TRAIL_THICKNESS,
    /** Size of the floating score text after a cut. */
    var popupTextScale: Float = DEFAULT_POPUP_TEXT_SCALE,
    /** How far the launch band creeps in from the sides each stage. */
    var launchCentreCreep: Float = DEFAULT_LAUNCH_CENTRE_CREEP,
    /** How fast the background embers drift. 0 freezes them. */
    var backgroundMotion: Float = DEFAULT_BACKGROUND_MOTION,
    /** How many embers float in the background. */
    var emberDensity: Float = DEFAULT_EMBER_DENSITY,
    /** How strongly the embers glow. */
    var emberBrightness: Float = DEFAULT_EMBER_BRIGHTNESS,
    /** How big each ember pixel is. */
    var emberSize: Float = DEFAULT_EMBER_SIZE,
    /** Full-screen colour flash on a great or perfect cut. */
    var screenFlashStrength: Float = DEFAULT_SCREEN_FLASH_STRENGTH,

    // ---- Slow motion ----
    /** Time crawls for a moment on a perfect cut. */
    var slowMoOnPerfect: Boolean = DEFAULT_SLOW_MO_ON_PERFECT,
    /** How far time slows: 0.2 means one-fifth speed. */
    var slowMoIntensity: Float = DEFAULT_SLOW_MO_INTENSITY,
    /** Seconds a perfect-cut slow motion lasts before easing back. */
    var slowMoDuration: Float = DEFAULT_SLOW_MO_DURATION,
    /** Drop into slow motion with a countdown when health gets critical. */
    var lowHealthSlowMo: Boolean = DEFAULT_LOW_HEALTH_SLOW_MO,
    /** Health points at or below which the critical warning starts flashing. */
    var lowHealthAt: Int = DEFAULT_LOW_HEALTH_AT,

    // ---- Sound ----
    /** Whether the game makes any noise at all. */
    var soundEnabled: Boolean = DEFAULT_SOUND_ENABLED,
    /** How loud it is. */
    var soundVolume: Float = DEFAULT_SOUND_VOLUME,
    /** How hard the neon outline burns around each shape. 0 turns it off. */
    var neonGlow: Float = DEFAULT_NEON_GLOW,
    /** Whether the bass loop plays under a run. */
    var musicEnabled: Boolean = DEFAULT_MUSIC_ENABLED,
    /** How loud that loop is. Held well under the effects by default. */
    var musicVolume: Float = DEFAULT_MUSIC_VOLUME,

    // ---- Ads ----
    /** Whether dying offers a watch-an-ad-to-carry-on card at all. */
    var continuesEnabled: Boolean = DEFAULT_CONTINUES_ENABLED,
    /** Cap on continues in one run. 0 means no cap - every death offers one. */
    var continuesPerRun: Int = DEFAULT_CONTINUES_PER_RUN,
    /** Every Nth game of a session needs an ad before it will start. 0 turns it off. */
    var adGateEvery: Int = DEFAULT_AD_GATE_EVERY,
    /** Health a revived run comes back with, as a fraction of the full bar. */
    var continueHealthFraction: Float = DEFAULT_CONTINUE_HEALTH_FRACTION
) {
    fun saveTo(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat("size_scale", sizeScale)
            .putFloat("flight_height", flightHeight)
            .putFloat("gravity_scale", gravityScale)
            .putFloat("rotation_scale", rotationScale)
            .putFloat("wall_strength", wallStrength)
            .putInt("stage_score_interval", stageScoreInterval)
            .putInt("starting_shape_count", startingShapeCount)
            .putInt("shapes_per_stage", shapesPerStage)
            .putInt("concurrency_per_stage", concurrencyPerStage)
            .putFloat("rotation_per_stage_percent", rotationPerStagePercent)
            .putInt("start_concurrency", startConcurrency)
            .putInt("max_concurrency", maxConcurrency)
            .putLong("spawn_gap_ms", spawnGapMs)
            .putInt("start_health", startHealth)
            .putFloat("perfect_threshold", perfectThreshold)
            .putFloat("great_threshold", greatThreshold)
            .putFloat("score_miss_weight", scoreMissWeight)
            .putFloat("great_bonus_percent", greatBonusPercent)
            .putFloat("health_loss_at_sixty_forty", healthLossAtSixtyForty)
            .putFloat("health_loss_curve", healthLossCurve)
            .putBoolean("perfect_restores_health", perfectRestoresHealth)
            .putFloat("perfect_heal_per_streak", perfectHealPerStreak)
            .putFloat("perfect_streak_bonus_percent", perfectStreakBonusPercent)
            .putFloat("combo_bonus_percent", comboBonusPercent)
            .putFloat("max_combo_multiplier", maxComboMultiplier)
            .putFloat("cold_streak_penalty_percent", coldStreakPenaltyPercent)
            .putBoolean("miss_ends_run", missEndsRun)
            .putBoolean("guide_line_enabled", guideLineEnabled)
            .putFloat("guide_line_opacity", guideLineOpacity)
            .putBoolean("particles_enabled", particlesEnabled)
            .putFloat("particle_amount", particleAmount)
            .putFloat("camera_shake_strength", cameraShakeStrength)
            .putBoolean("vibration_enabled", vibrationEnabled)
            .putFloat("vibration_strength", vibrationStrength)
            .putFloat("trail_thickness", trailThickness)
            .putFloat("popup_text_scale", popupTextScale)
            .putFloat("launch_centre_creep", launchCentreCreep)
            .putFloat("background_motion", backgroundMotion)
            .putFloat("ember_density", emberDensity)
            .putFloat("ember_brightness", emberBrightness)
            .putFloat("ember_size", emberSize)
            .putFloat("screen_flash_strength", screenFlashStrength)
            .putBoolean("slow_mo_on_perfect", slowMoOnPerfect)
            .putFloat("slow_mo_intensity", slowMoIntensity)
            .putFloat("slow_mo_duration", slowMoDuration)
            .putBoolean("low_health_slow_mo", lowHealthSlowMo)
            .putInt("low_health_at", lowHealthAt)
            .putBoolean("sound_enabled", soundEnabled)
            .putFloat("sound_volume", soundVolume)
            .putFloat("neon_glow", neonGlow)
            .putBoolean("music_enabled", musicEnabled)
            .putFloat("music_volume", musicVolume)
            .putBoolean("continues_enabled", continuesEnabled)
            .putInt("continues_per_run", continuesPerRun)
            .putInt("ad_gate_every", adGateEvery)
            .putFloat("continue_health_fraction", continueHealthFraction)
            .apply()
    }

    companion object {
        // Defaults hand-tuned in-app and reported back by the player.
        const val DEFAULT_SIZE_SCALE = 1.9f
        const val DEFAULT_FLIGHT_HEIGHT = 0.58f
        const val DEFAULT_GRAVITY_SCALE = 0.8f
        const val DEFAULT_ROTATION_SCALE = 0.6f
        const val DEFAULT_WALL_STRENGTH = 0.6f
        const val DEFAULT_STAGE_SCORE_INTERVAL = 1530
        const val DEFAULT_STARTING_SHAPE_COUNT = 4
        const val DEFAULT_SHAPES_PER_STAGE = 2
        const val DEFAULT_CONCURRENCY_PER_STAGE = 1
        const val DEFAULT_ROTATION_PER_STAGE_PERCENT = 10f
        const val DEFAULT_START_CONCURRENCY = 1
        const val DEFAULT_MAX_CONCURRENCY = 5
        const val DEFAULT_SPAWN_GAP_MS = 1000L
        const val DEFAULT_START_HEALTH = 100
        const val DEFAULT_PERFECT_THRESHOLD = 1.5f
        const val DEFAULT_GREAT_THRESHOLD = 5f
        const val DEFAULT_SCORE_MISS_WEIGHT = 1.0f
        const val DEFAULT_GREAT_BONUS_PERCENT = 25f
        const val DEFAULT_HEALTH_LOSS_AT_SIXTY_FORTY = 2f
        const val DEFAULT_HEALTH_LOSS_CURVE = 1.8f
        const val DEFAULT_PERFECT_RESTORES_HEALTH = true
        const val DEFAULT_PERFECT_HEAL_PER_STREAK = 10f
        const val DEFAULT_PERFECT_STREAK_BONUS_PERCENT = 30f
        const val DEFAULT_COMBO_BONUS_PERCENT = 15f
        const val DEFAULT_MAX_COMBO_MULTIPLIER = 4f
        const val DEFAULT_COLD_STREAK_PENALTY_PERCENT = 20f
        const val DEFAULT_MISS_ENDS_RUN = true
        const val DEFAULT_GUIDE_LINE_ENABLED = false
        const val DEFAULT_GUIDE_LINE_OPACITY = 0.32f
        const val DEFAULT_PARTICLES_ENABLED = true
        const val DEFAULT_PARTICLE_AMOUNT = 0.5f
        const val DEFAULT_CAMERA_SHAKE_STRENGTH = 0.7f
        const val DEFAULT_VIBRATION_ENABLED = true
        const val DEFAULT_VIBRATION_STRENGTH = 1.0f
        const val DEFAULT_TRAIL_THICKNESS = 1.0f
        const val DEFAULT_POPUP_TEXT_SCALE = 1.5f
        const val DEFAULT_LAUNCH_CENTRE_CREEP = 0.05f
        const val DEFAULT_BACKGROUND_MOTION = 2.0f
        const val DEFAULT_EMBER_DENSITY = 0.3f
        const val DEFAULT_EMBER_BRIGHTNESS = 0.4f
        const val DEFAULT_EMBER_SIZE = 0.4f
        const val DEFAULT_SCREEN_FLASH_STRENGTH = 0.6f
        const val DEFAULT_SLOW_MO_ON_PERFECT = true
        const val DEFAULT_SLOW_MO_INTENSITY = 0.07f
        const val DEFAULT_SLOW_MO_DURATION = 2.6f
        const val DEFAULT_LOW_HEALTH_SLOW_MO = true
        const val DEFAULT_LOW_HEALTH_AT = 20
        const val DEFAULT_SOUND_ENABLED = true
        const val DEFAULT_SOUND_VOLUME = 0.8f
        const val DEFAULT_NEON_GLOW = 1.0f
        const val DEFAULT_MUSIC_ENABLED = true
        const val DEFAULT_MUSIC_VOLUME = 0.45f
        const val MIN_MUSIC_VOLUME = 0f
        const val MAX_MUSIC_VOLUME = 1f
        const val MIN_SOUND_VOLUME = 0f
        const val MAX_SOUND_VOLUME = 1f
        const val MIN_NEON_GLOW = 0f
        const val MAX_NEON_GLOW = 2.5f

        const val DEFAULT_CONTINUES_ENABLED = true

        /** No cap: a run can be bought back on every death. */
        const val DEFAULT_CONTINUES_PER_RUN = 0
        const val DEFAULT_AD_GATE_EVERY = 10
        const val DEFAULT_CONTINUE_HEALTH_FRACTION = 1.0f

        const val MIN_CONTINUES_PER_RUN = 0
        const val MAX_CONTINUES_PER_RUN = 5
        const val MIN_AD_GATE_EVERY = 0
        const val MAX_AD_GATE_EVERY = 25
        const val MIN_CONTINUE_HEALTH_FRACTION = 0.25f
        const val MAX_CONTINUE_HEALTH_FRACTION = 1.0f

        const val MIN_SIZE_SCALE = 0.5f
        const val MAX_SIZE_SCALE = 3.0f
        const val MIN_FLIGHT_HEIGHT = 0.25f
        const val MAX_FLIGHT_HEIGHT = 0.9f
        const val MIN_GRAVITY_SCALE = 0.4f
        const val MAX_GRAVITY_SCALE = 2.5f
        const val MIN_ROTATION_SCALE = 0.0f
        const val MAX_ROTATION_SCALE = 3.0f
        const val MIN_WALL_STRENGTH = 0f
        const val MAX_WALL_STRENGTH = 1f
        const val MIN_STAGE_SCORE_INTERVAL = 500
        const val MAX_STAGE_SCORE_INTERVAL = 15000
        const val MIN_STARTING_SHAPE_COUNT = 1
        const val MAX_STARTING_SHAPE_COUNT = 12
        const val MIN_SHAPES_PER_STAGE = 0
        const val MAX_SHAPES_PER_STAGE = 4
        const val MIN_CONCURRENCY_PER_STAGE = 0
        const val MAX_CONCURRENCY_PER_STAGE = 3
        const val MIN_ROTATION_PER_STAGE_PERCENT = 0f
        const val MAX_ROTATION_PER_STAGE_PERCENT = 50f
        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY_LIMIT = 8
        const val MIN_SPAWN_GAP_MS = 200L
        const val MAX_SPAWN_GAP_MS = 3000L
        const val MIN_START_HEALTH = 20
        const val MAX_START_HEALTH = 200
        const val MIN_PERFECT_THRESHOLD = 0.2f
        const val MAX_PERFECT_THRESHOLD = 10f
        const val MIN_GREAT_THRESHOLD = 2f
        const val MAX_GREAT_THRESHOLD = 9f
        const val MIN_SCORE_MISS_WEIGHT = 0f
        const val MAX_SCORE_MISS_WEIGHT = 3f
        const val MIN_GREAT_BONUS_PERCENT = 0f
        const val MAX_GREAT_BONUS_PERCENT = 150f
        const val MIN_HEALTH_LOSS_AT_SIXTY_FORTY = 0f
        const val MAX_HEALTH_LOSS_AT_SIXTY_FORTY = 15f
        const val MIN_HEALTH_LOSS_CURVE = 1f
        const val MAX_HEALTH_LOSS_CURVE = 3.5f
        const val MIN_COMBO_BONUS_PERCENT = 0f
        const val MAX_COMBO_BONUS_PERCENT = 50f
        const val MIN_MAX_COMBO_MULTIPLIER = 1f
        const val MAX_MAX_COMBO_MULTIPLIER = 6f
        const val MIN_COLD_STREAK_PENALTY_PERCENT = 0f
        const val MAX_COLD_STREAK_PENALTY_PERCENT = 60f
        const val MIN_GUIDE_LINE_OPACITY = 0.1f
        const val MAX_GUIDE_LINE_OPACITY = 1f
        const val MIN_PARTICLE_AMOUNT = 0.2f
        const val MAX_PARTICLE_AMOUNT = 3f
        const val MIN_CAMERA_SHAKE_STRENGTH = 0f
        const val MAX_CAMERA_SHAKE_STRENGTH = 2f
        const val MIN_VIBRATION_STRENGTH = 0.1f
        const val MAX_VIBRATION_STRENGTH = 1f
        const val MIN_TRAIL_THICKNESS = 0.4f
        const val MAX_TRAIL_THICKNESS = 2.5f
        const val MIN_POPUP_TEXT_SCALE = 0.6f
        const val MAX_POPUP_TEXT_SCALE = 3f
        const val MIN_LAUNCH_CENTRE_CREEP = 0f
        const val MAX_LAUNCH_CENTRE_CREEP = 0.12f
        const val MIN_BACKGROUND_MOTION = 0f
        const val MAX_BACKGROUND_MOTION = 2.5f
        const val MIN_EMBER_DENSITY = 0f
        const val MAX_EMBER_DENSITY = 3f
        const val MIN_EMBER_BRIGHTNESS = 0f
        const val MAX_EMBER_BRIGHTNESS = 2.5f
        const val MIN_EMBER_SIZE = 0.4f
        const val MAX_EMBER_SIZE = 3f
        const val MIN_SCREEN_FLASH_STRENGTH = 0f
        const val MAX_SCREEN_FLASH_STRENGTH = 2f
        const val MIN_SLOW_MO_INTENSITY = 0.02f
        const val MAX_SLOW_MO_INTENSITY = 0.9f
        const val MIN_SLOW_MO_DURATION = 0.3f
        const val MAX_SLOW_MO_DURATION = 7f
        const val MIN_LOW_HEALTH_AT = 5
        const val MAX_LOW_HEALTH_AT = 80
        const val MIN_PERFECT_HEAL_PER_STREAK = 0f
        const val MAX_PERFECT_HEAL_PER_STREAK = 40f
        const val MIN_PERFECT_STREAK_BONUS_PERCENT = 0f
        const val MAX_PERFECT_STREAK_BONUS_PERCENT = 150f

        private const val PREFS_NAME = "half_measures_settings"

        fun load(context: Context): GameSettings {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return GameSettings(
                sizeScale = p.getFloat("size_scale", DEFAULT_SIZE_SCALE),
                flightHeight = p.getFloat("flight_height", DEFAULT_FLIGHT_HEIGHT),
                gravityScale = p.getFloat("gravity_scale", DEFAULT_GRAVITY_SCALE),
                rotationScale = p.getFloat("rotation_scale", DEFAULT_ROTATION_SCALE),
                wallStrength = p.getFloat("wall_strength", DEFAULT_WALL_STRENGTH),
                stageScoreInterval = p.getInt("stage_score_interval", DEFAULT_STAGE_SCORE_INTERVAL),
                startingShapeCount = p.getInt("starting_shape_count", DEFAULT_STARTING_SHAPE_COUNT),
                shapesPerStage = p.getInt("shapes_per_stage", DEFAULT_SHAPES_PER_STAGE),
                concurrencyPerStage = p.getInt("concurrency_per_stage", DEFAULT_CONCURRENCY_PER_STAGE),
                rotationPerStagePercent = p.getFloat("rotation_per_stage_percent", DEFAULT_ROTATION_PER_STAGE_PERCENT),
                startConcurrency = p.getInt("start_concurrency", DEFAULT_START_CONCURRENCY),
                maxConcurrency = p.getInt("max_concurrency", DEFAULT_MAX_CONCURRENCY),
                spawnGapMs = p.getLong("spawn_gap_ms", DEFAULT_SPAWN_GAP_MS),
                startHealth = p.getInt("start_health", DEFAULT_START_HEALTH),
                perfectThreshold = p.getFloat("perfect_threshold", DEFAULT_PERFECT_THRESHOLD),
                greatThreshold = p.getFloat("great_threshold", DEFAULT_GREAT_THRESHOLD),
                scoreMissWeight = p.getFloat("score_miss_weight", DEFAULT_SCORE_MISS_WEIGHT),
                greatBonusPercent = p.getFloat("great_bonus_percent", DEFAULT_GREAT_BONUS_PERCENT),
                healthLossAtSixtyForty = p.getFloat("health_loss_at_sixty_forty", DEFAULT_HEALTH_LOSS_AT_SIXTY_FORTY),
                healthLossCurve = p.getFloat("health_loss_curve", DEFAULT_HEALTH_LOSS_CURVE),
                perfectRestoresHealth = p.getBoolean("perfect_restores_health", DEFAULT_PERFECT_RESTORES_HEALTH),
                perfectHealPerStreak = p.getFloat("perfect_heal_per_streak", DEFAULT_PERFECT_HEAL_PER_STREAK),
                perfectStreakBonusPercent = p.getFloat("perfect_streak_bonus_percent", DEFAULT_PERFECT_STREAK_BONUS_PERCENT),
                comboBonusPercent = p.getFloat("combo_bonus_percent", DEFAULT_COMBO_BONUS_PERCENT),
                maxComboMultiplier = p.getFloat("max_combo_multiplier", DEFAULT_MAX_COMBO_MULTIPLIER),
                coldStreakPenaltyPercent = p.getFloat("cold_streak_penalty_percent", DEFAULT_COLD_STREAK_PENALTY_PERCENT),
                missEndsRun = p.getBoolean("miss_ends_run", DEFAULT_MISS_ENDS_RUN),
                guideLineEnabled = p.getBoolean("guide_line_enabled", DEFAULT_GUIDE_LINE_ENABLED),
                guideLineOpacity = p.getFloat("guide_line_opacity", DEFAULT_GUIDE_LINE_OPACITY),
                particlesEnabled = p.getBoolean("particles_enabled", DEFAULT_PARTICLES_ENABLED),
                particleAmount = p.getFloat("particle_amount", DEFAULT_PARTICLE_AMOUNT),
                cameraShakeStrength = p.getFloat("camera_shake_strength", DEFAULT_CAMERA_SHAKE_STRENGTH),
                vibrationEnabled = p.getBoolean("vibration_enabled", DEFAULT_VIBRATION_ENABLED),
                vibrationStrength = p.getFloat("vibration_strength", DEFAULT_VIBRATION_STRENGTH),
                trailThickness = p.getFloat("trail_thickness", DEFAULT_TRAIL_THICKNESS),
                popupTextScale = p.getFloat("popup_text_scale", DEFAULT_POPUP_TEXT_SCALE),
                launchCentreCreep = p.getFloat("launch_centre_creep", DEFAULT_LAUNCH_CENTRE_CREEP),
                backgroundMotion = p.getFloat("background_motion", DEFAULT_BACKGROUND_MOTION),
                emberDensity = p.getFloat("ember_density", DEFAULT_EMBER_DENSITY),
                emberBrightness = p.getFloat("ember_brightness", DEFAULT_EMBER_BRIGHTNESS),
                emberSize = p.getFloat("ember_size", DEFAULT_EMBER_SIZE),
                screenFlashStrength = p.getFloat("screen_flash_strength", DEFAULT_SCREEN_FLASH_STRENGTH),
                slowMoOnPerfect = p.getBoolean("slow_mo_on_perfect", DEFAULT_SLOW_MO_ON_PERFECT),
                slowMoIntensity = p.getFloat("slow_mo_intensity", DEFAULT_SLOW_MO_INTENSITY),
                slowMoDuration = p.getFloat("slow_mo_duration", DEFAULT_SLOW_MO_DURATION),
                lowHealthSlowMo = p.getBoolean("low_health_slow_mo", DEFAULT_LOW_HEALTH_SLOW_MO),
                lowHealthAt = p.getInt("low_health_at", DEFAULT_LOW_HEALTH_AT),
                soundEnabled = p.getBoolean("sound_enabled", DEFAULT_SOUND_ENABLED),
                soundVolume = p.getFloat("sound_volume", DEFAULT_SOUND_VOLUME),
                neonGlow = p.getFloat("neon_glow", DEFAULT_NEON_GLOW),
                musicEnabled = p.getBoolean("music_enabled", DEFAULT_MUSIC_ENABLED),
                musicVolume = p.getFloat("music_volume", DEFAULT_MUSIC_VOLUME),
                continuesEnabled = p.getBoolean("continues_enabled", DEFAULT_CONTINUES_ENABLED),
                continuesPerRun = p.getInt("continues_per_run", DEFAULT_CONTINUES_PER_RUN),
                adGateEvery = p.getInt("ad_gate_every", DEFAULT_AD_GATE_EVERY),
                continueHealthFraction =
                    p.getFloat("continue_health_fraction", DEFAULT_CONTINUE_HEALTH_FRACTION)
            )
        }
    }
}
