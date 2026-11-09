package com.ldrago.carromai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object BoardVision {

    data class LiveScanOutput(
        val pucks: List<Puck>,
        val pockets: List<Pocket>,
        val detectedStrikerPos: Vec2?,
        val detectedMode: String
    )

    fun scanFullBoard(bitmap: Bitmap, forceSmallPocket: Boolean): LiveScanOutput {
        val w = bitmap.width
        val h = bitmap.height

        // Calibrated to Carrom Pool's exact wooden board layout
        val boardLeft = (w * 0.072f).toInt()
        val boardRight = (w * 0.928f).toInt()
        val boardWidth = boardRight - boardLeft
        val boardTop = (h * 0.278f).toInt()
        val boardBottom = boardTop + boardWidth

        val baselineY = boardBottom - (boardWidth * 0.132f)
        val minBaseX = (boardLeft + boardWidth * 0.18f).toInt()
        val maxBaseX = (boardRight - boardWidth * 0.18f).toInt()

        // 1. Live Striker Detection along Baseline (Detects glowing gold/orange ring or bright white striker)
        var strikerXSum = 0f
        var strikerHits = 0
        val yBaseInt = baselineY.toInt().coerceIn(0, h - 1)
        for (x in minBaseX..maxBaseX step 6) {
            val px = bitmap.getPixel(x, yBaseInt)
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            // Glowing yellow/orange ring around active striker OR bright white core
            if ((r > 235 && g in 140..230 && b < 90) || (r > 240 && g > 240 && b > 235)) {
                strikerXSum += x
                strikerHits++
            }
        }
        val detectedStriker = if (strikerHits >= 3) {
            Vec2(strikerXSum / strikerHits, baselineY)
        } else null

        // 2. Scan Board Pucks (White, Black, Red Queen)
        val detectedClusters = mutableListOf<Puck>()
        val pocketInset = boardWidth * 0.064f
        val scanLeft = (boardLeft + pocketInset * 1.25f).toInt()
        val scanRight = (boardRight - pocketInset * 1.25f).toInt()
        val scanTop = (boardTop + pocketInset * 1.25f).toInt()
        val scanBottom = (boardBottom - pocketInset * 1.50f).toInt()

        var puckId = 1
        var hasRedQueen = false

        for (y in scanTop until scanBottom step 9) {
            if (y >= h) continue
            for (x in scanLeft until scanRight step 9) {
                if (x >= w) continue
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val puckType = classifyPixelAsPuck(r, g, b)
                if (puckType != 0) {
                    if (puckType == 3) hasRedQueen = true
                    val pos = Vec2(x.toFloat(), y.toFloat())
                    val existing = detectedClusters.any { it.pos.dist(pos) < 38f }
                    if (!existing) {
                        detectedClusters.add(Puck(puckId++, pos, puckType, false))
                    }
                }
            }
        }

        // 3. Auto Mode & Dynamic Hole Size Detection
        // Freestyle has smaller potting holes (33f) vs Carrom/Disc Pool (40f)
        val autoMode = if (forceSmallPocket) "FREESTYLE" else if (hasRedQueen) "CARROM" else "DISC"
        val pocketRadius = if (autoMode == "FREESTYLE") 33f else 40f

        val pockets = listOf(
            Pocket(Vec2(boardLeft + pocketInset, boardTop + pocketInset), pocketRadius),
            Pocket(Vec2(boardRight - pocketInset, boardTop + pocketInset), pocketRadius),
            Pocket(Vec2(boardLeft + pocketInset, boardBottom - pocketInset), pocketRadius),
            Pocket(Vec2(boardRight - pocketInset, boardBottom - pocketInset), pocketRadius)
        )

        val finalPucks = detectStackedPucks(detectedClusters, 27f)
        BoardMemoryCache.updateCache(finalPucks, pockets, autoMode)

        return LiveScanOutput(finalPucks, pockets, detectedStriker, autoMode)
    }

    private fun classifyPixelAsPuck(r: Int, g: Int, b: Int): Int {
        if (r > 180 && g < 52 && b < 60) return 3 // Red Queen
        if (r > 224 && g > 220 && b > 202) return 1 // White Puck
        if (r in 15..45 && g in 15..45 && b in 18..48) return 2 // Black Puck
        return 0
    }

    fun detectStackedPucks(rawPucks: List<Puck>, puckRadius: Float): List<Puck> {
        return rawPucks.map { p1 ->
            val stacked = rawPucks.any { p2 ->
                p1.id != p2.id && p1.pos.dist(p2.pos) < (puckRadius * 1.78f)
            }
            p1.copy(isStacked = stacked)
        }
    }
}

class AimOverlayView(context: Context, private val screenW: Int, private val screenH: Int) : View(context) {

    var boardLeft = screenW * 0.072f
    var boardRight = screenW * 0.928f
    var boardWidth = boardRight - boardLeft
    var boardTop = screenH * 0.278f
    var boardBottom = boardTop + boardWidth
    var baselineY = boardBottom - (boardWidth * 0.132f)

    var strikerPos = Vec2(screenW * 0.50f, baselineY)
    // Pull Handle represents your finger pulling back the striker (controls both Angle & Power!)
    var pullHandlePos = Vec2(screenW * 0.50f, baselineY + 170f)

    var laserColor: Int = Color.parseColor("#00F5D4")
    var lineThickness: Float = 5f
    var freestyleSmallPockets: Boolean = false
    var is2v2Mode: Boolean = false
    var isManualTouchEnabled: Boolean = false

    private var dragMode = 0 // 0 = None, 1 = Move Striker on Baseline, 2 = Pull Power & Angle

    private val strikerPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
    }

    private val puckPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val powerPullPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFD60A")
        strokeWidth = 4f
    }

    private val stopMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF2A85")
        strokeWidth = 4f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        isFakeBoldText = true
    }

    fun getPockets(): List<Pocket> {
        val inset = boardWidth * 0.064f
        val r = if (freestyleSmallPockets) 33f else 40f
        return listOf(
            Pocket(Vec2(boardLeft + inset, boardTop + inset), r),
            Pocket(Vec2(boardRight - inset, boardTop + inset), r),
            Pocket(Vec2(boardLeft + inset, boardBottom - inset), r),
            Pocket(Vec2(boardRight - inset, boardBottom - inset), r)
        )
    }

    fun getAimAngleAndPower(): Pair<Float, Float> {
        val dx = strikerPos.x - pullHandlePos.x
        val dy = strikerPos.y - pullHandlePos.y
        val pullDist = hypot(dx, dy).coerceAtLeast(5f)
        val angleRad = atan2(dy, dx)
        val powerPct = (pullDist / 240f).coerceIn(0.08f, 1.0f)
        return Pair(angleRad, powerPct)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        strikerPathPaint.color = laserColor
        strikerPathPaint.strokeWidth = (lineThickness * 0.8f).coerceAtLeast(2.5f)
        puckPathPaint.strokeWidth = lineThickness

        val pockets = getPockets()
        for (pk in pockets) {
            powerPullPaint.alpha = 95
            canvas.drawCircle(pk.pos.x, pk.pos.y, pk.radius, powerPullPaint)
        }
        powerPullPaint.alpha = 255

        val (aimAngle, powerPct) = getAimAngleAndPower()

        // Run 300-Step Box2D Forward Physics Simulation
        val engine = CarromPhysicsEngine(baselineY, boardLeft + 120f, boardRight - 120f)
        val sim = engine.simulateRealTimeStepTrajectory(
            strikerPos = strikerPos,
            aimAngleRad = aimAngle,
            powerPercent = powerPct,
            pucks = BoardMemoryCache.cachedPucks,
            pockets = pockets,
            boardLeft = boardLeft + 18f,
            boardTop = boardTop + 18f,
            boardRight = boardRight - 18f,
            boardBottom = boardBottom - 18f
        )

        // 1. Draw Curved/Bouncing Striker Path + Exact Friction Stop Point (⊗)
        drawPolyPath(canvas, sim.strikerPath, strikerPathPaint)
        val sStop = sim.strikerStopPoint
        canvas.drawCircle(sStop.x, sStop.y, 14f, stopMarkerPaint)
        canvas.drawLine(sStop.x - 9f, sStop.y - 9f, sStop.x + 9f, sStop.y + 9f, stopMarkerPaint)
        canvas.drawLine(sStop.x + 9f, sStop.y - 9f, sStop.x - 9f, sStop.y + 9f, stopMarkerPaint)

        // 2. Draw Every Hit Puck's Future Trajectory (Chain Collisions + Pocket Lock)
        for (puckRes in sim.impactedPuckPaths) {
            puckPathPaint.color = if (puckRes.pottedInPocket) Color.parseColor("#00F554") else laserColor
            drawPolyPath(canvas, puckRes.pathPoints, puckPathPaint)
            canvas.drawCircle(puckRes.stopPoint.x, puckRes.stopPoint.y, 16f, puckPathPaint)
        }

        // 3. Draw Detected Pucks (Stacked Pucks highlighted in Gold)
        for (p in BoardMemoryCache.cachedPucks) {
            val pColor = if (p.isStacked) Color.parseColor("#FFD60A") else Color.parseColor("#55FFFFFF")
            puckPathPaint.color = pColor
            canvas.drawCircle(p.pos.x, p.pos.y, 26f, puckPathPaint)
        }

        // 4. Draw Striker Ring & Gradual Power Pull Handle
        canvas.drawCircle(strikerPos.x, strikerPos.y, 36f, powerPullPaint)
        canvas.drawLine(strikerPos.x, strikerPos.y, pullHandlePos.x, pullHandlePos.y, powerPullPaint)
        canvas.drawCircle(pullHandlePos.x, pullHandlePos.y, 24f, stopMarkerPaint)

        val pwrInt = (powerPct * 100).toInt()
        canvas.drawText("$pwrInt%", pullHandlePos.x + 30f, pullHandlePos.y + 8f, textPaint)
    }

    private fun drawPolyPath(canvas: Canvas, pts: List<Vec2>, paint: Paint) {
        if (pts.size < 2) return
        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            path.lineTo(pts[i].x, pts[i].y)
        }
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isManualTouchEnabled) return false
        val tx = event.rawX
        val ty = event.rawY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (hypot(tx - pullHandlePos.x, ty - pullHandlePos.y) < 85f) {
                    dragMode = 2
                    return true
                }
                if (hypot(tx - strikerPos.x, ty - strikerPos.y) < 85f) {
                    dragMode = 1
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == 1) {
                    val dx = pullHandlePos.x - strikerPos.x
                    val dy = pullHandlePos.y - strikerPos.y
                    strikerPos.x = tx.coerceIn(boardLeft + 115f, boardRight - 115f)
                    if (is2v2Mode) {
                        strikerPos.y = ty.coerceIn(boardTop + 90f, boardBottom - 90f)
                        baselineY = strikerPos.y
                    }
                    pullHandlePos.x = strikerPos.x + dx
                    pullHandlePos.y = strikerPos.y + dy
                    invalidate()
                    return true
                } else if (dragMode == 2) {
                    pullHandlePos.x = tx
                    pullHandlePos.y = ty
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = 0
            }
        }
        return false
    }
}