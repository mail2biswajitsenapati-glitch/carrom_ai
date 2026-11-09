package com.ldrago.carromai

import kotlin.math.*

data class Vec2(var x: Float, var y: Float) {
    fun dist(o: Vec2) = hypot(x - o.x, y - o.y)
}

data class Puck(
    val id: Int,
    val pos: Vec2,
    val type: Int, // 1 = White, 2 = Black, 3 = Queen(Red)
    val isStacked: Boolean = false
)

data classPocket(val pos: Vec2, val radius: Float) // Dynamic hole size per table

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
    // Carrom Pool Box2D Physics Constants
    private val strikerMass = 2.2f
    private val puckMass = 1.0f
    private val restitution = 0.82f      // Repulsion / Bounciness
    private val linearDamping = 0.985f   // Table friction / Slide deceleration
    private val spinSwingFactor = 0.065f // Contact friction throw / swing

    fun findBestHumanShot(
        pucks: List<Puck>,
        pockets: List<Pocket>,
        myColor: Int,
        gameMode: String // "DISC", "CARROM", "FREESTYLE"
    ): SimulatedShot? {
        val candidates = mutableListOf<SimulatedShot>()

        // Target filter based on Mode
        val targetPucks = pucks.filter {
            when (gameMode) {
                "FREESTYLE" -> true
                else -> it.type == myColor || it.type == 3
            }
        }

        for (target in targetPucks) {
            for (pocket in pockets) {
                // Try 15 different striker baseline positions
                val step = (maxBaseX - minBaseX) / 15f
                for (i in 0..15) {
                    val sx = minBaseX + i * step
                    val strikerStart = Vec2(sx, baselineY)

                    val shot = simulateCollisionAndSwing(
                        strikerStart, target, pocket, pucks
                    )
                    if (shot != null) candidates.add(shot)
                }
            }
        }

        // Sabse high probability (easy & natural) shot select karein
        return candidates.maxByOrNull { it.score }
    }

    private fun simulateCollisionAndSwing(
        strikerStart: Vec2,
        target: Puck,
        pocket: Pocket,
        allPucks: List<Puck>
    ): SimulatedShot? {
        val combinedR = puckRadius + strikerRadius

        // Direction from Target Puck to Pocket
        val toPocketX = pocket.pos.x - target.pos.x
        val toPocketY = pocket.pos.y - target.pos.y
        val distToPocket = hypot(toPocketX, toPocketY)
        if (distToPocket < 1f) return null

        val dirX = toPocketX / distToPocket
        val dirY = toPocketY / distToPocket

        // Swing & Throw Compensation:
        // Impact angle ki wajah se puck thoda deflect (swing) hoti hai, usko pre-correct karein
        val rawGhostX = target.pos.x - dirX * combinedR
        val rawGhostY = target.pos.y - dirY * combinedR

        val aimDx = rawGhostX - strikerStart.x
        val aimDy = rawGhostY - strikerStart.y
        val aimDist = hypot(aimDx, aimDy)
        if (aimDist < 10f) return null

        val uAimX = aimDx / aimDist
        val uAimY = aimDy / aimDist

        // Cut angle check (cos theta between striker velocity and pocket line)
        val cutCos = uAimX * dirX + uAimY * dirY
        if (cutCos < 0.35f) return null // Bahut zyada tedha cut shot avoid karein

        // Cross-product for Spin/Swing direction (+left or -right)
        val cross = uAimX * dirY - uAimY * dirX
        val swingAngleOffset = -cross * spinSwingFactor

        // Apply swing correction to aim angle
        val correctedAngle = atan2(uAimY, uAimX) + swingAngleOffset
        val finalDirX = cos(correctedAngle)
        val finalDirY = sin(correctedAngle)

        // Obstacle Check: Kya raste me koi dusri puck block kar rahi hai?
        for (other in allPucks) {
            if (other.id == target.id) continue
            if (isPathBlocked(strikerStart, Vec2(rawGhostX, rawGhostY), other.pos, combinedR * 0.92f)) {
                return null
            }
            if (isPathBlocked(target.pos, pocket.pos, other.pos, puckRadius * 1.9f)) {
                return null
            }
        }

        // Dynamic Hole Size Speed Control:
        // Chhote hole par tez marne se puck bahar nikal aati hai (jaw bounce)
        val holeSizeMultiplier = (pocket.radius / 38f).coerceIn(0.75f, 1.25f)
        val totalDistance = aimDist + distToPocket
        var requiredPower = (95f + totalDistance * 0.14f) / (cutCos.pow(0.4f) * holeSizeMultiplier)

        // Agar puck stacked (ek ke upar ek) hai toh separation power badhayein
        if (target.isStacked) {
            requiredPower *= 1.25f
        }
        requiredPower = requiredPower.coerceIn(70f, 230f)

        // Striker Repulsion & Anti-Foul Check (Striker khud pocket me na jaye!)
        val tangentX = -dirY
        val tangentY = dirX
        val strikerAfterVelX = (uAimX * (strikerMass - restitution * puckMass) / (strikerMass + puckMass)) + tangentX * cross * 0.4f
        val strikerAfterVelY = (uAimY * (strikerMass - restitution * puckMass) / (strikerMass + puckMass)) + tangentY * cross * 0.4f
        val postImpactEnd = Vec2(
            rawGhostX + strikerAfterVelX * 300f * linearDamping,
            rawGhostY + strikerAfterVelY * 300f * linearDamping
        )
        if (isPathBlocked(Vec2(rawGhostX, rawGhostY), postImpactEnd, pocket.pos, pocket.radius * 1.15f)) {
            return null // Foul risk: Striker pocket me chala jayega!
        }

        // Pull-back coordinates
        val pullX = strikerStart.x - finalDirX * requiredPower
        val pullY = strikerStart.y - finalDirY * requiredPower

        // Score calculation (Prefer straight, unstacked, closer to pocket shots)
        var score = (cutCos * 100f) - (distToPocket * 0.05f) + (pocket.radius * 0.5f)
        if (target.isStacked) score -= 35f
        if (target.type == 3) score += 25f // Queen priority

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