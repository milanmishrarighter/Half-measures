package com.halfmeasures.slicegame

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private lateinit var ads: RewardedAds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ads = RewardedAds(this)
        ads.initialize()

        gameView = GameView(this).apply {
            onOpenSettings = {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
            onOpenInstructions = {
                startActivity(Intent(this@MainActivity, InstructionsActivity::class.java))
            }
            isRewardedAdReady = { ads.isReady() }
            onWatchRewardedAd = { onEarned, onDeclined -> ads.show(onEarned, onDeclined) }
        }
        setContentView(gameView)
        hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        // Backgrounding mid-run should not cost the player the run. An ad going
        // full-screen also lands here, but by then the game is already parked in a
        // waiting state, so there is nothing playing to pause.
        gameView.pauseIfPlaying()
    }

    override fun onResume() {
        super.onResume()
        gameView.refreshSettings()
        gameView.checkStrandedAd()
    }

    override fun onDestroy() {
        super.onDestroy()
        ads.destroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }
}
