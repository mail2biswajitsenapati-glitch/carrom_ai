package com.ldrago.carromai

import android.util.LruCache
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
    val targetPos: Vec2,
    val ghostPos: Vec2,
    val pocketPos: Vec2,
    val pullBackPos: Vec2,
    val power: Float,
    val score: Float
)

// Full Time-Step Simulation Result (Striker Path + All Hit Pucks + Stopping Points)
data class PuckPathResult(
    val puckId: Int,
    val pathPoints: List<Vec2>,
    val stopPoint: Vec2,
    val pottedInPocket: Boolean
)

data class StepSimulationResult(
    val strikerPath: List<Vec2>,
    val strikerStopPoint: Vec2,
    val strikerFoul: Boolean,
    val impactedPuckPaths: List<PuckPathResult>
)

object BoardMemoryCache {
    private val frameCache = LruCache<String, List<Puck>>(10)
    var cachedPucks: List<Puck> = emptyList()
    var cachedPockets: List<Pocket> = emptyList()
    var detectedMode: String = "CARROM" // "CARROM", "DISC", "FREESTYLE"
    var lastScanTimestamp: Long = 0L

    fun updateCache(pucks: List<Puck>, pockets: List<Pocket>, mode: String) {
        cachedPucks = pucks
        cachedPockets = pockets
        detectedMode = mode
        lastScanTimestamp = System.currentTimeMillis()
        frameCache.put("LATEST_BOARD", pucks)
    }
}

private class SimBody(
    val id: Int,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val radius: Float,
    val mass: Float,
    var spin: Float = 0f,
    var isPotted: Boolean = false,
    var wasHit: Boolean = false,
    val trail: MutableList<Vec2> = mutableListOf()
)

class CarromPhysicsEngine(
    private val baselineY: Float,
    private val minBaseX: Float,
    private val maxBaseX: Float,
    private val puckRadius: Float = 27f,
    private val strikerRadius: Float = 35f
) {
    private val strikerMass = 2.25f
    private val puckMass = 1.0f
    private val wallRestitution = 0.78f
    private val puckRestitution = 0.84f
    private val tableFrictionDamping = 0.978f // Controls exact stopping distance
    private val magnusCurveFactor = 0.0045f   // Controls realistic arc/swing on angled hits

    /**
     * Exact Time-Step (1/60s) Forward Physics Simulation:
     * Simulates Striker Power, Friction Stopping Point, Spin Curve (Arc),
     * Wall Rebounds, Stacked Pucks Separation, and Multi-Puck Chain Collisions.
     */
    fun simulateRealTimeStepTrajectory(
        strikerPos: Vec2,
        aimAngleRad: Float,
        powerPercent: Float, // 0.05f to 1.0f (Gradual Power Pull)
        pucks: List<Puck>,
        pockets: List<Pocket>,
        boardLeft: Float,
        boardTop: Float,
        boardRight: Float,
        boardBottom: Float
    ): StepSimulationResult {
        val maxInitialSpeed = 46f
        val initialSpeed = (powerPercent.coerceIn(0.05f, 1.0f)) * maxInitialSpeed

        val striker = SimBody(
            id = 0,
            x = strikerPos.x,
            y = strikerPos.y,
            vx = cos(aimAngleRad) * initialSpeed,
            vy = sin(aimAngleRad) * initialSpeed,
            radius = strikerRadius,
            mass = strikerMass,
            wasHit = true
        )
        striker.trail.add(Vec2(striker.x, striker.y))

        val simPucks = pucks.map { p ->
            val b = SimBody(
                id = p.id,
                x = p.pos.x,
                y = p.pos.y,
                vx = 0f,
                vy = 0f,
                radius = puckRadius,
                mass = puckMass
            )
            b.trail.add(Vec2(b.x, b.y))
            b
        }

        val allBodies = mutableListOf<SimBody>()
        allBodies.add(striker)
        allBodies.addAll(simPucks)

        val totalSteps = 260 // ~4.3 seconds of real-time board motion
        for (step in 0 until totalSteps) {
            var anyMoving = false

            // 1. Advance Positions, Apply Friction & Spin Curve (Arc)
            for (b in allBodies) {
                if (b.isPotted) continue
                val speed = hypot(b.vx, b.vy)
                if (speed > 0.18f) {
                    anyMoving = true

                    // Magnus Lateral Curve Effect (Swing/Arc when spinning)
                    if (kotlin.math.abs(b.spin) > 0.001f) {
                        val perpX = -b.vy / speed
                        val perpY = b.vx / speed
                        b.vx += perpX * b.spin * speed * magnusCurveFactor
                        b.vy += perpY * b.spin * speed * magnusCurveFactor
                        b.spin *= 0.96f
                    }

                    b.x += b.vx
                    b.y += b.vy
                    b.vx *= tableFrictionDamping
                    b.vy *= tableFrictionDamping

                    // Record path every 2nd step for smooth curve rendering
                    if (step % 2 == 0 && b.wasHit) {
                        b.trail.add(Vec2(b.x, b.y))
                    }
                } else {
                    b.vx = 0f
                    b.vy = 0f
                }

                // 2. Wall Cushion Bounce with Spin Transfer
                if (b.x - b.radius < boardLeft) {
                    b.x = boardLeft + b.radius
                    b.vx = -b.vx * wallRestitution
                    b.vy *= 0.94f
                    if (b.wasHit) b.trail.add(Vec2(b.x, b.y))
                } else if (b.x + b.radius > boardRight) {
                    b.x = boardRight - b.radius
                    b.vx = -b.vx * wallRestitution
                    b.vy *= 0.94f
                    if (b.wasHit) b.trail.add(Vec2(b.x, b.y))
                }

                if (b.y - b.radius < boardTop) {
                    b.y = boardTop + b.radius
                    b.vy = -b.vy * wallRestitution
                    b.vx *= 0.94f
                    if (b.wasHit) b.trail.add(Vec2(b.x, b.y))
                } else if (b.y + b.radius > boardBottom) {
                    b.y = boardBottom - b.radius
                    b.vy = -b.vy * wallRestitution
                    b.vx *= 0.94f
                    if (b.wasHit) b.trail.add(Vec2(b.x, b.y))
                }

                // 3. Check Pocket Capture (Dynamic Hole Size & Speed Acceptance)
                for (pk in pockets) {
                    val dPocket = hypot(b.x - pk.pos.x, b.y - pk.pos.y)
                    // Small hole rejects overly fast edge hits
                    val effectiveCaptureR = if (speed > 28f) pk.radius * 0.78f else pk.radius * 0.95f
                    if (dPocket <= effectiveCaptureR) {
                        b.isPotted = true
                        b.vx = 0f
                        b.vy = 0f
                        b.trail.add(Vec2(pk.pos.x, pk.pos.y))
                        break
                    }
                }
            }

            // 4. Multi-Body Elastic Collision + Stacked Puck Separation
            for (i in 0 until allBodies.size) {
                val b1 = allBodies[i]
                if (b1.isPotted) continue
                for (j in i + 1 until allBodies.size) {
                    val b2 = allBodies[j]
                    if (b2.isPotted) continue

                    val dx = b2.x - b1.x
                    val dy = b2.y - b1.y
                    val dist = hypot(dx, dy)
                    val minDist = b1.radius + b2.radius

                    if (dist < minDist && dist > 0.001f) {
                        val nx = dx / dist
                        val ny = dy / dist

                        // Positional separation (Handles stacked/overlapping pucks cleanly)
                        val overlap = minDist - dist
                        val totalMass = b1.mass + b2.mass
                        b1.x -= nx * overlap * (b2.mass / totalMass)
                        b1.y -= ny * overlap * (b2.mass / totalMass)
                        b2.x += nx * overlap * (b1.mass / totalMass)
                        b2.y += ny * overlap * (b1.mass / totalMass)

                        // Relative Velocity
                        val rvx = b2.vx - b1.vx
                        val rvy = b2.vy - b1.vy
                        val velAlongNormal = rvx * nx + rvy * ny

                        if (velAlongNormal < 0f) {
                            b1.wasHit = true
                            b2.wasHit = true
                            b1.trail.add(Vec2(b1.x, b1.y))
                            b2.trail.add(Vec2(b2.x, b2.y))

                            val impulse = -(1f + puckRestitution) * velAlongNormal / (1f / b1.mass + 1f / b2.mass)
                            val impulseX = impulse * nx
                            val impulseY = impulse * ny

                            b1.vx -= impulseX / b1.mass
                            b1.vy -= impulseY / b1.mass
                            b2.vx += impulseX / b2.mass
                            b2.vy += impulseY / b2.mass

                            // Tangent Friction Spin/Throw (Induces realistic arc after glancing cut)
                            val tx = -ny
                            val ty = nx
                            val tangentSpeed = rvx * tx + rvy * ty
                            b1.spin += tangentSpeed * 0.12f
                            b2.spin -= tangentSpeed * 0.18f
                        }
                    }
                }
            }

            if (!anyMoving) break
        }

        striker.trail.add(Vec2(striker.x, striker.y))
        val hitPuckResults = simPucks.filter { it.wasHit }.map { p ->
            p.trail.add(Vec2(p.x, p.y))
            PuckPathResult(
                puckId = p.id,
                pathPoints = p.trail,
                stopPoint = Vec2(p.x, p.y),
                pottedInPocket = p.isPotted
            )
        }

        return StepSimulationResult(
            strikerPath = striker.trail,
            strikerStopPoint = Vec2(striker.x, striker.y),
            strikerFoul = striker.isPotted,
            impactedPuckPaths = hitPuckResults
        )
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
                val step = (maxBaseX - minBaseX) / 14f
                for (i in 0..14) {
                    val sx = minBaseX + (i.toFloat() * step)
                    val strikerStart = Vec2(sx, baselineY)
                    val shot = evaluateCandidateShot(strikerStart, target, pocket, pucks)
                    if (shot != null) candidates.add(shot)
                }
            }
        }
        return candidates.maxByOrNull { it.score }
    }

    private fun evaluateCandidateShot(
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
        if (cutCos < 0.40f) return null

        for (other in allPucks) {
            if (other.id == target.id) continue
            if (isPathBlocked(strikerStart, Vec2(rawGhostX, rawGhostY), other.pos, combinedR * 0.9f)) return null
            if (isPathBlocked(target.pos, pocket.pos, other.pos, puckRadius * 1.85f)) return null
        }

        val holeFactor = (pocket.radius / 38f).coerceIn(0.8f, 1.2f)
        val totalDistance = aimDist + distToPocket
        var requiredPower = (105f + totalDistance * 0.15f) / (cutCos.pow(0.4f) * holeFactor)
        if (target.isStacked) requiredPower *= 1.22f
        requiredPower = requiredPower.coerceIn(85f, 230f)

        val pullX = strikerStart.x - uAimX * requiredPower
        val pullY = strikerStart.y - uAimY * requiredPower

        var score = (cutCos * 100f) - (distToPocket * 0.05f)
        if (target.isStacked) score -= 25f
        if (target.type == 3) score += 30f

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