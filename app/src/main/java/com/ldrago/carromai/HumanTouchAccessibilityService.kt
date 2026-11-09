package com.ldrago.carromai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs
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
        val thinkDelay = Random.nextLong(1400L, 3100L)

        handler.postDelayed({
            slideStrikerHumanly(currentStrikerX, targetStrikerX, baselineY) {
                val aimPause = Random.nextLong(420L, 850L)
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
        if (abs(startX - endX) < 6f) {
            onComplete()
            return
        }

        val path = Path()
        path.moveTo(startX, y)

        val midX = (startX + endX) / 2f
        val arcOffsetY = Random.nextDouble(-14.0, 14.0).toFloat()
        val endJitterY = Random.nextInt(-2, 3).toFloat()
        path.quadTo(midX, y + arcOffsetY, endX, y + endJitterY)

        val duration = Random.nextLong(260L, 440L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
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

        val wobbleX = pullBack.x + Random.nextInt(-18, 19).toFloat()
        val wobbleY = pullBack.y + Random.nextInt(-12, 13).toFloat()

        val ctrlX = ((startX + wobbleX) / 2f) + Random.nextInt(-10, 11).toFloat()
        val ctrlY = ((startY + wobbleY) / 2f) + Random.nextInt(-10, 11).toFloat()

        path.quadTo(ctrlX, ctrlY, wobbleX, wobbleY)
        path.lineTo(pullBack.x, pullBack.y)

        val aimDuration = Random.nextLong(650L, 1250L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, aimDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
    }
}