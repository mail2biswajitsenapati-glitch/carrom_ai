package com.ldrago.carromai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlin.random.Random

class HumanTouchAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun executeHumanShot(
        currentStrikerX: Float,
        targetStrikerX: Float,
        baselineY: Float,
        pullBack: Vec2
    ) {
        val handler = Handler(Looper.getMainLooper())

        // 1. Human "Looking at board" delay (1.4s to 3.1s)
        val thinkDelay = Random.nextLong(1400, 3100)

        handler.postDelayed({
            // Step A: Slide Striker on Baseline using a curved thumb path
            slideStrikerHumanly(currentStrikerX, targetStrikerX, baselineY) {
                
                // Step B: Pause 400-850ms before pulling back striker
                val aimPause = Random.nextLong(420, 850)
                handler.postDelayed({
                    pullAndReleaseWithAimWobble(targetStrikerX, baselineY, pullBack)
                }, aimPause)
            }
        }, thinkDelay)
    }

    private fun slideStrikerHumanly(
        startX: Float,
        endX: Float,
        y: Float,
        onComplete: () -> Unit
    ) {
        if ( kotlin.math.abs(startX - endX) < 6f ) {
            onComplete()
            return
        }

        val path = Path()
        path.moveTo(startX, y)

        // Thumb arc: बीच में हल्का सा नीचे या ऊपर curve होना
        val midX = (startX + endX) / 2f
        val arcOffsetY = Random.nextDouble(-14.0, 14.0).toFloat()
        path.quadTo(midX, y + arcOffsetY, endX, y + Random.nextInt(-2, 2))

        val duration = Random.nextLong(260, 440)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete()
            }
        }, null)
    }

    private fun pullAndReleaseWithAimWobble(startX: Float, startY: Float, pullBack: Vec2) {
        val path = Path()
        path.moveTo(startX, startY)

        // Insaan pehle thoda galat angle (overshoot) par kheencta hai, phir asli angle par settle karta hai
        val wobbleX = pullBack.x + Random.nextInt(-18, 18)
        val wobbleY = pullBack.y + Random.nextInt(-12, 12)

        val ctrlX = (startX + wobbleX) / 2f + Random.nextInt(-10, 10)
        val ctrlY = (startY + wobbleY) / 2f + Random.nextInt(-10, 10)

        // Curve 1: Pull back to rough aim
        path.quadTo(ctrlX, ctrlY, wobbleX, wobbleY)
        // Curve 2: Micro fine-tune to exact calculated aim before releasing finger
        path.lineTo(pullBack.x, pullBack.y)

        // Total aiming hold time (650ms to 1250ms feels like a real player aiming)
        val aimDuration = Random.nextLong(650, 1250)
        val stroke = GestureDescription.StrokeDescription(path, 0, aimDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
    }
}