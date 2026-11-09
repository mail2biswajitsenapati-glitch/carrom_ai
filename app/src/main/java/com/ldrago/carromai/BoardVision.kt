package com.ldrago.carromai

fun detectStackedPucks(rawPucks: List<Puck>, puckRadius: Float): List<Puck> {
    return rawPucks.map { p1 ->
        val stacked = rawPucks.any { p2 ->
            p1.id != p2.id && p1.pos.dist(p2.pos) < (puckRadius * 1.75f)
        }
        p1.copy(isStacked = stacked)
    }
}