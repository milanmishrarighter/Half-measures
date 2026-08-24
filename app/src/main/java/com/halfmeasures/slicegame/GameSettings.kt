package com.halfmeasures.slicegame

import android.content.Context

/**
 * Every tunable knob in the game, persisted to SharedPreferences so the whole
 * feel of the game can be dialled in from the in-app Settings screen.
 *
 * Spawning works as a concurrency ladder: at most [startConcurrency] shapes are
 * allowed on screen at once at the start of a run. Every [concurrencyStepScore]
 * points that cap goes up by one (never past [maxConcurrency]). Whenever fewer
 * shapes are alive than the current cap, a new one spawns every [spawnGapMs].
 *
 * Scoring works off "deviation": half the area imbalance, i.e. how many
 * percentage points the bigger piece sits above a perfect 50. A 60/40 cut has a
 * deviation of 10, a perfect cut 0, a total whiff 50.
 */
data class GameSettings(
    // ---- Shapes ----
    var sizeScale: Float = DEFAULT_SIZE_SCALE,
    var speedScale: Float = DEFAULT_SPEED_SCALE,
    var gravityScale: Float = DEFAULT_GRAVITY_SCALE,
    var rotationScale: Float = DEFAULT_ROTATION_SCALE,
    /** 0 = sides are transparent and shapes drift off; 1 = fully elastic bounce. */
    var wallStrength: Float = DEFAULT_WALL_STRENGTH,
    /** Multiplies every shape's unlock score. Lower = harder shapes arrive sooner. */
    var shapeUnlockPace: Float = DEFAULT_SHAPE_UNLOCK_PACE,

    // ---- Spawning ----
    var startConcurrency: Int = DEFAULT_START_CONCURRENCY,
    var concurrencyStepScore: Int = DEFAULT_CONCURRENCY_STEP_SCORE,
    var maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    var spawnGapMs: Long = DEFAULT_SPAWN_GAP_MS,

    // ---- Scoring & health ----
    var startHealth: Int = DEFAULT_START_HEALTH,
    /** Deviation at or below this counts as a PERFECT cut. */
    var perfectThreshold: Float = DEFAULT_PERFECT_THRESHOLD,
    /** Points lost per point of deviation, from a 100-point perfect cut. */
    var scoreMissWeight: Float = DEFAULT_SCORE_MISS_WEIGHT,
    /** Health lost per point of deviation. 0.2 means a 60/40 cut costs 2. */
    var healthLossPerDeviation: Float = DEFAULT_HEALTH_LOSS_PER_DEVIATION,
    /** A perfect cut refills the health bar. */
    var perfectRestoresHealth: Boolean = DEFAULT_PERFECT_RESTORES_HEALTH,
    /** Extra score multiplier gained per consecutive perfect cut, as a percentage. */
    var comboBonusPercent: Float = DEFAULT_COMBO_BONUS_PERCENT,
    var maxComboMultiplier: Float = DEFAULT_MAX_COMBO_MULTIPLIER,
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
    var trailThickness: Float = DEFAULT_TRAIL_THICKNESS
) {
    fun saveTo(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat("size_scale", sizeScale)
            .putFloat("speed_scale", speedScale)
            .putFloat("gravity_scale", gravityScale)
            .putFloat("rotation_scale", rotationScale)
            .putFloat("wall_strength", wallStrength)
            .putFloat("shape_unlock_pace", shapeUnlockPace)
            .putInt("start_concurrency", startConcurrency)
            .putInt("concurrency_step_score", concurrencyStepScore)
            .putInt("max_concurrency", maxConcurrency)
            .putLong("spawn_gap_ms", spawnGapMs)
            .putInt("start_health", startHealth)
            .putFloat("perfect_threshold", perfectThreshold)
            .putFloat("score_miss_weight", scoreMissWeight)
            .putFloat("health_loss_per_deviation", healthLossPerDeviation)
            .putBoolean("perfect_restores_health", perfectRestoresHealth)
            .putFloat("combo_bonus_percent", comboBonusPercent)
            .putFloat("max_combo_multiplier", maxComboMultiplier)
            .putBoolean("miss_ends_run", missEndsRun)
            .putBoolean("guide_line_enabled", guideLineEnabled)
            .putFloat("guide_line_opacity", guideLineOpacity)
            .putBoolean("particles_enabled", particlesEnabled)
            .putFloat("particle_amount", particleAmount)
            .putFloat("camera_shake_strength", cameraShakeStrength)
            .putBoolean("vibration_enabled", vibrationEnabled)
            .putFloat("vibration_strength", vibrationStrength)
            .putFloat("trail_thickness", trailThickness)
            .apply()
    }

    companion object {
        // Defaults hand-tuned in-app and reported back by the player.
        const val DEFAULT_SIZE_SCALE = 1.9f
        const val DEFAULT_SPEED_SCALE = 1.2f
        const val DEFAULT_GRAVITY_SCALE = 0.8f
        const val DEFAULT_ROTATION_SCALE = 1.3f
        const val DEFAULT_WALL_STRENGTH = 0.6f
        const val DEFAULT_SHAPE_UNLOCK_PACE = 1.0f
        const val DEFAULT_START_CONCURRENCY = 1
        const val DEFAULT_CONCURRENCY_STEP_SCORE = 1383
        const val DEFAULT_MAX_CONCURRENCY = 2
        const val DEFAULT_SPAWN_GAP_MS = 1400L
        const val DEFAULT_START_HEALTH = 100
        const val DEFAULT_PERFECT_THRESHOLD = 1.5f
        const val DEFAULT_SCORE_MISS_WEIGHT = 1.0f
        const val DEFAULT_HEALTH_LOSS_PER_DEVIATION = 0.2f
        const val DEFAULT_PERFECT_RESTORES_HEALTH = true
        const val DEFAULT_COMBO_BONUS_PERCENT = 10f
        const val DEFAULT_MAX_COMBO_MULTIPLIER = 3f
        const val DEFAULT_MISS_ENDS_RUN = true
        const val DEFAULT_GUIDE_LINE_ENABLED = true
        const val DEFAULT_GUIDE_LINE_OPACITY = 0.55f
        const val DEFAULT_PARTICLES_ENABLED = true
        const val DEFAULT_PARTICLE_AMOUNT = 1.0f
        const val DEFAULT_CAMERA_SHAKE_STRENGTH = 1.0f
        const val DEFAULT_VIBRATION_ENABLED = true
        const val DEFAULT_VIBRATION_STRENGTH = 1.0f
        const val DEFAULT_TRAIL_THICKNESS = 1.0f

        const val MIN_SIZE_SCALE = 0.5f
        const val MAX_SIZE_SCALE = 3.0f
        const val MIN_SPEED_SCALE = 0.3f
        const val MAX_SPEED_SCALE = 2.5f
        const val MIN_GRAVITY_SCALE = 0.4f
        const val MAX_GRAVITY_SCALE = 2.5f
        const val MIN_ROTATION_SCALE = 0.0f
        const val MAX_ROTATION_SCALE = 4.0f
        const val MIN_WALL_STRENGTH = 0f
        const val MAX_WALL_STRENGTH = 1f
        const val MIN_SHAPE_UNLOCK_PACE = 0.2f
        const val MAX_SHAPE_UNLOCK_PACE = 3.0f
        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY_LIMIT = 8
        const val MIN_CONCURRENCY_STEP_SCORE = 50
        const val MAX_CONCURRENCY_STEP_SCORE = 3000
        const val MIN_SPAWN_GAP_MS = 200L
        const val MAX_SPAWN_GAP_MS = 3000L
        const val MIN_START_HEALTH = 20
        const val MAX_START_HEALTH = 200
        const val MIN_PERFECT_THRESHOLD = 0.2f
        const val MAX_PERFECT_THRESHOLD = 10f
        const val MIN_SCORE_MISS_WEIGHT = 0f
        const val MAX_SCORE_MISS_WEIGHT = 3f
        const val MIN_HEALTH_LOSS_PER_DEVIATION = 0f
        const val MAX_HEALTH_LOSS_PER_DEVIATION = 2f
        const val MIN_COMBO_BONUS_PERCENT = 0f
        const val MAX_COMBO_BONUS_PERCENT = 50f
        const val MIN_MAX_COMBO_MULTIPLIER = 1f
        const val MAX_MAX_COMBO_MULTIPLIER = 5f
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

        private const val PREFS_NAME = "half_measures_settings"

        fun load(context: Context): GameSettings {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return GameSettings(
                sizeScale = p.getFloat("size_scale", DEFAULT_SIZE_SCALE),
                speedScale = p.getFloat("speed_scale", DEFAULT_SPEED_SCALE),
                gravityScale = p.getFloat("gravity_scale", DEFAULT_GRAVITY_SCALE),
                rotationScale = p.getFloat("rotation_scale", DEFAULT_ROTATION_SCALE),
                wallStrength = p.getFloat("wall_strength", DEFAULT_WALL_STRENGTH),
                shapeUnlockPace = p.getFloat("shape_unlock_pace", DEFAULT_SHAPE_UNLOCK_PACE),
                startConcurrency = p.getInt("start_concurrency", DEFAULT_START_CONCURRENCY),
                concurrencyStepScore = p.getInt("concurrency_step_score", DEFAULT_CONCURRENCY_STEP_SCORE),
                maxConcurrency = p.getInt("max_concurrency", DEFAULT_MAX_CONCURRENCY),
                spawnGapMs = p.getLong("spawn_gap_ms", DEFAULT_SPAWN_GAP_MS),
                startHealth = p.getInt("start_health", DEFAULT_START_HEALTH),
                perfectThreshold = p.getFloat("perfect_threshold", DEFAULT_PERFECT_THRESHOLD),
                scoreMissWeight = p.getFloat("score_miss_weight", DEFAULT_SCORE_MISS_WEIGHT),
                healthLossPerDeviation = p.getFloat("health_loss_per_deviation", DEFAULT_HEALTH_LOSS_PER_DEVIATION),
                perfectRestoresHealth = p.getBoolean("perfect_restores_health", DEFAULT_PERFECT_RESTORES_HEALTH),
                comboBonusPercent = p.getFloat("combo_bonus_percent", DEFAULT_COMBO_BONUS_PERCENT),
                maxComboMultiplier = p.getFloat("max_combo_multiplier", DEFAULT_MAX_COMBO_MULTIPLIER),
                missEndsRun = p.getBoolean("miss_ends_run", DEFAULT_MISS_ENDS_RUN),
                guideLineEnabled = p.getBoolean("guide_line_enabled", DEFAULT_GUIDE_LINE_ENABLED),
                guideLineOpacity = p.getFloat("guide_line_opacity", DEFAULT_GUIDE_LINE_OPACITY),
                particlesEnabled = p.getBoolean("particles_enabled", DEFAULT_PARTICLES_ENABLED),
                particleAmount = p.getFloat("particle_amount", DEFAULT_PARTICLE_AMOUNT),
                cameraShakeStrength = p.getFloat("camera_shake_strength", DEFAULT_CAMERA_SHAKE_STRENGTH),
                vibrationEnabled = p.getBoolean("vibration_enabled", DEFAULT_VIBRATION_ENABLED),
                vibrationStrength = p.getFloat("vibration_strength", DEFAULT_VIBRATION_STRENGTH),
                trailThickness = p.getFloat("trail_thickness", DEFAULT_TRAIL_THICKNESS)
            )
        }
    }
}
