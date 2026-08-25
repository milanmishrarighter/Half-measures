package com.halfmeasures.slicegame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View

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
    enum class State { READY, PLAYING, PAUSED, SETTLING, GAME_OVER }

    /** Opens the settings screen; wired up by the hosting activity. */
    var onOpenSettings: (() -> Unit)? = null
    /** Opens the how-to-play screen; wired up by the hosting activity. */
    var onOpenInstructions: (() -> Unit)? = null

    private var settings = GameSettings.load(context)
    private val scores = context.getSharedPreferences("half_measures_scores", Context.MODE_PRIVATE)
    private val random = Random(System.currentTimeMillis())
    private val effects = EffectSystem(random)
    private val haptics = Haptics(context)

    private var gravity = GameShape.BASE_GRAVITY * settings.gravityScale

    fun refreshSettings() {
        settings = GameSettings.load(context)
        gravity = GameShape.BASE_GRAVITY * settings.gravityScale
        // A paused run is still a run: leaving it alone means resuming with the
        // health it was paused on rather than a free refill.
        if (state != State.PLAYING && state != State.PAUSED) {
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
    private var unlockedKinds = 0

    private var lastFrameTimeNanos = 0L
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
    private val gameOverCard = RectF()
    /** The pause target in the top-right corner, live only during play. */
    private val pauseButton = RectF()
    private val pauseCard = RectF()
    private val pauseResume = RectF()
    private val pauseMenu = RectF()
    private val primaryButton: RectF get() = when (state) {
        State.GAME_OVER -> overPrimary
        State.PAUSED -> pauseResume
        else -> readyPrimary
    }
    private val secondaryButton: RectF get() = when (state) {
        State.GAME_OVER -> overSecondary
        State.PAUSED -> pauseMenu
        else -> readySecondary
    }
    private val tertiaryButton: RectF get() = if (state == State.GAME_OVER) overTertiary else readyTertiary
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
    private var backgroundColor = Theme.stageBackground(0)
    private var accentColor = Theme.stageAccent(0)
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
    private val roundRect = RectF()

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
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameTimeNanos == 0L) lastFrameTimeNanos = frameTimeNanos
        var realDt = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
        lastFrameTimeNanos = frameTimeNanos
        realDt = min(realDt, 1f / 30f) // after a stall, step conservatively instead of teleporting

        if (state == State.PAUSED) {
            // A pause stops the world outright: no simulation, no timers, no embers.
            // Only the overlay is redrawn, so the run resumes exactly where it stood.
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
            return
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
        dangerRecovery = lowHealthSlowMoSeconds()
        dangerAlert = lowHealthSlowMoSeconds()
        perfectSlowMo = 0f
        effects.addFlash(Theme.danger, 0.5f * settings.screenFlashStrength)
        pixels.flash(1.4f)
        if (settings.vibrationEnabled) haptics.gameOver(settings.vibrationStrength)
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
        val readyTop = h * 0.53f
        readyPrimary.set(cx - buttonWidth / 2f, readyTop, cx + buttonWidth / 2f, readyTop + buttonHeight)
        readySecondary.set(
            cx - buttonWidth / 2f, readyTop + buttonHeight + buttonGap,
            cx + buttonWidth / 2f, readyTop + buttonHeight * 2 + buttonGap
        )
        readyTertiary.set(
            cx - buttonWidth / 2f, readyTop + (buttonHeight + buttonGap) * 2,
            cx + buttonWidth / 2f, readyTop + buttonHeight * 3 + buttonGap * 2
        )

        // Four buttons have to fit under the card now, so they are shorter and
        // tighter than the title screen's three.
        val overHeight = 46f * density
        val overGap = 10f * density
        val cardHeight = measureGameOverCard()
        val cardGap = 20f * density
        val blockHeight = cardHeight + cardGap + overHeight * 4 + overGap * 3
        val blockTop = ((h - blockHeight) / 2f).coerceAtLeast(16f * density)

        gameOverCard.set(w * 0.09f, blockTop, w * 0.91f, blockTop + cardHeight)
        var overTop = gameOverCard.bottom + cardGap
        for (rect in arrayOf(overPrimary, overSecondary, overTertiary, overQuaternary)) {
            rect.set(cx - buttonWidth / 2f, overTop, cx + buttonWidth / 2f, overTop + overHeight)
            overTop += overHeight + overGap
        }

        layoutPauseOverlay(w, h, buttonWidth, buttonHeight, buttonGap)
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
        val cardHeight = 118f * density + buttonHeight * 2 + buttonGap + 20f * density
        val cardTop = (h - cardHeight) / 2f
        pauseCard.set(cx - cardWidth / 2f, cardTop, cx + cardWidth / 2f, cardTop + cardHeight)

        val innerWidth = min(buttonWidth, cardWidth - 36f * density)
        val firstTop = cardTop + 118f * density
        pauseResume.set(cx - innerWidth / 2f, firstTop, cx + innerWidth / 2f, firstTop + buttonHeight)
        pauseMenu.set(
            cx - innerWidth / 2f, firstTop + buttonHeight + buttonGap,
            cx + innerWidth / 2f, firstTop + buttonHeight * 2 + buttonGap
        )
    }

    /** Total height the game-over card needs for everything it draws. */
    private fun measureGameOverCard(): Float =
        CARD_HEADER_HEIGHT * density +
            24f * density +
            CARD_STAT_ROW_HEIGHT * density * 5 +
            30f * density +
            26f * density + CARD_BREAKDOWN_ROW_HEIGHT * density * (cutBuckets.size - 1) +
            CARD_BOTTOM_PADDING * density

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
            stage = stage,
            warmth = streakWarmth(),
            emberDensity = settings.emberDensity,
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
                shapes.add(GameShape.spawnRandom(width, height, random, nowMs, stage, settings))
                spawnCountdown = settings.spawnGapMs / 1000f
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

        if (state == State.GAME_OVER) cardReveal += dt
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

        // Creep toward the level's colours so a level change is a slow shift in
        // the light rather than a hard cut.
        val colourEase = min(1f, dt * 0.9f)
        backgroundColor = Theme.lerpColor(backgroundColor, Theme.stageBackground(stage), colourEase)
        accentColor = Theme.lerpColor(accentColor, Theme.stageAccent(stage), colourEase)

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
        unlockedKinds = ShapeKind.unlockedCount(
            stage, settings.startingShapeCount, settings.shapesPerStage
        )
        // The shape gradients are cached per palette entry and tinted by level,
        // so they have to be rebuilt when the level changes.
        bodyShaders.clear()
        if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
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
        val scoreX = gameOverCard.centerX()
        val scoreY = gameOverCard.top + 100f * density
        val x = scoreX + (random.nextFloat() - 0.5f) * gameOverCard.width() * 0.85f
        val y = scoreY + (random.nextFloat() - 0.5f) * 130f * density
        val palette = Theme.shapePalette[random.nextInt(Theme.shapePalette.size)]
        val color = if (random.nextFloat() < 0.4f) Theme.gold else palette[0]

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
        if (score > bestScore) {
            bestScore = score
            beatBestScore = true
            fireworkTimer = 0f
            scores.edit().putInt("best_score", bestScore).apply()
        }
        effects.addShake(0.7f * settings.cameraShakeStrength)
        if (settings.vibrationEnabled) haptics.gameOver(settings.vibrationStrength)
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
            shapes.add(GameShape.spawnRandom(width, height, random, nowMs, 0, settings))
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
            state = State.GAME_OVER
            cardReveal = 0f
            fireworkTimer = 0f
        }
    }

    /**
     * Called when the activity loses focus. Leaving the app mid-run would otherwise
     * hand the player a dead run on their return, so it pauses itself.
     */
    fun pauseIfPlaying() {
        if (state == State.PLAYING) pauseGame()
    }

    /** Freezes the run where it stands and raises the pause card. */
    private fun pauseGame() {
        if (state != State.PLAYING) return
        state = State.PAUSED
        pressedButton = 0
        hasLastTouch = false
        trailPoints.clear()
        if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
    }

    private fun resumeGame() {
        if (state != State.PAUSED) return
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
        backgroundColor = Theme.stageBackground(0)
        accentColor = Theme.stageAccent(0)
        stage = 0
        pixels.reset()
        bodyShaders.clear()
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
        score = 0
        displayedScore = 0f
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
        stage = 0
        unlockedKinds = ShapeKind.unlockedCount(
            0, settings.startingShapeCount, settings.shapesPerStage
        )
        timeScale = 1f
        perfectSlowMo = 0f
        dangerRecovery = 0f
        dangerAlert = 0f
        dangerArmed = true
        pixels.reset()
        bodyShaders.clear()
        backgroundColor = Theme.stageBackground(0)
        accentColor = Theme.stageAccent(0)
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
                if (state == State.SETTLING) return true
                if (state == State.PAUSED) {
                    pressedButton = when {
                        pauseResume.contains(event.x, event.y) -> 1
                        pauseMenu.contains(event.x, event.y) -> 2
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
                if (state == State.PAUSED) return true
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
                if (state == State.SETTLING) return true
                if (state == State.PAUSED) {
                    val released = pressedButton
                    pressedButton = 0
                    when {
                        released == 1 && pauseResume.contains(event.x, event.y) -> resumeGame()
                        released == 2 && pauseMenu.contains(event.x, event.y) -> returnToMenu()
                    }
                    if (released != 0 && settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
                    return true
                }
                if (state != State.PLAYING) {
                    val released = pressedButton
                    pressedButton = 0
                    hasLastTouch = false
                    when {
                        released == 1 && primaryButton.contains(event.x, event.y) -> {
                            if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
                            startNewGame()
                        }
                        released == 2 && secondaryButton.contains(event.x, event.y) -> {
                            if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
                            onOpenInstructions?.invoke()
                        }
                        released == 3 && tertiaryButton.contains(event.x, event.y) -> {
                            if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
                            onOpenSettings?.invoke()
                        }
                        released == 4 && overQuaternary.contains(event.x, event.y) -> {
                            if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
                            returnToMenu()
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
        startNewGame()
    }

    private fun handleSwipeSegment(x: Float, y: Float) {
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
     * 100 points for a flawless halving, falling away with the miss, then bent by
     * streaks: a run of great-or-better cuts compounds the payout, while a run of
     * sloppy ones eats into it and can start costing points outright.
     */
    private fun applyScore(deviation: Float, grade: Grade): Int {
        val sloppy = grade == Grade.POOR || grade == Grade.MISS

        // The two streaks are mutually exclusive: a perfect ends any good run and
        // starts a perfect run, and a merely-great cut does the reverse.
        when (grade) {
            Grade.PERFECT -> {
                perfectCount++
                perfectStreak++
                hotStreak = 0
                coldStreak = 0
                bestPerfectStreak = max(bestPerfectStreak, perfectStreak)
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
        // Ramps from nothing at the edge of the great window up to the full bonus at a perfect.
        val precision = if (deviation <= settings.greatThreshold && settings.greatThreshold > 0f) {
            1f + (settings.greatBonusPercent / 100f) * (1f - deviation / settings.greatThreshold)
        } else {
            1f
        }

        var gained = (base * precision * comboMultiplier()).roundToInt()

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
     * Good and perfect streaks pay separately and stack: every great-or-better cut
     * in a row adds the good-streak bonus, and every *perfect* in a row adds its own
     * larger bonus on top. Both are additive so the ceiling stays predictable.
     */
    private fun comboMultiplier(): Float {
        // Only one streak can be live at a time, so whichever is running pays.
        val bonus = if (perfectStreak > 0) {
            (perfectStreak - 1).coerceAtLeast(0) * settings.perfectStreakBonusPercent / 100f
        } else {
            (hotStreak - 1).coerceAtLeast(0) * settings.comboBonusPercent / 100f
        }
        return (1f + bonus).coerceAtMost(settings.maxComboMultiplier)
    }

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
                    shape.angularVelocity * 1.6f, shape.paletteIndex
                )
            )
        }
        if (right.size >= 3) {
            pieces.add(
                SlicedPiece(
                    right, shape.x, shape.y,
                    shape.vx - nx * kick, shape.vy - ny * kick,
                    shape.angularVelocity * -1.6f, shape.paletteIndex
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
                color = tintedLight(shape.paletteIndex),
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
        if (state == State.PLAYING || state == State.SETTLING || state == State.PAUSED) {
            drawHud(canvas)
        }

        when (state) {
            State.READY -> drawReadyScreen(canvas)
            State.GAME_OVER -> drawGameOverScreen(canvas)
            State.PAUSED -> drawPauseScreen(canvas)
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

    /** A shape's own colour, pulled a little toward the current level's hue. */
    private fun tintedLight(paletteIndex: Int): Int =
        Theme.lerpColor(Theme.shapePalette[paletteIndex][0], Theme.stageAccent(stage), STAGE_TINT)

    private fun bodyShader(paletteIndex: Int): RadialGradient =
        bodyShaders.getOrPut(paletteIndex) {
            val pair = Theme.shapePalette[paletteIndex]
            val light = tintedLight(paletteIndex)
            val deep = Theme.lerpColor(pair[1], Theme.stageAccent(stage), STAGE_TINT * 0.7f)
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

        // Body, lit from the upper left.
        buildPath(verts)
        val shader = bodyShader(shape.paletteIndex)
        shaderMatrix.reset()
        shaderMatrix.postScale(r * 1.55f, r * 1.55f)
        shaderMatrix.postTranslate(shape.x - r * 0.38f, shape.y - r * 0.42f)
        shader.setLocalMatrix(shaderMatrix)
        fillPaint.shader = shader
        canvas.drawPath(path, fillPaint)
        fillPaint.shader = null

        // Bright rim, then a soft inner contour for a bevelled look.
        rimPaint.strokeWidth = max(2f, r * 0.045f)
        rimPaint.color = Theme.withAlpha(Theme.lighten(tintedLight(shape.paletteIndex), 0.55f), 0.75f)
        canvas.drawPath(path, rimPaint)

        if (settings.guideLineEnabled) drawGuideLine(canvas, shape, verts, r)
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

        fillPaint.color = Theme.withAlpha(tintedLight(piece.paletteIndex), alpha * 0.95f)
        canvas.drawPath(path, fillPaint)
        rimPaint.strokeWidth = 3f
        rimPaint.color = Theme.withAlpha(Color.WHITE, alpha * 0.35f)
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
                "${perfectStreak}x PERFECT  ·  ${"%.1f".format(comboMultiplier())}x",
                width / 2f, streakY, uiBoldPaint
            )
        } else if (hotStreak > 1) {
            val beat = 1f + 0.05f * sin(elapsed * 9f)
            uiBoldPaint.textAlign = Paint.Align.CENTER
            uiBoldPaint.textSize = 20f * density * beat
            uiBoldPaint.color = Theme.good
            canvas.drawText(
                "${hotStreak}x STREAK  ·  ${"%.1f".format(comboMultiplier())}x",
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
        drawButton(canvas, pauseMenu, "MAIN MENU", primary = false, pressed = pressedButton == 2)
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

        if (bestScore > 0) {
            drawChip(canvas, cx, height * 0.43f, "BEST  $bestScore")
        }

        drawButton(canvas, primaryButton, "PLAY", primary = true, pressed = pressedButton == 1)
        drawButton(canvas, secondaryButton, "HOW TO PLAY", primary = false, pressed = pressedButton == 2)
        drawButton(canvas, tertiaryButton, "SETTINGS", primary = false, pressed = pressedButton == 3)
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
        val padX = 28f * density
        val rowStep = CARD_STAT_ROW_HEIGHT * density

        // The whole card eases up into place from slightly small and low.
        canvas.save()
        val scale = 0.94f + 0.06f * boxAlpha
        canvas.translate(0f, (1f - boxAlpha) * 26f * density)
        canvas.scale(scale, scale, cx, gameOverCard.centerY())

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

        uiPaint.textSize = 15f * density
        uiPaint.color = Theme.withAlpha(Theme.textFaint, scoreAlpha)
        canvas.drawText("FINAL SCORE", cx, cardTop + 132f * density, uiPaint)

        rimPaint.strokeWidth = 1.5f
        rimPaint.color = Theme.withAlpha(Theme.hairline, revealAlpha(CARD_ROWS_AT - 0.1f))
        val dividerY = cardTop + CARD_HEADER_HEIGHT * density
        canvas.drawLine(cardLeft + padX, dividerY, cardRight - padX, dividerY, rimPaint)

        val left = cardLeft + padX
        val right = cardRight - padX
        var rowY = dividerY + 30f * density

        // Stats arrive one at a time rather than all at once.
        drawStatRow(canvas, left, right, rowY, "BEST SCORE", bestScore.toString(), Theme.accent, 0)
        rowY += rowStep
        drawStatRow(canvas, left, right, rowY, "CUTS SURVIVED", cutCount.toString(), Theme.textPrimary, 1)
        rowY += rowStep
        drawStatRow(canvas, left, right, rowY, "PERFECT CUTS", perfectCount.toString(), Theme.gold, 2)
        rowY += rowStep
        drawStatRow(canvas, left, right, rowY, "BEST PERFECT STREAK", "${bestPerfectStreak}x", Theme.gold, 3)
        rowY += rowStep
        drawStatRow(canvas, left, right, rowY, "BEST GOOD STREAK", "${bestStreak}x", Theme.good, 4)
        rowY += 30f * density

        drawCutBreakdown(canvas, cardLeft, cardRight, rowY, CARD_BREAKDOWN_ROW_HEIGHT * density)

        canvas.restore()

        // Buttons come in last, once the card has finished settling.
        val buttonAlpha = revealAlpha(CARD_ROWS_AT + CARD_ROW_STAGGER * 5 + CARD_BREAKDOWN_STAGGER * 5)
        if (buttonAlpha > 0.01f) {
            val small = 18f * density
            drawButton(canvas, primaryButton, "RETRY", primary = true, pressed = pressedButton == 1, alpha = buttonAlpha, textSize = small)
            drawButton(canvas, secondaryButton, "HOW TO PLAY", primary = false, pressed = pressedButton == 2, alpha = buttonAlpha, textSize = small)
            drawButton(canvas, tertiaryButton, "SETTINGS", primary = false, pressed = pressedButton == 3, alpha = buttonAlpha, textSize = small)
            drawButton(canvas, overQuaternary, "MAIN MENU", primary = false, pressed = pressedButton == 4, alpha = buttonAlpha, textSize = small)
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

        val padX = 28f * density

        val headingAlpha = revealAlpha(CARD_ROWS_AT + CARD_ROW_STAGGER * 5)

        // A rule above the heading separates the distribution from the stats.
        rimPaint.strokeWidth = 1.5f
        rimPaint.color = Theme.withAlpha(Theme.hairline, headingAlpha)
        canvas.drawLine(
            cardLeft + padX, top - 16f * density,
            cardRight - padX, top - 16f * density, rimPaint
        )

        uiBoldPaint.textAlign = Paint.Align.LEFT
        uiBoldPaint.textSize = 13f * density
        uiBoldPaint.letterSpacing = 0.14f
        uiBoldPaint.color = Theme.withAlpha(Theme.textSecondary, headingAlpha)
        canvas.drawText("HOW YOUR CUTS LANDED", cardLeft + padX, top, uiBoldPaint)
        uiBoldPaint.letterSpacing = 0f

        val labelWidth = 46f * density
        val countWidth = 34f * density
        val barLeft = cardLeft + padX + labelWidth
        val barRight = cardRight - padX - countWidth
        val barSpan = (barRight - barLeft).coerceAtLeast(1f)

        var y = top + 16f * density
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
                centerY + 4.5f * density,
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
                centerY + 4.5f * density,
                uiBoldPaint
            )

            y += rowHeight
        }
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

    /** One "LABEL .......... value" line on the game-over card. */
    private fun drawStatRow(
        canvas: Canvas,
        left: Float,
        right: Float,
        y: Float,
        label: String,
        value: String,
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
        canvas.drawText(value, right - slide, y, uiBoldPaint)
    }

    private fun drawChip(canvas: Canvas, cx: Float, cy: Float, text: String) {
        uiBoldPaint.textAlign = Paint.Align.CENTER
        uiBoldPaint.textSize = 17f * density
        val textWidth = uiBoldPaint.measureText(text)
        val padX = 20f * density
        val padY = 11f * density
        roundRect.set(cx - textWidth / 2f - padX, cy - padY - 8f * density,
            cx + textWidth / 2f + padX, cy + padY + 6f * density)
        panelPaint.color = Theme.withAlpha(Theme.accent, 0.13f)
        canvas.drawRoundRect(roundRect, roundRect.height() / 2f, roundRect.height() / 2f, panelPaint)
        uiBoldPaint.color = Theme.accent
        canvas.drawText(text, cx, cy + 5f * density, uiBoldPaint)
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

        const val CARD_HEADER_HEIGHT = 156f
        const val CARD_STAT_ROW_HEIGHT = 26f
        const val CARD_BREAKDOWN_ROW_HEIGHT = 21f
        const val CARD_BOTTOM_PADDING = 26f

        val EMERGENCY_RED = Color.rgb(255, 62, 74)

        /** Seconds the last-cut readout stays under the score. */
        /** How far a shape's colour is pulled toward the level's hue. */
        const val STAGE_TINT = 0.32f

        const val LAST_CUT_HOLD = 1.1f
        /** Longest the ending waits for airborne shapes to clear. */
        const val SETTLE_MAX_SECONDS = 2.6f

        // Reveal timings for the game-over card, in seconds.
        const val CARD_TITLE_AT = 0.26f
        const val CARD_SCORE_AT = 0.46f
        const val CARD_ROWS_AT = 0.80f
        const val CARD_ROW_STAGGER = 0.11f
        const val CARD_BREAKDOWN_STAGGER = 0.09f

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
        val paletteIndex: Int
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
