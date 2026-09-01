package com.halfmeasures.slicegame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The play surface: shapes launch up from the bottom, the player swipes to slice
 * them, and every cut is graded on how close it lands to a perfect 50/50 split.
 * Drawn entirely with Canvas - no game engine, no XML.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    /**
     * READY and GAME_OVER show overlays; SETTLING is the beat in between, where the
     * run is already over but the shapes still in the air are allowed to fall out
     * of frame before the card slides in over an empty stage.
     */
    enum class State {
        READY, PLAYING, PAUSED, SETTLING,
        /** Every Nth game of a session asks for an ad before it will start. */
        AD_GATE,
        /** An ad is on screen; the game is parked until it answers. */
        AD_PENDING,
        /** 3, 2, 1 before a bought-back run picks up again. */
        RESUMING,
        GAME_OVER
    }

    /** What the ad currently being watched is being watched for. */
    private enum class AdPurpose { NONE, CONTINUE, GATE }

    /** Opens the settings screen; wired up by the hosting activity. */
    var onOpenSettings: (() -> Unit)? = null
    /** Opens the how-to-play screen; wired up by the hosting activity. */
    var onOpenInstructions: (() -> Unit)? = null
    /**
     * Plays a rewarded ad. The first callback fires only once the reward is
     * genuinely earned; the second fires on every other outcome, with a reason and
     * whether the user backed out themselves (as opposed to the ad failing).
     */
    var onWatchRewardedAd: ((() -> Unit, (String, Boolean) -> Unit, () -> Unit) -> Unit)? = null
    /** Abandons an ad that was asked for but never appeared. */
    var onCancelPendingAd: (() -> Unit)? = null
    /** Asks for an ad to be fetched, so a failed press makes the next one likelier. */
    var onPreloadAd: (() -> Unit)? = null
    /** Closes the app outright, so the next launch starts at the title screen. */
    var onExitApp: (() -> Unit)? = null
    /** Whether an ad is loaded right now. Nothing is offered when it is not. */
    var isRewardedAdReady: (() -> Boolean)? = null

    private var settings = GameSettings.load(context)
    private val scores = context.getSharedPreferences("half_measures_scores", Context.MODE_PRIVATE)
    private val random = Random(System.currentTimeMillis())
    private val effects = EffectSystem(random)
    private val haptics = Haptics(context)
    private val sounds = Sounds.of(context)

    private var gravity = GameShape.BASE_GRAVITY * settings.gravityScale

    fun refreshSettings() {
        settings = GameSettings.load(context)
        gravity = GameShape.BASE_GRAVITY * settings.gravityScale
        sounds.enabled = settings.soundEnabled
        sounds.volume = settings.soundVolume
        sounds.voiceEnabled = settings.voiceEnabled
        sounds.musicEnabled = settings.musicEnabled
        sounds.musicVolume = settings.musicVolume
        // Only the title screen picks up a new starting-health setting. Every
        // other state is mid-run - including the moments either side of an ad -
        // and refilling the bar there would hand out a free heal.
        if (state == State.READY) {
            health = settings.startHealth
            maxHealth = settings.startHealth
        }
    }

    // ---- Run state ----
    private var state = State.READY
    private var maxHealth = settings.startHealth
    private var health = settings.startHealth
    private var score = 0
    private var bestScore = scores.getInt("best_score", 0)
    /**
     * Personal bests for everything the score card reports, so a run can be read
     * against the player's own ceiling rather than only against the last one.
     */
    /** Runs finished on this device. A first run has no record to break. */
    private var runsFinished = scores.getInt("runs_finished", 0)
    /**
     * Every final score ever posted, so the average can be kept without storing
     * the runs themselves. The average is what the rank is drawn from: a best
     * score is one lucky afternoon, an average is how well you actually play.
     */
    private var scoreTotal = scores.getLong("score_total", 0L)
    /** How the average moved on the run just finished, and whether it had one to move from. */
    private var averageDelta = 0
    private var averageMoved = false
    /** +1 if this run promoted the player, -1 if it demoted them, 0 if neither. */
    private var rankMoved = 0
    /** How many of the card's reveal cues have been played this time round. */
    private var cardCue = 0
    private val averageScore: Int
        get() = if (runsFinished <= 0) 0 else (scoreTotal / runsFinished).toInt()
    private var bestCuts = scores.getInt("best_cuts", 0)
    private var bestPerfectCuts = scores.getInt("best_perfect_cuts", 0)
    private var recordPerfectStreak = scores.getInt("best_perfect_streak", 0)
    private var recordGoodStreak = scores.getInt("best_good_streak", 0)
    /** True when the run just finished beat the stored record. */
    private var beatBestScore = false
    /** Seconds until the next firework goes up on a record-breaking run. */
    private var fireworkTimer = 0f
    /** Seconds spent waiting for the last shapes to clear after the run ended. */
    private var settleTimer = 0f
    /** Countdowns driving the idle demo that plays behind the title screen. */
    private var menuSpawnTimer = 0f
    private var menuCutTimer = 0f
    /** Seconds since the game-over card began revealing itself. */
    private var cardReveal = 0f

    // ---- Ads ----
    /** Continues already spent on this run. */
    private var continuesUsed = 0
    /** Whether the score card is currently showing its continue button. */
    private var continueOffered = false
    /** Seconds left of the 3-2-1 before a continued run resumes. */
    private var resumeCountdown = 0f
    /** Where to go back to if the player walks away from the ad gate. */
    private var adGateReturn = State.READY
    private var pendingAdPurpose = AdPurpose.NONE
    /** A short line explaining why an ad did not pay out, if one did not. */
    private var adNotice = ""
    private var adNoticeAge = 99f
    /** Seconds spent on the waiting screen without an ad appearing. */
    private var adPendingAge = 0f
    /** True once the ad's own screen is up, so there is a reward to lose. */
    private var adPresented = false
    /** True while the "skip the reward?" confirmation is up. */
    private var confirmingAdExit = false
    /**
     * Bumped for every ad asked for. A cancelled request's callbacks can still
     * arrive afterwards - the SDK does not know we walked away - and without this
     * a late reward would restart a run the player had already left.
     */
    private var adRequestId = 0

    // ---- Last-cut readout, shown under the score rather than over the action ----
    private var lastCutLabel = ""
    private var lastCutPoints = 0
    private var lastCutColor = Theme.textPrimary
    private var lastCutAge = 99f
    private var perfectStreak = 0
    private var bestStreak = 0
    private var bestPerfectStreak = 0
    private var perfectCount = 0
    private var cutCount = 0
    private var endedOnMiss = false

    private var lastFrameTimeNanos = 0L
    /** Whether the frame loop is running. Owned by the activity's lifecycle. */
    private var loopRunning = false
    /**
     * Seconds until the next shape is thrown in. Counted down in *game* time, so
     * slow motion holds the queue back instead of letting a crowd pile up and
     * burst onto the screen the instant normal speed returns.
     */
    private var spawnCountdown = 0f
    private var elapsed = 0f

    // ---- Time control ----
    /** Everything on screen runs at this multiple of real time. */
    private var timeScale = 1f
    /** Seconds remaining of the celebratory slow motion after a perfect cut. */
    private var perfectSlowMo = 0f
    /** Seconds left of the short speed dip when health first turns critical. */
    private var dangerRecovery = 0f
    /**
     * Seconds the LOW HEALTH alert stays on screen. It is a one-shot announcement
     * like the perfect-cut celebration, not a state that hangs around for as long
     * as health happens to be low.
     */
    private var dangerAlert = 0f
    private var dangerArmed = true

    /** Consecutive great-or-better cuts, and consecutive sloppy ones. */
    private var hotStreak = 0
    private var coldStreak = 0
    private var stage = 0

    /** Health bar lags the true value so gains and losses read as motion. */
    private var displayedHealth = settings.startHealth.toFloat()
    private var displayedScore = 0f

    private val shapes = ArrayList<GameShape>()
    private val pieces = ArrayList<SlicedPiece>()
    private val trailPoints = ArrayList<TrailPoint>()

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var hasLastTouch = false
    /** Where the blade last spoke, and when, so the swipe noise stays occasional. */
    private val trailMaxAgeMs = 165L

    // ---- Buttons (laid out in onSizeChanged, hit-tested in onTouchEvent) ----
    /** The ready screen centres its buttons; the game-over screen stacks them under its card. */
    private val readyPrimary = RectF()
    private val readySecondary = RectF()
    private val readyTertiary = RectF()
    private val overPrimary = RectF()
    private val overSecondary = RectF()
    private val overTertiary = RectF()
    /** The game-over card carries a fourth button back to the title screen. */
    private val overQuaternary = RectF()
    /** The ad-backed continue, drawn above RETRY only when one is available. */
    private val overContinue = RectF()
    /**
     * The card is drawn at full size into a canvas scaled down by [CARD_SCALE], so
     * every dimension inside it shrinks together and none of the drawing code has
     * to know. This is that design-space rect; [gameOverCardVisual] is where it
     * actually lands on screen, for anything drawn outside the scaled block.
     */
    private val gameOverCard = RectF()
    private val gameOverCardVisual = RectF()
    /** The pause target in the top-right corner, live only during play. */
    private val pauseButton = RectF()
    private val pauseCard = RectF()
    private val pauseResume = RectF()
    private val pauseSettings = RectF()
    private val pauseMenu = RectF()
    /** The two ad overlays share a card shape and a two-button footer. */
    private val adCard = RectF()
    private val adPrimary = RectF()
    private val adSecondary = RectF()
    /** The way out of the waiting screen, and the two confirmation answers. */
    private val adCancel = RectF()
    private val adConfirmLeave = RectF()
    private val adConfirmStay = RectF()
    private val primaryButton: RectF get() = when (state) {
        State.GAME_OVER -> overPrimary
        State.PAUSED -> pauseResume
        State.AD_GATE -> adPrimary
        else -> readyPrimary
    }
    private val secondaryButton: RectF get() = when (state) {
        State.GAME_OVER -> overSecondary
        State.PAUSED -> pauseMenu
        State.AD_GATE -> adSecondary
        else -> readySecondary
    }

    /** The states that are a card with two buttons and nothing else running. */
    private fun isOverlayState(): Boolean =
        state == State.PAUSED || state == State.AD_GATE
    private val tertiaryButton: RectF get() = when (state) {
        State.GAME_OVER -> overTertiary
        State.PAUSED -> pauseSettings
        else -> readyTertiary
    }
    private var pressedButton = 0 // 0 none, 1 primary, 2 secondary, 3 tertiary, 4 quaternary

    // ---- Paints, all reused ----
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /**
     * The backdrop is a single flat colour rather than a gradient. A dark gradient
     * down a tall screen only crosses a handful of distinct 8-bit values, which is
     * what produced the stepped banding; one solid value cannot band at any size.
     * It eases toward the current level's hue so the change reads as a slow drift.
     */
    /** Which slice of the score ramp the cached shape gradients were built for. */
    private var lastHueBucket = 0
    private var backgroundColor = Theme.scoreBackground(0)
    private var accentColor = Theme.scoreAccent(0)
    private val scrimPaint = Paint()
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val panelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Theme.hairline
    }

    private val displayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Theme.display(context)
        textAlign = Paint.Align.CENTER
    }
    private val uiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Theme.ui(context)
        textAlign = Paint.Align.CENTER
    }
    private val uiBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Theme.uiBold(context)
        textAlign = Paint.Align.LEFT
    }

    /** Pixel cells are drawn without anti-aliasing so the edges stay crisp and blocky. */
    private val pixelPaint = Paint()
    private val flashPaint = Paint()
    private val pixels = PixelBackground(random)

    private val path = Path()
    private val shaderMatrix = Matrix()
    private val bodyShaders = HashMap<Int, RadialGradient>()
    /**
     * The material's shaders, keyed the same way. A vertical ramp per colour slot,
     * and one tile of film grain shared by all of them.
     */
    private val materialShaders = HashMap<Int, LinearGradient>()
    private val grainMatrix = Matrix()
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shapeBounds = RectF()
    private val grainShader: BitmapShader by lazy {
        BitmapShader(buildGrainTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val roundRect = RectF()
    /** Scratch for measuring a line's real ink, so gaps are set from what shows. */
    private val inkBounds = Rect()

    private var density = 1f

    init {
        isFocusable = true
        density = resources.displayMetrics.density
    }

    // ---------------------------------------------------------------------
    // Lifecycle & frame loop
    // ---------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoop()
    }

    /**
     * The frame loop is now owned by the activity's lifecycle rather than running
     * for the life of the process. It used to keep ticking and invalidating while
     * another activity was in front - which, with a video ad on top, meant this
     * view was competing with the ad's own UI for the one main thread they share.
     */
    fun startLoop() {
        // Idempotent, so this is a safe place to make sure the effects are built.
        sounds.prepare()
        // On a cold launch the view is already READY without ever having gone
        // through resetToTitle, so nothing had asked for music yet and there was
        // nothing for resume to pick up. Asking outright is safe either way: the
        // engine resumes a track it already has, and defers if it is still loading.
        when (state) {
            State.READY -> sounds.startMusic()
            State.PLAYING -> sounds.resumeMusic()
            else -> {}
        }
        if (loopRunning) return
        loopRunning = true
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopLoop() {
        // Whatever took the foreground from us should not have to play over this.
        sounds.pauseMusic()
        if (!loopRunning) return
        loopRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!loopRunning) return
        if (lastFrameTimeNanos == 0L) lastFrameTimeNanos = frameTimeNanos
        var realDt = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
        lastFrameTimeNanos = frameTimeNanos
        realDt = min(realDt, 1f / 30f) // after a stall, step conservatively instead of teleporting

        if (state == State.PAUSED || state == State.AD_PENDING) {
            // A pause stops the world outright: no simulation, no timers, no embers.
            // Only the overlay is redrawn, so the run resumes exactly where it stood.
            // An ad is the same thing with someone else's screen on top.
            if (state == State.AD_PENDING && !adPresented) {
                // This only counts while the waiting screen is actually in front:
                // once the ad appears, the activity pauses and the loop stops. So a
                // long ad can never time out - only an ad that never arrives.
                adPendingAge += realDt
                if (adPendingAge > AD_WAIT_TIMEOUT) cancelPendingAd("Ad did not load")
            }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
            return
        }

        adNoticeAge += realDt
        // Real time: this is an announcement to the player, not part of the
        // simulation, so slow motion must not stretch it.
        if (state == State.RESUMING) {
            val before = ceil(resumeCountdown.toDouble()).toInt()
            resumeCountdown -= realDt
            val after = ceil(resumeCountdown.toDouble()).toInt()
            // One tick per digit, and a higher one on the last as play resumes.
            if (after != before) sounds.play(Sfx.COUNTDOWN, rate = if (after <= 0) 1.5f else 1f)
            if (resumeCountdown <= 0f) {
                state = State.PLAYING
                spawnCountdown = 0.5f
            }
        }

        // The alert and the speed ramp both run on real time, so slow motion
        // cannot stretch the warning out.
        dangerAlert = (dangerAlert - realDt).coerceAtLeast(0f)
        updateTimeControl(realDt)
        update(realDt * timeScale)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * Works out how fast the world should run this frame. A perfect cut drops into
     * slow motion and eases back. Dropping into critical health dips the speed just
     * long enough to register, then climbs straight back to normal - the warning
     * itself is carried by the flashing bar rather than by holding time hostage.
     */
    private fun updateTimeControl(realDt: Float) {
        if (state != State.PLAYING) {
            timeScale = 1f
            return
        }

        if (dangerRecovery > 0f) {
            dangerRecovery = (dangerRecovery - realDt).coerceAtLeast(0f)
            // Linear climb back to normal speed.
            val progress = 1f - dangerRecovery / lowHealthSlowMoSeconds()
            timeScale = settings.slowMoIntensity + (1f - settings.slowMoIntensity) * progress
            return
        }

        if (perfectSlowMo > 0f) {
            perfectSlowMo = (perfectSlowMo - realDt).coerceAtLeast(0f)
            val total = settings.slowMoDuration.coerceAtLeast(0.05f)
            // Ease out: slowest at the moment of the cut, gliding back to full speed.
            val progress = (1f - perfectSlowMo / total).coerceIn(0f, 1f)
            val eased = progress * progress
            timeScale = settings.slowMoIntensity + (1f - settings.slowMoIntensity) * eased
            return
        }

        timeScale = 1f
    }

    private fun triggerDangerSequence() {
        sounds.play(SfxBank.DANGER, gain = 0.9f, spread = 3)
        dangerRecovery = lowHealthSlowMoSeconds()
        dangerAlert = lowHealthSlowMoSeconds()
        perfectSlowMo = 0f
        effects.addFlash(Theme.danger, 0.5f * settings.screenFlashStrength)
        pixels.flash(1.4f)
        if (settings.vibrationEnabled) haptics.lowHealth(settings.vibrationStrength)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        pixels.resize(w, h)

        layoutButtons(w, h)
    }

    /**
     * Both overlays are laid out here. The game-over card is measured from its own
     * contents and the buttons are stacked underneath it, with the whole block
     * centred - so the card can never spill past its own edge onto the buttons.
     */
    private fun layoutButtons(w: Int, h: Int) {
        val buttonWidth = min(w * 0.72f, 300f * density)
        val buttonHeight = 58f * density
        val buttonGap = 14f * density
        val cx = w / 2f

        // Sat a little higher than centre so the stack clears the bottom of the screen.
        val readyTop = h * 0.56f
        readyPrimary.set(cx - buttonWidth / 2f, readyTop, cx + buttonWidth / 2f, readyTop + buttonHeight)
        readySecondary.set(
            cx - buttonWidth / 2f, readyTop + buttonHeight + buttonGap,
            cx + buttonWidth / 2f, readyTop + buttonHeight * 2 + buttonGap
        )
        readyTertiary.set(
            cx - buttonWidth / 2f, readyTop + (buttonHeight + buttonGap) * 2,
            cx + buttonWidth / 2f, readyTop + buttonHeight * 3 + buttonGap * 2
        )

        layoutGameOverBlock()

        layoutPauseOverlay(w, h, buttonWidth, buttonHeight, buttonGap)

        // Measured from its own copy rather than guessed at, so the space under
        // the paragraph matches the space above it however many lines it runs to.
        val adWidth = min(w * 0.86f, 380f * density)
        val adInner = adWidth - AD_CARD_PAD * 2f * density
        uiPaint.textSize = AD_BODY_SIZE * density
        val bodyBlock = wrapLines(adGateBody(), adInner, uiPaint).size * AD_BODY_SIZE * 1.3f * density

        val adHeadBlock = (AD_CARD_PAD + AD_TITLE_DROP) * density
        val adHeight = adHeadBlock + bodyBlock + AD_CARD_PAD * density +
            buttonHeight * 2 + buttonGap + AD_CARD_PAD * density
        val adTop = (h - adHeight) / 2f
        adCard.set(cx - adWidth / 2f, adTop, cx + adWidth / 2f, adTop + adHeight)
        // The waiting screen: one cancel button low on the screen, and a pair of
        // answers in the middle for the confirmation that can replace it.
        val cancelWidth = min(w * 0.5f, 200f * density)
        val cancelHeight = 46f * density
        adCancel.set(
            cx - cancelWidth / 2f, h * 0.68f,
            cx + cancelWidth / 2f, h * 0.68f + cancelHeight
        )
        val confirmWidth = min(w * 0.62f, 260f * density)
        val confirmTop = h * 0.52f
        adConfirmLeave.set(
            cx - confirmWidth / 2f, confirmTop,
            cx + confirmWidth / 2f, confirmTop + cancelHeight
        )
        adConfirmStay.set(
            cx - confirmWidth / 2f, confirmTop + cancelHeight + 10f * density,
            cx + confirmWidth / 2f, confirmTop + cancelHeight * 2 + 10f * density
        )

        val adFirstTop = adTop + adHeadBlock + bodyBlock + AD_CARD_PAD * density
        adPrimary.set(cx - adInner / 2f, adFirstTop, cx + adInner / 2f, adFirstTop + buttonHeight)
        adSecondary.set(
            cx - adInner / 2f, adFirstTop + buttonHeight + buttonGap,
            cx + adInner / 2f, adFirstTop + buttonHeight * 2 + buttonGap
        )
    }

    /**
     * The card and everything stacked under it, centred as one block. The stack is
     * four short buttons, plus the ad-backed continue above them when there is one
     * to offer - so this has to be redone whenever that availability changes, not
     * only when the view is resized.
     */
    private fun layoutGameOverBlock() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val cx = w / 2f
        val blockWidth = min(w * 0.78f, 320f * density)
        val gap = 10f * density
        val rowHeight = GAME_OVER_BUTTON_HEIGHT * density

        // The card is measured at full size and then shown scaled down, so the
        // stack below it has to be placed against the scaled height.
        val designWidth = w * 0.82f / CARD_SCALE
        val designHeight = measureGameOverCard()
        val visualWidth = designWidth * CARD_SCALE
        val visualHeight = designHeight * CARD_SCALE

        val rows = if (continueOffered) 3 else 2
        // Wide enough to seat the "could not load an ad" line when there is one.
        val cardGap = 22f * density
        val blockHeight = visualHeight + cardGap + rowHeight * rows + gap * (rows - 1)
        val blockTop = ((h - blockHeight) / 2f).coerceAtLeast(12f * density)

        gameOverCardVisual.set(cx - visualWidth / 2f, blockTop, cx + visualWidth / 2f, blockTop + visualHeight)
        // Centred on the same point, so scaling about that centre lands it exactly
        // on the visual rect.
        val centreY = gameOverCardVisual.centerY()
        gameOverCard.set(
            cx - designWidth / 2f, centreY - designHeight / 2f,
            cx + designWidth / 2f, centreY + designHeight / 2f
        )

        val left = cx - blockWidth / 2f
        val right = cx + blockWidth / 2f
        var top = gameOverCardVisual.bottom + cardGap

        // One button per row, centred: continue, retry, main menu. How to play and
        // settings are on the title screen and were only repeated here.
        if (continueOffered) {
            overContinue.set(left, top, right, top + rowHeight)
            top += rowHeight + gap
        } else {
            // An empty rect can never be hit-tested, which is the point.
            overContinue.setEmpty()
        }
        overPrimary.set(left, top, right, top + rowHeight)
        top += rowHeight + gap
        overQuaternary.set(left, top, right, top + rowHeight)

        overSecondary.setEmpty()
        overTertiary.setEmpty()
    }

    /**
     * The pause target rides in the top-right corner, and the dialog it opens is a
     * small centred card with the running score and a way out.
     */
    private fun layoutPauseOverlay(w: Int, h: Int, buttonWidth: Float, buttonHeight: Float, buttonGap: Float) {
        val pad = 22f * density
        val size = PAUSE_BUTTON_SIZE * density
        pauseButton.set(w - pad - size, pad - 2f * density, w - pad, pad + size - 2f * density)

        val cx = w / 2f
        val cardWidth = min(w * 0.78f, 330f * density)
        val cardHeight = 118f * density + buttonHeight * 3 + buttonGap * 2 + 20f * density
        val cardTop = (h - cardHeight) / 2f
        pauseCard.set(cx - cardWidth / 2f, cardTop, cx + cardWidth / 2f, cardTop + cardHeight)

        val innerWidth = min(buttonWidth, cardWidth - 36f * density)
        val firstTop = cardTop + 118f * density
        pauseResume.set(cx - innerWidth / 2f, firstTop, cx + innerWidth / 2f, firstTop + buttonHeight)
        // Settings are reachable mid-run now, not only from the title screen - the
        // moment you want the music off is the moment it is playing.
        val settingsTop = firstTop + buttonHeight + buttonGap
        pauseSettings.set(cx - innerWidth / 2f, settingsTop, cx + innerWidth / 2f, settingsTop + buttonHeight)
        val menuTop = settingsTop + buttonHeight + buttonGap
        pauseMenu.set(cx - innerWidth / 2f, menuTop, cx + innerWidth / 2f, menuTop + buttonHeight)
    }

    /**
     * Total height the card needs, walked in exactly the order the draw walks it so
     * the two cannot drift apart: header, rule, five stat rows, rule, heading, the
     * breakdown rows, and the same inset at the bottom as at the sides.
     */
    private fun measureGameOverCard(): Float {
        val toLastStat = CARD_RULE_GAP + CARD_STAT_ROW_HEIGHT * 4
        val toHeading = CARD_RULE_GAP * 2
        val toFirstRow = CARD_RULE_GAP - CARD_BREAKDOWN_ROW_HEIGHT / 2f - CARD_ROW_TEXT_OFFSET
        val rows = CARD_BREAKDOWN_ROW_HEIGHT * (cutBuckets.size - 1)
        return (CARD_HEADER_HEIGHT + toLastStat + toHeading + toFirstRow + rows + CARD_PAD) * density
    }

    // ---------------------------------------------------------------------
    // Simulation
    // ---------------------------------------------------------------------

    private fun update(dt: Float) {
        elapsed += dt
        val nowMs = System.currentTimeMillis()

        trailPoints.removeAll { nowMs - it.timeMs > trailMaxAgeMs }

        pixels.update(
            dt = dt,
            energy = effects.energy,
            healthFraction = if (maxHealth > 0) displayedHealth / maxHealth else 1f,
            warmth = streakWarmth(),
            runColor = Theme.scoreEnergy(score),
            runGlow = Theme.scoreEnergyDim(score),
            // The field thickens as the run goes: five percent more embers every
            // thousand points, capped so a long run does not end up a snowstorm.
            emberDensity = settings.emberDensity *
                (1f + EMBER_GROWTH_PER_1K * (score / 1000)).coerceAtMost(MAX_EMBER_GROWTH),
            emberBrightness = settings.emberBrightness,
            emberSize = settings.emberSize,
            driftSpeed = settings.backgroundMotion
        )

        if (state == State.PLAYING) {
            updateStage()

            val cap = (settings.startConcurrency + stage * settings.concurrencyPerStage)
                .coerceIn(1, settings.maxConcurrency)
            spawnCountdown -= dt
            if (shapes.size < cap && spawnCountdown <= 0f && width > 0 && height > 0) {
                shapes.add(GameShape.spawnRandom(width, height, random, nowMs, stage, score, settings))
                spawnCountdown = spawnGapSeconds()
            }

            // Critical health: one short dip in speed, then the flashing bar carries it.
            if (settings.lowHealthSlowMo) {
                if (dangerArmed && health > 0 && health <= settings.lowHealthAt) {
                    dangerArmed = false
                    triggerDangerSequence()
                } else if (health > settings.lowHealthAt + 10) {
                    dangerArmed = true
                }
            }

            var i = shapes.size - 1
            while (i >= 0) {
                val s = shapes[i]
                s.update(dt, gravity)
                if (settings.wallStrength > 0f) bounceOffWalls(s)
                if (s.isOffScreen(width, height)) {
                    shapes.removeAt(i)
                    if (settings.missEndsRun) {
                        endedOnMiss = true
                        sounds.play(Sfx.MISS)
                        endRun()
                    } else {
                        perfectStreak = 0
                    }
                }
                i--
            }

            if (health <= 0) {
                health = 0
                endRun()
            }
        } else if (state == State.READY) {
            updateMenuDemo(dt, nowMs)
        } else if (state == State.SETTLING) {
            // Let whatever is still airborne drop away. Walls are off so nothing
            // can be trapped bouncing, and none of it can be cut any more.
            var i = shapes.size - 1
            while (i >= 0) {
                val s = shapes[i]
                s.update(dt, gravity)
                if (s.isOffScreen(width, height)) shapes.removeAt(i)
                i--
            }
            updateSettling(dt)
        }

        if (state == State.GAME_OVER) {
            cardReveal += dt
            playCardCues()
        }
        lastCutAge += dt

        var i = pieces.size - 1
        while (i >= 0) {
            val p = pieces[i]
            p.update(dt, gravity)
            if (!p.alive) pieces.removeAt(i)
            i--
        }

        if (state == State.GAME_OVER && beatBestScore && cardReveal > CARD_SCORE_AT) updateFireworks(dt)

        effects.update(dt, gravity)

        // The shape gradients are cached per palette entry and tinted by the
        // score's hue, so they have to be dropped as that hue moves.
        val hueBucket = score / 250
        if (hueBucket != lastHueBucket) {
            lastHueBucket = hueBucket
            bodyShaders.clear()
            materialShaders.clear()
        }

        // Creep toward the score's colours so the change is a slow shift in the
        // light rather than a hard cut.
        val colourEase = min(1f, dt * 0.9f)
        backgroundColor = Theme.lerpColor(backgroundColor, Theme.scoreBackground(score), colourEase)
        accentColor = Theme.lerpColor(accentColor, Theme.scoreAccent(score), colourEase)

        // Ten BPM per thousand points, so a good run audibly tightens up.
        sounds.setMusicSpeed(1f + (score / 1000) * 10f / MUSIC_BASE_BPM)

        displayedHealth += (health - displayedHealth) * min(1f, dt * 9f)
        displayedScore += (score - displayedScore) * min(1f, dt * 12f)
    }

    /**
     * Advances the difficulty stage. Every stage the player earns brings more
     * shapes on screen at once, faster tumbling, and fresh, harder shape kinds.
     */
    /**
     * Advances the level silently. There is no banner: the level shows itself
     * through the scene's colour drifting to a new hue, which stays out of the
     * way of the cut feedback instead of colliding with it.
     */
    private fun updateStage() {
        val interval = max(1, settings.stageScoreInterval)
        val newStage = score / interval
        if (newStage == stage) return

        stage = newStage
        // The shape gradients are cached per palette entry and tinted by level,
        // so they have to be rebuilt when the level changes.
        bodyShaders.clear()
        materialShaders.clear()
        if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
        sounds.play(Sfx.LEVEL_UP, gain = 0.9f)
    }

    /**
     * Tally of how every cut landed, indexed by [Grade] so the breakdown always
     * agrees with the PERFECT CUTS figure above it - bucketing on raw deviation
     * instead let near-misses inside a wider band masquerade as dead-centre cuts.
     */
    private val cutBuckets = IntArray(CUT_BUCKET_LABELS.size)

    private fun recordCutBucket(grade: Grade) {
        cutBuckets[grade.ordinal]++
    }

    /**
     * Celebration for a new record: pixel shells launched at a steady clip across
     * the upper screen, each one popping into a ring of embers and sparks.
     */
    private fun updateFireworks(dt: Float) {
        fireworkTimer -= dt
        if (fireworkTimer > 0f || width <= 0) return
        fireworkTimer = 0.18f + random.nextFloat() * 0.22f

        // Centred on the score itself, so the celebration reads as being about it.
        val scoreX = gameOverCardVisual.centerX()
        val scoreY = gameOverCardVisual.top + 100f * density * CARD_SCALE
        val x = scoreX + (random.nextFloat() - 0.5f) * gameOverCardVisual.width() * 0.85f
        val y = scoreY + (random.nextFloat() - 0.5f) * 130f * density
        val color = if (random.nextFloat() < 0.4f) Theme.gold
            else Theme.shapeLight(score, random.nextInt(Theme.shapeSlots))

        // Chunky pixel debris, matching the background's blocky vocabulary.
        pixels.burst(x, y, 2.4f)
        pixels.flash(0.5f)
        effects.radialBurst(x, y, color, 26, 460f, 1.5f)
        effects.radialBurst(x, y, Theme.lighten(color, 0.4f), 14, 260f, 1.1f)
        effects.shockwave(x, y, width * 0.16f, color, 0.5f, 6f)
        effects.addEnergy(0.5f)
    }

    /** -1 while the player is cold, +1 while they are hot; drives the backdrop's colour. */
    private fun streakWarmth(): Float = when {
        hotStreak >= 2 -> (hotStreak / 5f).coerceAtMost(1f)
        coldStreak >= 2 -> -(coldStreak / 4f).coerceAtMost(1f)
        else -> 0f
    }

    private fun bounceOffWalls(s: GameShape) {
        val left = s.radius
        val right = width - s.radius
        if (s.x < left && s.vx < 0f) {
            s.x = left
            s.vx = -s.vx * settings.wallStrength
            wallImpact(s, left)
        } else if (s.x > right && s.vx > 0f) {
            s.x = right
            s.vx = -s.vx * settings.wallStrength
            wallImpact(s, right)
        }
    }

    private fun wallImpact(s: GameShape, wallX: Float) {
        if (!settings.particlesEnabled) return
        val count = (6 * settings.particleAmount).roundToInt().coerceIn(1, 24)
        effects.burst(
            x = wallX,
            y = s.y,
            dirX = 0f,
            dirY = 1f,
            spread = s.radius * 0.5f,
            color = tintedLight(s.paletteIndex),
            count = count,
            speed = 170f,
            sizeScale = 0.8f
        )
    }

    private fun endRun() {
        if (state != State.PLAYING) return
        // Hand over to the settling beat; the card waits until the stage is clear.
        state = State.SETTLING
        settleTimer = 0f
        cardReveal = 0f
        hasLastTouch = false
        trailPoints.clear()
        // A record needs something to beat. On the very first run everything is a
        // record by definition, and celebrating that is just noise.
        if (score > bestScore && runsFinished > 0) {
            beatBestScore = true
            sounds.play(Sfx.BEST)
            fireworkTimer = 0f
        }
        if (score > bestScore) {
            bestScore = score
            scores.edit().putInt("best_score", bestScore).apply()
        }
        // Captured before this run lands, so the card can say which way it went.
        val previousAverage = averageScore
        averageMoved = runsFinished > 0
        val previousRank = Ranks.forScore(previousAverage).number
        // The first run is averaged against a zero it never played, so one lucky
        // opening game is half an average rather than a whole one. Without it a
        // single six-thousand-point first run walked straight into a rank that
        // ought to take a while.
        runsFinished += if (runsFinished == 0) 2 else 1
        scoreTotal += score
        averageDelta = averageScore - previousAverage
        // A rank is only a move if the player had one to move from.
        rankMoved = if (!averageMoved) 0 else
            Ranks.forScore(averageScore).number.compareTo(previousRank)
        cardCue = 0
        scores.edit()
            .putInt("runs_finished", runsFinished)
            .putLong("score_total", scoreTotal)
            .apply()
        commitPersonalBests()
        effects.addShake(0.7f * settings.cameraShakeStrength)
        if (settings.vibrationEnabled) haptics.gameOver(settings.vibrationStrength)
        if (!beatBestScore) sounds.play(Sfx.GAME_OVER)
        // The track belongs to the run, so it goes when the run does rather than
        // playing on under the score card.
        sounds.stopMusic()
    }

    /**
     * A slow, silent demo behind the title screen: shapes drift up and an unseen
     * blade halves one every so often. It uses the real shapes and the real
     * slicing, just without scoring, so the menu shows the game rather than a
     * mock-up of it.
     */
    private fun updateMenuDemo(dt: Float, nowMs: Long) {
        if (width <= 0 || height <= 0) return

        menuSpawnTimer -= dt
        if (menuSpawnTimer <= 0f && shapes.size < 3) {
            menuSpawnTimer = 0.9f + random.nextFloat() * 0.8f
            shapes.add(GameShape.spawnRandom(width, height, random, nowMs, 0, 0, settings))
        }

        var i = shapes.size - 1
        while (i >= 0) {
            val shape = shapes[i]
            shape.update(dt, gravity)
            if (shape.isOffScreen(width, height)) shapes.removeAt(i)
            i--
        }

        menuCutTimer -= dt
        if (menuCutTimer <= 0f) {
            menuCutTimer = 0.7f + random.nextFloat() * 0.7f
            demoSliceOne()
        }
    }

    /** Halves whichever demo shape is nearest the top of its arc. */
    private fun demoSliceOne() {
        var best: GameShape? = null
        for (shape in shapes) {
            // Near the apex, where a real player would take the shot.
            if (shape.y > height * 0.72f || shape.y < height * 0.12f) continue
            if (best == null || shape.y < best.y) best = shape
        }
        val shape = best ?: return

        val angle = random.nextFloat() * 3.1416f
        val reach = shape.radius * 2.4f
        val ax = shape.x - cos(angle) * reach
        val ay = shape.y - sin(angle) * reach
        val bx = shape.x + cos(angle) * reach
        val by = shape.y + sin(angle) * reach

        val poly = shape.worldVertices()
        val (left, right) = SliceMath.splitPolygon(poly, ax, ay, bx, by)
        if (left.size < 3 || right.size < 3) return

        shapes.remove(shape)
        val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay)).coerceAtLeast(0.001f)
        val dirX = (bx - ax) / len
        val dirY = (by - ay) / len
        spawnPieces(shape, left, right, dirX, dirY)

        if (settings.particlesEnabled) {
            effects.burst(
                x = shape.x, y = shape.y,
                dirX = dirX, dirY = dirY,
                spread = shape.radius * 0.8f,
                color = tintedLight(shape.paletteIndex),
                count = (12 * settings.particleAmount).roundToInt().coerceIn(2, 40),
                speed = 320f,
                sizeScale = 1f
            )
        }
        // A brief ghost of the blade, so the cut reads as a cut.
        val nowMs = System.currentTimeMillis()
        trailPoints.add(TrailPoint(ax, ay, nowMs))
        trailPoints.add(TrailPoint(bx, by, nowMs))
    }

    /**
     * Rolls this run's stats into the stored bests. Written together at the end of
     * a run rather than on every cut: a dozen small commits per run buys nothing,
     * and the card is the only place they are read.
     */
    private fun commitPersonalBests() {
        val cuts = max(bestCuts, cutCount)
        val perfects = max(bestPerfectCuts, perfectCount)
        val perfectRun = max(recordPerfectStreak, bestPerfectStreak)
        val goodRun = max(recordGoodStreak, bestStreak)
        if (cuts == bestCuts && perfects == bestPerfectCuts &&
            perfectRun == recordPerfectStreak && goodRun == recordGoodStreak
        ) {
            return
        }
        bestCuts = cuts
        bestPerfectCuts = perfects
        recordPerfectStreak = perfectRun
        recordGoodStreak = goodRun
        scores.edit()
            .putInt("best_cuts", bestCuts)
            .putInt("best_perfect_cuts", bestPerfectCuts)
            .putInt("best_perfect_streak", recordPerfectStreak)
            .putInt("best_good_streak", recordGoodStreak)
            .apply()
    }

    /**
     * Seconds between shapes at the current level. The gap shrinks by a fixed
     * percentage each level and is floored, so the ladder can be made to bite
     * without the game ever turning into a solid wall of shapes.
     */
    private fun spawnGapSeconds(): Float {
        val decay = (1f - settings.spawnSpeedUpPercent / 100f).coerceIn(0.5f, 1f)
        val gap = settings.spawnGapMs * decay.toDouble().pow(stage.toDouble()).toFloat()
        return gap.coerceAtLeast(GameSettings.MIN_EFFECTIVE_SPAWN_GAP_MS.toFloat()) / 1000f
    }

    /** Half the perfect-cut slow motion: long enough to register, short enough not to drag. */
    private fun lowHealthSlowMoSeconds(): Float =
        (settings.slowMoDuration * 0.5f).coerceAtLeast(0.05f)

    /**
     * Lets the remaining shapes fall away, then reveals the card. Capped so a shape
     * wedged against a bouncy wall can never stall the ending.
     */
    private fun updateSettling(dt: Float) {
        settleTimer += dt
        if (shapes.isEmpty() || settleTimer > SETTLE_MAX_SECONDS) {
            shapes.clear()
            enterGameOver()
        }
    }

    /**
     * Whether the card shows a continue at all. Deliberately not conditional on an
     * ad being loaded: a button that comes and goes is worse than one that is
     * always there and occasionally says it could not fetch an ad. The failure is
     * reported on the card instead.
     */
    private fun canOfferContinue(): Boolean {
        if (!settings.continuesEnabled) return false
        // A cap of zero means no cap: a good run can be bought back as many times
        // as the player is willing to sit through an ad for.
        val cap = settings.continuesPerRun
        return cap <= 0 || continuesUsed < cap
    }

    private fun enterGameOver() {
        state = State.GAME_OVER
        cardReveal = 0f
        fireworkTimer = 0f
        pressedButton = 0
        refreshContinueOffer()
    }

    /**
     * Whether the card shows its continue button, and where everything under the
     * card then sits. Recomputed on arriving at the card and again after an ad,
     * since spending one may leave nothing loaded to offer next time.
     */
    private fun refreshContinueOffer() {
        continueOffered = canOfferContinue()
        layoutGameOverBlock()
    }

    /** Back to the card after a declined ad, without replaying its reveal. */
    private fun returnToCard() {
        state = State.GAME_OVER
        pressedButton = 0
        refreshContinueOffer()
    }

    /**
     * Buys the run back. The score, the stage and every stat carry over untouched -
     * that is the whole point - but the health bar refills and the board is cleared
     * so the player is not dropped straight back into the shape that killed them.
     */
    private fun grantContinue() {
        continuesUsed++
        shapes.clear()
        pieces.clear()
        trailPoints.clear()
        effects.clear()
        health = max(1, (maxHealth * settings.continueHealthFraction).roundToInt())
        displayedHealth = health.toFloat()
        endedOnMiss = false
        dangerArmed = true
        dangerAlert = 0f
        dangerRecovery = 0f
        perfectSlowMo = 0f
        timeScale = 1f
        perfectStreak = 0
        hotStreak = 0
        coldStreak = 0
        lastCutAge = 99f
        pressedButton = 0
        hasLastTouch = false
        resumeCountdown = AdConfig.RESUME_COUNTDOWN_SECONDS
        sounds.startMusic()
        state = State.RESUMING
        lastFrameTimeNanos = 0L
    }

    /**
     * Every request to start a game goes through here, so the session gate catches
     * PLAY, RETRY and a sliced start button alike. The gate is skipped when no ad
     * is loaded: a toll nobody can pay is just a locked door.
     */
    private fun requestNewGame() {
        if (PlaySession.nextGameIsGated(settings.adGateEvery) && isRewardedAdReady?.invoke() == true) {
            adGateReturn = state
            pressedButton = 0
            state = State.AD_GATE
            return
        }
        startNewGame()
    }

    private fun requestAd(purpose: AdPurpose) {
        val show = onWatchRewardedAd
        // Checked before committing to the waiting screen rather than after. An ad
        // that is not loaded should read as "not right now" on the card the player
        // is already looking at, never as a screen they then have to escape.
        if (show == null || isRewardedAdReady?.invoke() != true) {
            // Kick a fetch on the way out, so pressing again in a few seconds has a
            // real chance of working rather than failing the same way.
            onPreloadAd?.invoke()
            settleAd(purpose, earned = false, reason = "Could not load an ad - try again", userBackedOut = false)
            return
        }
        pendingAdPurpose = purpose
        pressedButton = 0
        adPendingAge = 0f
        adPresented = false
        confirmingAdExit = false
        val id = ++adRequestId
        state = State.AD_PENDING
        // Posted rather than called straight through: the SDK can answer
        // synchronously on a failure, and re-entering a state change from inside
        // the call that caused it is how you get a screen stuck half-way.
        show(
            { post { onAdResult(id, purpose, earned = true, reason = "", userBackedOut = false) } },
            { reason, backedOut ->
                post { onAdResult(id, purpose, earned = false, reason = reason, userBackedOut = backedOut) }
            },
            { post { if (id == adRequestId) adPresented = true } }
        )
    }

    /** The guarded entry point for the SDK's answers. Stale ones are dropped. */
    private fun onAdResult(
        id: Int,
        purpose: AdPurpose,
        earned: Boolean,
        reason: String,
        userBackedOut: Boolean
    ) {
        if (id != adRequestId) return
        settleAd(purpose, earned, reason, userBackedOut)
    }

    /**
     * Leaves the waiting screen without a reward. Reachable from the CANCEL button,
     * from the back gesture, and from the timeout, so an ad that never arrives can
     * never hold the game hostage.
     */
    private fun cancelPendingAd(reason: String) {
        if (state != State.AD_PENDING) return
        // Retires this request before leaving, so anything the SDK says afterwards
        // lands on a stale id and is ignored.
        adRequestId++
        onCancelPendingAd?.invoke()
        confirmingAdExit = false
        settleAd(pendingAdPurpose, earned = false, reason = reason, userBackedOut = true)
    }

    /**
     * The back gesture, routed in from the activity. Returns true when the game
     * consumed it. Every modal the game puts up has to answer back, or the only way
     * out of one is to kill the app.
     */
    fun handleBackPressed(): Boolean = when {
        state == State.AD_PENDING && confirmingAdExit -> {
            confirmingAdExit = false
            true
        }
        state == State.AD_PENDING -> {
            // Once the ad is up there is a reward on the table, so leaving is a
            // decision to confirm rather than a reflex to obey.
            if (adPresented) confirmingAdExit = true else cancelPendingAd("Cancelled")
            true
        }
        state == State.AD_GATE -> {
            // Back out of the gate is the same as EXIT APP: there is no third
            // option here, or the gate would not be a gate.
            PlaySession.reset()
            onExitApp?.invoke()
            true
        }
        state == State.PAUSED -> {
            resumeGame()
            true
        }
        state == State.PLAYING -> {
            pauseGame()
            true
        }
        state == State.GAME_OVER -> {
            returnToMenu()
            true
        }
        else -> false
    }

    /** The single place every ad outcome lands, so no path can strand the game. */
    private fun settleAd(purpose: AdPurpose, earned: Boolean, reason: String, userBackedOut: Boolean) {
        pendingAdPurpose = AdPurpose.NONE
        adPresented = false
        confirmingAdExit = false
        if (!earned) {
            adNotice = reason
            adNoticeAge = 0f
        }
        when (purpose) {
            AdPurpose.CONTINUE -> if (earned) grantContinue() else returnToCard()
            AdPurpose.GATE -> {
                // Backing out of an ad that was there to watch puts the gate back
                // up. An ad that would not play is not the player's fault, so the
                // game starts anyway rather than locking them out.
                if (!earned && userBackedOut) {
                    state = State.AD_GATE
                } else {
                    startNewGame()
                }
            }
            AdPurpose.NONE -> {}
        }
    }

    /**
     * Called when the activity loses focus. Leaving the app mid-run would otherwise
     * hand the player a dead run on their return, so it pauses itself.
     */
    /**
     * Drops the music stream on the way out. The sample pool is the process's, not
     * this view's - the settings screen clicks through it too - so it stays.
     */
    fun releaseSounds() {
        sounds.releaseMusic()
    }

    fun pauseIfPlaying() {
        if (state == State.PLAYING) pauseGame()
    }

    /**
     * Safety net for an ad that never answers. Every outcome is supposed to come
     * back through a callback, but if one is ever dropped the game would sit on the
     * LOADING AD screen forever, so coming back to the foreground with the ad gone
     * settles it as a decline. The delay lets a real callback win the race.
     */
    fun checkStrandedAd() {
        if (state != State.AD_PENDING || confirmingAdExit) return
        val purpose = pendingAdPurpose
        postDelayed({
            if (state == State.AD_PENDING && !confirmingAdExit && pendingAdPurpose == purpose) {
                settleAd(purpose, earned = false, reason = "Ad did not finish", userBackedOut = true)
            }
        }, STRANDED_AD_GRACE_MS)
    }

    /** Freezes the run where it stands and raises the pause card. */
    private fun pauseGame() {
        if (state != State.PLAYING) return
        state = State.PAUSED
        pressedButton = 0
        hasLastTouch = false
        trailPoints.clear()
        if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
        sounds.play(Sfx.BUTTON)
        sounds.pauseMusic()
    }

    private fun resumeGame() {
        if (state != State.PAUSED) return
        sounds.resumeMusic()
        state = State.PLAYING
        // The clock has been standing still, so drop the stale timestamp rather
        // than handing the next frame the whole paused duration as its delta.
        lastFrameTimeNanos = 0L
    }

    /**
     * Abandons whatever is on screen and goes back to the title. Called from the
     * pause card and from the game-over card, so a run can always be walked away
     * from without going through another one.
     */
    private fun returnToMenu() {
        shapes.clear()
        pieces.clear()
        trailPoints.clear()
        effects.clear()
        pressedButton = 0
        hasLastTouch = false
        timeScale = 1f
        perfectSlowMo = 0f
        dangerRecovery = 0f
        dangerAlert = 0f
        menuSpawnTimer = 0f
        menuCutTimer = 0.8f
        health = settings.startHealth
        maxHealth = settings.startHealth
        displayedHealth = health.toFloat()
        backgroundColor = Theme.scoreBackground(0)
        accentColor = Theme.scoreAccent(0)
        stage = 0
        pixels.reset()
        bodyShaders.clear()
        materialShaders.clear()
        continuesUsed = 0
        // The menu gets a track too, at its written tempo. Only a run in progress
        // pushes it faster.
        sounds.setMusicSpeed(1f)
        sounds.startMusic()
        state = State.READY
        lastFrameTimeNanos = 0L
    }

    private fun startNewGame() {
        shapes.clear()
        pieces.clear()
        trailPoints.clear()
        effects.clear()
        maxHealth = settings.startHealth
        health = settings.startHealth
        displayedHealth = health.toFloat()
        // Everything downstream of the score - the level, the backdrop's hue, which
        // shapes are in the pool - falls out of this one number, so starting part
        // way up the ladder needs nothing else set.
        score = settings.startingScore.coerceAtLeast(0)
        displayedScore = score.toFloat()
        perfectStreak = 0
        hotStreak = 0
        coldStreak = 0
        perfectCount = 0
        cutCount = 0
        endedOnMiss = false
        beatBestScore = false
        fireworkTimer = 0f
        menuSpawnTimer = 0f
        menuCutTimer = 0.8f
        settleTimer = 0f
        cardReveal = 0f
        lastCutAge = 99f
        bestStreak = 0
        bestPerfectStreak = 0
        stage = score / max(1, settings.stageScoreInterval)
        timeScale = 1f
        perfectSlowMo = 0f
        dangerRecovery = 0f
        dangerAlert = 0f
        dangerArmed = true
        pixels.reset()
        bodyShaders.clear()
        materialShaders.clear()
        lastHueBucket = score / 250
        backgroundColor = Theme.scoreBackground(score)
        accentColor = Theme.scoreAccent(score)
        continuesUsed = 0
        PlaySession.countGame()
        sounds.startMusic()
        state = State.PLAYING
        spawnCountdown = 0.32f
        cutBuckets.fill(0)
    }

    // ---------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state == State.SETTLING || state == State.RESUMING) return true
                if (state == State.AD_PENDING) {
                    pressedButton = when {
                        confirmingAdExit && adConfirmLeave.contains(event.x, event.y) -> 1
                        confirmingAdExit && adConfirmStay.contains(event.x, event.y) -> 2
                        !confirmingAdExit && adCancel.contains(event.x, event.y) -> 1
                        else -> 0
                    }
                    return true
                }
                if (isOverlayState()) {
                    pressedButton = when {
                        primaryButton.contains(event.x, event.y) -> 1
                        secondaryButton.contains(event.x, event.y) -> 2
                        state == State.PAUSED &&
                            pauseSettings.contains(event.x, event.y) -> 3
                        else -> 0
                    }
                    return true
                }
                if (state == State.PLAYING && pauseButton.contains(event.x, event.y)) {
                    pauseGame()
                    return true
                }
                if (state != State.PLAYING) {
                    pressedButton = when {
                        primaryButton.contains(event.x, event.y) -> 1
                        secondaryButton.contains(event.x, event.y) -> 2
                        tertiaryButton.contains(event.x, event.y) -> 3
                        state == State.GAME_OVER && overQuaternary.contains(event.x, event.y) -> 4
                        state == State.GAME_OVER && continueOffered &&
                            overContinue.contains(event.x, event.y) -> 5
                        else -> 0
                    }
                    // The title screen also lets the player swipe the start button
                    // open like a shape, so track the finger for that.
                    lastTouchX = event.x
                    lastTouchY = event.y
                    hasLastTouch = true
                    trailPoints.add(TrailPoint(event.x, event.y, System.currentTimeMillis()))
                    return true
                }
                lastTouchX = event.x
                lastTouchY = event.y
                hasLastTouch = true
                trailPoints.add(TrailPoint(event.x, event.y, System.currentTimeMillis()))
                pixels.burst(event.x, event.y, 1.0f)
            }

            MotionEvent.ACTION_MOVE -> {
                if (isOverlayState() || state == State.AD_PENDING || state == State.RESUMING) return true
                if (state == State.READY) {
                    // Draw a blade on the menu and let it cut the start button.
                    val menuNow = System.currentTimeMillis()
                    for (i in 0 until event.historySize) {
                        trailPoints.add(TrailPoint(event.getHistoricalX(i), event.getHistoricalY(i), menuNow))
                        handleMenuSwipe(event.getHistoricalX(i), event.getHistoricalY(i))
                    }
                    trailPoints.add(TrailPoint(event.x, event.y, menuNow))
                    handleMenuSwipe(event.x, event.y)
                    return true
                }
                if (state != State.PLAYING) return true
                val nowMs = System.currentTimeMillis()
                for (i in 0 until event.historySize) {
                    handleSwipeSegment(event.getHistoricalX(i), event.getHistoricalY(i))
                    trailPoints.add(TrailPoint(event.getHistoricalX(i), event.getHistoricalY(i), nowMs))
                }
                handleSwipeSegment(event.x, event.y)
                trailPoints.add(TrailPoint(event.x, event.y, nowMs))
                // Dragging stirs the floor, densest near its surface.
                pixels.burst(event.x, event.y, 1.0f)
            }

            MotionEvent.ACTION_UP -> {
                if (state == State.SETTLING || state == State.RESUMING) return true
                if (state == State.AD_PENDING) {
                    val released = pressedButton
                    pressedButton = 0
                    if (released != 0) sounds.play(Sfx.BUTTON)
                    if (confirmingAdExit) {
                        if (released == 1 && adConfirmLeave.contains(event.x, event.y)) {
                            cancelPendingAd("Reward skipped")
                        } else if (released == 2 && adConfirmStay.contains(event.x, event.y)) {
                            confirmingAdExit = false
                        }
                    } else if (released == 1 && adCancel.contains(event.x, event.y)) {
                        if (adPresented) confirmingAdExit = true else cancelPendingAd("Cancelled")
                    }
                    return true
                }
                if (isOverlayState()) {
                    val released = pressedButton
                    pressedButton = 0
                    val onPrimary = released == 1 && primaryButton.contains(event.x, event.y)
                    val onSecondary = released == 2 && secondaryButton.contains(event.x, event.y)
                    val onTertiary = released == 3 && state == State.PAUSED &&
                        pauseSettings.contains(event.x, event.y)
                    if (onPrimary || onSecondary || onTertiary) {
                        if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
                        sounds.play(Sfx.BUTTON)
                    }
                    when (state) {
                        State.PAUSED -> {
                            if (onPrimary) resumeGame()
                            // Stays paused underneath, so closing settings drops the
                            // player back on the card they opened it from.
                            if (onTertiary) onOpenSettings?.invoke()
                            if (onSecondary) returnToMenu()
                        }
                        State.AD_GATE -> {
                            if (onPrimary) requestAd(AdPurpose.GATE)
                            // The alternative to watching is leaving. The count is
                            // cleared on the way out, so a relaunch opens on a clean
                            // session rather than straight back into this card.
                            if (onSecondary) {
                                PlaySession.reset()
                                onExitApp?.invoke()
                            }
                        }
                        else -> {}
                    }
                    return true
                }
                if (state != State.PLAYING) {
                    val released = pressedButton
                    pressedButton = 0
                    hasLastTouch = false
                    when {
                        released == 1 && primaryButton.contains(event.x, event.y) -> {
                            if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
                            sounds.play(Sfx.BUTTON)
                            requestNewGame()
                        }
                        released == 2 && secondaryButton.contains(event.x, event.y) -> {
                            if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
                            sounds.play(Sfx.BUTTON)
                            onOpenInstructions?.invoke()
                        }
                        released == 3 && tertiaryButton.contains(event.x, event.y) -> {
                            if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
                            sounds.play(Sfx.BUTTON)
                            onOpenSettings?.invoke()
                        }
                        released == 4 && overQuaternary.contains(event.x, event.y) -> {
                            if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
                            sounds.play(Sfx.BUTTON)
                            returnToMenu()
                        }
                        released == 5 && overContinue.contains(event.x, event.y) -> {
                            if (settings.vibrationEnabled) haptics.great(settings.vibrationStrength)
                            sounds.play(Sfx.BUTTON, rate = 1.2f)
                            requestAd(AdPurpose.CONTINUE)
                        }
                    }
                    return true
                }
                hasLastTouch = false
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedButton = 0
                hasLastTouch = false
            }
        }
        return true
    }

    /**
     * On the title screen a swipe is a blade like any other: dragging it through
     * the start button cuts it open and begins the run. The other buttons are
     * ordinary taps, so a stray swipe cannot dump the player into settings.
     */
    private fun handleMenuSwipe(x: Float, y: Float) {
        if (!hasLastTouch) {
            lastTouchX = x
            lastTouchY = y
            hasLastTouch = true
            return
        }
        val ax = lastTouchX
        val ay = lastTouchY
        lastTouchX = x
        lastTouchY = y

        val dx = x - ax
        val dy = y - ay
        if (dx * dx + dy * dy < 36f) return

        // Only a swipe that travels across the button counts, not a slow drag
        // that happens to start inside it.
        val entered = readyPrimary.contains(ax, ay)
        val exited = readyPrimary.contains(x, y)
        if (entered == exited) return

        pressedButton = 0
        sliceStartButton(ax, ay, x, y)
    }

    /** Bursts the start button apart along the swipe, then begins the run. */
    private fun sliceStartButton(ax: Float, ay: Float, bx: Float, by: Float) {
        val cx = readyPrimary.centerX()
        val cy = readyPrimary.centerY()
        val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay)).coerceAtLeast(0.001f)

        if (settings.particlesEnabled) {
            effects.burst(
                x = cx, y = cy,
                dirX = (bx - ax) / len, dirY = (by - ay) / len,
                spread = readyPrimary.width() * 0.5f,
                color = Theme.accent,
                count = (34 * settings.particleAmount).roundToInt().coerceIn(4, 120),
                speed = 620f,
                sizeScale = 1.4f
            )
        }
        effects.shockwave(cx, cy, readyPrimary.width() * 0.9f, Theme.accent, 0.5f, 10f)
        effects.addFlash(Theme.accent, 0.3f * settings.screenFlashStrength)
        effects.addShake(0.6f * settings.cameraShakeStrength)
        pixels.burst(cx, cy, 1.6f)
        if (settings.vibrationEnabled) haptics.great(settings.vibrationStrength)
        sounds.play(SfxBank.SLICE, gain = 0.9f)
        sounds.play(SfxBank.PERFECT, gain = 0.8f, spread = 3)
        requestNewGame()
    }

    /**
     * Air, not impact: the blade moving before it has hit anything. Fired once per
     * gesture past a distance threshold, and rate-limited, so dragging a finger
     * around does not turn into a drone.
     */
    private fun handleSwipeSegment(x: Float, y: Float) {
        // No sound for the swipe itself. It fired on every drag, including one
        // through empty air, so the loudest noise in the game was the one that
        // meant nothing had happened.
        if (!hasLastTouch) {
            lastTouchX = x
            lastTouchY = y
            hasLastTouch = true
            return
        }
        val ax = lastTouchX
        val ay = lastTouchY
        val bx = x
        val by = y
        lastTouchX = x
        lastTouchY = y

        val dx = bx - ax
        val dy = by - ay
        if (dx * dx + dy * dy < 4f) return // touch jitter, not a swipe

        var i = shapes.size - 1
        while (i >= 0) {
            val shape = shapes[i]
            if (SliceMath.segmentSlicesShape(shape, ax, ay, bx, by)) {
                if (sliceShape(shape, ax, ay, bx, by)) shapes.removeAt(i)
            }
            i--
        }
    }

    /** How well a cut halved the shape, which drives score, health, and how loud the celebration is. */
    /**
     * Tiers a cut can land in, tightest first. GREAT is the band that keeps a good
     * streak alive; GOOD and below are ordinary cuts that break it.
     */
    private enum class Grade { PERFECT, GREAT, GOOD, FAIR, POOR, MISS }

    private fun gradeFor(deviation: Float): Grade = when {
        deviation <= settings.perfectThreshold -> Grade.PERFECT
        deviation <= settings.greatThreshold -> Grade.GREAT
        // Held clear of the great window, so a wide great setting can never
        // squeeze the 60/40 band down to nothing.
        deviation <= max(10f, settings.greatThreshold + 2f) -> Grade.GOOD
        deviation <= 20f -> Grade.FAIR
        deviation <= 30f -> Grade.POOR
        else -> Grade.MISS
    }

    /** Returns true when the cut landed and the shape should be consumed. */
    private fun sliceShape(shape: GameShape, ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val poly = shape.worldVertices()
        val (left, right) = SliceMath.splitPolygon(poly, ax, ay, bx, by)
        val areaA = SliceMath.polygonArea(left)
        val areaB = SliceMath.polygonArea(right)
        if (areaA <= 0f || areaB <= 0f) return false

        val deviation = SliceMath.deviationPercent(areaA, areaB)
        val grade = gradeFor(deviation)
        cutCount++

        // Score first: it advances the streaks, and the heal is sized from the
        // perfect streak. Doing it the other way round meant the first perfect
        // healed for a streak of zero, which is to say not at all.
        val gained = applyScore(deviation, grade)
        applyHealth(deviation, grade)

        val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay)).coerceAtLeast(0.001f)
        val dirX = (bx - ax) / len
        val dirY = (by - ay) / len

        spawnPieces(shape, left, right, dirX, dirY)
        spawnCutEffects(shape, dirX, dirY, grade)

        val bigger = (max(areaA, areaB) / (areaA + areaB) * 100f).roundToInt()
        val split = "$bigger/${100 - bigger}"
        recordCutBucket(grade)

        // Over the shape, keep it to the bare verdict so the action stays readable.
        val popupY = (shape.y - shape.radius - 22f * density).coerceAtLeast(height * 0.12f)
        effects.popup(
            headline = if (grade == Grade.PERFECT) "PERFECT" else split,
            subline = "",
            x = shape.x.coerceIn(width * 0.22f, width * 0.78f),
            y = popupY,
            color = gradeColor(grade),
            emphasis = if (grade == Grade.PERFECT) 1f else 0f
        )

        // The wordier feedback - grade, points, streak - collects under the score.
        lastCutLabel = when (grade) {
            Grade.PERFECT -> "PERFECT"
            Grade.GREAT -> "GREAT"
            Grade.GOOD -> "GOOD"
            Grade.FAIR -> "OKAY"
            else -> "SLOPPY"
        }
        lastCutPoints = gained
        lastCutColor = gradeColor(grade)
        lastCutAge = 0f

        if (settings.vibrationEnabled) {
            when (grade) {
                Grade.PERFECT -> haptics.perfect(settings.vibrationStrength)
                Grade.GREAT -> haptics.great(settings.vibrationStrength)
                else -> haptics.cut(settings.vibrationStrength, 1f - deviation / 50f)
            }
        }

        // The blade first, then the verdict on top of it. Both come from banks of
        // ten recipes, pitched per shot, so a long run never settles into a pattern.
        sounds.play(SfxBank.SLICE, gain = 0.75f)
        when (grade) {
            Grade.PERFECT -> {
                // Perfects hold the middle of the pitch range: these are the
                // flourishes, and a wildly detuned one would sound like a mistake.
                sounds.play(SfxBank.PERFECT, spread = 3)
                // The announcer lands just behind the flourish rather than on top
                // of it, so the two are heard as a call and a response.
                // spread 1 is no pitch shift at all: these are recordings of a
                // person, and detuning one is instantly audible as a wobble.
                postDelayed({ sounds.play(SfxBank.VOICE, gain = 1f, spread = 1) }, 110L)
                // Each perfect in a row answers a step higher on top of that, so a
                // streak audibly climbs.
                if (perfectStreak > 1) {
                    sounds.play(Sfx.HEAL, gain = 0.65f, rate = 1f + (perfectStreak - 1) * 0.06f)
                }
            }
            // A great is the good bank played high and confident, a plain good sits
            // in the middle: same ten recipes, two different characters.
            Grade.GREAT -> sounds.play(SfxBank.GOOD, gain = 1f, spread = 3)
            Grade.GOOD -> sounds.play(SfxBank.GOOD, gain = 0.85f)
            else -> sounds.play(SfxBank.BAD, gain = 0.85f)
        }

        if (grade == Grade.PERFECT && settings.slowMoOnPerfect && dangerRecovery <= 0f) {
            perfectSlowMo = settings.slowMoDuration
        }
        return true
    }

    private fun streakSuffix(): String = if (hotStreak > 1) "  ·  ${hotStreak}x STREAK" else ""

    /**
     * A perfect cut - and only a perfect cut - refills the bar. Everything else
     * costs health on a curve anchored at a 60/40 cut, so a sloppy chop bleeds
     * far more than a near miss rather than merely proportionally more.
     */
    private fun applyHealth(deviation: Float, grade: Grade) {
        if (grade == Grade.PERFECT && settings.perfectRestoresHealth) {
            // applyScore has already advanced the streak, so the first perfect in a
            // row heals one step, the second two, and ten refill a full bar.
            val heal = (perfectStreak * settings.perfectHealPerStreak).roundToInt()
            health = (health + heal).coerceIn(0, maxHealth)
            return
        }
        val ratio = deviation / 10f // 1.0 at a 60/40 cut
        val loss = settings.healthLossAtSixtyForty *
            ratio.toDouble().pow(settings.healthLossCurve.toDouble()).toFloat()
        health = (health - loss.roundToInt()).coerceIn(0, maxHealth)
    }

    /**
     * A hundred points for a dead-centre cut, one point off for every percentage
     * point of miss - so a 50/50 pays 100 and a 49/51 pays 99 - and then the
     * perfect streak multiplies the lot: the second perfect in a row pays double,
     * the third triple, and so on up to the ceiling.
     *
     * There is no bonus for precision on top of the base any more. That is what
     * put 123 on the board for a flawless cut: a flat 100, a precision bonus, and
     * a combo multiplier all stacked on one number nobody could work backwards
     * from. The good streak pays nothing extra at all - it already earns its keep
     * by not costing health.
     */
    private fun applyScore(deviation: Float, grade: Grade): Int {
        val sloppy = grade == Grade.POOR || grade == Grade.MISS

        // The two streaks are counted separately and a perfect belongs to both.
        // A perfect used to end a good run, which read as a punishment for the
        // best cut in the game.
        when (grade) {
            Grade.PERFECT -> {
                perfectCount++
                perfectStreak++
                hotStreak++
                coldStreak = 0
                bestPerfectStreak = max(bestPerfectStreak, perfectStreak)
                bestStreak = max(bestStreak, hotStreak)
            }
            Grade.GREAT -> {
                hotStreak++
                perfectStreak = 0
                coldStreak = 0
                bestStreak = max(bestStreak, hotStreak)
            }
            else -> {
                perfectStreak = 0
                hotStreak = 0
                if (sloppy) coldStreak++ else coldStreak = 0
            }
        }

        val base = (100f - deviation * settings.scoreMissWeight).coerceAtLeast(0f)
        var gained = (base * comboMultiplier()).roundToInt()

        if (coldStreak > 0) {
            // Each sloppy cut in a row shaves more off the payout; past the second
            // one the run actively bleeds points rather than merely earning few.
            val penalty = (settings.coldStreakPenaltyPercent / 100f * coldStreak).coerceAtMost(1.6f)
            gained = (gained * (1f - penalty)).roundToInt()
            if (coldStreak >= 3) gained -= (25 * (coldStreak - 2))
        }

        score = (score + gained).coerceAtLeast(0)
        return gained
    }

    /**
     * The perfect streak, straight: one perfect pays once, two in a row pay twice,
     * three three times, up to the ceiling. Nothing else multiplies, so the number
     * on screen is the number applied.
     */
    private fun comboMultiplier(): Float =
        perfectStreak.coerceAtLeast(1).toFloat().coerceAtMost(settings.maxComboMultiplier)

    private fun spawnPieces(
        shape: GameShape,
        left: List<PointF2>,
        right: List<PointF2>,
        dirX: Float,
        dirY: Float
    ) {
        // Push the halves apart perpendicular to the blade.
        val nx = -dirY
        val ny = dirX
        val kick = 105f
        if (left.size >= 3) {
            pieces.add(
                SlicedPiece(
                    left, shape.x, shape.y,
                    shape.vx + nx * kick, shape.vy + ny * kick,
                    shape.angularVelocity * 1.6f, shape.paletteIndex, shape.radius
                )
            )
        }
        if (right.size >= 3) {
            pieces.add(
                SlicedPiece(
                    right, shape.x, shape.y,
                    shape.vx - nx * kick, shape.vy - ny * kick,
                    shape.angularVelocity * -1.6f, shape.paletteIndex, shape.radius
                )
            )
        }
    }

    /**
     * The celebration, sized to the cut. A poor chop gets a dull scatter; a great
     * one gets a real burst and a ring; a perfect one gets the full carnival -
     * layered rings, a golden shower of debris, a screen flash and a hard kick.
     */
    private fun spawnCutEffects(shape: GameShape, dirX: Float, dirY: Float, grade: Grade) {
        val r = shape.radius
        val amount = settings.particleAmount

        if (settings.particlesEnabled) {
            val bladeCount = when (grade) {
                Grade.PERFECT -> 46
                Grade.GREAT -> 34
                Grade.GOOD -> 24
                Grade.FAIR -> 18
                else -> 12
            }
            val bladeSpeed = when (grade) {
                Grade.PERFECT -> 620f
                Grade.GREAT -> 500f
                else -> 360f
            }
            effects.burst(
                x = shape.x, y = shape.y,
                dirX = dirX, dirY = dirY,
                spread = r * 0.9f,
                // Half the shape's own colour, half the run's, so the spray reads as
                // this cut of this shape at this point in the run.
                color = Theme.lerpColor(tintedLight(shape.paletteIndex), accentColor, 0.5f),
                count = (bladeCount * amount).roundToInt().coerceIn(3, 160),
                speed = bladeSpeed,
                sizeScale = if (grade == Grade.PERFECT) 1.5f else 1.15f
            )

            if (grade == Grade.GREAT || grade == Grade.PERFECT) {
                val burstColor = if (grade == Grade.PERFECT) Theme.gold else Theme.accent
                effects.radialBurst(
                    shape.x, shape.y, burstColor,
                    ((if (grade == Grade.PERFECT) 44 else 24) * amount).roundToInt().coerceIn(4, 150),
                    if (grade == Grade.PERFECT) 700f else 460f,
                    if (grade == Grade.PERFECT) 1.6f else 1.2f
                )
            }
            if (grade == Grade.PERFECT) {
                // A second, slower shower of the shape's own colour, so the gold
                // burst reads on top of confetti rather than alone.
                effects.radialBurst(
                    shape.x, shape.y, Theme.lighten(tintedLight(shape.paletteIndex), 0.25f),
                    (30 * amount).roundToInt().coerceIn(3, 120), 330f, 1.35f
                )
            }
        }

        when (grade) {
            Grade.PERFECT -> {
                effects.shockwave(shape.x, shape.y, r * 5.5f, Theme.gold, 0.55f, 16f)
                effects.shockwave(shape.x, shape.y, r * 8f, Theme.withAlpha(Theme.goldDeep, 0.9f), 0.62f, 9f, 0.07f)
                effects.shockwave(shape.x, shape.y, r * 11f, Color.WHITE, 0.7f, 5f, 0.15f)
                effects.addShake(1.5f * settings.cameraShakeStrength)
                effects.addFlash(Theme.gold, 0.55f * settings.screenFlashStrength)
                effects.addEnergy(1.6f)
                pixels.burst(shape.x, shape.y, 1.5f)
                pixels.flash(1.5f)
            }
            Grade.GREAT -> {
                effects.shockwave(shape.x, shape.y, r * 4.4f, Theme.accent, 0.5f, 11f)
                effects.shockwave(shape.x, shape.y, r * 6.2f, Theme.withAlpha(Color.WHITE, 0.8f), 0.55f, 5f, 0.08f)
                effects.addShake(0.8f * settings.cameraShakeStrength)
                effects.addFlash(Theme.accent, 0.22f * settings.screenFlashStrength)
                effects.addEnergy(0.9f)
                pixels.burst(shape.x, shape.y, 1.0f)
                pixels.flash(0.65f)
            }
            Grade.GOOD -> {
                effects.shockwave(shape.x, shape.y, r * 3f, Theme.good, 0.42f, 6f)
                effects.addShake(0.35f * settings.cameraShakeStrength)
                effects.addEnergy(0.35f)
                pixels.burst(shape.x, shape.y, 0.6f)
            }
            else -> {
                // Fair, poor and outright misses all get the same muted acknowledgement.
                effects.addShake(0.16f * settings.cameraShakeStrength)
                pixels.burst(shape.x, shape.y, 0.3f)
            }
        }

        // A cold streak makes itself felt: the screen bruises red and kicks.
        if (coldStreak >= 2) {
            effects.addFlash(Theme.danger, 0.25f * coldStreak.coerceAtMost(4) * settings.screenFlashStrength)
            effects.addShake(0.35f * coldStreak.coerceAtMost(4) * settings.cameraShakeStrength)
            pixels.flash(0.5f * coldStreak.coerceAtMost(4))
        }
    }

    private fun gradeColor(grade: Grade): Int = when (grade) {
        Grade.PERFECT -> Theme.gold
        Grade.GREAT -> Theme.good
        Grade.GOOD -> Theme.accent
        Grade.FAIR -> Color.rgb(255, 190, 90)
        Grade.POOR -> Color.rgb(255, 140, 80)
        Grade.MISS -> Theme.danger
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)

        val (shakeX, shakeY) = effects.shakeOffset(settings.cameraShakeStrength)
        val shaken = shakeX != 0f || shakeY != 0f
        if (shaken) {
            canvas.save()
            canvas.translate(shakeX, shakeY)
        }

        for (piece in pieces) drawPiece(canvas, piece)
        for (shape in shapes) drawShape(canvas, shape)
        drawShockwaves(canvas)
        drawParticles(canvas)
        drawTrail(canvas)

        if (shaken) canvas.restore()

        drawFlash(canvas)
        drawCriticalWarning(canvas)
        drawPopups(canvas)
        // The HUD stays up through the settling beat so the final score is visible
        // right until the card takes over.
        if (state == State.PLAYING || state == State.SETTLING ||
            state == State.PAUSED || state == State.RESUMING
        ) {
            drawHud(canvas)
        }

        when (state) {
            State.READY -> drawReadyScreen(canvas)
            State.GAME_OVER -> drawGameOverScreen(canvas)
            State.PAUSED -> drawPauseScreen(canvas)
            State.AD_GATE -> drawAdGate(canvas)
            State.AD_PENDING -> drawAdPending(canvas)
            State.RESUMING -> drawResumeCountdown(canvas)
            State.PLAYING, State.SETTLING -> {}
        }
    }

    private fun drawBackground(canvas: Canvas) {
        // One flat value: nothing to quantise, so nothing to band, at any size.
        canvas.drawColor(backgroundColor)
        pixels.draw(canvas, pixelPaint, effects.energy)

        // A quiet seam along the floor, tinted by how the run is going.
        rimPaint.strokeWidth = 2f
        rimPaint.color = Theme.withAlpha(
            Theme.lerpColor(pixels.horizonColor(), accentColor, 0.55f),
            0.22f + 0.30f * effects.energy.coerceAtMost(1f)
        )
        canvas.drawLine(0f, height * 0.995f, width.toFloat(), height * 0.995f, rimPaint)
    }

    private fun buildPath(vertices: List<PointF2>) {
        path.rewind()
        vertices.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
    }

    /**
     * A shape's own colour, pulled toward the run's colour and lifted with it. The
     * shapes are the brightest thing on a black field, so they climb the same
     * spiral rather than sitting at one brightness all run.
     */
    private fun tintedLight(paletteIndex: Int): Int = Theme.shapeLight(score, paletteIndex)

    /**
     * The lit-glass material: one vertical ramp for the body, one tile of grain
     * over it.
     *
     * The ramp runs near-black at the top through the shape's own colour to a
     * narrow hot band low down and back into colour at the bottom, which is what a
     * translucent solid lit from below does. It is fixed to the screen's vertical
     * rather than to the shape, so everything on screen agrees about where the
     * light is instead of each shape carrying its own - the old radial highlight
     * sat in the upper left of every shape however it was tumbling.
     */
    private fun materialShader(paletteIndex: Int): LinearGradient =
        materialShaders.getOrPut(paletteIndex) {
            LinearGradient(
                0f, -1f, 0f, 1f,
                Theme.shapeRamp(score, paletteIndex),
                Theme.SHAPE_RAMP_STOPS,
                Shader.TileMode.CLAMP
            )
        }

    /**
     * One tile of film grain: monochrome speckle at low alpha, repeated. Grain is
     * the film rather than the subject, so the tile is a fixed size in pixels and
     * never scales with the shape it lies over.
     */
    private fun buildGrainTile(): Bitmap {
        val size = GRAIN_TILE
        val pixels = IntArray(size * size)
        val noise = java.util.Random(20260901L)
        for (i in pixels.indices) {
            // A narrow spread around mid grey. Wide noise reads as television
            // static; this is only just visible, which is the point of grain.
            val v = (128 + (noise.nextGaussian() * 46).toInt()).coerceIn(0, 255)
            pixels[i] = Color.argb(255, v, v, v)
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** Lays grain over whatever path was last built, clipped to it. */
    private fun drawGrain(canvas: Canvas, seed: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        path.computeBounds(shapeBounds, true)
        canvas.save()
        canvas.clipPath(path)
        // Offset per shape, so two shapes side by side are not wearing the same
        // speckle in the same place.
        grainMatrix.setTranslate(seed % GRAIN_TILE, (seed * 1.7f) % GRAIN_TILE)
        grainShader.setLocalMatrix(grainMatrix)
        grainPaint.shader = grainShader
        grainPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawRect(shapeBounds, grainPaint)
        grainPaint.shader = null
        grainPaint.alpha = 255
        canvas.restore()
    }

    private fun bodyShader(paletteIndex: Int): RadialGradient =
        bodyShaders.getOrPut(paletteIndex) {
            val light = tintedLight(paletteIndex)
            val deep = Theme.shapeDeep(score, paletteIndex)
            RadialGradient(
                0f, 0f, 1f,
                intArrayOf(Theme.lighten(light, 0.22f), light, deep),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }

    private fun drawShape(canvas: Canvas, shape: GameShape) {
        val verts = shape.worldVertices()
        val r = shape.radius * shape.spawnScale

        // Fake bloom: two scaled-up translucent copies behind the body. Cheaper and
        // hardware-accelerated, unlike a blur mask filter.
        canvas.save()
        canvas.translate(shape.x, shape.y)
        canvas.scale(1.14f, 1.14f)
        canvas.translate(-shape.x, -shape.y)
        buildPath(verts)
        glowPaint.color = Theme.withAlpha(tintedLight(shape.paletteIndex), 0.10f)
        canvas.drawPath(path, glowPaint)
        canvas.restore()

        canvas.save()
        canvas.translate(shape.x, shape.y)
        canvas.scale(1.06f, 1.06f)
        canvas.translate(-shape.x, -shape.y)
        buildPath(verts)
        glowPaint.color = Theme.withAlpha(tintedLight(shape.paletteIndex), 0.16f)
        canvas.drawPath(path, glowPaint)
        canvas.restore()

        buildPath(verts)
        if (settings.grainyShapes) {
            val shader = materialShader(shape.paletteIndex)
            shaderMatrix.reset()
            shaderMatrix.postScale(r, r * 1.04f)
            shaderMatrix.postTranslate(shape.x, shape.y)
            shader.setLocalMatrix(shaderMatrix)
            fillPaint.shader = shader
            canvas.drawPath(path, fillPaint)
            fillPaint.shader = null
            drawGrain(canvas, shape.spawnTimeMs.toFloat() % 997f, GRAIN_ALPHA)
        } else {
            // The original: a highlight in the upper left, no grain.
            val shader = bodyShader(shape.paletteIndex)
            shaderMatrix.reset()
            shaderMatrix.postScale(r * 1.55f, r * 1.55f)
            shaderMatrix.postTranslate(shape.x - r * 0.38f, shape.y - r * 0.42f)
            shader.setLocalMatrix(shaderMatrix)
            fillPaint.shader = shader
            canvas.drawPath(path, fillPaint)
            fillPaint.shader = null
        }

        drawNeonEdge(canvas, tintedLight(shape.paletteIndex), r)

        if (settings.guideLineEnabled) drawGuideLine(canvas, shape, verts, r)
    }

    /**
     * A neon tube around the outline. Four strokes on the same path, each narrower
     * and brighter than the last, ending on a near-white core - which is what a lit
     * gas tube actually looks like, and what a single fat stroke never does. The
     * whole stack breathes slightly so the edge reads as lit rather than printed.
     *
     * [path] is already built by the caller, so this is four fills of geometry that
     * has been walked once.
     */
    private fun drawNeonEdge(canvas: Canvas, tint: Int, r: Float) {
        val strength = settings.neonGlow
        val base = max(2f, r * 0.045f)

        if (strength <= 0.01f) {
            rimPaint.strokeWidth = base
            rimPaint.color = Theme.withAlpha(Theme.lighten(tint, 0.55f), 0.75f)
            canvas.drawPath(path, rimPaint)
            return
        }

        val breath = 0.86f + 0.14f * sin(elapsed * 2.7f + r)
        val halo = Theme.lighten(tint, 0.15f)

        // Outer bloom first, widest and faintest, then inward.
        rimPaint.strokeWidth = base * 5.5f
        rimPaint.color = Theme.withAlpha(halo, 0.10f * strength * breath)
        canvas.drawPath(path, rimPaint)

        rimPaint.strokeWidth = base * 3.2f
        rimPaint.color = Theme.withAlpha(halo, 0.17f * strength * breath)
        canvas.drawPath(path, rimPaint)

        rimPaint.strokeWidth = base * 1.7f
        rimPaint.color = Theme.withAlpha(Theme.lighten(tint, 0.5f), (0.55f * strength).coerceAtMost(0.8f))
        canvas.drawPath(path, rimPaint)

        // The core is pulled most of the way to white but keeps a little of the
        // shape's own hue, so a red shape still glows red rather than going grey.
        rimPaint.strokeWidth = base * 0.75f
        rimPaint.color = Theme.withAlpha(Theme.lighten(tint, 0.88f), (0.9f * strength).coerceAtMost(1f))
        canvas.drawPath(path, rimPaint)
    }

    /**
     * A faint dotted hint of where a 50/50 cut lands. The line is clipped to the
     * shape so it never spills outside the outline, and the offset is a per-kind
     * constant in unit space, rotated and scaled into place here.
     */
    private fun drawGuideLine(canvas: Canvas, shape: GameShape, verts: List<PointF2>, r: Float) {
        val c = cos(shape.rotation)
        val s = sin(shape.rotation)
        val offset = shape.kind.bisectorOffsetUnit * r
        // Normal of the shape's local +x axis, rotated into world space.
        val px = shape.x + -s * offset
        val py = shape.y + c * offset

        canvas.save()
        buildPath(verts)
        canvas.clipPath(path)

        val spacing = max(7f, r * 0.15f)
        val dotRadius = max(1.4f, r * 0.028f)
        // Drift the dots along the line so the hint reads as alive, not printed on.
        val phase = (elapsed * 14f) % spacing

        guidePaint.style = Paint.Style.FILL
        guidePaint.color = Theme.withAlpha(Color.WHITE, settings.guideLineOpacity)

        var t = -r - phase
        while (t <= r) {
            canvas.drawCircle(px + c * t, py + s * t, dotRadius, guidePaint)
            t += spacing
        }
        guidePaint.style = Paint.Style.STROKE

        canvas.restore()
    }

    private fun drawPiece(canvas: Canvas, piece: SlicedPiece) {
        if (piece.points.size < 3) return
        val alpha = piece.remaining
        canvas.save()
        canvas.translate(piece.x - piece.originX, piece.y - piece.originY)
        canvas.rotate(piece.spin, piece.originX, piece.originY)

        path.rewind()
        piece.points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()

        val tint = tintedLight(piece.paletteIndex)
        if (settings.grainyShapes) {
            // The halves keep the material, or a shape would change substance at
            // the instant it came apart.
            val shader = materialShader(piece.paletteIndex)
            shaderMatrix.reset()
            shaderMatrix.postScale(piece.radiusHint, piece.radiusHint * 1.04f)
            shaderMatrix.postTranslate(piece.originX, piece.originY)
            shader.setLocalMatrix(shaderMatrix)
            fillPaint.shader = shader
            fillPaint.alpha = (alpha * 242f).toInt().coerceIn(0, 255)
            canvas.drawPath(path, fillPaint)
            fillPaint.shader = null
            fillPaint.alpha = 255
            drawGrain(canvas, piece.originX, GRAIN_ALPHA * alpha)
        } else {
            fillPaint.color = Theme.withAlpha(tint, alpha * 0.95f)
            canvas.drawPath(path, fillPaint)
        }

        // The halves keep the neon while they fall, fading with the rest of them.
        val strength = settings.neonGlow
        if (strength > 0.01f) {
            rimPaint.strokeWidth = 9f
            rimPaint.color = Theme.withAlpha(Theme.lighten(tint, 0.15f), alpha * 0.14f * strength)
            canvas.drawPath(path, rimPaint)
        }
        rimPaint.strokeWidth = 3f
        rimPaint.color = Theme.withAlpha(Theme.lighten(tint, 0.85f), alpha * 0.55f)
        canvas.drawPath(path, rimPaint)
        canvas.restore()
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in effects.particles) {
            // Hold full brightness for most of the life, then fade off quickly.
            val life = p.remaining
            val alpha = (life * 1.6f).coerceAtMost(1f)
            particlePaint.color = Theme.withAlpha(p.color, alpha)

            when (p.shape) {
                ParticleShape.STREAK -> {
                    particlePaint.style = Paint.Style.STROKE
                    particlePaint.strokeWidth = p.size * 0.42f * life + 1.5f
                    val tail = 0.045f
                    canvas.drawLine(p.x, p.y, p.x - p.vx * tail, p.y - p.vy * tail, particlePaint)
                    particlePaint.style = Paint.Style.FILL
                }

                ParticleShape.DOT ->
                    canvas.drawCircle(p.x, p.y, p.size * life * 0.5f + 1f, particlePaint)

                ParticleShape.SHARD -> {
                    // A tumbling chip of the shape, so debris reads as broken material.
                    val half = p.size * life * 0.5f + 1f
                    canvas.save()
                    canvas.rotate(Math.toDegrees(p.rotation.toDouble()).toFloat(), p.x, p.y)
                    canvas.drawRect(p.x - half, p.y - half * 0.55f, p.x + half, p.y + half * 0.55f, particlePaint)
                    canvas.restore()
                }
            }
        }
    }

    private fun drawShockwaves(canvas: Canvas) {
        for (s in effects.shockwaves) {
            if (!s.started) continue
            val t = s.progress
            val radius = s.maxRadius * (1f - (1f - t) * (1f - t))
            ringPaint.strokeWidth = s.thickness * (1f - t) + 1f
            ringPaint.color = Theme.withAlpha(s.color, (1f - t) * 0.8f)
            canvas.drawCircle(s.x, s.y, radius, ringPaint)
        }
    }

    /** A brief full-screen wash of colour on a strong cut. */
    private fun drawFlash(canvas: Canvas) {
        val amount = effects.flash
        if (amount <= 0.005f) return
        flashPaint.color = Theme.withAlpha(effects.flashColor, (amount * 0.4f).coerceAtMost(0.5f))
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
    }

    private fun drawTrail(canvas: Canvas) {
        if (trailPoints.size < 2) return
        val nowMs = System.currentTimeMillis()
        val thickness = settings.trailThickness
        for (i in 1 until trailPoints.size) {
            val p0 = trailPoints[i - 1]
            val p1 = trailPoints[i]
            val age = (nowMs - p1.timeMs).toFloat() / trailMaxAgeMs
            val life = (1f - age).coerceIn(0f, 1f)

            // A wide soft halo under a bright core reads as a blade streak.
            // The blade keeps its own teal. Colouring it with the run turned the
            // one thing the player is steering into part of the scenery.
            trailPaint.color = Theme.withAlpha(Theme.accent, life * 0.30f)
            trailPaint.strokeWidth = (34f * life + 6f) * thickness
            canvas.drawLine(p0.x, p0.y, p1.x, p1.y, trailPaint)

            trailPaint.color = Theme.withAlpha(Color.WHITE, life * 0.95f)
            trailPaint.strokeWidth = (13f * life + 2.5f) * thickness
            canvas.drawLine(p0.x, p0.y, p1.x, p1.y, trailPaint)
        }
    }

    private fun drawPopups(canvas: Canvas) {
        for (p in effects.popups) {
            val t = p.progress
            val alpha = (1f - t * t).coerceIn(0f, 1f)
            val rise = t * 150f
            // Slight overshoot on entry so the text punches in rather than fading in.
            val pop = if (t < 0.18f) 0.6f + 2.2f * t else 1f + 0.06f * (1f - t)

            val baseSize = (19f + 13f * p.emphasis) * settings.popupTextScale
            displayPaint.textAlign = Paint.Align.CENTER
            displayPaint.textSize = baseSize * density * pop
            val y = p.y - rise

            // A soft halo behind the headline so big text stays readable over debris.
            if (p.emphasis > 0f) {
                displayPaint.style = Paint.Style.STROKE
                displayPaint.strokeWidth = 10f * density * p.emphasis
                displayPaint.color = Theme.withAlpha(p.color, alpha * 0.22f)
                canvas.drawText(p.headline, p.x, y, displayPaint)
                displayPaint.style = Paint.Style.FILL
            }

            displayPaint.color = Theme.withAlpha(p.color, alpha)
            canvas.drawText(p.headline, p.x, y, displayPaint)

            if (p.subline.isNotEmpty()) {
                uiBoldPaint.textAlign = Paint.Align.CENTER
                uiBoldPaint.textSize = (15f + 5f * p.emphasis) * density * settings.popupTextScale
                uiBoldPaint.color = Theme.withAlpha(Theme.textSecondary, alpha * 0.95f)
                canvas.drawText(p.subline, p.x, y + displayPaint.textSize * 0.78f, uiBoldPaint)
            }
        }
    }

    // ---- HUD ----

    private fun drawHud(canvas: Canvas) {
        val pad = 22f * density
        val barHeight = 13f * density
        val barTop = pad + 6f * density
        // The bar stops short of the pause target rather than running under it.
        val barWidth = width - pad * 2 - (PAUSE_BUTTON_SIZE + 12f) * density

        // Track
        roundRect.set(pad, barTop, pad + barWidth, barTop + barHeight)
        panelPaint.color = Theme.withAlpha(Color.WHITE, 0.10f)
        canvas.drawRoundRect(roundRect, barHeight / 2f, barHeight / 2f, panelPaint)

        // Fill
        val frac = (displayedHealth / maxHealth).coerceIn(0f, 1f)
        val critical = health <= settings.lowHealthAt && health > 0
        if (frac > 0.001f) {
            var healthColor = when {
                frac > 0.55f -> Theme.good
                frac > 0.25f -> Theme.gold
                else -> Theme.danger
            }
            // The bar breathes in time with the full-screen warning.
            if (critical) {
                val breath = 0.5f + 0.5f * cos(elapsed * 3.1f)
                healthColor = Theme.lerpColor(EMERGENCY_RED, Color.WHITE, breath * 0.45f)
            }
            roundRect.set(pad, barTop, pad + barWidth * frac, barTop + barHeight)
            panelPaint.color = healthColor
            canvas.drawRoundRect(roundRect, barHeight / 2f, barHeight / 2f, panelPaint)

            // Highlight along the top of the fill.
            roundRect.set(pad + 2f, barTop + 2f, pad + barWidth * frac - 2f, barTop + barHeight * 0.5f)
            if (roundRect.right > roundRect.left) {
                panelPaint.color = Theme.withAlpha(Color.WHITE, 0.28f)
                canvas.drawRoundRect(roundRect, barHeight / 4f, barHeight / 4f, panelPaint)
            }
        }

        uiBoldPaint.textAlign = Paint.Align.LEFT
        uiBoldPaint.textSize = 15f * density
        uiBoldPaint.color = if (critical) {
            Theme.lerpColor(EMERGENCY_RED, Color.WHITE, (0.5f + 0.5f * cos(elapsed * 3.1f)) * 0.45f)
        } else {
            Theme.textFaint
        }
        canvas.drawText(
            if (critical) "LOW HEALTH" else "HEALTH",
            pad, barTop + barHeight + 20f * density, uiBoldPaint
        )

        uiBoldPaint.textAlign = Paint.Align.RIGHT
        uiBoldPaint.color = Theme.textSecondary
        canvas.drawText(
            "${health.coerceAtLeast(0)}/$maxHealth",
            pad + barWidth,
            barTop + barHeight + 20f * density,
            uiBoldPaint
        )

        // Stage badge, top-right under the bar.
        uiBoldPaint.textAlign = Paint.Align.RIGHT
        uiBoldPaint.textSize = 14f * density
        uiBoldPaint.color = Theme.withAlpha(Theme.gold, 0.9f)
        canvas.drawText("STAGE ${stage + 1}", pad + barWidth, barTop + barHeight + 44f * density, uiBoldPaint)

        drawPauseButton(canvas)

        // Score, centred and large.
        displayPaint.textAlign = Paint.Align.CENTER
        displayPaint.textSize = 34f * density
        displayPaint.color = Theme.textPrimary
        canvas.drawText(displayedScore.roundToInt().toString(), width / 2f, barTop + barHeight + 62f * density, displayPaint)

        uiPaint.textSize = 14f * density
        uiPaint.color = Theme.textFaint
        canvas.drawText("SCORE", width / 2f, barTop + barHeight + 80f * density, uiPaint)

        // Everything wordy about the last cut lives here, under the score, rather
        // than over the shapes where it competed with the debris.
        val feedY = barTop + barHeight + 106f * density
        if (lastCutAge < LAST_CUT_HOLD) {
            val fade = (1f - lastCutAge / LAST_CUT_HOLD).coerceIn(0f, 1f)
            val pop = if (lastCutAge < 0.12f) 1f + 0.25f * (1f - lastCutAge / 0.12f) else 1f
            uiBoldPaint.textAlign = Paint.Align.CENTER
            uiBoldPaint.textSize = 20f * density * pop
            uiBoldPaint.color = Theme.withAlpha(lastCutColor, fade)
            canvas.drawText("$lastCutLabel  +$lastCutPoints", width / 2f, feedY, uiBoldPaint)
        }

        val streakY = feedY + 30f * density
        if (perfectStreak > 1) {
            val beat = 1f + 0.07f * sin(elapsed * 10f)
            uiBoldPaint.textAlign = Paint.Align.CENTER
            uiBoldPaint.textSize = 22f * density * beat
            uiBoldPaint.color = Theme.gold
            canvas.drawText(
                "${perfectStreak}x PERFECT  ·  ${"%.0f".format(comboMultiplier() * 100f)} PTS",
                width / 2f, streakY, uiBoldPaint
            )
        } else if (hotStreak > 1) {
            val beat = 1f + 0.05f * sin(elapsed * 9f)
            uiBoldPaint.textAlign = Paint.Align.CENTER
            uiBoldPaint.textSize = 20f * density * beat
            uiBoldPaint.color = Theme.good
            canvas.drawText(
                "${hotStreak}x GOOD STREAK",
                width / 2f, streakY, uiBoldPaint
            )
        } else if (coldStreak > 1) {
            val beat = 0.5f + 0.5f * sin(elapsed * 12f)
            uiBoldPaint.textAlign = Paint.Align.CENTER
            uiBoldPaint.textSize = 20f * density
            uiBoldPaint.color = Theme.lerpColor(Theme.danger, Color.WHITE, beat * 0.5f)
            canvas.drawText("${coldStreak}x COLD  ·  SCORE FALLING", width / 2f, streakY, uiBoldPaint)
        }
    }

    /**
     * The low-health warning is one slow red breath over the whole scene, drawn
     * between the action and the headline so the entire screen reads as alarmed
     * while the shapes stay visible through it.
     */
    private fun drawCriticalWarning(canvas: Canvas) {
        if (state != State.PLAYING || dangerAlert <= 0f) return

        val total = lowHealthSlowMoSeconds()
        // Fades out over the last third rather than vanishing mid-pulse.
        val presence = (dangerAlert / total).coerceIn(0f, 1f).let { min(1f, it * 3f) }
        // Two quick pulses across the alert, so it reads as an alarm, not a mood.
        val breath = (0.5f + 0.5f * cos((total - dangerAlert) * 9f)) * presence

        scrimPaint.shader = null
        scrimPaint.color = Theme.withAlpha(EMERGENCY_RED, (0.07f + 0.20f * breath) * presence)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // Heavier at the edges so the middle of the play area stays readable.
        val edge = width * 0.34f
        scrimPaint.shader = LinearGradient(
            0f, 0f, edge, 0f,
            Theme.withAlpha(EMERGENCY_RED, 0.26f * breath), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, edge, height.toFloat(), scrimPaint)
        scrimPaint.shader = LinearGradient(
            width.toFloat(), 0f, width - edge, 0f,
            Theme.withAlpha(EMERGENCY_RED, 0.26f * breath), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(width - edge, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        scrimPaint.shader = null

        val cx = width / 2f
        val cy = height * 0.5f

        displayPaint.textAlign = Paint.Align.CENTER
        displayPaint.textSize = 30f * density * (1f + 0.06f * breath)

        displayPaint.style = Paint.Style.STROKE
        displayPaint.strokeWidth = 12f * density
        displayPaint.color = Theme.withAlpha(EMERGENCY_RED, (0.30f + 0.35f * breath) * presence)
        canvas.drawText("LOW HEALTH", cx, cy, displayPaint)
        displayPaint.style = Paint.Style.FILL

        displayPaint.color = Theme.withAlpha(Color.WHITE, (0.72f + 0.28f * breath) * presence)
        canvas.drawText("LOW HEALTH", cx, cy, displayPaint)

        uiBoldPaint.textAlign = Paint.Align.CENTER
        uiBoldPaint.textSize = 17f * density
        uiBoldPaint.color = Theme.withAlpha(EMERGENCY_RED, (0.8f + 0.2f * breath) * presence)
        canvas.drawText("$health HP LEFT", cx, cy + 30f * density, uiBoldPaint)
    }

    // ---- Overlays ----

    /** Two bars in a soft disc - small, dim, and out of the way until wanted. */
    private fun drawPauseButton(canvas: Canvas) {
        val cx = pauseButton.centerX()
        val cy = pauseButton.centerY()
        val r = pauseButton.width() / 2f

        panelPaint.shader = null
        panelPaint.alpha = 255
        panelPaint.color = Theme.withAlpha(Color.WHITE, if (state == State.PAUSED) 0.18f else 0.10f)
        canvas.drawCircle(cx, cy, r, panelPaint)

        val barW = r * 0.24f
        val barH = r * 0.86f
        val gap = r * 0.22f
        panelPaint.color = Theme.withAlpha(Color.WHITE, 0.78f)
        roundRect.set(cx - gap - barW, cy - barH / 2f, cx - gap, cy + barH / 2f)
        canvas.drawRoundRect(roundRect, barW / 2f, barW / 2f, panelPaint)
        roundRect.set(cx + gap, cy - barH / 2f, cx + gap + barW, cy + barH / 2f)
        canvas.drawRoundRect(roundRect, barW / 2f, barW / 2f, panelPaint)
    }

    private fun drawPauseScreen(canvas: Canvas) {
        scrimPaint.shader = null
        scrimPaint.color = Color.argb(200, 2, 3, 8)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        val radius = 24f * density
        panelPaint.shader = null
        panelPaint.alpha = 255
        panelPaint.color = Theme.card
        canvas.drawRoundRect(pauseCard, radius, radius, panelPaint)
        panelStrokePaint.color = Theme.hairline
        canvas.drawRoundRect(pauseCard, radius, radius, panelStrokePaint)

        val cx = pauseCard.centerX()

        uiBoldPaint.textAlign = Paint.Align.CENTER
        uiBoldPaint.textSize = 15f * density
        uiBoldPaint.letterSpacing = 0.16f
        uiBoldPaint.color = Theme.accent
        canvas.drawText("PAUSED", cx, pauseCard.top + 34f * density, uiBoldPaint)
        uiBoldPaint.letterSpacing = 0f

        displayPaint.textAlign = Paint.Align.CENTER
        displayPaint.textSize = 40f * density
        displayPaint.color = Theme.textPrimary
        canvas.drawText(score.toString(), cx, pauseCard.top + 82f * density, displayPaint)

        uiPaint.textSize = 14f * density
        uiPaint.color = Theme.textFaint
        canvas.drawText("SCORE", cx, pauseCard.top + 100f * density, uiPaint)

        drawButton(canvas, pauseResume, "RESUME", primary = true, pressed = pressedButton == 1)
        drawButton(canvas, pauseSettings, "SETTINGS", primary = false, pressed = pressedButton == 3)
        drawButton(canvas, pauseMenu, "MAIN MENU", primary = false, pressed = pressedButton == 2)
    }

    /**
     * The gate card. One heading, one paragraph, two buttons, and the same inset on
     * every side - the card is measured from the wrapped copy in layoutAdOverlay,
     * so this only has to draw where that said things go.
     */
    private fun drawAdCard(canvas: Canvas, headline: String, body: String) {
        scrimPaint.shader = null
        scrimPaint.color = Color.argb(214, 2, 3, 8)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        val radius = 24f * density
        panelPaint.shader = null
        panelPaint.alpha = 255
        panelPaint.color = Theme.card
        canvas.drawRoundRect(adCard, radius, radius, panelPaint)
        panelStrokePaint.color = Theme.hairline
        canvas.drawRoundRect(adCard, radius, radius, panelStrokePaint)

        val cx = adCard.centerX()

        displayPaint.textAlign = Paint.Align.CENTER
        displayPaint.textSize = 26f * density
        displayPaint.color = Theme.accent
        canvas.drawText(headline, cx, adCard.top + (AD_CARD_PAD + 26f) * density, displayPaint)

        uiPaint.textAlign = Paint.Align.CENTER
        uiPaint.textSize = AD_BODY_SIZE * density
        uiPaint.color = Theme.textSecondary
        drawWrapped(
            canvas, body, cx,
            adCard.top + (AD_CARD_PAD + AD_TITLE_DROP) * density + AD_BODY_SIZE * density,
            adCard.width() - AD_CARD_PAD * 2f * density, uiPaint
        )
    }

    /** Greedy word wrap, shared by the measure pass and the draw. */
    private fun wrapLines(text: String, maxWidth: Float, paint: Paint): List<String> {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                lines.add(line.toString())
                line.setLength(0)
                line.append(word)
            } else {
                line.setLength(0)
                line.append(candidate)
            }
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        return lines
    }

    private fun drawWrapped(canvas: Canvas, text: String, cx: Float, top: Float, maxWidth: Float, paint: Paint) {
        var y = top
        val step = paint.textSize * 1.3f
        for (line in wrapLines(text, maxWidth, paint)) {
            canvas.drawText(line, cx, y, paint)
            y += step
        }
    }

    /** The gate's copy, needed by the measure pass as well as the draw. */
    private fun adGateBody(): String =
        "Every ${settings.adGateEvery}th retry requires you to watch an ad. This is to support " +
            "the development of the app and continue making it better and more interesting. " +
            "Please consider supporting the devs. This will only take a few seconds."

    /**
     * The continue, sold rather than merely listed: gold instead of teal, breathing
     * behind three layered haloes so it reads as the offer and not another row in a
     * list. The cost is carried by a superscript AD set against the label, the way
     * a footnote marker rides a word - present, unmissable once seen, and taking up
     * none of the room a second line of copy would.
     */
    private fun drawContinueButton(canvas: Canvas, alpha: Float) {
        if (!continueOffered || overContinue.isEmpty) return

        val rect = overContinue
        val pressed = pressedButton == 5
        val pulse = 0.5f + 0.5f * sin(elapsed * 3.2f)
        val radius = rect.height() / 2f

        // Three haloes rather than a blur mask filter: hardware accelerated, and
        // the layering is what makes it read as glow instead of a fat outline.
        panelPaint.shader = null
        for (i in 3 downTo 1) {
            val spread = i * 5f * density * (0.7f + 0.3f * pulse)
            roundRect.set(
                rect.left - spread, rect.top - spread,
                rect.right + spread, rect.bottom + spread
            )
            panelPaint.color = Theme.withAlpha(Theme.gold, (0.11f / i) * (0.55f + 0.45f * pulse) * alpha)
            canvas.drawRoundRect(roundRect, radius + spread, radius + spread, panelPaint)
        }

        val inset = if (pressed) 2f * density else 0f
        roundRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        panelPaint.shader = LinearGradient(
            roundRect.left, roundRect.top, roundRect.right, roundRect.bottom,
            Theme.withAlpha(Theme.gold, alpha), Theme.withAlpha(Theme.goldDeep, alpha),
            Shader.TileMode.CLAMP
        )
        // The shader is modulated by the paint's own alpha, and the haloes above
        // just left it low.
        panelPaint.alpha = 255
        canvas.drawRoundRect(roundRect, radius, radius, panelPaint)
        panelPaint.shader = null
        panelPaint.alpha = 255

        panelStrokePaint.color =
            Theme.withAlpha(Theme.lighten(Theme.gold, 0.65f), (0.4f + 0.45f * pulse) * alpha)
        canvas.drawRoundRect(roundRect, radius, radius, panelStrokePaint)
        panelStrokePaint.color = Theme.hairline

        drawMarkedLabel(canvas, roundRect, CONTINUE_LABEL, INK_ON_GOLD, alpha)
    }

    /**
     * A label with a small raised AD after it, the way a footnote marker rides a
     * word. Measured as one unit and centred as one unit, so the button reads as
     * balanced rather than as a word with something stuck after it.
     */
    private fun drawMarkedLabel(canvas: Canvas, rect: RectF, label: String, ink: Int, alpha: Float) {
        val labelSize = 18f * density
        val markSize = labelSize * 0.52f
        uiBoldPaint.textAlign = Paint.Align.LEFT

        uiBoldPaint.textSize = labelSize
        val labelWidth = uiBoldPaint.measureText(label)
        val baseline = rect.centerY() - (uiBoldPaint.descent() + uiBoldPaint.ascent()) / 2f

        uiBoldPaint.textSize = markSize
        uiBoldPaint.letterSpacing = 0.06f
        val markWidth = uiBoldPaint.measureText(CONTINUE_MARK)
        uiBoldPaint.letterSpacing = 0f

        val kern = 2.5f * density
        val startX = rect.centerX() - (labelWidth + kern + markWidth) / 2f

        uiBoldPaint.textSize = labelSize
        uiBoldPaint.color = Theme.withAlpha(ink, alpha)
        canvas.drawText(label, startX, baseline, uiBoldPaint)

        uiBoldPaint.textSize = markSize
        uiBoldPaint.letterSpacing = 0.06f
        // Raised most of a cap height, which is where an exponent sits.
        canvas.drawText(CONTINUE_MARK, startX + labelWidth + kern, baseline - labelSize * 0.46f, uiBoldPaint)
        uiBoldPaint.letterSpacing = 0f
        uiBoldPaint.textAlign = Paint.Align.CENTER
    }

    /** A plain primary button carrying the same superscript AD. */
    private fun drawMarkedButton(canvas: Canvas, rect: RectF, label: String, pressed: Boolean) {
        drawButton(canvas, rect, "", primary = true, pressed = pressed)
        val inset = if (pressed) 2f * density else 0f
        roundRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        drawMarkedLabel(canvas, roundRect, label, Color.rgb(6, 20, 26), 1f)
    }

    private fun drawAdGate(canvas: Canvas) {
        drawAdCard(canvas, "AD BREAK", adGateBody())
        // Marked the same way the continue is, so the two ad-backed buttons in the
        // game read as the same kind of thing.
        drawMarkedButton(canvas, adPrimary, "RETRY", pressed = pressedButton == 1)
        drawButton(canvas, adSecondary, "EXIT APP", primary = false, pressed = pressedButton == 2)
    }

    /**
     * The waiting screen. It used to be a dead-end label; it is now a screen with a
     * way off it, because an ad that never arrives must not be able to end the
     * session. The dots say something is still happening, and CANCEL is there from
     * the first frame rather than appearing once things have already gone wrong.
     */
    private fun drawAdPending(canvas: Canvas) {
        scrimPaint.shader = null
        scrimPaint.color = Color.argb(240, 2, 3, 8)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        val cx = width / 2f

        if (confirmingAdExit) {
            displayPaint.textAlign = Paint.Align.CENTER
            displayPaint.textSize = 22f * density
            displayPaint.color = Theme.textPrimary
            canvas.drawText("SKIP THE REWARD?", cx, adConfirmLeave.top - 62f * density, displayPaint)

            uiPaint.textAlign = Paint.Align.CENTER
            uiPaint.textSize = 15f * density
            uiPaint.color = Theme.textFaint
            drawWrapped(
                canvas, "Leaving now means no continue.",
                cx, adConfirmLeave.top - 32f * density, width * 0.7f, uiPaint
            )

            drawButton(canvas, adConfirmLeave, "SKIP IT", primary = false, pressed = pressedButton == 1, textSize = 16f * density)
            drawButton(canvas, adConfirmStay, "KEEP WATCHING", primary = true, pressed = pressedButton == 2, textSize = 16f * density)
            return
        }

        uiBoldPaint.textAlign = Paint.Align.CENTER
        uiBoldPaint.textSize = 16f * density
        uiBoldPaint.letterSpacing = 0.18f
        uiBoldPaint.color = Theme.textSecondary
        canvas.drawText("LOADING AD", cx, height * 0.46f, uiBoldPaint)
        uiBoldPaint.letterSpacing = 0f

        // Three dots taking it in turns, so a slow load still looks alive.
        val dotY = height * 0.46f + 22f * density
        val dotRadius = 3f * density
        panelPaint.shader = null
        for (i in 0..2) {
            val phase = (elapsed * 2.6f - i * 0.35f) % 1f
            val lift = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
            panelPaint.color = Theme.withAlpha(Theme.accent, 0.25f + 0.6f * lift)
            canvas.drawCircle(cx + (i - 1) * 14f * density, dotY, dotRadius, panelPaint)
        }

        drawButton(canvas, adCancel, "CANCEL", primary = false, pressed = pressedButton == 1, textSize = 16f * density)
    }

    /** 3, 2, 1 over the cleared board before a bought-back run picks up. */
    private fun drawResumeCountdown(canvas: Canvas) {
        scrimPaint.shader = null
        scrimPaint.color = Color.argb(150, 2, 3, 8)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        val cx = width / 2f
        val cy = height * 0.46f
        val seconds = ceil(resumeCountdown.toDouble()).toInt().coerceAtLeast(1)
        // Each digit swells as it lands and shrinks as its second runs out.
        val within = resumeCountdown - (seconds - 1)
        val pop = 1f + 0.35f * (1f - within).coerceIn(0f, 1f)

        displayPaint.textAlign = Paint.Align.CENTER
        displayPaint.textSize = 96f * density / pop
        displayPaint.color = Theme.withAlpha(Theme.accent, (within * 1.4f).coerceIn(0.15f, 1f))
        canvas.drawText(seconds.toString(), cx, cy, displayPaint)

        uiBoldPaint.textAlign = Paint.Align.CENTER
        uiBoldPaint.textSize = 18f * density
        uiBoldPaint.letterSpacing = 0.16f
        uiBoldPaint.color = Theme.textSecondary
        canvas.drawText("BACK IN", cx, cy - 92f * density, uiBoldPaint)
        uiBoldPaint.letterSpacing = 0f

        uiPaint.textAlign = Paint.Align.CENTER
        uiPaint.textSize = 16f * density
        uiPaint.color = Theme.textFaint
        canvas.drawText("Score $score kept", cx, cy + 44f * density, uiPaint)
    }

    /**
     * Why an ad did not pay out. The continue button is always there now, so this
     * line is what a press that could not fetch an ad turns into - it has to be
     * legible as a failure rather than read as decoration.
     */
    private fun drawAdNotice(canvas: Canvas, cy: Float) {
        if (adNoticeAge > AD_NOTICE_HOLD || adNotice.isEmpty()) return
        val fade = (1f - adNoticeAge / AD_NOTICE_HOLD).coerceIn(0f, 1f)
        uiBoldPaint.textAlign = Paint.Align.CENTER
        uiBoldPaint.textSize = 14f * density
        uiBoldPaint.color = Theme.withAlpha(Theme.danger, fade)
        canvas.drawText(adNotice, width / 2f, cy, uiBoldPaint)
    }

    private fun drawReadyScreen(canvas: Canvas) {
        // The demo behind this is scenery, not the subject: held well back so the
        // title and the buttons are what the eye lands on.
        scrimPaint.shader = null
        scrimPaint.color = Color.argb(216, 2, 3, 8)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        val cx = width / 2f
        val titleY = height * 0.24f

        displayPaint.textAlign = Paint.Align.CENTER
        displayPaint.textSize = 30f * density
        displayPaint.color = Theme.textPrimary
        canvas.drawText("HALF", cx, titleY, displayPaint)
        displayPaint.color = Theme.accent
        canvas.drawText("MEASURES", cx, titleY + 38f * density, displayPaint)

        uiPaint.textSize = 19f * density
        uiPaint.color = Theme.textSecondary
        canvas.drawText("Slice every shape exactly in half.", cx, titleY + 76f * density, uiPaint)

        // A gentle nudge that the button itself can be cut.
        val hintPulse = 0.55f + 0.45f * (0.5f + 0.5f * sin(elapsed * 2.4f))
        uiPaint.textSize = 15f * density
        uiPaint.color = Theme.withAlpha(Theme.accent, hintPulse)
        canvas.drawText("Tap PLAY - or slice straight through it.", cx, titleY + 100f * density, uiPaint)

        // Only after a run: a rank handed out before anyone has played says
        // nothing about them.
        if (runsFinished > 0) {
            val y = height * 0.42f
            drawScorePills(canvas, cx, y, 1f, showDelta = false)
            drawRankPill(
                canvas, cx, y + (PILL_HEIGHT + SUMMARY_GAP) * density,
                Ranks.forScore(averageScore), 1f
            )
        }

        drawAdNotice(canvas, primaryButton.top - 14f * density)
        drawButton(canvas, primaryButton, "PLAY", primary = true, pressed = pressedButton == 1)
        drawButton(canvas, secondaryButton, "HOW TO PLAY", primary = false, pressed = pressedButton == 2)
        drawButton(canvas, tertiaryButton, "SETTINGS", primary = false, pressed = pressedButton == 3)
    }

    /**
     * The card's reveal, scored. Each element that slides in gets its own noise
     * from the banks the game already carries: a swipe as the card arrives, a
     * chime under the score, a rising tick per stat row, and a flourish if the
     * run changed the player's rank. Fired once each, in order, off the same
     * clock the drawing reads - so the sound cannot drift from the picture.
     */
    private fun playCardCues() {
        while (cardCue < CARD_CUE_TIMES.size && cardReveal >= CARD_CUE_TIMES[cardCue]) {
            when (val cue = cardCue) {
                0 -> sounds.play(SfxBank.SWIPE, gain = 0.55f)
                1 -> sounds.play(Sfx.HEAL, gain = 0.7f, rate = 0.92f)
                2 -> {
                    // The rank line lands with its own flourish, up or down.
                    if (rankMoved > 0) sounds.play(Sfx.RANK_UP, gain = 1f)
                    else if (rankMoved < 0) sounds.play(Sfx.RANK_DOWN, gain = 0.9f)
                    else sounds.play(Sfx.BUTTON, gain = 0.45f, rate = 1.15f)
                }
                // One tick per stat row, each a step higher than the last.
                else -> sounds.play(
                    Sfx.BUTTON, gain = 0.32f, rate = 1f + (cue - 3) * 0.09f
                )
            }
            cardCue++
        }
    }

    /** 0 until [at], then eases to 1 - the stagger behind the card's reveal. */
    private fun revealAlpha(at: Float, span: Float = 0.3f): Float {
        val t = ((cardReveal - at) / span).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t)
    }

    private fun drawGameOverScreen(canvas: Canvas) {
        // The scrim fades in with the card rather than slamming on.
        val scrimAlpha = revealAlpha(0f, 0.4f)
        scrimPaint.shader = null
        scrimPaint.color = Color.argb((205 * scrimAlpha).toInt(), 3, 5, 12)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        if (beatBestScore) {
            pixels.draw(canvas, pixelPaint, effects.energy)
            drawShockwaves(canvas)
            drawParticles(canvas)
        }

        val boxAlpha = revealAlpha(0f, 0.32f)
        val cardLeft = gameOverCard.left
        val cardRight = gameOverCard.right
        val cardTop = gameOverCard.top
        val cx = gameOverCard.centerX()
        val padX = CARD_PAD * density
        val ruleGap = CARD_RULE_GAP * density
        val rowStep = CARD_STAT_ROW_HEIGHT * density

        // The whole card eases up into place from slightly small and low.
        canvas.save()
        val scale = 0.94f + 0.06f * boxAlpha
        canvas.translate(0f, (1f - boxAlpha) * 26f * density)
        canvas.scale(scale * CARD_SCALE, scale * CARD_SCALE, cx, gameOverCard.centerY())

        val radius = 26f * density
        panelPaint.shader = null
        panelPaint.color = Theme.withAlpha(Theme.card, boxAlpha)
        canvas.drawRoundRect(gameOverCard, radius, radius, panelPaint)
        panelStrokePaint.color = Theme.withAlpha(Theme.hairline, boxAlpha)
        canvas.drawRoundRect(gameOverCard, radius, radius, panelStrokePaint)
        panelStrokePaint.color = Theme.hairline

        val titleAlpha = revealAlpha(CARD_TITLE_AT)
        val accentColor = if (endedOnMiss) Theme.danger else Theme.gold

        uiBoldPaint.textAlign = Paint.Align.CENTER
        uiBoldPaint.textSize = 17f * density
        if (beatBestScore) {
            // A record headline blinks and swells so it cannot be mistaken for the
            // ordinary end-of-run line.
            val blink = 0.55f + 0.45f * cos(cardReveal * 7.5f)
            uiBoldPaint.textSize = 19f * density * (1f + 0.09f * blink)
            uiBoldPaint.color = Theme.withAlpha(
                Theme.lerpColor(Theme.gold, Color.WHITE, blink * 0.7f), titleAlpha
            )
        } else {
            uiBoldPaint.color = Theme.withAlpha(accentColor, titleAlpha)
        }
        canvas.drawText(
            when {
                beatBestScore -> "NEW BEST!"
                endedOnMiss -> "ONE GOT AWAY"
                else -> "OUT OF HEALTH"
            },
            cx, cardTop + 40f * density, uiBoldPaint
        )

        // The score lands with a flash that settles into its final colour.
        val scoreAlpha = revealAlpha(CARD_SCORE_AT, 0.26f)
        val scoreFlash = (1f - ((cardReveal - CARD_SCORE_AT) / 0.45f)).coerceIn(0f, 1f)
        displayPaint.textAlign = Paint.Align.CENTER
        displayPaint.textSize = 52f * density * (1f + 0.14f * scoreFlash)
        displayPaint.color = Theme.withAlpha(
            Theme.lerpColor(
                if (beatBestScore) Theme.gold else Theme.textPrimary,
                Color.WHITE,
                scoreFlash
            ),
            scoreAlpha
        )
        canvas.drawText(score.toString(), cx, cardTop + 112f * density, displayPaint)

        uiPaint.textAlign = Paint.Align.CENTER
        uiPaint.textSize = 15f * density
        uiPaint.color = Theme.withAlpha(Theme.textFaint, scoreAlpha)
        canvas.drawText("FINAL SCORE", cx, cardTop + 132f * density, uiPaint)

        // The all-time best sits with the score it is measured against rather than
        // buried in the table below, which is the only place a player looks first.
        // The rank-change line's space is reserved either way and the block is
        // centred in it, so a promotion cannot push the card - or the buttons under
        // it - down the screen, and the spacing stays even when there is no line.
        val pillsY = cardTop + (SUMMARY_PILLS_Y + if (rankMoved != 0) 0f else RESERVED_FLASH / 2f) * density

        drawScorePills(canvas, cx, pillsY, scoreAlpha)

        // The rank follows the average, not the best. A best score is one lucky
        // afternoon; an average is how well you actually play.
        val rank = Ranks.forScore(averageScore)
        val rankAlpha = revealAlpha(CARD_SCORE_AT + 0.1f, 0.3f)

        // Every gap down this block is the same clear space, measured between what
        // the eye can actually see - pill edges, the pips' own radius, and the ink
        // of the line below - rather than between centres of things that are not
        // the same height.
        val gap = SUMMARY_GAP * density
        val pillHeight = PILL_HEIGHT * density

        // A promotion or demotion gets a small line of its own, tucked close above
        // the pill it is talking about - closer than the block's own rhythm, the
        // way a caption sits with its subject.
        var below = pillsY + pillHeight / 2f
        if (rankMoved != 0) {
            val up = rankMoved > 0
            // Only the colour pulses. Swelling the type as well moved the text's
            // own box every frame, which is what made it look shoved in.
            val beat = 0.55f + 0.45f * cos(cardReveal * 8f)
            uiBoldPaint.textAlign = Paint.Align.CENTER
            uiBoldPaint.textSize = RANK_FLASH_TEXT * density
            uiBoldPaint.letterSpacing = 0.16f
            uiBoldPaint.color = Theme.withAlpha(
                Theme.lerpColor(
                    if (up) rungColor(rank.number) else Theme.danger, Color.WHITE, beat * 0.55f
                ),
                rankAlpha
            )
            val line = if (up) "YOU JUST RANKED UP" else "YOU JUST RANKED DOWN"
            // Tracking adds a trailing space after the last letter, which a centred
            // draw counts as ink; half of it back puts the line on the true centre.
            canvas.drawText(
                line, cx + RANK_FLASH_TEXT * 0.16f * density / 2f,
                below + gap + RANK_FLASH_SPACE * density, uiBoldPaint
            )
            uiBoldPaint.letterSpacing = 0f
            below += RESERVED_FLASH * density
        }

        val rankY = below + gap + pillHeight / 2f
        drawRankPill(canvas, cx, rankY, rank, rankAlpha)

        val pipReach = ladderReach()
        val ladderY = rankY + pillHeight / 2f + gap + pipReach
        drawRankLadder(canvas, cx, ladderY, rank, rankAlpha)

        uiPaint.textAlign = Paint.Align.CENTER
        uiPaint.textSize = 13f * density
        uiPaint.color = Theme.withAlpha(Theme.textFaint, rankAlpha)
        val goal = rankGoalText(rank)
        uiPaint.getTextBounds(goal, 0, goal.length, inkBounds)
        canvas.drawText(goal, cx, ladderY + pipReach + gap - inkBounds.top, uiPaint)

        rimPaint.strokeWidth = 1.5f
        rimPaint.color = Theme.withAlpha(Theme.hairline, revealAlpha(CARD_ROWS_AT - 0.1f))
        val dividerY = cardTop + CARD_HEADER_HEIGHT * density
        canvas.drawLine(cardLeft + padX, dividerY, cardRight - padX, dividerY, rimPaint)

        val left = cardLeft + padX
        val right = cardRight - padX
        var rowY = dividerY + ruleGap

        // Stats arrive one at a time rather than all at once.
        // Two numeric columns, right-aligned, so the eye can run straight down
        // either one.
        val bestRight = right
        val runRight = right - CARD_BEST_COLUMN * density

        drawStatHeader(canvas, runRight, bestRight, rowY)
        rowY += rowStep
        drawStatRow(
            canvas, left, runRight, bestRight, rowY, "CUTS SURVIVED",
            cutCount.toString(), bestCuts.toString(), Theme.textPrimary, 1
        )
        rowY += rowStep
        drawStatRow(
            canvas, left, runRight, bestRight, rowY, "PERFECT CUTS",
            perfectCount.toString(), bestPerfectCuts.toString(), Theme.gold, 2
        )
        rowY += rowStep
        drawStatRow(
            canvas, left, runRight, bestRight, rowY, "PERFECT STREAK",
            "${bestPerfectStreak}x", "${recordPerfectStreak}x", Theme.gold, 3
        )
        rowY += rowStep
        drawStatRow(
            canvas, left, runRight, bestRight, rowY, "GOOD STREAK",
            "${bestStreak}x", "${recordGoodStreak}x", Theme.good, 4
        )

        // The same baseline-rule-baseline gap as the header divider above.
        drawCutBreakdown(canvas, cardLeft, cardRight, rowY + ruleGap * 2, CARD_BREAKDOWN_ROW_HEIGHT * density)

        canvas.restore()

        // Buttons come in last, once the card has finished settling.
        val buttonAlpha = revealAlpha(CARD_ROWS_AT + CARD_ROW_STAGGER * 5 + CARD_BREAKDOWN_STAGGER * 5)
        if (buttonAlpha > 0.01f) {
            val label = 18f * density
            drawContinueButton(canvas, buttonAlpha)
            drawButton(canvas, primaryButton, "RETRY", primary = true, pressed = pressedButton == 1, alpha = buttonAlpha, textSize = label)
            drawButton(canvas, overQuaternary, "MAIN MENU", primary = false, pressed = pressedButton == 4, alpha = buttonAlpha, textSize = label)
            drawAdNotice(canvas, gameOverCardVisual.bottom + 20f * density)
        }
    }

    /**
     * How the run's cuts were distributed across accuracy bands. Every band is
     * listed even when empty, so the player can see at a glance both what they hit
     * and what they avoided, with bars scaled against the most common band.
     */
    private fun drawCutBreakdown(
        canvas: Canvas,
        cardLeft: Float,
        cardRight: Float,
        top: Float,
        rowHeight: Float
    ) {
        var peak = 0
        for (count in cutBuckets) peak = max(peak, count)

        val padX = CARD_PAD * density
        val ruleGap = CARD_RULE_GAP * density

        val headingAlpha = revealAlpha(CARD_ROWS_AT + CARD_ROW_STAGGER * 5)

        // The rule sits the same distance above this heading as the header divider
        // sits above the stats, rather than nearly touching the type.
        val ruleY = top - ruleGap
        rimPaint.strokeWidth = 1.5f
        rimPaint.color = Theme.withAlpha(Theme.hairline, headingAlpha)
        canvas.drawLine(cardLeft + padX, ruleY, cardRight - padX, ruleY, rimPaint)

        uiBoldPaint.textAlign = Paint.Align.LEFT
        uiBoldPaint.textSize = 14f * density
        uiBoldPaint.letterSpacing = 0.14f
        // Same face as everywhere else, weighted up: this is a section heading and
        // was reading lighter than the rows it introduces.
        uiBoldPaint.isFakeBoldText = true
        uiBoldPaint.color = Theme.withAlpha(Theme.textPrimary, headingAlpha)
        canvas.drawText("HOW YOUR CUTS LANDED", cardLeft + padX, top, uiBoldPaint)
        uiBoldPaint.isFakeBoldText = false
        uiBoldPaint.letterSpacing = 0f

        val labelWidth = 46f * density
        val countWidth = 34f * density
        val barLeft = cardLeft + padX + labelWidth
        val barRight = cardRight - padX - countWidth
        val barSpan = (barRight - barLeft).coerceAtLeast(1f)

        // Placed so the first row's text sits one rule-gap below the heading, the
        // same rhythm the stats have under their divider.
        var y = top + ruleGap - rowHeight / 2f - CARD_ROW_TEXT_OFFSET * density
        // Band 0 is PERFECT, already reported as its own stat above - listing it
        // here again would just repeat the same number.
        var shown = 0
        for (index in 1 until cutBuckets.size) {
            val count = cutBuckets[index]
            val rowAlpha = revealAlpha(CARD_ROWS_AT + CARD_ROW_STAGGER * 5 + CARD_BREAKDOWN_STAGGER * shown)
            shown++
            if (rowAlpha <= 0.01f) break
            val centerY = y + rowHeight * 0.5f

            uiBoldPaint.textAlign = Paint.Align.LEFT
            uiBoldPaint.textSize = 14f * density
            uiBoldPaint.color = Theme.withAlpha(
                if (count > 0) Theme.textPrimary else Theme.textFaint, rowAlpha
            )
            canvas.drawText(
                CUT_BUCKET_LABELS[index],
                cardLeft + padX,
                centerY + CARD_ROW_TEXT_OFFSET * density,
                uiBoldPaint
            )

            val barHeight = 8f * density
            roundRect.set(barLeft, centerY - barHeight / 2f, barRight, centerY + barHeight / 2f)
            panelPaint.color = Theme.withAlpha(Color.WHITE, 0.07f * rowAlpha)
            canvas.drawRoundRect(roundRect, barHeight / 2f, barHeight / 2f, panelPaint)

            if (count > 0 && peak > 0) {
                // The bar itself wipes out from the left as the row arrives.
                val fraction = count.toFloat() / peak * rowAlpha
                roundRect.set(
                    barLeft, centerY - barHeight / 2f,
                    barLeft + barSpan * fraction, centerY + barHeight / 2f
                )
                panelPaint.color = Theme.withAlpha(bucketColor(index), rowAlpha)
                canvas.drawRoundRect(roundRect, barHeight / 2f, barHeight / 2f, panelPaint)
            }

            uiBoldPaint.textAlign = Paint.Align.RIGHT
            uiBoldPaint.color = Theme.withAlpha(
                if (count > 0) Theme.textPrimary else Theme.textFaint, rowAlpha
            )
            canvas.drawText(
                count.toString(),
                cardRight - padX,
                centerY + CARD_ROW_TEXT_OFFSET * density,
                uiBoldPaint
            )

            y += rowHeight
        }
    }

    /**
     * What it takes to rank up, stated as the thing the player can actually aim
     * at. "2,062 to Hexagon Halver" told them a gap they would have to do
     * arithmetic on; the target average is the number itself.
     */
    private fun rankGoalText(rank: Rank): String {
        if (Ranks.next(rank) == null) return "TOP RANK"
        return "Get your average to ${formatScore(rank.ceiling)} to rank up"
    }

    /** Thousands separators, so a five-figure target reads at a glance. */
    private fun formatScore(value: Int): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val out = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
            out.append(c)
        }
        return out.toString()
    }

    /** Bands shade from gold at dead centre through to red at a total whiff. */
    private fun bucketColor(index: Int): Int = when (index) {
        0 -> Theme.gold
        1 -> Theme.good
        2 -> Theme.accent
        3 -> Color.rgb(255, 190, 90)
        4 -> Color.rgb(255, 140, 80)
        else -> Theme.danger
    }

    /**
     * One text size for every word in the summary block.
     *
     * The captions used to run four points smaller than the figures beside them,
     * which left BEST and AVG sitting on a different optical line to their own
     * numbers. Everything in a pill is now set at the same size and on the same
     * baseline, and the caption is separated by a colon rather than by size.
     */
    private fun pillTextSize(): Float = PILL_TEXT * density

    /** Measures a run of pill text at the block's one size and tracking. */
    private fun measurePill(text: String): Float {
        uiBoldPaint.textSize = pillTextSize()
        uiBoldPaint.letterSpacing = PILL_TRACKING
        val w = uiBoldPaint.measureText(text)
        uiBoldPaint.letterSpacing = 0f
        return w
    }

    /**
     * Half the height of a badge, taken from the ink of the text it sits beside -
     * so the shape spans exactly the same top-to-bottom as the letters and never
     * towers over them.
     */
    private fun glyphRadius(): Float {
        uiBoldPaint.textSize = pillTextSize()
        return (uiBoldPaint.descent() - uiBoldPaint.ascent()) * GLYPH_OF_TEXT
    }

    /** The baseline that centres pill text on [cy], at the block's one size. */
    private fun pillBaseline(cy: Float): Float {
        uiBoldPaint.textSize = pillTextSize()
        return cy - (uiBoldPaint.descent() + uiBoldPaint.ascent()) / 2f
    }

    /** Width a labelled pill needs, so a row of them can be centred as a group. */
    private fun pillWidth(label: String, value: String): Float =
        PILL_PAD * density + measurePill(label) + PILL_GAP * density +
            measurePill(value) + PILL_PAD * density

    /**
     * A small labelled capsule: a caption, a colon, and the figure. The whole
     * summary block is built from these so it reads as one family rather than as
     * a chip, a badge and a heading that happen to be near each other.
     */
    private fun drawPillAt(
        canvas: Canvas,
        left: Float,
        cy: Float,
        label: String,
        value: String,
        accent: Int,
        alpha: Float,
        delta: Int = 0
    ) {
        val width = pillWidth(label, value) + deltaWidth(delta)
        val height = PILL_HEIGHT * density
        roundRect.set(left, cy - height / 2f, left + width, cy + height / 2f)
        panelPaint.shader = null
        panelPaint.alpha = 255
        panelPaint.color = Theme.withAlpha(accent, 0.14f * alpha)
        canvas.drawRoundRect(roundRect, height / 2f, height / 2f, panelPaint)

        val baseline = pillBaseline(cy)
        var x = left + PILL_PAD * density

        uiBoldPaint.textAlign = Paint.Align.LEFT
        uiBoldPaint.textSize = pillTextSize()
        uiBoldPaint.letterSpacing = PILL_TRACKING
        uiBoldPaint.color = Theme.withAlpha(Theme.textFaint, alpha)
        canvas.drawText(label, x, baseline, uiBoldPaint)
        x += measurePill(label) + PILL_GAP * density

        uiBoldPaint.textSize = pillTextSize()
        uiBoldPaint.letterSpacing = PILL_TRACKING
        uiBoldPaint.color = Theme.withAlpha(accent, alpha)
        canvas.drawText(value, x, baseline, uiBoldPaint)
        uiBoldPaint.letterSpacing = 0f
        if (delta != 0) {
            drawDeltaMark(canvas, x + measurePill(value) + DELTA_GAP * density, cy, delta, alpha)
        }
        uiBoldPaint.textAlign = Paint.Align.CENTER
    }

    private fun deltaWidth(delta: Int): Float =
        if (delta == 0) 0f else (DELTA_GAP + DELTA_WIDTH) * density

    /**
     * Just the head of an arrow: a small filled triangle, up in green or down in
     * red. A whole arrow beside a number reads as a control you could press.
     */
    private fun drawDeltaMark(canvas: Canvas, left: Float, cy: Float, delta: Int, alpha: Float) {
        val w = DELTA_WIDTH * density
        val h = w * 0.75f
        val up = delta > 0
        path.rewind()
        if (up) {
            path.moveTo(left + w / 2f, cy - h / 2f)
            path.lineTo(left + w, cy + h / 2f)
            path.lineTo(left, cy + h / 2f)
        } else {
            path.moveTo(left + w / 2f, cy + h / 2f)
            path.lineTo(left + w, cy - h / 2f)
            path.lineTo(left, cy - h / 2f)
        }
        path.close()
        fillPaint.shader = null
        fillPaint.color = Theme.withAlpha(if (up) Theme.good else Theme.danger, alpha)
        canvas.drawPath(path, fillPaint)
    }

    /** BEST and AVERAGE together, centred on [cx] as one block. */
    private fun drawScorePills(
        canvas: Canvas, cx: Float, cy: Float, alpha: Float, showDelta: Boolean = true
    ) {
        val bestValue = formatScore(bestScore)
        val avgValue = formatScore(averageScore)
        val gap = PILL_ROW_GAP * density
        // The arrow says which way the run just moved the average, so it belongs to
        // the card that reports that run and nowhere else.
        val delta = if (showDelta && averageMoved) averageDelta else 0
        val bestW = pillWidth(BEST_LABEL, bestValue)
        val avgW = pillWidth(AVG_LABEL, avgValue) + deltaWidth(delta)
        var x = cx - (bestW + gap + avgW) / 2f
        // Three fixed hues across the block, so best, average and rank are never
        // telling each other apart by position alone.
        drawPillAt(canvas, x, cy, BEST_LABEL, bestValue, Theme.accent, alpha)
        x += bestW + gap
        drawPillAt(canvas, x, cy, AVG_LABEL, avgValue, Theme.violet, alpha, delta)
    }

    /**
     * The rank, as a pill of the same family: caption, then the rank's own shape
     * drawn from the outline the game throws, then the title. Same padding, same
     * gaps and the same baseline as the two pills above it.
     */
    private fun drawRankPill(canvas: Canvas, cx: Float, cy: Float, rank: Rank, alpha: Float) {
        val title = rank.title.uppercase()
        // The whole pill wears the rank's own colour - only the caption stays the
        // same quiet grey as BEST and AVG, since that word is not the fact.
        val tint = rungColor(rank.number)
        val labelWidth = measurePill(RANK_LABEL)
        val titleWidth = measurePill(title)
        val glyph = glyphRadius()

        val height = PILL_HEIGHT * density
        val width = PILL_PAD * density + labelWidth + PILL_GAP * density +
            glyph * 2 + PILL_GAP * density + titleWidth + PILL_PAD * density
        val left = cx - width / 2f

        roundRect.set(left, cy - height / 2f, left + width, cy + height / 2f)
        panelPaint.shader = null
        panelPaint.alpha = 255
        panelPaint.color = Theme.withAlpha(tint, 0.16f * alpha)
        canvas.drawRoundRect(roundRect, height / 2f, height / 2f, panelPaint)

        val baseline = pillBaseline(cy)
        var x = left + PILL_PAD * density

        // Captioned like BEST and AVG, so all three read as the same kind of fact.
        uiBoldPaint.textAlign = Paint.Align.LEFT
        uiBoldPaint.textSize = pillTextSize()
        uiBoldPaint.letterSpacing = PILL_TRACKING
        uiBoldPaint.color = Theme.withAlpha(Theme.textFaint, alpha)
        canvas.drawText(RANK_LABEL, x, baseline, uiBoldPaint)
        x += labelWidth + PILL_GAP * density

        drawRankGlyph(canvas, x + glyph, cy, rank, glyph, alpha)
        x += glyph * 2 + PILL_GAP * density

        uiBoldPaint.textSize = pillTextSize()
        uiBoldPaint.letterSpacing = PILL_TRACKING
        uiBoldPaint.color = Theme.withAlpha(tint, alpha)
        canvas.drawText(title, x, baseline, uiBoldPaint)
        uiBoldPaint.letterSpacing = 0f
        uiBoldPaint.textAlign = Paint.Align.CENTER
    }

    /**
     * The badge itself, drawn from the kind's equal-area outline so a circle, a
     * square and a star all carry the same visual weight, and centred on its own
     * bounding box so it sits dead centre in the space the pill gave it.
     */
    private fun drawRankGlyph(
        canvas: Canvas, cx: Float, cy: Float, rank: Rank, r: Float, alpha: Float
    ) {
        val verts = rank.shape.glyphVertices
        val tint = rungColor(rank.number)

        path.rewind()
        verts.forEachIndexed { i, p ->
            val x = cx + p.x * r
            val y = cy + p.y * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        fillPaint.shader = null
        fillPaint.color = Theme.withAlpha(tint, 0.22f * alpha)
        canvas.drawPath(path, fillPaint)
        rimPaint.strokeWidth = 1.8f * density
        rimPaint.color = Theme.withAlpha(tint, 0.95f * alpha)
        canvas.drawPath(path, rimPaint)
    }

    /**
     * The ladder's own colour at rung [number]: warm gold at the bottom, swinging
     * a clear step of hue at a time through green and teal to a deep indigo at
     * the top. Two stops between gold and one blue left the middle of the ladder
     * looking like one colour repeated.
     */
    private fun rungColor(number: Int): Int {
        val t = (number - 1) / (Ranks.count - 1f).coerceAtLeast(1f)
        return Color.HSVToColor(
            floatArrayOf(
                RUNG_HUE_START + (RUNG_HUE_END - RUNG_HUE_START) * t,
                0.58f + 0.30f * t,
                1f - 0.10f * t
            )
        )
    }

    /**
     * Thirteen pips, the ones you have earned filled, each in its own rung's
     * colour. Where you stand on the ladder is a shape best answered by a
     * picture, not by "1 of 13".
     */
    /** How far the ladder reaches from its own line - the swollen pip's radius. */
    private fun ladderReach(): Float = PIP_RADIUS * PIP_CURRENT * density

    private fun drawRankLadder(canvas: Canvas, cx: Float, cy: Float, rank: Rank, alpha: Float) {
        val pip = PIP_RADIUS * density
        val gap = 9f * density
        val total = (Ranks.count - 1) * gap
        var x = cx - total / 2f
        panelPaint.shader = null
        panelPaint.alpha = 255
        for (i in 1..Ranks.count) {
            val earned = i <= rank.number
            panelPaint.color = Theme.withAlpha(
                if (earned) rungColor(i) else Color.WHITE, (if (earned) 0.9f else 0.16f) * alpha
            )
            canvas.drawCircle(x, cy, if (i == rank.number) pip * PIP_CURRENT else pip, panelPaint)
            x += gap
        }
    }

    /**
     * A row of the run's stat table: the label, this run's figure, and the
     * player's standing best, in two right-aligned columns under a header. Two
     * columns beat an inline "best 12" beside every number - the eye can compare
     * straight down a column, which is the whole reason anyone wants the figure.
     */
    private fun drawStatRow(
        canvas: Canvas,
        left: Float,
        runRight: Float,
        bestRight: Float,
        y: Float,
        label: String,
        value: String,
        best: String,
        valueColor: Int,
        order: Int
    ) {
        val alpha = revealAlpha(CARD_ROWS_AT + CARD_ROW_STAGGER * order)
        if (alpha <= 0.01f) return
        // Each row slides the last few pixels into place as it fades up.
        val slide = (1f - alpha) * 10f * density

        uiBoldPaint.textAlign = Paint.Align.LEFT
        uiBoldPaint.textSize = 14f * density
        uiBoldPaint.color = Theme.withAlpha(Theme.textFaint, alpha)
        canvas.drawText(label, left + slide, y, uiBoldPaint)

        uiBoldPaint.textAlign = Paint.Align.RIGHT
        uiBoldPaint.textSize = 17f * density
        uiBoldPaint.color = Theme.withAlpha(valueColor, alpha)
        canvas.drawText(value, runRight - slide, y, uiBoldPaint)

        // The best column goes gold when this run is the one that set it.
        val isRecord = best == value
        uiBoldPaint.textSize = 16f * density
        uiBoldPaint.color = Theme.withAlpha(
            if (isRecord) Theme.gold else Theme.textSecondary, alpha * if (isRecord) 1f else 0.75f
        )
        canvas.drawText(best, bestRight - slide, y, uiBoldPaint)
    }

    /** The two column headings above the stat table. */
    private fun drawStatHeader(canvas: Canvas, runRight: Float, bestRight: Float, y: Float) {
        val alpha = revealAlpha(CARD_ROWS_AT - 0.05f)
        if (alpha <= 0.01f) return
        uiBoldPaint.textAlign = Paint.Align.RIGHT
        uiBoldPaint.textSize = 11f * density
        uiBoldPaint.letterSpacing = 0.14f
        uiBoldPaint.color = Theme.withAlpha(Theme.textFaint, alpha * 0.85f)
        canvas.drawText("THIS RUN", runRight, y, uiBoldPaint)
        canvas.drawText("BEST", bestRight, y, uiBoldPaint)
        uiBoldPaint.letterSpacing = 0f
    }

    private fun drawButton(
        canvas: Canvas,
        rect: RectF,
        label: String,
        primary: Boolean,
        pressed: Boolean,
        alpha: Float = 1f,
        textSize: Float = 22f * density
    ) {
        val radius = rect.height() / 2f
        val inset = if (pressed) 2f * density else 0f
        roundRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)

        if (primary) {
            panelPaint.shader = LinearGradient(
                roundRect.left, roundRect.top, roundRect.right, roundRect.bottom,
                Theme.withAlpha(Theme.accent, alpha), Theme.withAlpha(Theme.accentDeep, alpha),
                Shader.TileMode.CLAMP
            )
            // A shader is modulated by the paint's own alpha, and this Paint is
            // shared with translucent panels, so it has to be reset to opaque or
            // the button inherits whatever faded value was set last.
            panelPaint.alpha = 255
            canvas.drawRoundRect(roundRect, radius, radius, panelPaint)
            panelPaint.shader = null
            panelPaint.alpha = 255
        } else {
            panelPaint.color = Theme.withAlpha(Color.WHITE, (if (pressed) 0.14f else 0.08f) * alpha)
            canvas.drawRoundRect(roundRect, radius, radius, panelPaint)
            panelStrokePaint.color = Theme.withAlpha(Theme.hairline, alpha)
            canvas.drawRoundRect(roundRect, radius, radius, panelStrokePaint)
            panelStrokePaint.color = Theme.hairline
        }

        uiBoldPaint.textAlign = Paint.Align.CENTER
        uiBoldPaint.textSize = textSize
        uiBoldPaint.color = Theme.withAlpha(
            if (primary) Color.rgb(6, 20, 26) else Theme.textPrimary, alpha
        )
        val baseline = roundRect.centerY() - (uiBoldPaint.descent() + uiBoldPaint.ascent()) / 2f
        canvas.drawText(label, roundRect.centerX(), baseline, uiBoldPaint)
    }

    // ---------------------------------------------------------------------
    // Small holders
    // ---------------------------------------------------------------------

    private companion object {
        /** Real seconds the critical-health countdown runs for. */
        /** Real seconds spent climbing linearly back to full speed afterwards. */
        /** Game-over card metrics, in dp - shared by the measure pass and the draw. */
        /** Side of the square pause target, in dp. */
        const val PAUSE_BUTTON_SIZE = 34f

        /** Grain tile edge, in pixels, and how strongly it sits over the body. */
        const val GRAIN_TILE = 128
        const val GRAIN_ALPHA = 0.13f

        /** Shared pill metrics, in dp, so every capsule on a screen matches. */
        const val PILL_HEIGHT = 34f
        const val PILL_PAD = 16f
        const val PILL_GAP = 8f

        /** Extra embers per thousand points, and the ceiling on that. */
        const val EMBER_GROWTH_PER_1K = 0.05f
        const val MAX_EMBER_GROWTH = 5f

        /** Shortest gap between two swipe noises, in real milliseconds. */

        /** The tempo the music is written at, which the score speeds up from. */
        const val MUSIC_BASE_BPM = 108f

        /** The gate card's inset, used on all four sides. */
        const val AD_CARD_PAD = 26f
        /** Card top inset to the first baseline of the body copy. */
        const val AD_TITLE_DROP = 60f
        const val AD_BODY_SIZE = 16f

        /** Seconds a failed-ad explanation stays on screen. */
        const val AD_NOTICE_HOLD = 5f

        /** How long a real ad callback is given to win the stranded-ad race. */
        const val STRANDED_AD_GRACE_MS = 1500L

        /** Seconds the waiting screen gives an ad to appear before giving up. */
        const val AD_WAIT_TIMEOUT = 8f

        /** Height of each button in the game-over card's stack, in dp. */
        const val GAME_OVER_BUTTON_HEIGHT = 50f

        /** How much of its designed size the score card is actually drawn at. */
        const val CARD_SCALE = 0.85f

        /**
         * One spacing scale runs the whole card: the same inset on the sides and
         * the bottom, and every horizontal rule sitting the same distance below the
         * baseline above it as above the baseline below it. The two rules used to be
         * spaced differently from each other, which is what made the lower half look
         * cramped against its heading.
         */
        const val CARD_PAD = 28f
        /** Baseline above a rule to the rule, and the rule to the baseline below. */
        const val CARD_RULE_GAP = 30f
        const val CARD_HEADER_HEIGHT = 332f

        /** The summary block: pills, rank pill, ladder, goal - one even rhythm. */
        const val SUMMARY_PILLS_Y = 162f
        /** The one clear space between every part of the summary block. */
        const val SUMMARY_GAP = 20f
        /** Ink height of the rank-change line, and how close it sits to its pill. */
        const val RANK_FLASH_SPACE = 9f
        const val RANK_FLASH_GAP = 8f
        const val RANK_FLASH_TEXT = 11f
        /** Space the line claims whether or not it is showing, so nothing shifts. */
        const val RESERVED_FLASH = RANK_FLASH_SPACE + RANK_FLASH_GAP

        const val BEST_LABEL = "BEST:"
        const val AVG_LABEL = "AVG:"
        const val RANK_LABEL = "RANK:"
        /** One size for every word in a pill, captions included. */
        const val PILL_TEXT = 16f
        const val PILL_TRACKING = 0.06f
        /** Gap between the two pills on the top row. */
        const val PILL_ROW_GAP = 10f
        const val DELTA_WIDTH = 9f
        const val DELTA_GAP = 7f
        /** A badge's half-height as a fraction of the pill text's ink height. */
        const val GLYPH_OF_TEXT = 0.44f
        const val PIP_RADIUS = 3.2f
        /** How much the pip you are standing on swells above the rest. */
        const val PIP_CURRENT = 1.35f
        const val RUNG_HUE_START = 44f
        const val RUNG_HUE_END = 252f
        /** Width reserved for the BEST column, measured in from the card's inset. */
        const val CARD_BEST_COLUMN = 66f
        const val CARD_STAT_ROW_HEIGHT = 26f
        const val CARD_BREAKDOWN_ROW_HEIGHT = 21f
        /** Baseline offset that centres a breakdown row's text in its row. */
        const val CARD_ROW_TEXT_OFFSET = 4.5f

        val EMERGENCY_RED = Color.rgb(255, 62, 74)

        /** Text and stamps on top of a gold fill. */
        val INK_ON_GOLD = Color.rgb(42, 24, 0)

        const val CONTINUE_LABEL = "CONTINUE"
        /** The superscript that says the continue costs an ad. */
        const val CONTINUE_MARK = "AD"

        /** Seconds the last-cut readout stays under the score. */
        /** How far a shape's colour is pulled toward the level's hue. */

        const val LAST_CUT_HOLD = 1.1f
        /** Longest the ending waits for airborne shapes to clear. */
        const val SETTLE_MAX_SECONDS = 2.6f

        // Reveal timings for the game-over card, in seconds.
        const val CARD_TITLE_AT = 0.26f
        const val CARD_SCORE_AT = 0.46f
        const val CARD_ROWS_AT = 0.80f
        const val CARD_ROW_STAGGER = 0.11f
        const val CARD_BREAKDOWN_STAGGER = 0.09f

        /**
         * When each of the card's reveal noises fires, on the same clock the
         * drawing reads: the card arriving, the score landing, the rank line, then
         * one tick per stat row as they stagger in.
         */
        val CARD_CUE_TIMES = floatArrayOf(
            0.02f, CARD_SCORE_AT, CARD_SCORE_AT + 0.12f,
            CARD_ROWS_AT, CARD_ROWS_AT + CARD_ROW_STAGGER,
            CARD_ROWS_AT + CARD_ROW_STAGGER * 2, CARD_ROWS_AT + CARD_ROW_STAGGER * 3,
            CARD_ROWS_AT + CARD_ROW_STAGGER * 4
        )

        /** Accuracy buckets shown on the game-over card, widest miss last. */
        /** One per [Grade], showing the worst split that still lands in that tier. */
        val CUT_BUCKET_LABELS = arrayOf("PERFECT", "45/55", "60/40", "70/30", "80/20", "90/10")
    }

    private class TrailPoint(val x: Float, val y: Float, val timeMs: Long)


    /** One half of a sliced shape, tumbling away and fading out. */
    private class SlicedPiece(
        val points: List<PointF2>,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        private val angularVelocity: Float,
        val paletteIndex: Int,
        /** The radius of the shape this came off, so its material matches. */
        val radiusHint: Float
    ) {
        val originX = x
        val originY = y
        var spin = 0f
            private set
        private var age = 0f
        private val lifeSpan = 1.25f

        val alive: Boolean get() = age < lifeSpan
        val remaining: Float get() = (1f - age / lifeSpan).coerceIn(0f, 1f)

        fun update(dt: Float, gravity: Float) {
            age += dt
            vy += gravity * dt
            x += vx * dt
            y += vy * dt
            spin += Math.toDegrees(angularVelocity.toDouble()).toFloat() * dt
        }
    }
}
