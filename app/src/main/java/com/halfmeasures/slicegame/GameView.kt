package com.halfmeasures.slicegame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Custom-drawn game surface. Shapes launch upward from the bottom of the
 * screen; the player swipes across them to slice each one into two pieces.
 * The closer the split is to a 50/50 area division, the less health is lost
 * and the more score is earned.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    enum class State { READY, PLAYING, GAME_OVER }

    // ---- Tunable constants ----
    private val startHealth = 100
    private val trailMaxAgeMs = 140L

    // ---- Player-adjustable settings (see GameSettings / SettingsActivity) ----
    private var settings = GameSettings.load(context)

    /** Effective gravity for the current speedScale: scaling velocities by s and gravity by s^2
     *  reproduces the exact same flight arc traced out 1/s times slower - true slow motion. */
    private var gravity = GameShape.BASE_GRAVITY * settings.speedScale * settings.speedScale

    fun refreshSettings() {
        settings = GameSettings.load(context)
        gravity = GameShape.BASE_GRAVITY * settings.speedScale * settings.speedScale
    }

    // ---- Game state ----
    private var state = State.READY
    private var health = startHealth
    private var score = 0
    private var bestScore = 0
    private var lastFrameTimeNanos = 0L
    private var nextSpawnAtMs = 0L
    private val random = Random(System.currentTimeMillis())

    private val shapes = ArrayList<GameShape>()
    private val pieces = ArrayList<SlicedPiece>()
    private val popups = ArrayList<ScorePopup>()
    private val trailPoints = ArrayList<TrailPoint>()

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var hasLastTouch = false

    // ---- Paint objects (reused to avoid per-frame allocation) ----
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shapeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.argb(90, 255, 255, 255)
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 56f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD)
    }
    private val hudSmallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        textSize = 34f
    }
    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD)
    }
    private val healthBarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 255, 255) }
    private val healthBarFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD)
    }
    private val bgPaint = Paint()

    init {
        isFocusable = true
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
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
        dt = min(dt, 1f / 30f) // clamp huge dt after a stall so shapes don't teleport

        update(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ---------------------------------------------------------------------
    // Update
    // ---------------------------------------------------------------------

    private fun update(dt: Float) {
        val nowMs = System.currentTimeMillis()

        // Fade the swipe trail.
        trailPoints.removeAll { nowMs - it.timeMs > trailMaxAgeMs }

        if (state == State.PLAYING) {
            if (nowMs >= nextSpawnAtMs && width > 0 && height > 0) {
                spawnShape(nowMs)
                scheduleNextSpawn(nowMs)
            }

            val iter = shapes.iterator()
            while (iter.hasNext()) {
                val s = iter.next()
                s.update(dt, gravity)
                if (s.isOffScreen(width, height)) {
                    iter.remove()
                }
            }

            if (health <= 0) {
                health = 0
                state = State.GAME_OVER
                bestScore = max(bestScore, score)
            }
        }

        // Sliced pieces keep flying and fading regardless of game state.
        val pieceIter = pieces.iterator()
        while (pieceIter.hasNext()) {
            val p = pieceIter.next()
            p.update(dt, gravity)
            if (p.age > 1.4f) pieceIter.remove()
        }

        val popupIter = popups.iterator()
        while (popupIter.hasNext()) {
            val pu = popupIter.next()
            pu.update(dt)
            if (pu.age > 0.9f) popupIter.remove()
        }
    }

    private fun scheduleNextSpawn(nowMs: Long) {
        // Ramps from the start interval down to its floor as the player's score climbs,
        // per settings.difficultyRampScore - not simply elapsed time.
        val progress = min(1f, score / settings.difficultyRampScore.toFloat())
        val start = settings.spawnIntervalStartMs
        val floor = settings.spawnIntervalFloorMs
        val interval = (start - (start - floor) * progress).toLong().coerceAtLeast(floor)
        nextSpawnAtMs = nowMs + interval - random.nextLong(0, interval / 3 + 1)
    }

    private fun spawnShape(nowMs: Long) {
        shapes.add(GameShape.spawnRandom(width, height, random, nowMs, settings.sizeScale, settings.speedScale))
    }

    // ---------------------------------------------------------------------
    // Touch handling / slicing
    // ---------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state != State.PLAYING) {
                    startNewGame()
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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

        // Ignore near-zero-length segments (jitter).
        val dx = bx - ax
        val dy = by - ay
        if (dx * dx + dy * dy < 4f) return

        val toRemove = ArrayList<GameShape>()
        for (shape in shapes) {
            if (shape.sliced) continue
            if (SliceMath.segmentSlicesShape(shape, ax, ay, bx, by)) {
                sliceShape(shape, ax, ay, bx, by)
                toRemove.add(shape)
            }
        }
        shapes.removeAll(toRemove)
    }

    private fun sliceShape(shape: GameShape, ax: Float, ay: Float, bx: Float, by: Float) {
        val poly = shape.worldVertices()
        val (left, right) = SliceMath.splitPolygon(poly, ax, ay, bx, by)
        if (left.size < 3 || right.size < 3) return

        val areaA = SliceMath.polygonArea(left)
        val areaB = SliceMath.polygonArea(right)
        val total = areaA + areaB
        val imbalancePercent = if (total <= 0f) 100f else kotlin.math.abs(areaA - areaB) / total * 100f

        // Health lost is a tenth of the imbalance: a perfect 50/50 cut costs nothing,
        // a 60/40 cut costs 2, a 100/0 "cut" (barely grazing) costs 10.
        val healthLoss = (imbalancePercent / 10f).roundToInt().coerceIn(0, 100)
        health = (health - healthLoss).coerceAtLeast(0)

        // Reward precision: perfect cuts are worth the most, up to a combo bonus.
        val precisionScore = max(0, 100 - imbalancePercent.toInt())
        val gained = 10 + precisionScore
        score += gained

        // Perpendicular direction to the slice line, used to push the two halves apart.
        val dx = bx - ax
        val dy = by - ay
        val len = kotlin.math.sqrt(dx * dx + dy * dy).let { if (it < 0.001f) 1f else it }
        val nx = -dy / len
        val ny = dx / len
        val kick = 90f

        pieces.add(SlicedPiece(left, shape.x, shape.y, shape.vx + nx * kick, shape.vy + ny * kick, shape.rotation, shape.angularVelocity, shape.color))
        pieces.add(SlicedPiece(right, shape.x, shape.y, shape.vx - nx * kick, shape.vy - ny * kick, shape.rotation, shape.angularVelocity, shape.color))

        val splitA = (max(areaA, areaB) / total * 100f).roundToInt()
        val label = if (healthLoss == 0) "PERFECT!" else "$splitA/${100 - splitA}"
        val popupColor = when {
            healthLoss <= 1 -> Color.rgb(6, 214, 160)
            healthLoss <= 5 -> Color.rgb(255, 209, 102)
            else -> Color.rgb(239, 71, 111)
        }
        popups.add(ScorePopup("+$gained  $label", shape.x, shape.y, popupColor))
    }

    // ---------------------------------------------------------------------
    // Game flow
    // ---------------------------------------------------------------------

    private fun startNewGame() {
        shapes.clear()
        pieces.clear()
        popups.clear()
        trailPoints.clear()
        health = startHealth
        score = 0
        state = State.PLAYING
        nextSpawnAtMs = System.currentTimeMillis() + 300
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)

        for (piece in pieces) drawPiece(canvas, piece)
        for (shape in shapes) drawShape(canvas, shape)

        drawTrail(canvas)
        for (popup in popups) drawPopup(canvas, popup)

        drawHud(canvas)

        when (state) {
            State.READY -> drawCenterMessage(canvas, "HALF MEASURES", "Slice each shape as close to 50/50 as you can\n\nTap to start")
            State.GAME_OVER -> drawCenterMessage(canvas, "GAME OVER", "Score: $score   Best: $bestScore\n\nTap to play again")
            State.PLAYING -> {}
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.rgb(13, 20, 33), Color.rgb(5, 8, 14),
                Shader.TileMode.CLAMP
            )
        }
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
    }

    private fun shapePath(vertices: List<PointF2>): Path {
        val path = Path()
        vertices.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        return path
    }

    private fun drawShape(canvas: Canvas, shape: GameShape) {
        val path = shapePath(shape.worldVertices())
        shapePaint.color = shape.color
        canvas.drawPath(path, shapePaint)
        canvas.drawPath(path, shapeStrokePaint)
    }

    private fun drawPiece(canvas: Canvas, piece: SlicedPiece) {
        if (piece.points.size < 3) return
        val path = Path()
        piece.points.forEachIndexed { i, p ->
            val px = piece.x + (p.x - piece.originX)
            val py = piece.y + (p.y - piece.originY)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        shapePaint.color = piece.color
        shapePaint.alpha = (255 * (1f - piece.age / 1.4f)).toInt().coerceIn(0, 255)
        canvas.drawPath(path, shapePaint)
        shapePaint.alpha = 255
    }

    private fun drawTrail(canvas: Canvas) {
        if (trailPoints.size < 2) return
        val nowMs = System.currentTimeMillis()
        for (i in 1 until trailPoints.size) {
            val p0 = trailPoints[i - 1]
            val p1 = trailPoints[i]
            val age = nowMs - p1.timeMs
            val alpha = (255 * (1f - age.toFloat() / trailMaxAgeMs)).toInt().coerceIn(0, 255)
            trailPaint.alpha = alpha
            trailPaint.strokeWidth = 26f * (alpha / 255f) + 5f
            canvas.drawLine(p0.x, p0.y, p1.x, p1.y, trailPaint)
        }
    }

    private fun drawPopup(canvas: Canvas, popup: ScorePopup) {
        val alpha = (255 * (1f - popup.age / 0.9f)).toInt().coerceIn(0, 255)
        popupPaint.color = popup.color
        popupPaint.alpha = alpha
        popupPaint.textSize = 40f
        canvas.drawText(popup.text, popup.x, popup.y - popup.age * 90f, popupPaint)
    }

    private fun drawHud(canvas: Canvas) {
        val pad = 40f

        // Health bar.
        val barW = width - pad * 2
        val barH = 34f
        val barY = pad
        canvas.drawRoundRect(pad, barY, pad + barW, barY + barH, 17f, 17f, healthBarBgPaint)
        val healthFrac = health / 100f
        healthBarFgPaint.color = when {
            health > 55 -> Color.rgb(6, 214, 160)
            health > 25 -> Color.rgb(255, 209, 102)
            else -> Color.rgb(239, 71, 111)
        }
        if (healthFrac > 0f) {
            canvas.drawRoundRect(pad, barY, pad + barW * healthFrac, barY + barH, 17f, 17f, healthBarFgPaint)
        }
        hudSmallTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("$health", pad + barW / 2f, barY + barH - 8f, hudSmallTextPaint)
        hudSmallTextPaint.textAlign = Paint.Align.LEFT

        // Score.
        canvas.drawText("Score: $score", pad, barY + barH + 60f, hudTextPaint)
    }

    private fun drawCenterMessage(canvas: Canvas, title: String, subtitle: String) {
        canvas.drawColor(Color.argb(140, 0, 0, 0))
        centerTextPaint.textSize = 76f
        canvas.drawText(title, width / 2f, height / 2f - 40f, centerTextPaint)

        centerTextPaint.textSize = 34f
        val lines = subtitle.split("\n")
        var ty = height / 2f + 30f
        for (line in lines) {
            if (line.isNotBlank()) canvas.drawText(line, width / 2f, ty, centerTextPaint)
            ty += 50f
        }
    }

    // ---------------------------------------------------------------------
    // Small data holders
    // ---------------------------------------------------------------------

    private class TrailPoint(val x: Float, val y: Float, val timeMs: Long)

    private class SlicedPiece(
        val points: List<PointF2>,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var rotation: Float,
        val angularVelocity: Float,
        val color: Int
    ) {
        val originX = x
        val originY = y
        var age = 0f

        fun update(dt: Float, gravity: Float) {
            vy += gravity * dt
            x += vx * dt
            y += vy * dt
            rotation += angularVelocity * dt
            age += dt
        }
    }

    private class ScorePopup(val text: String, val x: Float, val y: Float, val color: Int) {
        var age = 0f
        fun update(dt: Float) {
            age += dt
        }
    }
}
