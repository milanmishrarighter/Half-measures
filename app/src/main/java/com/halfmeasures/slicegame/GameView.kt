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
    private var bestScore = 0
    private var perfectStreak = 0
    private var bestStreak = 0
    private var perfectCount = 0
    private var cutCount = 0
    private var endedOnMiss = false
    private var unlockedKinds = 0

    private var lastFrameTimeNanos = 0L
    private var nextSpawnAtMs = 0L
    private var elapsed = 0f

    /** Health bar lags the true value so gains and losses read as motion. */
    private var displayedHealth = settings.startHealth.toFloat()
    private var displayedScore = 0f

    private val shapes = ArrayList<GameShape>()
    private val pieces = ArrayList<SlicedPiece>()
    private val trailPoints = ArrayList<TrailPoint>()
    private val bokeh = ArrayList<Bokeh>()

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var hasLastTouch = false
    private val trailMaxAgeMs = 165L

    // ---- Buttons (laid out in onSizeChanged, hit-tested in onTouchEvent) ----
    private val primaryButton = RectF()
    private val secondaryButton = RectF()
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
    private val bokehPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val floorGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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
        var dt = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
        lastFrameTimeNanos = frameTimeNanos
        dt = min(dt, 1f / 30f) // after a stall, step conservatively instead of teleporting

        update(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Theme.bgTop, Theme.bgBottom, Color.rgb(4, 5, 12)),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        floorGlowPaint.shader = RadialGradient(
            w / 2f, h * 1.02f, h * 0.42f,
            intArrayOf(Theme.withAlpha(Theme.bgGlow, 0.5f), Theme.withAlpha(Theme.bgGlow, 0f)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        bokeh.clear()
        repeat(14) {
            bokeh.add(
                Bokeh(
                    x = random.nextFloat() * w,
                    y = random.nextFloat() * h,
                    radius = (w * 0.03f) + random.nextFloat() * (w * 0.11f),
                    drift = 6f + random.nextFloat() * 18f,
                    phase = random.nextFloat() * 6.2832f,
                    alpha = 0.025f + random.nextFloat() * 0.045f
                )
            )
        }

        layoutButtons(w, h)
    }

    private fun layoutButtons(w: Int, h: Int) {
        val buttonWidth = min(w * 0.72f, 300f * density)
        val buttonHeight = 58f * density
        val cx = w / 2f
        val primaryTop = h * 0.62f
        primaryButton.set(
            cx - buttonWidth / 2f, primaryTop,
            cx + buttonWidth / 2f, primaryTop + buttonHeight
        )
        val secondaryTop = primaryTop + buttonHeight + 16f * density
        secondaryButton.set(
            cx - buttonWidth / 2f, secondaryTop,
            cx + buttonWidth / 2f, secondaryTop + buttonHeight
        )
    }

    // ---------------------------------------------------------------------
    // Simulation
    // ---------------------------------------------------------------------

    private fun update(dt: Float) {
        elapsed += dt
        val nowMs = System.currentTimeMillis()

        trailPoints.removeAll { nowMs - it.timeMs > trailMaxAgeMs }

        for (b in bokeh) b.update(dt, height)

        if (state == State.PLAYING) {
            val cap = (settings.startConcurrency + score / max(1, settings.concurrencyStepScore))
                .coerceAtMost(settings.maxConcurrency)
            if (shapes.size < cap && nowMs >= nextSpawnAtMs && width > 0 && height > 0) {
                shapes.add(GameShape.spawnRandom(width, height, random, nowMs, score, settings))
                nextSpawnAtMs = nowMs + settings.spawnGapMs
            }

            announceNewShapeUnlocks()

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

        effects.update(dt, gravity)

        displayedHealth += (health - displayedHealth) * min(1f, dt * 9f)
        displayedScore += (score - displayedScore) * min(1f, dt * 12f)
    }

    /** Celebrates the moment a harder shape joins the rotation. */
    private fun announceNewShapeUnlocks() {
        val kinds = ShapeKind.values()
        val pace = settings.shapeUnlockPace
        var count = 0
        for (k in kinds) if (k.unlockScore * pace <= score) count++

        if (unlockedKinds == 0) {
            unlockedKinds = count
            return
        }
        if (count > unlockedKinds) {
            // Kinds are declared in ascending unlock order, so the newest is the last unlocked.
            val fresh = kinds[count - 1]
            effects.popup(
                headline = "NEW SHAPE",
                subline = fresh.displayName.uppercase(),
                x = width / 2f,
                y = height * 0.3f,
                color = Theme.accent,
                emphatic = true
            )
            if (settings.vibrationEnabled) haptics.tick(settings.vibrationStrength)
            unlockedKinds = count
        }
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
        val count = (5 * settings.particleAmount).roundToInt().coerceIn(1, 18)
        effects.burst(
            x = wallX,
            y = s.y,
            dirX = 0f,
            dirY = 1f,
            spread = s.radius * 0.5f,
            color = s.lightColor,
            count = count,
            speed = 130f
        )
    }

    private fun endRun() {
        if (state != State.PLAYING) return
        state = State.GAME_OVER
        bestScore = max(bestScore, score)
        bestStreak = max(bestStreak, perfectStreak)
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
        perfectCount = 0
        cutCount = 0
        endedOnMiss = false
        unlockedKinds = 0
        state = State.PLAYING
        nextSpawnAtMs = System.currentTimeMillis() + 320
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

    /** Returns true when the cut landed and the shape should be consumed. */
    private fun sliceShape(shape: GameShape, ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val poly = shape.worldVertices()
        val (left, right) = SliceMath.splitPolygon(poly, ax, ay, bx, by)
        val areaA = SliceMath.polygonArea(left)
        val areaB = SliceMath.polygonArea(right)
        if (areaA <= 0f || areaB <= 0f) return false

        val deviation = SliceMath.deviationPercent(areaA, areaB)
        val perfect = deviation <= settings.perfectThreshold
        cutCount++

        // Health: a perfect cut refills the bar, anything else costs by how far off it was.
        if (perfect && settings.perfectRestoresHealth) {
            health = maxHealth
        } else {
            val loss = (deviation * settings.healthLossPerDeviation).roundToInt()
            health = (health - loss).coerceIn(0, maxHealth)
        }

        // Score: 100 for a flawless halving, falling away with the miss.
        if (perfect) {
            perfectStreak++
            perfectCount++
            bestStreak = max(bestStreak, perfectStreak)
        } else {
            perfectStreak = 0
        }
        // A flawless cut is worth a clean 100; the combo bonus only starts paying
        // from the second perfect in a row, so the first one is exactly 100.
        val multiplier = comboMultiplier()
        val base = (100f - deviation * settings.scoreMissWeight).coerceAtLeast(0f)
        val gained = (base * multiplier).roundToInt()
        score += gained

        val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay)).coerceAtLeast(0.001f)
        val dirX = (bx - ax) / len
        val dirY = (by - ay) / len

        spawnPieces(shape, left, right, dirX, dirY)
        spawnCutEffects(shape, dirX, dirY, deviation, perfect)

        val bigger = (max(areaA, areaB) / (areaA + areaB) * 100f).roundToInt()
        effects.popup(
            headline = if (perfect) "PERFECT" else "+$gained",
            subline = if (perfect) "+$gained" else "$bigger / ${100 - bigger}",
            x = shape.x,
            y = shape.y,
            color = qualityColor(deviation, perfect),
            emphatic = perfect
        )

        if (settings.vibrationEnabled) {
            if (perfect) haptics.perfect(settings.vibrationStrength)
            else haptics.cut(settings.vibrationStrength, 1f - deviation / 50f)
        }
        return true
    }

    /** 1.0 until a second consecutive perfect, then grows with the streak. */
    private fun comboMultiplier(): Float {
        val bonusSteps = (perfectStreak - 1).coerceAtLeast(0)
        return (1f + bonusSteps * settings.comboBonusPercent / 100f)
            .coerceAtMost(settings.maxComboMultiplier)
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

    private fun spawnCutEffects(
        shape: GameShape,
        dirX: Float,
        dirY: Float,
        deviation: Float,
        perfect: Boolean
    ) {
        if (settings.particlesEnabled) {
            val amount = settings.particleAmount
            val sparkCount = ((if (perfect) 30 else 16) * amount).roundToInt().coerceIn(2, 90)
            effects.burst(
                x = shape.x, y = shape.y,
                dirX = dirX, dirY = dirY,
                spread = shape.radius * 0.85f,
                color = shape.lightColor,
                count = sparkCount,
                speed = 300f
            )
            if (perfect) {
                effects.radialBurst(
                    shape.x, shape.y, Theme.gold,
                    (26 * amount).roundToInt().coerceIn(4, 80), 380f
                )
                effects.shockwave(shape.x, shape.y, shape.radius * 4.2f, Theme.gold)
            }
        }
        if (perfect) {
            effects.addShake(0.85f * settings.cameraShakeStrength)
        } else if (deviation < 12f) {
            effects.addShake(0.22f * settings.cameraShakeStrength)
        }
    }

    private fun qualityColor(deviation: Float, perfect: Boolean): Int = when {
        perfect -> Theme.gold
        deviation <= 6f -> Theme.good
        deviation <= 16f -> Theme.accent
        deviation <= 28f -> Color.rgb(255, 190, 90)
        else -> Theme.danger
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

        drawPopups(canvas)
        if (state == State.PLAYING) drawHud(canvas)

        when (state) {
            State.READY -> drawReadyScreen(canvas)
            State.GAME_OVER -> drawGameOverScreen(canvas)
            State.PLAYING -> {}
        }
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        for (b in bokeh) {
            bokehPaint.color = Theme.withAlpha(Theme.bgGlow, b.alpha)
            canvas.drawCircle(b.x, b.renderY, b.radius, bokehPaint)
        }
        canvas.drawRect(0f, height * 0.55f, width.toFloat(), height.toFloat(), floorGlowPaint)

        // A soft line the shapes launch from, so the bottom edge reads as a stage.
        rimPaint.strokeWidth = 2f
        rimPaint.color = Theme.withAlpha(Theme.accent, 0.10f)
        canvas.drawLine(0f, height * 0.985f, width.toFloat(), height * 0.985f, rimPaint)
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

        if (settings.guideLineEnabled) drawGuideLine(canvas, shape, r)
    }

    /**
     * The dashed line marking exactly where a 50/50 cut lands. The offset is a
     * per-kind constant in unit space, rotated and scaled into place here.
     */
    private fun drawGuideLine(canvas: Canvas, shape: GameShape, r: Float) {
        val c = cos(shape.rotation)
        val s = sin(shape.rotation)
        val offset = shape.kind.bisectorOffsetUnit * r
        // Normal of the shape's local +x axis, rotated into world space.
        val nx = -s
        val ny = c
        val px = shape.x + nx * offset
        val py = shape.y + ny * offset

        val reach = r * 1.24f
        val dashLength = max(9f, r * 0.17f)
        val gapLength = dashLength * 0.75f
        val step = dashLength + gapLength
        // Crawl the dash pattern so the guide reads as active rather than static.
        val phase = (elapsed * 26f) % step

        guidePaint.strokeWidth = max(2.5f, r * 0.05f)
        guidePaint.color = Theme.withAlpha(Color.WHITE, settings.guideLineOpacity)

        var t = -reach - phase
        while (t < reach) {
            val segStart = t.coerceAtLeast(-reach)
            val segEnd = (t + dashLength).coerceAtMost(reach)
            if (segEnd > segStart) {
                canvas.drawLine(
                    px + c * segStart, py + s * segStart,
                    px + c * segEnd, py + s * segEnd,
                    guidePaint
                )
            }
            t += step
        }

        // Tiny pips at the ends to sell it as a measuring mark.
        guidePaint.strokeWidth = max(2f, r * 0.04f)
        guidePaint.color = Theme.withAlpha(Theme.accent, settings.guideLineOpacity * 0.9f)
        val pip = r * 0.13f
        canvas.drawLine(px + c * reach - nx * pip, py + s * reach - ny * pip,
            px + c * reach + nx * pip, py + s * reach + ny * pip, guidePaint)
        canvas.drawLine(px - c * reach - nx * pip, py - s * reach - ny * pip,
            px - c * reach + nx * pip, py - s * reach + ny * pip, guidePaint)
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
            val alpha = p.remaining
            particlePaint.color = Theme.withAlpha(p.color, alpha)
            if (p.streak) {
                particlePaint.style = Paint.Style.STROKE
                particlePaint.strokeWidth = p.size * 0.55f * alpha + 1f
                val tailScale = 0.035f
                canvas.drawLine(p.x, p.y, p.x - p.vx * tailScale, p.y - p.vy * tailScale, particlePaint)
                particlePaint.style = Paint.Style.FILL
            } else {
                canvas.drawCircle(p.x, p.y, p.size * alpha * 0.6f + 0.5f, particlePaint)
            }
        }
    }

    private fun drawShockwaves(canvas: Canvas) {
        for (s in effects.shockwaves) {
            val t = s.progress
            val radius = s.maxRadius * (1f - (1f - t) * (1f - t))
            ringPaint.strokeWidth = 9f * (1f - t) + 1f
            ringPaint.color = Theme.withAlpha(s.color, (1f - t) * 0.75f)
            canvas.drawCircle(s.x, s.y, radius, ringPaint)
        }
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
            val rise = t * 120f
            // Slight overshoot on entry so the text pops in rather than fading in.
            val pop = if (t < 0.18f) 0.7f + 1.9f * t else 1f + 0.05f * (1f - t)

            val baseSize = if (p.emphatic) 21f else 17f
            displayPaint.textAlign = Paint.Align.CENTER
            displayPaint.textSize = baseSize * density * pop
            displayPaint.color = Theme.withAlpha(p.color, alpha)
            canvas.drawText(p.headline, p.x, p.y - rise, displayPaint)

            if (p.subline.isNotEmpty()) {
                uiPaint.textSize = 16f * density
                uiPaint.color = Theme.withAlpha(Theme.textSecondary, alpha * 0.9f)
                canvas.drawText(p.subline, p.x, p.y - rise + displayPaint.textSize * 0.9f, uiPaint)
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
        if (frac > 0.001f) {
            val healthColor = when {
                frac > 0.55f -> Theme.good
                frac > 0.25f -> Theme.gold
                else -> Theme.danger
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
        uiBoldPaint.color = Theme.textFaint
        canvas.drawText("HEALTH", pad, barTop + barHeight + 20f * density, uiBoldPaint)

        uiBoldPaint.textAlign = Paint.Align.RIGHT
        uiBoldPaint.color = Theme.textSecondary
        canvas.drawText(
            "${health.coerceAtLeast(0)}/$maxHealth",
            pad + barWidth,
            barTop + barHeight + 20f * density,
            uiBoldPaint
        )

        // Score, centred and large.
        displayPaint.textAlign = Paint.Align.CENTER
        displayPaint.textSize = 34f * density
        displayPaint.color = Theme.textPrimary
        canvas.drawText(displayedScore.roundToInt().toString(), width / 2f, barTop + barHeight + 62f * density, displayPaint)

        uiPaint.textSize = 14f * density
        uiPaint.color = Theme.textFaint
        canvas.drawText("SCORE", width / 2f, barTop + barHeight + 80f * density, uiPaint)

        if (perfectStreak > 1) {
            val multiplier = comboMultiplier()
            uiBoldPaint.textAlign = Paint.Align.CENTER
            uiBoldPaint.textSize = 20f * density
            uiBoldPaint.color = Theme.gold
            canvas.drawText(
                "${perfectStreak}x PERFECT  ·  ${"%.1f".format(multiplier)}x",
                width / 2f,
                barTop + barHeight + 108f * density,
                uiBoldPaint
            )
        }
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
        scrimPaint.color = Color.argb(200, 3, 5, 12)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        val cx = width / 2f
        val cardLeft = width * 0.09f
        val cardRight = width * 0.91f
        val cardTop = height * 0.20f
        val cardBottom = primaryButton.top - 26f * density

        roundRect.set(cardLeft, cardTop, cardRight, cardBottom)
        val radius = 26f * density
        panelPaint.color = Theme.card
        canvas.drawRoundRect(roundRect, radius, radius, panelPaint)
        canvas.drawRoundRect(roundRect, radius, radius, panelStrokePaint)

        val accentColor = if (endedOnMiss) Theme.danger else Theme.gold

        uiBoldPaint.textAlign = Paint.Align.CENTER
        uiBoldPaint.textSize = 17f * density
        uiBoldPaint.color = accentColor
        canvas.drawText(
            if (endedOnMiss) "ONE GOT AWAY" else "OUT OF HEALTH",
            cx, cardTop + 40f * density, uiBoldPaint
        )

        displayPaint.textAlign = Paint.Align.CENTER
        displayPaint.textSize = 52f * density
        displayPaint.color = Theme.textPrimary
        canvas.drawText(score.toString(), cx, cardTop + 112f * density, displayPaint)

        uiPaint.textSize = 15f * density
        uiPaint.color = Theme.textFaint
        canvas.drawText("FINAL SCORE", cx, cardTop + 130f * density, uiPaint)

        // Divider
        rimPaint.strokeWidth = 1.5f
        rimPaint.color = Theme.hairline
        val dividerY = cardTop + 152f * density
        canvas.drawLine(cardLeft + 28f * density, dividerY, cardRight - 28f * density, dividerY, rimPaint)

        // Three stat columns.
        val statY = dividerY + 46f * density
        val third = (cardRight - cardLeft) / 3f
        drawStat(canvas, cardLeft + third * 0.5f, statY, bestScore.toString(), "BEST")
        drawStat(canvas, cardLeft + third * 1.5f, statY, perfectCount.toString(), "PERFECT")
        drawStat(canvas, cardLeft + third * 2.5f, statY, "${bestStreak}x", "STREAK")

        val accuracy = if (cutCount > 0) (perfectCount * 100f / cutCount).roundToInt() else 0
        uiPaint.textSize = 15f * density
        uiPaint.color = Theme.textFaint
        canvas.drawText(
            "$cutCount cuts · $accuracy% perfect",
            cx, statY + 38f * density, uiPaint
        )

        drawButton(canvas, primaryButton, "RETRY", primary = true, pressed = pressedButton == 1)
        drawButton(canvas, secondaryButton, "SETTINGS", primary = false, pressed = pressedButton == 2)
    }

    private fun drawStat(canvas: Canvas, cx: Float, cy: Float, value: String, label: String) {
        displayPaint.textAlign = Paint.Align.CENTER
        displayPaint.textSize = 19f * density
        displayPaint.color = Theme.textPrimary
        canvas.drawText(value, cx, cy, displayPaint)

        uiPaint.textSize = 13f * density
        uiPaint.color = Theme.textFaint
        canvas.drawText(label, cx, cy + 20f * density, uiPaint)
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

    private class TrailPoint(val x: Float, val y: Float, val timeMs: Long)

    private class Bokeh(
        val x: Float,
        var y: Float,
        val radius: Float,
        val drift: Float,
        val phase: Float,
        val alpha: Float
    ) {
        private var t = phase
        var renderY = y
            private set

        fun update(dt: Float, screenH: Int) {
            t += dt
            y -= drift * dt
            if (y < -radius * 2f) y = screenH + radius * 2f
            renderY = y + sin(t * 0.6f) * radius * 0.12f
        }
    }

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
