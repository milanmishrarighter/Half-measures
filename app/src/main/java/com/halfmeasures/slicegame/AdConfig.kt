package com.halfmeasures.slicegame

/**
 * Where the ad identifiers live.
 *
 * [USE_TEST_ADS] is on, so the app serves Google's own demo inventory. Those two
 * IDs are published by Google, work without an AdMob account, and always fill -
 * which is what makes the reward flow testable before there is anything to sign
 * up with. They must never ship: real ad units clicked by a developer get an
 * account banned, and test units are the sanctioned way to avoid that.
 *
 * When the AdMob account exists: paste the real IDs into [LIVE_APPLICATION_ID]
 * and [LIVE_REWARDED_UNIT_ID], flip [USE_TEST_ADS] to false, and copy the same
 * application ID into the meta-data element in AndroidManifest.xml - the manifest
 * cannot read a Kotlin constant, so that one value is stated in two places.
 */
object AdConfig {

    /** Serve Google's demo ads instead of real inventory. */
    const val USE_TEST_ADS = true

    // Google's published test identifiers. Not secrets, and not tied to anyone.
    const val TEST_APPLICATION_ID = "ca-app-pub-3940256099942544~3347511713"
    const val TEST_REWARDED_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    /** Replace with the real AdMob app ID, then mirror it into the manifest. */
    const val LIVE_APPLICATION_ID = ""
    /** Replace with the real rewarded ad unit ID. */
    const val LIVE_REWARDED_UNIT_ID = ""

    val rewardedUnitId: String
        get() = if (USE_TEST_ADS || LIVE_REWARDED_UNIT_ID.isEmpty()) {
            TEST_REWARDED_UNIT_ID
        } else {
            LIVE_REWARDED_UNIT_ID
        }

    /** Seconds counted down on screen before a continued run picks back up. */
    const val RESUME_COUNTDOWN_SECONDS = 3f
}

/**
 * Games played since the process started. Deliberately not persisted: the gate is
 * "every tenth game this session", so closing the app clears it, and it survives
 * an activity being recreated underneath the same process.
 */
object PlaySession {
    var gamesPlayed = 0
        private set

    fun countGame() {
        gamesPlayed++
    }

    /** The number the game about to start will be. */
    fun nextGameNumber(): Int = gamesPlayed + 1

    /** True when the game about to start falls on the ad gate. */
    fun nextGameIsGated(every: Int): Boolean =
        every > 0 && nextGameNumber() % every == 0
}
