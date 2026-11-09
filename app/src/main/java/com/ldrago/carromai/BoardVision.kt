package com.ldrago.carromai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.hypot

object BoardVision {

    fun scanBoardFromBitmap(bitmap: Bitmap): Pair<List<Puck>, List<Pocket>> {
        val w = bitmap.width
        val h = bitmap.height

        // Dynamic Board Bounds (Standard Portrait Phone Calibration)
        val boardLeft = (w * 0.08f).toInt()
        val boardRight = (w * 0.92f).toInt()
        val boardWidth = boardRight - boardLeft
        val boardTop = (h * 0.26f).toInt()
        val boardBottom = boardTop + boardWidth

        val pocketInset = boardWidth * 0.065f
        val pockets = listOf(
            Pocket(Vec2(boardLeft + pocketInset, boardTop + pocketInset), 38f),
            Pocket(Vec2(boardRight - pocketInset, boardTop + pocketInset), 38f),
            Pocket(Vec2(boardLeft + pocketInset, boardBottom - pocketInset), 38f),
            Pocket(Vec2(boardRight - pocketInset, boardBottom - pocketInset), 38f)
        )

        val detectedClusters = mutableListOf<Puck>()
        val step = 12 // Fast pixel grid scan step
        var puckId = 1

        val scanLeft = (boardLeft + pocketInset * 1.3f).toInt()
        val scanRight = (boardRight - pocketInset * 1.3f).toInt()
        val scanTop = (boardTop + pocketInset * 1.3f).toInt()
        val scanBottom = (boardBottom - pocketInset * 1.5f).toInt()

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
                    // Check if already part of an existing puck cluster (within 42px)
                    val existing = detectedClusters.any { it.pos.dist(pos) < 42f }
                    if (!existing) {
                        detectedClusters.add(Puck(puckId++, pos, puckType, false))
                    }
                }
            }
        }

        return Pair(detectStackedPucks(detectedClusters, 28f), pockets)
    }

    private fun classifyPixelAsPuck(r: Int, g: Int, b: Int): Int {
        // 3 = Red Queen (High Red, Low Green/Blue)
        if (r > 175 && g < 55 && b < 65) return 3
        // 1 = White Puck (Bright Cream/White center)
        if (r > 225 && g > 220 && b > 200) return 1
        // 2 = Black Puck (Very Dark Grey/Black center, distinct from wooden board)
        if (r in 18..48 && g in 18..48 && b in 20..52) return 2
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