package com.halfmeasures.slicegame

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Watches whether any of the app's own screens is in front.
 *
 * The music used to be paused by the game view when its frame loop stopped, which
 * happens on every activity change - so walking from the title screen into
 * settings silenced the track, even though the player had never left the app. The
 * count here only reaches zero when the last screen goes away, which is the one
 * moment the music should actually stop.
 *
 * The pause menu is a separate thing and still stops the music itself: a paused
 * run is meant to be silent whether or not the player then opens settings.
 */
class HalfMeasuresApp : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {

            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                if (started == 0) Sounds.of(this@HalfMeasuresApp).resumeMusic()
                started++
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) Sounds.of(this@HalfMeasuresApp).pauseMusic()
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
