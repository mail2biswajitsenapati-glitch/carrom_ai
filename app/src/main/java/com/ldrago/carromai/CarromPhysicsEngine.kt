package com.ldrago.carromai

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

data class Vec2(var x: Float, var y: Float) {
    fun dist(o: Vec2): Float = hypot(x - o.x, y - o.y)
}

data class Puck(
    val id: Int,
    val pos: Vec2,
    val type: Int, // 1 = White, 2 = Black, 3 = Queen(Red)
    val isStacked: Boolean = false
)

data class Pocket(
    val pos: Vec2,
    val radius: Float
)

data class SimulatedShot(
    val strikerStartX: Float,
    val pullBackPos: Vec2,
    val power: Float,
    val score: Float
)

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
    private val linearDamping = 0.985f
    private val spinSwingFactor = 0.065f

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
                val step = (maxBaseX - minBaseX) / 15f
                for (i in 0..15) {
                    val sx = minBaseX + (i.toFloat() * step)
                    val strikerStart = Vec2(sx, baselineY)

                    val shot = simulateCollisionAndSwing(
                        strikerStart, target, pocket, pucks
                    )
                    if (shot != null) {
                        candidates.add(shot)
                    }
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
        if (cutCos < 0.35f) return null

        val cross = uAimX * dirY - uAimY * dirX
        val swingAngleOffset = -cross * spinSwingFactor

        val correctedAngle = atan2(uAimY, uAimX) + swingAngleOffset
        val finalDirX = cos(correctedAngle)
        val finalDirY = sin(correctedAngle)

        for (other in allPucks) {
            if (other.id == target.id) continue
            if (isPathBlocked(strikerStart, Vec2(rawGhostX, rawGhostY), other.pos, combinedR * 0.92f)) {
                return null
            }
            if (isPathBlocked(target.pos, pocket.pos, other.pos, puckRadius * 1.9f)) {
                return null
            }
        }

        val holeSizeMultiplier = (pocket.radius / 38f).coerceIn(0.75f, 1.25f)
        val totalDistance = aimDist + distToPocket
        var requiredPower = (95f + totalDistance * 0.14f) / (cutCos.pow(0.4f) * holeSizeMultiplier)

        if (target.isStacked) {
            requiredPower *= 1.25f
        }
        requiredPower = requiredPower.coerceIn(70f, 230f)

        val tangentX = -dirY
        val tangentY = dirX
        val massRatio = (strikerMass - restitution * puckMass) / (strikerMass + puckMass)
        val strikerAfterVelX = (uAimX * massRatio) + (tangentX * cross * 0.4f)
        val strikerAfterVelY = (uAimY * massRatio) + (tangentY * cross * 0.4f)

        val postImpactEnd = Vec2(
            rawGhostX + strikerAfterVelX * 300f * linearDamping,
            rawGhostY + strikerAfterVelY * 300f * linearDamping
        )
        if (isPathBlocked(Vec2(rawGhostX, rawGhostY), postImpactEnd, pocket.pos, pocket.radius * 1.15f)) {
            return null
        }

        val pullX = strikerStart.x - finalDirX * requiredPower
        val pullY = strikerStart.y - finalDirY * requiredPower

        var score = (cutCos * 100f) - (distToPocket * 0.05f) + (pocket.radius * 0.5f)
        if (target.isStacked) score -= 35f
        if (target.type == 3) score += 25f

        return SimulatedShot(strikerStart.x, Vec2(pullX, pullY), requiredPower, score)
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