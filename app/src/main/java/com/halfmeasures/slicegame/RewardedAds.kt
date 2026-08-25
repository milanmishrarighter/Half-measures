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
 * Keeps exactly one rewarded ad loaded and ready.
 *
 * The game asks [isReady] before it offers anything, so a player who is offline,
 * or whose ad simply has not filled, is never shown a button that cannot work.
 * Every path out of [show] lands on one of the two callbacks, because the game
 * sits in a modal state waiting for an answer and silence would strand it.
 */
class RewardedAds(private val activity: Activity) {

    private var ad: RewardedAd? = null
    private var loading = false
    private var showing = false
    /** True once the ad's own activity is actually up in front of us. */
    private var presented = false
    private var failedLoads = 0
    private val handler = Handler(Looper.getMainLooper())

    fun initialize() {
        MobileAds.initialize(activity) { load() }
    }

    fun isReady(): Boolean = ad != null && !showing

    /** Whether an ad is currently on screen, as opposed to merely being asked for. */
    fun isPresenting(): Boolean = presented

    /**
     * Gives up on an ad that was asked for but never appeared. The SDK may still
     * call back later; [showing] going false means that callback finds nothing to
     * report and is ignored, so a cancelled ad cannot pay out after the fact.
     */
    fun abandonPending() {
        if (presented) return
        showing = false
        load()
    }

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
     * Shows the loaded ad. [onEarned] fires only once the user has actually
     * watched enough to earn the reward; every other outcome - no ad, a failure to
     * present, or the user backing out early - goes to [onDeclined] with a reason
     * and whether the user themselves walked away.
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
        ad = null

        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                presented = true
                onPresented()
            }

            override fun onAdDismissedFullScreenContent() {
                showing = false
                presented = false
                load()
                // Backing out early is a choice, not a failure, and the caller
                // treats the two differently.
                if (earned) onEarned() else onDeclined("Ad closed early", true)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                showing = false
                presented = false
                load()
                onDeclined(error.message, false)
            }
        }

        current.show(activity) { earned = true }
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        ad = null
    }

    /** Backs off on repeated failures rather than hammering a dead network. */
    private fun scheduleRetry() {
        failedLoads++
        val delayMs = (2_000L shl (failedLoads - 1).coerceAtMost(4)).coerceAtMost(60_000L)
        handler.postDelayed({ load() }, delayMs)
    }

    private companion object {
        const val TAG = "RewardedAds"
    }
}
