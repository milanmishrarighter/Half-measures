package com.halfmeasures.slicegame

/**
 * Where the ad identifiers live.
 *
 * Two IDs are needed and they are not interchangeable: an **application ID**,
 * which ends in a tilde and identifies the app to the SDK, and an **ad unit ID**,
 * which ends in a slash and identifies the one placement being requested. The
 * application ID has to be stated twice - here and in the manifest's meta-data -
 * because the manifest cannot read a Kotlin constant, and the SDK refuses to
 * start if the two disagree.
 *
 * Going live is [USE_TEST_ADS] to false, with both live values filled in. Until
 * then the app serves Google's published demo inventory, which works without an
 * account and always fills. Test ads are not politeness: clicking a real ad unit
 * from your own device is what gets an AdMob account disabled, and the demo units
 * are the sanctioned way to exercise the reward flow without doing that.
 */
object AdConfig {

    /** Serve Google's demo ads instead of real inventory. */
    const val USE_TEST_ADS = true

    // Google's published test identifiers. Not secrets, and not tied to anyone.
    const val TEST_APPLICATION_ID = "ca-app-pub-3940256099942544~3347511713"
    const val TEST_REWARDED_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    /**
     * The real AdMob application ID - the one with a tilde in it.
     *
     * Still blank. Whatever goes here must also be pasted into the
     * `com.google.android.gms.ads.APPLICATION_ID` meta-data in AndroidManifest.xml.
     */
    const val LIVE_APPLICATION_ID = ""

    /** The real rewarded ad unit ID, serving both the continue and the retry gate. */
    const val LIVE_REWARDED_UNIT_ID = "ca-app-pub-6520630912116541/8048925060"

    /**
     * Whether a request should go to real inventory.
     *
     * Both live values have to be present, not just the flag. A build that turned
     * the flag off while the application ID was still blank would ask for real ads
     * under a test application ID - a mismatch the SDK either refuses outright or
     * silently never fills, with no obvious cause on screen.
     */
    val live: Boolean
        get() = !USE_TEST_ADS &&
            LIVE_APPLICATION_ID.isNotEmpty() &&
            LIVE_REWARDED_UNIT_ID.isNotEmpty()

    /**
     * The unit every rewarded request uses - the continue button on the score card
     * and the every-tenth-game gate alike. One placement covers both: they are the
     * same ad, offered at two moments, and reporting them apart would only split
     * one small number in two.
     */
    val rewardedUnitId: String
        get() = if (live) LIVE_REWARDED_UNIT_ID else TEST_REWARDED_UNIT_ID

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

    /**
     * Clears the count. Called when the player leaves through the gate: closing the
     * task does not always kill the process, so without this the very next launch
     * could put the same gate straight back in front of them.
     */
    fun reset() {
        gamesPlayed = 0
    }

    /** The number the game about to start will be. */
    fun nextGameNumber(): Int = gamesPlayed + 1

    /** True when the game about to start falls on the ad gate. */
    fun nextGameIsGated(every: Int): Boolean =
        every > 0 && nextGameNumber() % every == 0
}
