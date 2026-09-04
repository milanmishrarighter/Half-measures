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
        // A rewarded ad disables the back button while it runs, so a creative that
        // fails to render leaves a black screen with no way off it. When the ad
        // manager gives up on one, this is how the player gets out.
        ads.onWedged = { bringSelfToFront() }
        ads.initialize()

        gameView = GameView(this).apply {
            onOpenSettings = {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
            onOpenInstructions = {
                startActivity(Intent(this@MainActivity, InstructionsActivity::class.java))
            }
            isRewardedAdReady = { ads.isReady() }
            onWatchRewardedAd = { onEarned, onDeclined, onPresented ->
                beginAdPresentation()
                ads.show(
                    {
                        endAdPresentation()
                        onEarned()
                    },
                    { reason, backedOut ->
                        endAdPresentation()
                        onDeclined(reason, backedOut)
                    },
                    onPresented
                )
            }
            onPreloadAd = { ads.load() }
            // Closes the whole task, not just this activity, so the next launch is
            // a cold start at the title screen rather than a resume.
            onExitApp = { finishAndRemoveTask() }
            onOpenStats = { startActivity(Intent(this@MainActivity, StatsActivity::class.java)) }
            onCancelPendingAd = {
                endAdPresentation()
                ads.abandonPending()
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
        // Retried on every return to the foreground. A first launch can finish
        // initialising after the first load attempt has already failed, which is
        // how a fresh install ends up with no ad until the app is opened again.
        ads.load()
    }

    /**
     * Reorders this activity back above a wedged ad. The ad's activity belongs to
     * this app and this task, so the app counts as foreground and the launch is
     * allowed; nothing is recreated, the existing instance simply comes forward.
     */
    private fun bringSelfToFront() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // The game draws its own modals on a Canvas, so nothing else in the system
        // knows they are there. Back has to be handed to it first or a waiting
        // screen has no way out but killing the app.
        if (!gameView.handleBackPressed()) super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        ads.destroy()
        gameView.releaseSounds()
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
