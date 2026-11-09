package com.ldrago.carromai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class HumanTouchAccessibilityService : AccessibilityService() {

    companion object {
        var instance: HumanTouchAccessibilityService? = null
    }

    private var windowManager: WindowManager? = null
    private var aimOverlayView: AimOverlayView? = null
    private var aimOverlayParams: WindowManager.LayoutParams? = null
    private var floatingRoot: LinearLayout? = null

    private var myColor: Int = 1 // 1 = White, 2 = Black
    private var gameMode: String = "DISC"
    private var autoPlayActive: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenW = 1080
    private var screenH = 2400

    private val colorPalette = listOf(
        Pair("CYAN", Color.parseColor("#00F5D4")),
        Pair("RED", Color.parseColor("#FF2A85")),
        Pair("GREEN", Color.parseColor("#00F554")),
        Pair("GOLD", Color.parseColor("#FFD60A")),
        Pair("PURPLE", Color.parseColor("#C77DFF")),
        Pair("WHITE", Color.parseColor("#FFFFFF"))
    )
    private var colorIndex = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        initScreenMetrics()
        if (Settings.canDrawOverlays(this)) {
            showFloatingController()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        autoPlayActive = false
        mainHandler.removeCallbacksAndMessages(null)
        removeOverlays()
        instance = null
    }

    private fun initScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
    }

    fun showFloatingController() {
        initScreenMetrics()
        if (floatingRoot != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 1. Add Full-Screen Laser Line Overlay (Pass-Through Touches by Default)
        aimOverlayView = AimOverlayView(this, screenW, screenH)
        aimOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager?.addView(aimOverlayView, aimOverlayParams)

        // 2. Build Floating Icon + Expandable Mod Menu Window
        val rootWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Floating Bubble Icon (Visible when Menu is Minimized)
        val bubbleIcon = TextView(this).apply {
            text = "🐉"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(28, 22, 28, 22)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60F172A"))
                cornerRadius = 100f
                setStroke(4, Color.parseColor("#00F5D4"))
            }
            visibility = View.GONE
        }

        // Expanded Control Panel
        val menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0090D16"))
                cornerRadius = 28f
                setStroke(3, Color.parseColor("#00F5D4"))
            }
        }

        // Top Bar (Drag Handle + Minimize Button)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 12)
        }

        val dragTitle = TextView(this).apply {
            text = "🐉 L-DRAGO MOD  "
            textSize = 12f
            setTextColor(Color.parseColor("#00F5D4"))
            typeface = Typeface.DEFAULT_BOLD
        }

        val minBtn = TextView(this).apply {
            text = " [—] "
            textSize = 13f
            setTextColor(Color.parseColor("#FFD60A"))
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                menuPanel.visibility = View.GONE
                bubbleIcon.visibility = View.VISIBLE
            }
        }
        topBar.addView(dragTitle)
        topBar.addView(minBtn)

        bubbleIcon.setOnClickListener {
            bubbleIcon.visibility = View.GONE
            menuPanel.visibility = View.VISIBLE
        }

        // A. Auto-Play ON/OFF Button
        val autoBtn = createMenuButton("🤖 AUTO PLAY: OFF", "#334155")
        autoBtn.setOnClickListener {
            autoPlayActive = !autoPlayActive
            if (autoPlayActive) {
                autoBtn.text = "🤖 AUTO PLAY: ON ⚡"
                setBtnColor(autoBtn, "#00B4D8")
                setManualTouchMode(false)
                runAutoPlayCycle()
            } else {
                autoBtn.text = "🤖 AUTO PLAY: OFF"
                setBtnColor(autoBtn, "#334155")
                mainHandler.removeCallbacksAndMessages(null)
            }
        }

        // B. Manual Aim Drag Mode Toggle + Shoot Button
        val moveAimBtn = createMenuButton("✋ Drag Aim Lines: OFF", "#1E293B")
        moveAimBtn.setOnClickListener {
            val current = aimOverlayView?.isManualEditMode ?: false
            val next = !current
            setManualTouchMode(next)
            if (next) {
                moveAimBtn.text = "✋ Drag Aim: ON (Move Rings)"
                setBtnColor(moveAimBtn, "#F59E0B")
                Toast.makeText(this, "Ab screen par Striker aur Target gola ungli se set karein!", Toast.LENGTH_SHORT).show()
            } else {
                moveAimBtn.text = "✋ Drag Aim Lines: OFF"
                setBtnColor(moveAimBtn, "#1E293B")
            }
        }

        val fireManualBtn = createMenuButton("⚡ SHOOT LASER LINE", "#10B981")
        fireManualBtn.setOnClickListener {
            moveAimBtn.text = "✋ Drag Aim Lines: OFF"
            setBtnColor(moveAimBtn, "#1E293B")
            setManualTouchMode(false)
            shootAlongCurrentLaser()
        }

        // C. Line Color Selector
        val colorBtn = createMenuButton("🎨 Line Color: CYAN", "#1E293B")
        colorBtn.setOnClickListener {
            colorIndex = (colorIndex + 1) % colorPalette.size
            val (name, col) = colorPalette[colorIndex]
            aimOverlayView?.laserColor = col
            aimOverlayView?.invalidate()
            colorBtn.text = "🎨 Line Color: $name"
        }

        // D. Line Thickness (- / +) Row
        val thickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val thickLabel = TextView(this).apply {
            text = " Line: 6px "
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val minusBtn = createSmallButton("➖") {
            val cur = (aimOverlayView?.lineThickness ?: 6f) - 2f
            val updated = cur.coerceIn(2f, 16f)
            aimOverlayView?.lineThickness = updated
            aimOverlayView?.invalidate()
            thickLabel.text = " Line: ${updated.toInt()}px "
        }
        val plusBtn = createSmallButton("➕") {
            val cur = (aimOverlayView?.lineThickness ?: 6f) + 2f
            val updated = cur.coerceIn(2f, 16f)
            aimOverlayView?.lineThickness = updated
            aimOverlayView?.invalidate()
            thickLabel.text = " Line: ${updated.toInt()}px "
        }
        thickRow.addView(minusBtn)
        thickRow.addView(thickLabel)
        thickRow.addView(plusBtn)

        // E. Rebound Bounces + Puck Color
        val bounceBtn = createMenuButton("🔄 Wall Bounces: 2", "#1E293B")
        bounceBtn.setOnClickListener {
            val nextB = ((aimOverlayView?.maxBounces ?: 2) % 3) + 1
            aimOverlayView?.maxBounces = nextB
            aimOverlayView?.invalidate()
            bounceBtn.text = "🔄 Wall Bounces: $nextB"
        }

        val puckBtn = createMenuButton("⚪ Target: WHITE", "#1E293B")
        puckBtn.setOnClickListener {
            if (myColor == 1 && gameMode != "FREESTYLE") {
                myColor = 2
                puckBtn.text = "⚫ Target: BLACK"
            } else if (myColor == 2 && gameMode != "FREESTYLE") {
                gameMode = "FREESTYLE"
                puckBtn.text = "👑 Target: FREESTYLE"
            } else {
                myColor = 1
                gameMode = "DISC"
                puckBtn.text = "⚪ Target: WHITE"
            }
        }

        val closeBtn = createMenuButton("✖ Exit Mod", "#7F1D1D")
        closeBtn.setOnClickListener {
            autoPlayActive = false
            mainHandler.removeCallbacksAndMessages(null)
            removeOverlays()
        }

        menuPanel.addView(topBar)
        menuPanel.addView(autoBtn)
        menuPanel.addView(moveAimBtn)
        menuPanel.addView(fireManualBtn)
        menuPanel.addView(colorBtn)
        menuPanel.addView(thickRow)
        menuPanel.addView(bounceBtn)
        menuPanel.addView(puckBtn)
        menuPanel.addView(closeBtn)

        rootWrapper.addView(bubbleIcon)
        rootWrapper.addView(menuPanel)

        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 25
            y = 180
        }

        // Enable Dragging for both Header and Minimized Icon
        val dragListener = object : View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = menuParams.x
                        initY = menuParams.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (abs(dx) > 8 || abs(dy) > 8) {
                            moved = true
                            menuParams.x = initX + dx
                            menuParams.y = initY + dy
                            windowManager?.updateViewLayout(rootWrapper, menuParams)
                        }
                        return moved
                    }
                }
                return false
            }
        }

        topBar.setOnTouchListener(dragListener)
        bubbleIcon.setOnTouchListener(dragListener)

        floatingRoot = rootWrapper
        windowManager?.addView(rootWrapper, menuParams)
    }

    private fun setManualTouchMode(interactive: Boolean) {
        val view = aimOverlayView ?: return
        val params = aimOverlayParams ?: return
        view.isManualEditMode = interactive

        params.flags = if (interactive) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        windowManager?.updateViewLayout(view, params)
        view.invalidate()
    }

    // Shoot along the currently drawn Laser Line (Manual or Semi-Auto)
    private fun shootAlongCurrentLaser() {
        val overlay = aimOverlayView ?: return
        val sPos = overlay.strikerPos
        val tPos = overlay.targetPos

        val dx = tPos.x - sPos.x
        val dy = tPos.y - sPos.y
        val dist = hypot(dx, dy).coerceAtLeast(10f)
        val uX = dx / dist
        val uY = dy / dist

        val pullPower = (135f + dist * 0.16f).coerceIn(110f, 235f)
        val pullBack = Vec2(sPos.x - uX * pullPower, sPos.y - uY * pullPower)
        val centerBaseX = (overlay.boardLeft + overlay.boardRight) * 0.5f

        executeHumanShot(centerBaseX, sPos.x, sPos.y, pullBack)
    }

    // Auto-Play Loop using Silent RAM HardwareBuffer (No Screen Mirroring!)
    private fun runAutoPlayCycle() {
        if (!autoPlayActive) return

        captureBoardToRam { bmp ->
            if (!autoPlayActive) return@captureBoardToRam
            val overlay = aimOverlayView

            if (bmp != null && overlay != null) {
                val (pucks, pockets) = BoardVision.scanBoardFromBitmap(bmp)
                bmp.recycle()

                val minX = overlay.boardLeft + 120f
                val maxX = overlay.boardRight - 120f
                val baseLine = overlay.baselineY
                val centerX = (minX + maxX) * 0.5f

                val engine = CarromPhysicsEngine(baseLine, minX, maxX)
                val bestShot = engine.findBestHumanShot(pucks, pockets, myColor, gameMode)

                if (bestShot != null) {
                    // Visually snap Laser Line on screen to chosen puck!
                    overlay.strikerPos = Vec2(bestShot.strikerStartX, baseLine)
                    overlay.targetPos = bestShot.targetPos
                    overlay.invalidate()

                    executeHumanShot(centerX, bestShot.strikerStartX, baseLine, bestShot.pullBackPos)
                } else {
                    // Fallback: Shoot along current laser aim
                    shootAlongCurrentLaser()
                }
            } else {
                shootAlongCurrentLaser()
            }

            if (autoPlayActive) {
                mainHandler.postDelayed({ runAutoPlayCycle() }, Random.nextLong(5500L, 7500L))
            }
        }
    }

    private fun captureBoardToRam(onBitmapReady: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hwBuffer = screenshot.hardwareBuffer
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                                val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                hwBitmap?.recycle()
                                hwBuffer.close()
                                onBitmapReady(softBitmap)
                            } catch (e: Exception) {
                                onBitmapReady(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            onBitmapReady(null)
                        }
                    }
                )
            } catch (e: Exception) {
                onBitmapReady(null)
            }
        } else {
            onBitmapReady(null)
        }
    }

    private fun createMenuButton(txt: String, hex: String): Button {
        return Button(this).apply {
            text = txt
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(20, 12, 20, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hex))
                cornerRadius = 16f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 5, 0, 5) }
        }
    }

    private fun createSmallButton(txt: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = txt
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(16, 8, 16, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = 14f
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setBtnColor(btn: Button, hex: String) {
        (btn.background as? GradientDrawable)?.setColor(Color.parseColor(hex))
    }

    private fun removeOverlays() {
        aimOverlayView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
        }
        aimOverlayView = null

        floatingRoot?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
        }
        floatingRoot = null
    }

    fun executeHumanShot(
        currentStrikerX: Float,
        targetStrikerX: Float,
        baselineY: Float,
        pullBack: Vec2
    ) {
        val thinkDelay = Random.nextLong(400L, 900L)

        mainHandler.postDelayed({
            slideStrikerHumanly(currentStrikerX, targetStrikerX, baselineY) {
                val aimPause = Random.nextLong(300L, 550L)
                mainHandler.postDelayed({
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
        val arcOffsetY = Random.nextDouble(-12.0, 12.0).toFloat()
        val endJitterY = Random.nextInt(-2, 3).toFloat()
        path.quadTo(midX, y + arcOffsetY, endX, y + endJitterY)

        val duration = Random.nextLong(240L, 380L)
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

        val wobbleX = pullBack.x + Random.nextInt(-14, 15).toFloat()
        val wobbleY = pullBack.y + Random.nextInt(-10, 11).toFloat()

        val ctrlX = ((startX + wobbleX) / 2f) + Random.nextInt(-8, 9).toFloat()
        val ctrlY = ((startY + wobbleY) / 2f) + Random.nextInt(-8, 9).toFloat()

        path.quadTo(ctrlX, ctrlY, wobbleX, wobbleY)
        path.lineTo(pullBack.x, pullBack.y)

        val aimDuration = Random.nextLong(550L, 950L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, aimDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
    }
}