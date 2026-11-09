package com.ldrago.carromai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

object BoardVision {

    fun scanBoardFromBitmap(bitmap: Bitmap): Pair<List<Puck>, List<Pocket>> {
        val w = bitmap.width
        val h = bitmap.height

        val boardLeft = (w * 0.075f).toInt()
        val boardRight = (w * 0.925f).toInt()
        val boardWidth = boardRight - boardLeft
        val boardTop = (h * 0.255f).toInt()
        val boardBottom = boardTop + boardWidth

        val pocketInset = boardWidth * 0.065f
        val pockets = listOf(
            Pocket(Vec2(boardLeft + pocketInset, boardTop + pocketInset), 40f),
            Pocket(Vec2(boardRight - pocketInset, boardTop + pocketInset), 40f),
            Pocket(Vec2(boardLeft + pocketInset, boardBottom - pocketInset), 40f),
            Pocket(Vec2(boardRight - pocketInset, boardBottom - pocketInset), 40f)
        )

        val detectedClusters = mutableListOf<Puck>()
        val step = 10
        var puckId = 1

        val scanLeft = (boardLeft + pocketInset * 1.25f).toInt()
        val scanRight = (boardRight - pocketInset * 1.25f).toInt()
        val scanTop = (boardTop + pocketInset * 1.25f).toInt()
        val scanBottom = (boardBottom - pocketInset * 1.45f).toInt()

        for (y in scanTop until scanBottom step step) {
            if (y >= h) continue
            for (x in scanLeft until scanRight step step) {
                if (x >= w) continue
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val puckType = classifyPixelAsPuck(r, g, b)
                if (puckType != 0) {
                    val pos = Vec2(x.toFloat(), y.toFloat())
                    val existing = detectedClusters.any { it.pos.dist(pos) < 40f }
                    if (!existing) {
                        detectedClusters.add(Puck(puckId++, pos, puckType, false))
                    }
                }
            }
        }

        val finalPucks = detectStackedPucks(detectedClusters, 28f)
        BoardMemoryCache.updateCache(finalPucks, pockets)
        return Pair(finalPucks, pockets)
    }

    private fun classifyPixelAsPuck(r: Int, g: Int, b: Int): Int {
        if (r > 175 && g < 55 && b < 65) return 3 // Red Queen
        if (r > 222 && g > 218 && b > 198) return 1 // White Puck
        if (r in 16..46 && g in 16..46 && b in 18..50) return 2 // Black Puck
        return 0
    }

    fun detectStackedPucks(rawPucks: List<Puck>, puckRadius: Float): List<Puck> {
        return rawPucks.map { p1 ->
            val stacked = rawPucks.any { p2 ->
                p1.id != p2.id && p1.pos.dist(p2.pos) < (puckRadius * 1.75f)
            }
            p1.copy(isStacked = stacked)
        }
    }
}

// 60 FPS Full-Screen Laser Aim & Rebound Overlay
class AimOverlayView(context: Context, private val screenW: Int, private val screenH: Int) : View(context) {

    var boardLeft = screenW * 0.075f
    var boardRight = screenW * 0.925f
    var boardTop = screenH * 0.255f
    var boardBottom = boardTop + (boardRight - boardLeft)
    var baselineY = boardBottom - (boardRight - boardLeft) * 0.135f

    var strikerPos = Vec2(screenW * 0.50f, baselineY)
    var targetPos = Vec2(screenW * 0.50f, (boardTop + boardBottom) * 0.50f)

    var laserColor: Int = Color.parseColor("#00F5D4")
    var lineThickness: Float = 6f
    var maxBounces: Int = 2
    var showLines: Boolean = true
    var isManualEditMode: Boolean = false

    private var draggingItem = 0 // 0 = None, 1 = Striker, 2 = Target

    private val mainLaserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val reboundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }

    private val pocketLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val deflectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF9F1C")
        strokeWidth = 3.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#4438BDF8")
        strokeWidth = 2.5f
    }

    fun getPockets(): List<Pocket> {
        val w = boardRight - boardLeft
        val inset = w * 0.065f
        return listOf(
            Pocket(Vec2(boardLeft + inset, boardTop + inset), 40f),
            Pocket(Vec2(boardRight - inset, boardTop + inset), 40f),
            Pocket(Vec2(boardLeft + inset, boardBottom - inset), 40f),
            Pocket(Vec2(boardRight - inset, boardBottom - inset), 40f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showLines) return

        mainLaserPaint.color = laserColor
        mainLaserPaint.strokeWidth = lineThickness

        reboundPaint.color = laserColor
        reboundPaint.alpha = 180
        reboundPaint.strokeWidth = (lineThickness * 0.75f).coerceAtLeast(2f)

        // 1. Draw Board Frame & Baseline
        canvas.drawRect(boardLeft, boardTop, boardRight, boardBottom, framePaint)
        canvas.drawLine(boardLeft + 110f, baselineY, boardRight - 110f, baselineY, framePaint)

        // 2. Draw 4 Corner Pockets
        val pockets = getPockets()
        for (p in pockets) {
            canvas.drawCircle(p.pos.x, p.pos.y, p.radius, framePaint)
        }

        // 3. Compute Physics & Rebound Trajectory
        val engine = CarromPhysicsEngine(baselineY, boardLeft + 120f, boardRight - 120f)
        val traj = engine.computeLaserTrajectory(
            strikerPos, targetPos,
            boardLeft, boardTop, boardRight, boardBottom,
            maxBounces, pockets
        )

        // 4. Draw Rebound Cushion Rays
        var prevPt = strikerPos
        for (pt in traj.bouncePoints) {
            canvas.drawLine(prevPt.x, prevPt.y, pt.x, pt.y, reboundPaint)
            canvas.drawCircle(pt.x, pt.y, 8f, reboundPaint)
            prevPt = pt
        }

        // 5. Draw Primary Laser (Striker -> Ghost Ball)
        canvas.drawLine(strikerPos.x, strikerPos.y, traj.ghostPos.x, traj.ghostPos.y, mainLaserPaint)

        // 6. Draw Target Puck -> Pocket Trajectory Line
        pocketLinePaint.color = if (traj.locksPocket) Color.parseColor("#00F554") else laserColor
        pocketLinePaint.strokeWidth = lineThickness
        canvas.drawLine(targetPos.x, targetPos.y, traj.puckTargetEnd.x, traj.puckTargetEnd.y, pocketLinePaint)

        // 7. Draw Striker Deflection Tangent Line
        canvas.drawLine(traj.ghostPos.x, traj.ghostPos.y, traj.strikerDeflectEnd.x, traj.strikerDeflectEnd.y, deflectPaint)

        // 8. Draw Ghost Ball Impact Circle, Target Ring & Striker Ring
        canvas.drawCircle(traj.ghostPos.x, traj.ghostPos.y, 26f, reboundPaint)
        canvas.drawCircle(targetPos.x, targetPos.y, 30f, pocketLinePaint)
        canvas.drawCircle(targetPos.x, targetPos.y, 6f, pocketLinePaint)

        canvas.drawCircle(strikerPos.x, strikerPos.y, 38f, mainLaserPaint)
        canvas.drawCircle(strikerPos.x, strikerPos.y, 8f, mainLaserPaint)

        // 9. Draw RAM Cached Pucks (if detected by Auto-Play)
        for (puck in BoardMemoryCache.cachedPucks) {
            canvas.drawCircle(puck.pos.x, puck.pos.y, 14f, framePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isManualEditMode) return false
        val tx = event.rawX
        val ty = event.rawY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (hypot(tx - strikerPos.x, ty - strikerPos.y) < 95f) {
                    draggingItem = 1
                    return true
                }
                if (hypot(tx - targetPos.x, ty - targetPos.y) < 95f) {
                    draggingItem = 2
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingItem == 1) {
                    strikerPos.x = tx.coerceIn(boardLeft + 115f, boardRight - 115f)
                    strikerPos.y = ty.coerceIn(boardTop + 100f, boardBottom - 60f)
                    baselineY = strikerPos.y
                    invalidate()
                    return true
                } else if (draggingItem == 2) {
                    targetPos.x = tx.coerceIn(boardLeft + 40f, boardRight - 40f)
                    targetPos.y = ty.coerceIn(boardTop + 40f, boardBottom - 40f)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingItem = 0
            }
        }
        return false
    }
}