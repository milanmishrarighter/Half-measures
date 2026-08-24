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

    enum class State { READY, PLAYING, GAME_OVER }

    /** Opens the settings screen; wired up by the hosting activity. */
    var onOpenSettings: (() -> Unit)? = null

    private var settings = GameSettings.load(context)
    private val scores = context.getSharedPreferences("half_measures_scores", Context.MODE_PRIVATE)
    private val random = Random(System.currentTimeMillis())
    private val effects = EffectSystem(random)
    private val haptics = Haptics(context)

    private var gravity = GameShape.BASE_GRAVITY * settings.gravityScale

    fun refreshSettings() {
        settings = GameSettings.load(context)
        gravity = GameShape.BASE_GRAVITY * settings.gravityScale
        if (state != State.PLAYING) {
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
    private val overPrimary = RectF()
    private val overSecondary = RectF()
    private val gameOverCard = RectF()
    private val primaryButton: RectF get() = if (state == State.GAME_OVER) overPrimary else readyPrimary
    private val secondaryButton: RectF get() = if (state == State.GAME_OVER) overSecondary else readySecondary
    private var pressedButton = 0 // 0 none, 1 primary, 2 secondary

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
    private val bgPaint = Paint()
    /**
     * A tiled speck of noise laid over the backdrop. A smooth dark gradient across
     * a tall screen crosses very few distinct 8-bit values, so it renders as
     * visible steps; a pixel of dither breaks the boundaries up and the eye reads
     * the whole thing as continuous.
     */
    private val ditherPaint = Paint()
    private var ditherShader: BitmapShader? = null
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

        // The countdown runs on real time; everything else obeys the time scale.
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
            val progress = 1f - dangerRecovery / settings.slowMoDuration.coerceAtLeast(0.05f)
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
        dangerRecovery = settings.slowMoDuration
        perfectSlowMo = 0f
        effects.addFlash(Theme.danger, 0.5f * settings.screenFlashStrength)
        pixels.flash(1.4f)
        if (settings.vibrationEnabled) haptics.gameOver(settings.vibrationStrength)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        if (ditherShader == null) ditherShader = buildDitherShader()

        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Theme.bgTop, Theme.bgBottom, Color.rgb(4, 5, 12)),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
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

        val readyTop = h * 0.62f
        readyPrimary.set(cx - buttonWidth / 2f, readyTop, cx + buttonWidth / 2f, readyTop + buttonHeight)
        readySecondary.set(
            cx - buttonWidth / 2f, readyTop + buttonHeight + buttonGap,
            cx + buttonWidth / 2f, readyTop + buttonHeight * 2 + buttonGap
        )

        val cardHeight = measureGameOverCard()
        val cardGap = 26f * density
        val blockHeight = cardHeight + cardGap + buttonHeight * 2 + buttonGap
        val blockTop = ((h - blockHeight) / 2f).coerceAtLeast(20f * density)

        gameOverCard.set(w * 0.09f, blockTop, w * 0.91f, blockTop + cardHeight)
        val overTop = gameOverCard.bottom + cardGap
        overPrimary.set(cx - buttonWidth / 2f, overTop, cx + buttonWidth / 2f, overTop + buttonHeight)
        overSecondary.set(
            cx - buttonWidth / 2f, overTop + buttonHeight + buttonGap,
            cx + buttonWidth / 2f, overTop + buttonHeight * 2 + buttonGap
        )
    }

    /** Total height the game-over card needs for everything it draws. */
    private fun measureGameOverCard(): Float =
        CARD_HEADER_HEIGHT * density +
            24f * density +
            CARD_STAT_ROW_HEIGHT * density * 5 +
            30f * density +
            16f * density + CARD_BREAKDOWN_ROW_HEIGHT * density * cutBuckets.size +
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
        }

        var i = pieces.size - 1
        while (i >= 0) {
            val p = pieces[i]
            p.update(dt, gravity)
            if (!p.alive) pieces.removeAt(i)
            i--
        }

        if (state == State.GAME_OVER && beatBestScore) updateFireworks(dt)

        effects.update(dt, gravity)

        displayedHealth += (health - displayedHealth) * min(1f, dt * 9f)
        displayedScore += (score - displayedScore) * min(1f, dt * 12f)
    }

    /**
     * Advances the difficulty stage. Every stage the player earns brings more
     * shapes on screen at once, faster tumbling, and fresh, harder shape kinds.
     */
    private fun updateStage() {
        val interval = max(1, settings.stageScoreInterval)
        val newStage = score / interval
        if (newStage == stage) return

        stage = newStage
        val unlocked = ShapeKind.unlockedCount(
            stage, settings.startingShapeCount, settings.shapesPerStage
        )
        val gained = unlocked - unlockedKinds
        unlockedKinds = unlocked

        val headline = "STAGE ${stage + 1}"
        val subline = if (gained > 0) {
            val names = ShapeKind.values()
                .copyOfRange(unlocked - gained, unlocked)
                .joinToString(" · ") { it.displayName.uppercase() }
            "NEW: $names"
        } else {
            "FASTER · BUSIER"
        }
        effects.popup(headline, subline, width / 2f, height * 0.32f, Theme.gold, 0.85f)
        effects.addFlash(Theme.gold, 0.3f * settings.screenFlashStrength)
        pixels.flash(1.6f)
        pixels.burst(width / 2f, height * 0.5f, 1.0f)
        effects.addEnergy(1.2f)
        if (settings.vibrationEnabled) haptics.great(settings.vibrationStrength)
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
        fireworkTimer = 0.22f + random.nextFloat() * 0.3f

        val x = width * (0.12f + random.nextFloat() * 0.76f)
        val y = height * (0.08f + random.nextFloat() * 0.34f)
        val palette = Theme.shapePalette[random.nextInt(Theme.shapePalette.size)]
        val color = if (random.nextFloat() < 0.4f) Theme.gold else palette[0]

        // Chunky pixel debris, matching the background's blocky vocabulary.
        pixels.burst(x, y, 2.4f)
        pixels.flash(0.5f)
        effects.radialBurst(x, y, color, 26, 460f, 1.5f)
        effects.radialBurst(x, y, Theme.lighten(color, 0.4f), 14, 260f, 1.1f)
        effects.shockwave(x, y, width * 0.22f, color, 0.5f, 7f)
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
            color = s.lightColor,
            count = count,
            speed = 170f,
            sizeScale = 0.8f
        )
    }

    private fun endRun() {
        if (state != State.PLAYING) return
        state = State.GAME_OVER
        if (score > bestScore) {
            bestScore = score
            beatBestScore = true
            fireworkTimer = 0f
            scores.edit().putInt("best_score", bestScore).apply()
        }
        effects.addShake(0.7f * settings.cameraShakeStrength)
        if (settings.vibrationEnabled) haptics.gameOver(settings.vibrationStrength)
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
        bestStreak = 0
        bestPerfectStreak = 0
        stage = 0
        unlockedKinds = ShapeKind.unlockedCount(
            0, settings.startingShapeCount, settings.shapesPerStage
        )
        timeScale = 1f
        perfectSlowMo = 0f
        dangerRecovery = 0f
        dangerArmed = true
        pixels.reset()
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
                if (state != State.PLAYING) {
                    pressedButton = when {
                        primaryButton.contains(event.x, event.y) -> 1
                        secondaryButton.contains(event.x, event.y) -> 2
                        else -> 0
                    }
                    return true
                }
                lastTouchX = event.x
                lastTouchY = event.y
                hasLastTouch = true
                trailPoints.add(TrailPoint(event.x, event.y, System.currentTimeMillis()))
                pixels.burst(event.x, event.y, 1.0f)
            }

            MotionEvent.ACTION_MOVE -> {
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
                if (state != State.PLAYING) {
                    val released = pressedButton
                    pressedButton = 0
                    when {
                        released == 1 && primaryButton.contains(event.x, event.y) -> {
                            if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
                            startNewGame()
                        }
                        released == 2 && secondaryButton.contains(event.x, event.y) -> {
                            if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
                            onOpenSettings?.invoke()
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
        deviation <= 10f -> Grade.GOOD
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

        applyHealth(deviation, grade)
        val gained = applyScore(deviation, grade)

        val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay)).coerceAtLeast(0.001f)
        val dirX = (bx - ax) / len
        val dirY = (by - ay) / len

        spawnPieces(shape, left, right, dirX, dirY)
        spawnCutEffects(shape, dirX, dirY, grade)

        val bigger = (max(areaA, areaB) / (areaA + areaB) * 100f).roundToInt()
        val split = "$bigger / ${100 - bigger}"
        recordCutBucket(grade)

        // Float the verdict well above the shape, clear of the flying halves and
        // debris, so it is actually readable instead of buried in the explosion.
        val popupY = (shape.y - shape.radius - 70f * density).coerceAtLeast(height * 0.14f)
        val headline = when {
            grade == Grade.PERFECT -> "PERFECT"
            gained < 0 -> "$gained"
            else -> "+$gained"
        }
        val subline = when {
            grade == Grade.PERFECT -> "+$gained" + streakSuffix()
            grade == Grade.GREAT -> "GREAT  ·  $split" + streakSuffix()
            coldStreak >= 2 -> "COLD STREAK  ·  $split"
            else -> split
        }
        effects.popup(
            headline = headline,
            subline = subline,
            x = shape.x,
            y = shape.y,
            color = if (gained < 0) Theme.danger else gradeColor(grade),
            emphasis = when {
                grade == Grade.PERFECT -> 1f
                grade == Grade.GREAT -> 0.6f
                coldStreak >= 2 -> 0.5f
                else -> 0f
            }
        )

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
            // The streak has already been advanced by applyScore, so the first
            // perfect heals one step, the second two, and ten refills a full bar.
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
                color = shape.lightColor,
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
                    shape.x, shape.y, Theme.lighten(shape.lightColor, 0.25f),
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
        if (state == State.PLAYING) {
            drawHud(canvas)
        }

        when (state) {
            State.READY -> drawReadyScreen(canvas)
            State.GAME_OVER -> drawGameOverScreen(canvas)
            State.PLAYING -> {}
        }
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        ditherShader?.let {
            ditherPaint.shader = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ditherPaint)
        }
        pixels.draw(canvas, pixelPaint, effects.energy)

        // A quiet seam along the floor, tinted by how the run is going.
        rimPaint.strokeWidth = 2f
        rimPaint.color = Theme.withAlpha(
            pixels.horizonColor(),
            0.22f + 0.30f * effects.energy.coerceAtMost(1f)
        )
        canvas.drawLine(0f, height * 0.995f, width.toFloat(), height * 0.995f, rimPaint)
    }

    /** A small tile of faint monochrome noise, repeated across the screen. */
    private fun buildDitherShader(): BitmapShader {
        val size = 64
        val pixels = IntArray(size * size)
        for (i in pixels.indices) {
            // A couple of levels of jitter is all it takes to dissolve a band edge.
            val level = random.nextInt(3)
            pixels[i] = Color.argb(9 + level * 5, 255, 255, 255)
        }
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        return BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun buildPath(vertices: List<PointF2>) {
        path.rewind()
        vertices.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
    }

    private fun bodyShader(paletteIndex: Int): RadialGradient =
        bodyShaders.getOrPut(paletteIndex) {
            val pair = Theme.shapePalette[paletteIndex]
            RadialGradient(
                0f, 0f, 1f,
                intArrayOf(Theme.lighten(pair[0], 0.22f), pair[0], pair[1]),
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
        glowPaint.color = Theme.withAlpha(shape.lightColor, 0.10f)
        canvas.drawPath(path, glowPaint)
        canvas.restore()

        canvas.save()
        canvas.translate(shape.x, shape.y)
        canvas.scale(1.06f, 1.06f)
        canvas.translate(-shape.x, -shape.y)
        buildPath(verts)
        glowPaint.color = Theme.withAlpha(shape.lightColor, 0.16f)
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
        rimPaint.color = Theme.withAlpha(Theme.lighten(shape.lightColor, 0.55f), 0.75f)
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

        fillPaint.color = Theme.withAlpha(Theme.shapePalette[piece.paletteIndex][0], alpha * 0.95f)
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
        val barWidth = width - pad * 2

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
            // Critical health alternates red and blue like an emergency light.
            if (critical) {
                val lamp = 0.5f + 0.5f * sin(elapsed * 8.2f)
                healthColor = Theme.lerpColor(EMERGENCY_RED, EMERGENCY_BLUE, lamp)
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
            Theme.lerpColor(EMERGENCY_RED, EMERGENCY_BLUE, 0.5f + 0.5f * sin(elapsed * 8.2f))
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

        // Score, centred and large.
        displayPaint.textAlign = Paint.Align.CENTER
        displayPaint.textSize = 34f * density
        displayPaint.color = Theme.textPrimary
        canvas.drawText(displayedScore.roundToInt().toString(), width / 2f, barTop + barHeight + 62f * density, displayPaint)

        uiPaint.textSize = 14f * density
        uiPaint.color = Theme.textFaint
        canvas.drawText("SCORE", width / 2f, barTop + barHeight + 80f * density, uiPaint)

        val streakY = barTop + barHeight + 108f * density
        if (hotStreak > 1) {
            // Hot streak: swells and glows as it climbs.
            val multiplier = comboMultiplier()
            val beat = 1f + 0.06f * sin(elapsed * 9f)
            uiBoldPaint.textAlign = Paint.Align.CENTER
            uiBoldPaint.textSize = 21f * density * beat
            uiBoldPaint.color = Theme.gold
            canvas.drawText(
                "${hotStreak}x STREAK  ·  ${"%.1f".format(multiplier)}x SCORE",
                width / 2f, streakY, uiBoldPaint
            )
        } else if (coldStreak > 1) {
            val beat = 0.5f + 0.5f * sin(elapsed * 12f)
            uiBoldPaint.textAlign = Paint.Align.CENTER
            uiBoldPaint.textSize = 21f * density
            uiBoldPaint.color = Theme.lerpColor(Theme.danger, Color.WHITE, beat * 0.5f)
            canvas.drawText("${coldStreak}x COLD  ·  SCORE FALLING", width / 2f, streakY, uiBoldPaint)
        }
    }

    /**
     * The critical-health warning: a red-and-blue wash alternating like an
     * ambulance light, plus a pulsing CRITICAL banner. Runs for as long as health
     * stays low rather than for a fixed countdown.
     */
    private fun drawCriticalWarning(canvas: Canvas) {
        if (state != State.PLAYING || health <= 0 || health > settings.lowHealthAt) return

        // Two lamps a half-cycle apart, so the colour alternates rather than throbs.
        val cycle = (elapsed * 2.4f) % 1f
        val redLamp = (cos(cycle * 6.2832f) * 0.5f + 0.5f)
        val blueLamp = 1f - redLamp

        val edge = width * 0.62f
        scrimPaint.shader = LinearGradient(
            0f, 0f, edge, 0f,
            Theme.withAlpha(EMERGENCY_RED, 0.62f * redLamp), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, edge, height.toFloat(), scrimPaint)

        scrimPaint.shader = LinearGradient(
            width.toFloat(), 0f, width - edge, 0f,
            Theme.withAlpha(EMERGENCY_BLUE, 0.62f * blueLamp), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(width - edge, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // A sweep across the top and bottom edges as well, so the whole frame pulses.
        val bandHeight = height * 0.16f
        scrimPaint.shader = LinearGradient(
            0f, 0f, 0f, bandHeight,
            Theme.withAlpha(if (redLamp > 0.5f) EMERGENCY_RED else EMERGENCY_BLUE, 0.34f),
            Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), bandHeight, scrimPaint)
        scrimPaint.shader = LinearGradient(
            0f, height.toFloat(), 0f, height - bandHeight,
            Theme.withAlpha(if (redLamp > 0.5f) EMERGENCY_BLUE else EMERGENCY_RED, 0.34f),
            Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, height - bandHeight, width.toFloat(), height.toFloat(), scrimPaint)
        scrimPaint.shader = null

        // Headline sized like the PERFECT call-out, dead centre, breathing in and out.
        val breathe = 0.5f + 0.5f * cos(elapsed * 5.5f)
        val lampColor = if (redLamp > 0.5f) EMERGENCY_RED else EMERGENCY_BLUE
        val cx = width / 2f
        val cy = height * 0.5f

        displayPaint.textAlign = Paint.Align.CENTER
        displayPaint.textSize = 30f * density * (1f + 0.06f * breathe)

        displayPaint.style = Paint.Style.STROKE
        displayPaint.strokeWidth = 12f * density
        displayPaint.color = Theme.withAlpha(lampColor, 0.28f * (0.5f + breathe))
        canvas.drawText("LOW HEALTH", cx, cy, displayPaint)
        displayPaint.style = Paint.Style.FILL

        displayPaint.color = Theme.withAlpha(Color.WHITE, 0.7f + 0.3f * breathe)
        canvas.drawText("LOW HEALTH", cx, cy, displayPaint)

        uiBoldPaint.textAlign = Paint.Align.CENTER
        uiBoldPaint.textSize = 17f * density
        uiBoldPaint.color = Theme.withAlpha(lampColor, 0.75f + 0.25f * breathe)
        canvas.drawText("$health HP LEFT", cx, cy + 30f * density, uiBoldPaint)
    }

    // ---- Overlays ----

    private fun drawReadyScreen(canvas: Canvas) {
        scrimPaint.color = Color.argb(170, 4, 6, 14)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        val cx = width / 2f

        displayPaint.textAlign = Paint.Align.CENTER
        displayPaint.textSize = 30f * density
        displayPaint.color = Theme.textPrimary
        canvas.drawText("HALF", cx, height * 0.29f, displayPaint)
        displayPaint.color = Theme.accent
        canvas.drawText("MEASURES", cx, height * 0.29f + 38f * density, displayPaint)

        uiPaint.textSize = 19f * density
        uiPaint.color = Theme.textSecondary
        canvas.drawText("Slice every shape exactly in half.", cx, height * 0.29f + 78f * density, uiPaint)
        uiPaint.color = Theme.textFaint
        canvas.drawText("Follow the dashed line. Miss nothing.", cx, height * 0.29f + 102f * density, uiPaint)

        if (bestScore > 0) {
            drawChip(canvas, cx, height * 0.47f, "BEST  $bestScore")
        }

        drawButton(canvas, primaryButton, "PLAY", primary = true, pressed = pressedButton == 1)
        drawButton(canvas, secondaryButton, "SETTINGS", primary = false, pressed = pressedButton == 2)
    }

    private fun drawGameOverScreen(canvas: Canvas) {
        scrimPaint.color = Color.argb(205, 3, 5, 12)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // Fireworks belong above the dimming scrim, not behind it.
        if (beatBestScore) {
            pixels.draw(canvas, pixelPaint, effects.energy)
            drawShockwaves(canvas)
            drawParticles(canvas)
        }

        val cardLeft = gameOverCard.left
        val cardRight = gameOverCard.right
        val cardTop = gameOverCard.top
        val cx = gameOverCard.centerX()
        val padX = 28f * density
        val rowStep = CARD_STAT_ROW_HEIGHT * density

        val radius = 26f * density
        panelPaint.color = Theme.card
        canvas.drawRoundRect(gameOverCard, radius, radius, panelPaint)
        canvas.drawRoundRect(gameOverCard, radius, radius, panelStrokePaint)

        val accentColor = if (endedOnMiss) Theme.danger else Theme.gold

        uiBoldPaint.textAlign = Paint.Align.CENTER
        uiBoldPaint.textSize = 17f * density
        uiBoldPaint.color = if (beatBestScore) Theme.gold else accentColor
        canvas.drawText(
            when {
                beatBestScore -> "NEW BEST!"
                endedOnMiss -> "ONE GOT AWAY"
                else -> "OUT OF HEALTH"
            },
            cx, cardTop + 40f * density, uiBoldPaint
        )

        displayPaint.textAlign = Paint.Align.CENTER
        displayPaint.textSize = 52f * density
        displayPaint.color = if (beatBestScore) Theme.gold else Theme.textPrimary
        canvas.drawText(score.toString(), cx, cardTop + 112f * density, displayPaint)

        uiPaint.textSize = 15f * density
        uiPaint.color = Theme.textFaint
        canvas.drawText("FINAL SCORE", cx, cardTop + 132f * density, uiPaint)

        rimPaint.strokeWidth = 1.5f
        rimPaint.color = Theme.hairline
        val dividerY = cardTop + CARD_HEADER_HEIGHT * density
        canvas.drawLine(cardLeft + padX, dividerY, cardRight - padX, dividerY, rimPaint)

        val left = cardLeft + padX
        val right = cardRight - padX
        var rowY = dividerY + 30f * density

        drawStatRow(canvas, left, right, rowY, "BEST SCORE", bestScore.toString(), Theme.accent)
        rowY += rowStep
        drawStatRow(canvas, left, right, rowY, "CUTS SURVIVED", cutCount.toString(), Theme.textPrimary)
        rowY += rowStep
        drawStatRow(canvas, left, right, rowY, "PERFECT CUTS", perfectCount.toString(), Theme.gold)
        rowY += rowStep
        drawStatRow(canvas, left, right, rowY, "BEST PERFECT STREAK", "${bestPerfectStreak}x", Theme.gold)
        rowY += rowStep
        drawStatRow(canvas, left, right, rowY, "BEST GOOD STREAK", "${bestStreak}x", Theme.good)
        rowY += 30f * density

        drawCutBreakdown(canvas, cardLeft, cardRight, rowY, CARD_BREAKDOWN_ROW_HEIGHT * density)

        drawButton(canvas, primaryButton, "RETRY", primary = true, pressed = pressedButton == 1)
        drawButton(canvas, secondaryButton, "SETTINGS", primary = false, pressed = pressedButton == 2)
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

        uiBoldPaint.textAlign = Paint.Align.LEFT
        uiBoldPaint.textSize = 12f * density
        uiBoldPaint.color = Theme.textFaint
        canvas.drawText("HOW YOUR CUTS LANDED", cardLeft + padX, top, uiBoldPaint)

        val labelWidth = 46f * density
        val countWidth = 34f * density
        val barLeft = cardLeft + padX + labelWidth
        val barRight = cardRight - padX - countWidth
        val barSpan = (barRight - barLeft).coerceAtLeast(1f)

        var y = top + 16f * density
        for (index in cutBuckets.indices) {
            val count = cutBuckets[index]
            val centerY = y + rowHeight * 0.5f

            uiBoldPaint.textAlign = Paint.Align.LEFT
            uiBoldPaint.textSize = 13f * density
            uiBoldPaint.color = if (count > 0) Theme.textSecondary else Theme.textFaint
            canvas.drawText(
                CUT_BUCKET_LABELS[index],
                cardLeft + padX,
                centerY + 4.5f * density,
                uiBoldPaint
            )

            val barHeight = 8f * density
            roundRect.set(barLeft, centerY - barHeight / 2f, barRight, centerY + barHeight / 2f)
            panelPaint.color = Theme.withAlpha(Color.WHITE, 0.07f)
            canvas.drawRoundRect(roundRect, barHeight / 2f, barHeight / 2f, panelPaint)

            if (count > 0 && peak > 0) {
                val fraction = count.toFloat() / peak
                roundRect.set(
                    barLeft, centerY - barHeight / 2f,
                    barLeft + barSpan * fraction, centerY + barHeight / 2f
                )
                panelPaint.color = bucketColor(index)
                canvas.drawRoundRect(roundRect, barHeight / 2f, barHeight / 2f, panelPaint)
            }

            uiBoldPaint.textAlign = Paint.Align.RIGHT
            uiBoldPaint.color = if (count > 0) Theme.textPrimary else Theme.textFaint
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
        valueColor: Int
    ) {
        uiBoldPaint.textAlign = Paint.Align.LEFT
        uiBoldPaint.textSize = 14f * density
        uiBoldPaint.color = Theme.textFaint
        canvas.drawText(label, left, y, uiBoldPaint)

        uiBoldPaint.textAlign = Paint.Align.RIGHT
        uiBoldPaint.textSize = 17f * density
        uiBoldPaint.color = valueColor
        canvas.drawText(value, right, y, uiBoldPaint)
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

    private fun drawButton(canvas: Canvas, rect: RectF, label: String, primary: Boolean, pressed: Boolean) {
        val radius = rect.height() / 2f
        val inset = if (pressed) 2f * density else 0f
        roundRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)

        if (primary) {
            panelPaint.shader = LinearGradient(
                roundRect.left, roundRect.top, roundRect.right, roundRect.bottom,
                Theme.accent, Theme.accentDeep, Shader.TileMode.CLAMP
            )
            panelPaint.color = Color.WHITE
            canvas.drawRoundRect(roundRect, radius, radius, panelPaint)
            panelPaint.shader = null
        } else {
            panelPaint.color = Theme.withAlpha(Color.WHITE, if (pressed) 0.14f else 0.08f)
            canvas.drawRoundRect(roundRect, radius, radius, panelPaint)
            panelStrokePaint.color = Theme.hairline
            canvas.drawRoundRect(roundRect, radius, radius, panelStrokePaint)
        }

        uiBoldPaint.textAlign = Paint.Align.CENTER
        uiBoldPaint.textSize = 22f * density
        uiBoldPaint.color = if (primary) Color.rgb(6, 20, 26) else Theme.textPrimary
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
        const val CARD_HEADER_HEIGHT = 156f
        const val CARD_STAT_ROW_HEIGHT = 26f
        const val CARD_BREAKDOWN_ROW_HEIGHT = 21f
        const val CARD_BOTTOM_PADDING = 26f

        val EMERGENCY_RED = Color.rgb(255, 62, 74)
        val EMERGENCY_BLUE = Color.rgb(74, 138, 255)

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
