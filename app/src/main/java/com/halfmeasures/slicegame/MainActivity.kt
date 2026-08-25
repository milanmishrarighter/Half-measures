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

    /**
     * True from the moment an ad is asked for until it has answered. While it is
     * set, this activity stops re-hiding the system bars: the game runs immersive,
     * and leaving the bars hidden under someone else's full-screen activity is how
     * an ad's close button ends up drawn where it cannot be tapped.
     */
    private var adShowing = false

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
            onWatchRewardedAd = { onEarned, onDeclined ->
                beginAdPresentation()
                ads.show(
                    {
                        endAdPresentation()
                        onEarned()
                    },
                    { reason, backedOut ->
                        endAdPresentation()
                        onDeclined(reason, backedOut)
                    }
                )
            }
        }
        setContentView(gameView)
        hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        // Backgrounding mid-run should not cost the player the run.
        gameView.pauseIfPlaying()
        // And the game must stop rendering entirely. It used to keep its frame loop
        // running behind whatever was in front, so a video ad was sharing the main
        // thread with a full-speed game loop.
        gameView.stopLoop()
    }

    override fun onResume() {
        super.onResume()
        gameView.refreshSettings()
        gameView.startLoop()
        gameView.checkStrandedAd()
    }

    override fun onDestroy() {
        super.onDestroy()
        ads.destroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !adShowing) hideSystemBars()
    }

    private fun beginAdPresentation() {
        adShowing = true
        showSystemBars()
    }

    private fun endAdPresentation() {
        adShowing = false
        hideSystemBars()
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
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
