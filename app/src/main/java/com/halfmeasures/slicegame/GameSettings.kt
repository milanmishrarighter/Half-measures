package com.halfmeasures.slicegame

import android.content.Context

/**
 * Player-adjustable tuning, persisted to SharedPreferences. Defaults reflect the
 * "slower and bigger than the very first version" baseline; sliders in
 * [SettingsActivity] scale further from there.
 */
data class GameSettings(
    /** Multiplies shape radius. 1.0 = default size. */
    var sizeScale: Float = DEFAULT_SIZE_SCALE,
    /** Multiplies shape flight velocity (and, squared, gravity) for true slow motion. 1.0 = default speed. */
    var speedScale: Float = DEFAULT_SPEED_SCALE,
    /** Milliseconds between spawns at the very start of a run (higher = shapes appear less often). */
    var spawnIntervalStartMs: Long = DEFAULT_SPAWN_INTERVAL_START_MS,
    /** Score at which spawn interval ramps all the way down to its fastest floor. Higher = slower ramp-up. */
    var difficultyRampScore: Int = DEFAULT_DIFFICULTY_RAMP_SCORE
) {
    /** Fastest the spawn interval ever gets, once fully ramped up. */
    val spawnIntervalFloorMs: Long
        get() = (spawnIntervalStartMs * 0.35f).toLong().coerceAtLeast(MIN_SPAWN_INTERVAL_FLOOR_MS)

    fun saveTo(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SIZE_SCALE, sizeScale)
            .putFloat(KEY_SPEED_SCALE, speedScale)
            .putLong(KEY_SPAWN_INTERVAL_START_MS, spawnIntervalStartMs)
            .putInt(KEY_DIFFICULTY_RAMP_SCORE, difficultyRampScore)
            .apply()
    }

    companion object {
        const val DEFAULT_SIZE_SCALE = 1.5f
        const val DEFAULT_SPEED_SCALE = 0.5f
        const val DEFAULT_SPAWN_INTERVAL_START_MS = 2400L
        const val DEFAULT_DIFFICULTY_RAMP_SCORE = 700

        const val MIN_SIZE_SCALE = 0.5f
        const val MAX_SIZE_SCALE = 3.0f
        const val MIN_SPEED_SCALE = 0.2f
        const val MAX_SPEED_SCALE = 1.5f
        const val MIN_SPAWN_INTERVAL_START_MS = 500L
        const val MAX_SPAWN_INTERVAL_START_MS = 4000L
        const val MIN_DIFFICULTY_RAMP_SCORE = 100
        const val MAX_DIFFICULTY_RAMP_SCORE = 3000
        const val MIN_SPAWN_INTERVAL_FLOOR_MS = 300L

        private const val PREFS_NAME = "half_measures_settings"
        private const val KEY_SIZE_SCALE = "size_scale"
        private const val KEY_SPEED_SCALE = "speed_scale"
        private const val KEY_SPAWN_INTERVAL_START_MS = "spawn_interval_start_ms"
        private const val KEY_DIFFICULTY_RAMP_SCORE = "difficulty_ramp_score"

        fun load(context: Context): GameSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return GameSettings(
                sizeScale = prefs.getFloat(KEY_SIZE_SCALE, DEFAULT_SIZE_SCALE),
                speedScale = prefs.getFloat(KEY_SPEED_SCALE, DEFAULT_SPEED_SCALE),
                spawnIntervalStartMs = prefs.getLong(KEY_SPAWN_INTERVAL_START_MS, DEFAULT_SPAWN_INTERVAL_START_MS),
                difficultyRampScore = prefs.getInt(KEY_DIFFICULTY_RAMP_SCORE, DEFAULT_DIFFICULTY_RAMP_SCORE)
            )
        }
    }
}
