package com.halfmeasures.slicegame

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * Keeps exactly one rewarded ad loaded, and refuses to let a broken one trap the
 * player.
 *
 * The ad's own activity is not ours to decorate. A rewarded ad deliberately
 * disables the back button while its video runs - the close button is the intended
 * way out - so when the creative fails to render there is a black screen with
 * neither. Two watchdogs cover that: one for an ad that never opens, one for an ad
 * that opens and never closes. Either fires [onWedged], which the activity uses to
 * pull itself back in front, and settles the request as declined. No watchdog path
 * ever pays a reward.
 */
class RewardedAds(private val activity: Activity) {

    /** Called when an ad has to be abandoned while its screen is still in front. */
    var onWedged: (() -> Unit)? = null

    private var ad: RewardedAd? = null
    private var loading = false
    private var showing = false
    /** True once the ad's own activity has actually rendered in front of us. */
    private var presented = false
    /** Set when a request is abandoned, so its late callbacks are ignored. */
    private var abandoned = false
    private var failedLoads = 0

    private val handler = Handler(Looper.getMainLooper())
    private var openWatchdog: Runnable? = null
    private var closeWatchdog: Runnable? = null
    private var pendingShow: Runnable? = null

    fun initialize() {
        MobileAds.initialize(activity) { load() }
    }

    fun isReady(): Boolean = ad != null && !showing

    fun load() {
        if (loading || ad != null || showing) return
        loading = true
        RewardedAd.load(
            activity,
            AdConfig.rewardedUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(loaded: RewardedAd) {
                    ad = loaded
                    loading = false
                    failedLoads = 0
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    ad = null
                    loading = false
                    Log.w(TAG, "rewarded ad failed to load: ${error.message}")
                    scheduleRetry()
                }
            }
        )
    }

    /**
     * Shows the loaded ad. [onEarned] fires only once the reward is genuinely
     * earned; every other outcome - no ad, a failure to present, the user backing
     * out, or a watchdog giving up - goes to [onDeclined] with a reason and whether
     * the user themselves walked away.
     */
    fun show(onEarned: () -> Unit, onDeclined: (String, Boolean) -> Unit, onPresented: () -> Unit) {
        val current = ad
        if (current == null || showing) {
            load()
            onDeclined("No ad ready", false)
            return
        }

        var earned = false
        showing = true
        presented = false
        abandoned = false
        ad = null

        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                if (abandoned) return
                cancel(openWatchdog)
                presented = true
                onPresented()
                // The ad is real and running. The only backstop left is for one
                // that never closes, well past the length of any rewarded ad.
                closeWatchdog = Runnable {
                    if (!showing || abandoned) return@Runnable
                    abandon()
                    onWedged?.invoke()
                    onDeclined("Ad did not close", false)
                }
                handler.postDelayed(closeWatchdog!!, CLOSE_TIMEOUT_MS)
            }

            override fun onAdDismissedFullScreenContent() {
                if (abandoned) return
                finish()
                // Backing out early is a choice, not a failure, and the caller
                // treats the two differently.
                if (earned) onEarned() else onDeclined("Ad closed early", true)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                if (abandoned) return
                finish()
                onDeclined(error.message, false)
            }
        }

        // Presented on the next beat rather than this one. The game runs behind
        // hidden system bars and restores them just before an ad; launching another
        // activity into the middle of that window transition is a known way to get
        // an ad that renders black and takes the back button down with it.
        pendingShow = Runnable {
            if (abandoned) return@Runnable
            current.show(activity) { earned = true }
        }
        handler.postDelayed(pendingShow!!, SHOW_DELAY_MS)

        openWatchdog = Runnable {
            if (presented || !showing || abandoned) return@Runnable
            Log.w(TAG, "rewarded ad never opened; abandoning")
            abandon()
            onWedged?.invoke()
            onDeclined("Ad failed to open", false)
        }
        handler.postDelayed(openWatchdog!!, OPEN_TIMEOUT_MS)
    }

    /** Gives up on an ad the player cancelled before it appeared. */
    fun abandonPending() {
        if (presented) return
        abandon()
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        ad = null
    }

    /**
     * Retires the current request. Its callbacks may still arrive - the SDK does
     * not know we walked away - and [abandoned] is what makes them no-ops, so an
     * abandoned ad can never pay out after the fact.
     */
    private fun abandon() {
        abandoned = true
        finish()
    }

    private fun finish() {
        cancel(openWatchdog)
        cancel(closeWatchdog)
        cancel(pendingShow)
        openWatchdog = null
        closeWatchdog = null
        pendingShow = null
        showing = false
        presented = false
        load()
    }

    private fun cancel(runnable: Runnable?) {
        runnable?.let { handler.removeCallbacks(it) }
    }

    /** Backs off on repeated failures rather than hammering a dead network. */
    private fun scheduleRetry() {
        failedLoads++
        val delayMs = (2_000L shl (failedLoads - 1).coerceAtMost(4)).coerceAtMost(60_000L)
        handler.postDelayed({ load() }, delayMs)
    }

    private companion object {
        const val TAG = "RewardedAds"

        /** Let the system-bar transition settle before the ad activity launches. */
        const val SHOW_DELAY_MS = 300L

        /** How long an ad gets to render before it is treated as broken. */
        const val OPEN_TIMEOUT_MS = 12_000L

        /** Well past the length of any rewarded ad, so only a wedged one trips it. */
        const val CLOSE_TIMEOUT_MS = 180_000L
    }
}
