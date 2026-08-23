package com.halfmeasures.slicegame

import android.content.Context

/**
 * Player-adjustable tuning, persisted to SharedPreferences.
 *
 * Spawning works as a concurrency ladder: at most [startConcurrency] shapes are
 * allowed on screen at once at the start of a run. Every [concurrencyStepScore]
 * points, that cap goes up by one (capped at [maxConcurrency]). Whenever fewer
 * shapes are alive than the current cap, a new one spawns every [spawnGapMs].
 * That reproduces "1 shape alone, then 2 with a 1s gap, then 3 with a 1s gap..."
 * directly from these numbers.
 */
data class GameSettings(
    /** Multiplies shape radius. 1.0 = default size. */
    var sizeScale: Float = DEFAULT_SIZE_SCALE,
    /** Multiplies launch velocity (both the upward speed and horizontal drift). */
    var speedScale: Float = DEFAULT_SPEED_SCALE,
    /** Multiplies gravity directly: higher falls faster AND reaches a lower peak height. */
    var gravityScale: Float = DEFAULT_GRAVITY_SCALE,
    /** Multiplies how fast shapes spin. */
    var rotationScale: Float = DEFAULT_ROTATION_SCALE,
    /** Max shapes allowed on screen at once, at the very start of a run (score 0). */
    var startConcurrency: Int = DEFAULT_START_CONCURRENCY,
    /** Score needed to raise the concurrency cap by one. */
    var concurrencyStepScore: Int = DEFAULT_CONCURRENCY_STEP_SCORE,
    /** Hard ceiling on how many shapes can ever be on screen at once. */
    var maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    /** Milliseconds between spawns whenever fewer shapes are alive than the current cap. */
    var spawnGapMs: Long = DEFAULT_SPAWN_GAP_MS
) {
    fun saveTo(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SIZE_SCALE, sizeScale)
            .putFloat(KEY_SPEED_SCALE, speedScale)
            .putFloat(KEY_GRAVITY_SCALE, gravityScale)
            .putFloat(KEY_ROTATION_SCALE, rotationScale)
            .putInt(KEY_START_CONCURRENCY, startConcurrency)
            .putInt(KEY_CONCURRENCY_STEP_SCORE, concurrencyStepScore)
            .putInt(KEY_MAX_CONCURRENCY, maxConcurrency)
            .putLong(KEY_SPAWN_GAP_MS, spawnGapMs)
            .apply()
    }

    companion object {
        const val DEFAULT_SIZE_SCALE = 1.5f
        const val DEFAULT_SPEED_SCALE = 1.0f
        const val DEFAULT_GRAVITY_SCALE = 1.0f
        const val DEFAULT_ROTATION_SCALE = 1.5f
        const val DEFAULT_START_CONCURRENCY = 1
        const val DEFAULT_CONCURRENCY_STEP_SCORE = 400
        const val DEFAULT_MAX_CONCURRENCY = 4
        const val DEFAULT_SPAWN_GAP_MS = 1000L

        const val MIN_SIZE_SCALE = 0.5f
        const val MAX_SIZE_SCALE = 3.0f
        const val MIN_SPEED_SCALE = 0.3f
        const val MAX_SPEED_SCALE = 2.5f
        const val MIN_GRAVITY_SCALE = 0.4f
        const val MAX_GRAVITY_SCALE = 2.5f
        const val MIN_ROTATION_SCALE = 0.0f
        const val MAX_ROTATION_SCALE = 4.0f
        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY_LIMIT = 8
        const val MIN_CONCURRENCY_STEP_SCORE = 50
        const val MAX_CONCURRENCY_STEP_SCORE = 3000
        const val MIN_SPAWN_GAP_MS = 200L
        const val MAX_SPAWN_GAP_MS = 3000L

        private const val PREFS_NAME = "half_measures_settings"
        private const val KEY_SIZE_SCALE = "size_scale"
        private const val KEY_SPEED_SCALE = "speed_scale"
        private const val KEY_GRAVITY_SCALE = "gravity_scale"
        private const val KEY_ROTATION_SCALE = "rotation_scale"
        private const val KEY_START_CONCURRENCY = "start_concurrency"
        private const val KEY_CONCURRENCY_STEP_SCORE = "concurrency_step_score"
        private const val KEY_MAX_CONCURRENCY = "max_concurrency"
        private const val KEY_SPAWN_GAP_MS = "spawn_gap_ms"

        fun load(context: Context): GameSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return GameSettings(
                sizeScale = prefs.getFloat(KEY_SIZE_SCALE, DEFAULT_SIZE_SCALE),
                speedScale = prefs.getFloat(KEY_SPEED_SCALE, DEFAULT_SPEED_SCALE),
                gravityScale = prefs.getFloat(KEY_GRAVITY_SCALE, DEFAULT_GRAVITY_SCALE),
                rotationScale = prefs.getFloat(KEY_ROTATION_SCALE, DEFAULT_ROTATION_SCALE),
                startConcurrency = prefs.getInt(KEY_START_CONCURRENCY, DEFAULT_START_CONCURRENCY),
                concurrencyStepScore = prefs.getInt(KEY_CONCURRENCY_STEP_SCORE, DEFAULT_CONCURRENCY_STEP_SCORE),
                maxConcurrency = prefs.getInt(KEY_MAX_CONCURRENCY, DEFAULT_MAX_CONCURRENCY),
                spawnGapMs = prefs.getLong(KEY_SPAWN_GAP_MS, DEFAULT_SPAWN_GAP_MS)
            )
        }
    }
}
