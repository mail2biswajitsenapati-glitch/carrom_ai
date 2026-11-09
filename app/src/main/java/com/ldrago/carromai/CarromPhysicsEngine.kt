package com.ldrago.carromai

import android.util.LruCache
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

data class Vec2(var x: Float, var y: Float) {
    fun dist(o: Vec2): Float = hypot(x - o.x, y - o.y)
}

data class Puck(
    val id: Int,
    val pos: Vec2,
    val type: Int, // 1 = White, 2 = Black, 3 = Queen
    val isStacked: Boolean = false
)

data class Pocket(
    val pos: Vec2,
    val radius: Float
)

data class SimulatedShot(
    val strikerStartX: Float,
    val targetPos: Vec2,
    val ghostPos: Vec2,
    val pocketPos: Vec2,
    val pullBackPos: Vec2,
    val power: Float,
    val score: Float
)

data class LaserTrajectory(
    val strikerPos: Vec2,
    val ghostPos: Vec2,
    val bouncePoints: List<Vec2>,
    val puckTargetEnd: Vec2,
    val strikerDeflectEnd: Vec2,
    val locksPocket: Boolean
)

// In-Memory RAM Cache for Instant Board State & Coordinates
object BoardMemoryCache {
    private val frameCache = LruCache<String, List<Puck>>(10)
    var cachedPucks: List<Puck> = emptyList()
    var cachedPockets: List<Pocket> = emptyList()
    var lastScanTimestamp: Long = 0L

    fun updateCache(pucks: List<Puck>, pockets: List<Pocket>) {
        cachedPucks = pucks
        cachedPockets = pockets
        lastScanTimestamp = System.currentTimeMillis()
        frameCache.put("LATEST_BOARD", pucks)
    }
}

class CarromPhysicsEngine(
    private val baselineY: Float,
    private val minBaseX: Float,
    private val maxBaseX: Float,
    private val puckRadius: Float = 28f,
    private val strikerRadius: Float = 36f
) {
    private val strikerMass = 2.2f
    private val puckMass = 1.0f
    private val restitution = 0.82f
    private val spinSwingFactor = 0.06f

    // Real-time 60 FPS Laser Line & Wall Rebound Calculator
    fun computeLaserTrajectory(
        strikerPos: Vec2,
        targetPos: Vec2,
        boardLeft: Float,
        boardTop: Float,
        boardRight: Float,
        boardBottom: Float,
        maxBounces: Int,
        pockets: List<Pocket>
    ): LaserTrajectory {
        val combinedR = puckRadius + strikerRadius
        val dx = targetPos.x - strikerPos.x
        val dy = targetPos.y - strikerPos.y
        val d = hypot(dx, dy).coerceAtLeast(1f)
        var dirX = dx / d
        var dirY = dy / d

        // Calculate Cushion Rebound Path (1 to 3 bounces)
        val bouncePts = mutableListOf<Vec2>()
        var curX = strikerPos.x
        var curY = strikerPos.y
        val minX = boardLeft + strikerRadius
        val maxX = boardRight - strikerRadius
        val minY = boardTop + strikerRadius
        val maxY = boardBottom - strikerRadius

        for (b in 0 until maxBounces) {
            val tx = if (dirX > 0.001f) (maxX - curX) / dirX
            else if (dirX < -0.001f) (minX - curX) / dirX
            else 99999f

            val ty = if (dirY > 0.001f) (maxY - curY) / dirY
            else if (dirY < -0.001f) (minY - curY) / dirY
            else 99999f

            val t = min(tx, ty).coerceAtLeast(1f)
            val hitX = curX + dirX * t
            val hitY = curY + dirY * t
            bouncePts.add(Vec2(hitX, hitY))

            if (tx < ty) dirX = -dirX else dirY = -dirY
            curX = hitX
            curY = hitY
        }

        // Check closest pocket to Target Puck for Auto-Snap Guide
        var bestPocket: Pocket? = null
        var bestCos = -1f
        val aimUnitX = dx / d
        val aimUnitY = dy / d

        for (p in pockets) {
            val px = p.pos.x - targetPos.x
            val py = p.pos.y - targetPos.y
            val pd = hypot(px, py).coerceAtLeast(1f)
            val ux = px / pd
            val uy = py / pd
            val c = aimUnitX * ux + aimUnitY * uy
            if (c > bestCos) {
                bestCos = c
                bestPocket = p
            }
        }

        val locksPocket = bestCos > 0.55f && bestPocket != null
        val puckDirX: Float
        val puckDirY: Float

        if (locksPocket && bestPocket != null) {
            val px = bestPocket.pos.x - targetPos.x
            val py = bestPocket.pos.y - targetPos.y
            val pd = hypot(px, py).coerceAtLeast(1f)
            puckDirX = px / pd
            puckDirY = py / pd
        } else {
            puckDirX = aimUnitX
            puckDirY = aimUnitY
        }

        val ghostPos = Vec2(
            targetPos.x - puckDirX * combinedR,
            targetPos.y - puckDirY * combinedR
        )

        val puckEnd = if (locksPocket && bestPocket != null) {
            bestPocket.pos
        } else {
            Vec2(targetPos.x + puckDirX * 420f, targetPos.y + puckDirY * 420f)
        }

        // Striker Tangent Deflection Line
        val cross = aimUnitX * puckDirY - aimUnitY * puckDirX
        val tangX = -puckDirY * (if (cross >= 0f) 1f else -1f)
        val tangY = puckDirX * (if (cross >= 0f) 1f else -1f)
        val deflectEnd = Vec2(ghostPos.x + tangX * 220f, ghostPos.y + tangY * 220f)

        return LaserTrajectory(strikerPos, ghostPos, bouncePts, puckEnd, deflectEnd, locksPocket)
    }

    fun findBestHumanShot(
        pucks: List<Puck>,
        pockets: List<Pocket>,
        myColor: Int,
        gameMode: String
    ): SimulatedShot? {
        val candidates = mutableListOf<SimulatedShot>()

        val targetPucks = pucks.filter {
            when (gameMode) {
                "FREESTYLE" -> true
                else -> it.type == myColor || it.type == 3
            }
        }

        for (target in targetPucks) {
            for (pocket in pockets) {
                val step = (maxBaseX - minBaseX) / 16f
                for (i in 0..16) {
                    val sx = minBaseX + (i.toFloat() * step)
                    val strikerStart = Vec2(sx, baselineY)
                    val shot = simulateCollisionAndSwing(strikerStart, target, pocket, pucks)
                    if (shot != null) candidates.add(shot)
                }
            }
        }

        return candidates.maxByOrNull { it.score }
    }

    private fun simulateCollisionAndSwing(
        strikerStart: Vec2,
        target: Puck,
        pocket: Pocket,
        allPucks: List<Puck>
    ): SimulatedShot? {
        val combinedR = puckRadius + strikerRadius
        val toPocketX = pocket.pos.x - target.pos.x
        val toPocketY = pocket.pos.y - target.pos.y
        val distToPocket = hypot(toPocketX, toPocketY)
        if (distToPocket < 1f) return null

        val dirX = toPocketX / distToPocket
        val dirY = toPocketY / distToPocket

        val rawGhostX = target.pos.x - dirX * combinedR
        val rawGhostY = target.pos.y - dirY * combinedR

        val aimDx = rawGhostX - strikerStart.x
        val aimDy = rawGhostY - strikerStart.y
        val aimDist = hypot(aimDx, aimDy)
        if (aimDist < 10f) return null

        val uAimX = aimDx / aimDist
        val uAimY = aimDy / aimDist

        val cutCos = uAimX * dirX + uAimY * dirY
        if (cutCos < 0.38f) return null

        val cross = uAimX * dirY - uAimY * dirX
        val swingAngleOffset = -cross * spinSwingFactor

        val correctedAngle = atan2(uAimY, uAimX) + swingAngleOffset
        val finalDirX = cos(correctedAngle)
        val finalDirY = sin(correctedAngle)

        for (other in allPucks) {
            if (other.id == target.id) continue
            if (isPathBlocked(strikerStart, Vec2(rawGhostX, rawGhostY), other.pos, combinedR * 0.9f)) {
                return null
            }
            if (isPathBlocked(target.pos, pocket.pos, other.pos, puckRadius * 1.85f)) {
                return null
            }
        }

        val totalDistance = aimDist + distToPocket
        var requiredPower = (105f + totalDistance * 0.15f) / cutCos.pow(0.4f)
        if (target.isStacked) requiredPower *= 1.25f
        requiredPower = requiredPower.coerceIn(85f, 235f)

        val pullX = strikerStart.x - finalDirX * requiredPower
        val pullY = strikerStart.y - finalDirY * requiredPower

        var score = (cutCos * 100f) - (distToPocket * 0.05f)
        if (target.isStacked) score -= 30f
        if (target.type == 3) score += 25f

        return SimulatedShot(
            strikerStartX = strikerStart.x,
            targetPos = target.pos,
            ghostPos = Vec2(rawGhostX, rawGhostY),
            pocketPos = pocket.pos,
            pullBackPos = Vec2(pullX, pullY),
            power = requiredPower,
            score = score
        )
    }

    private fun isPathBlocked(a: Vec2, b: Vec2, obstacle: Vec2, minClearance: Float): Boolean {
        val abX = b.x - a.x
        val abY = b.y - a.y
        val lenSq = abX * abX + abY * abY
        if (lenSq == 0f) return false
        val t = (((obstacle.x - a.x) * abX + (obstacle.y - a.y) * abY) / lenSq).coerceIn(0f, 1f)
        val projX = a.x + t * abX
        val projY = a.y + t * abY
        return hypot(obstacle.x - projX, obstacle.y - projY) < minClearance
    }
}