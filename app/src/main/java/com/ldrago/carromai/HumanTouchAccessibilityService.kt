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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class HumanTouchAccessibilityService : AccessibilityService() {

    companion object {
        var instance: HumanTouchAccessibilityService? = null
    }

    private var windowManager: WindowManager? = null
    private var aimOverlayView: AimOverlayView? = null
    private var aimOverlayParams: WindowManager.LayoutParams? = null
    private var floatingRoot: LinearLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null

    private var myColor: Int = 1 // 1 = White, 2 = Black
    private var autoPlayActive: Boolean = false
    private var hideFromRecording: Boolean = true // Stream-Proof by default!
    private var liveRadarRunning: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenW = 1080
    private var screenH = 2400

    private val colorPalette = listOf(
        Pair("CYAN", Color.parseColor("#00F5D4")),
        Pair("GREEN", Color.parseColor("#00F554")),
        Pair("RED", Color.parseColor("#FF2A85")),
        Pair("GOLD", Color.parseColor("#FFD60A")),
        Pair("WHITE", Color.parseColor("#FFFFFF"))
    )
    private var colorIdx = 0

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
        liveRadarRunning = false
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

        // 1. Full-Screen Live Trajectory Layer (With FLAG_SECURE so screen recorders cannot see it!)
        aimOverlayView = AimOverlayView(this, screenW, screenH)
        var baseOverlayFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (hideFromRecording) {
            baseOverlayFlags = baseOverlayFlags or WindowManager.LayoutParams.FLAG_SECURE
        }

        aimOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            baseOverlayFlags,
            PixelFormat.TRANSLUCENT
        )
        windowManager?.addView(aimOverlayView, aimOverlayParams)

        // 2. Compact Modern Pill Floating UI (Fits cleanly at top without blocking the Carrom board)
        val rootWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val minimizedBubble = TextView(this).apply {
            text = "🎯"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(22, 16, 22, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E612151C"))
                cornerRadius = 100f
                setStroke(3, Color.parseColor("#00F5D4"))
            }
            visibility = View.GONE
        }

        val compactPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB12151C"))
                cornerRadius = 22f
                setStroke(2, Color.parseColor("#00F5D4"))
            }
        }

        // Row 1: Title + Stream-Proof Toggle + Minimize + Close
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dragLabel = TextView(this).apply {
            text = "🎯 KING AIM  "
            textSize = 11f
            setTextColor(Color.parseColor("#00F5D4"))
            typeface = Typeface.DEFAULT_BOLD
        }

        val recHideBtn = chipBtn("👁️ RecHide: ON", "#00B4D8")
        recHideBtn.setOnClickListener {
            hideFromRecording = !hideFromRecording
            recHideBtn.text = if (hideFromRecording) "👁️ RecHide: ON" else "👁️ RecHide: OFF"
            setChipBg(recHideBtn, if (hideFromRecording) "#00B4D8" else "#334155")
            applyStreamProofFlag()
        }

        val holeBtn = chipBtn("Hole: Normal", "#2B3343")
        holeBtn.setOnClickListener {
            val view = aimOverlayView ?: return@setOnClickListener
            view.freestyleSmallPockets = !view.freestyleSmallPockets
            holeBtn.text = if (view.freestyleSmallPockets) "Hole: Free(S)" else "Hole: Normal"
            view.invalidate()
        }

        val minBtn = chipBtn("—", "#475569")
        minBtn.setOnClickListener {
            compactPanel.visibility = View.GONE
            minimizedBubble.visibility = View.VISIBLE
        }

        val exitBtn = chipBtn("✖", "#7F1D1D")
        exitBtn.setOnClickListener {
            autoPlayActive = false
            liveRadarRunning = false
            mainHandler.removeCallbacksAndMessages(null)
            removeOverlays()
        }

        row1.addView(dragLabel)
        row1.addView(recHideBtn)
        row1.addView(holeBtn)
        row1.addView(minBtn)
        row1.addView(exitBtn)

        // Row 2: Manual Aim Touch + Color + 2v2 + AutoPlay
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 0)
        }

        val aimLockBtn = chipBtn("✋ Aim: Unlock", "#F59E0B")
        setManualAimInteractive(true) // Start with interactive aim adjustment ready
        aimLockBtn.setOnClickListener {
            val cur = aimOverlayView?.isManualTouchEnabled ?: false
            val next = !cur
            setManualAimInteractive(next)
            aimLockBtn.text = if (next) "✋ Aim: Unlock" else "🔒 Aim: Pass"
            setChipBg(aimLockBtn, if (next) "#F59E0B" else "#2B3343")
        }

        val colorBtn = chipBtn("🎨 CYAN", "#2B3343")
        colorBtn.setOnClickListener {
            colorIdx = (colorIdx + 1) % colorPalette.size
            val (name, col) = colorPalette[colorIdx]
            aimOverlayView?.laserColor = col
            aimOverlayView?.invalidate()
            colorBtn.text = "🎨 $name"
        }

        val teamBtn = chipBtn("1v1", "#2B3343")
        teamBtn.setOnClickListener {
            val view = aimOverlayView ?: return@setOnClickListener
            view.is2v2Mode = !view.is2v2Mode
            teamBtn.text = if (view.is2v2Mode) "2v2" else "1v1"
        }

        val autoBtn = chipBtn("🤖 Auto: OFF", "#2B3343")
        autoBtn.setOnClickListener {
            autoPlayActive = !autoPlayActive
            if (autoPlayActive) {
                autoBtn.text = "🤖 Auto: ON"
                setChipBg(autoBtn, "#10B981")
                setManualAimInteractive(false)
                aimLockBtn.text = "🔒 Aim: Pass"
                setChipBg(aimLockBtn, "#2B3343")
            } else {
                autoBtn.text = "🤖 Auto: OFF"
                setChipBg(autoBtn, "#2B3343")
            }
        }

        row2.addView(aimLockBtn)
        row2.addView(colorBtn)
        row2.addView(teamBtn)
        row2.addView(autoBtn)

        compactPanel.addView(row1)
        compactPanel.addView(row2)

        minimizedBubble.setOnClickListener {
            minimizedBubble.visibility = View.GONE
            compactPanel.visibility = View.VISIBLE
        }

        rootWrapper.addView(minimizedBubble)
        rootWrapper.addView(compactPanel)

        var menuFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (hideFromRecording) {
            menuFlags = menuFlags or WindowManager.LayoutParams.FLAG_SECURE
        }

        menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            menuFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 70
        }

        val dragTouchListener = object : View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val p = menuParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = p.x
                        initY = p.y
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
                            p.x = initX + dx
                            p.y = initY + dy
                            windowManager?.updateViewLayout(rootWrapper, p)
                        }
                        return moved
                    }
                }
                return false
            }
        }

        dragLabel.setOnTouchListener(dragTouchListener)
        minimizedBubble.setOnTouchListener(dragTouchListener)

        floatingRoot = rootWrapper
        windowManager?.addView(rootWrapper, menuParams)

        // Start Continuous Live Board & Striker Scanner
        liveRadarRunning = true
        runLiveBoardRadarLoop()
    }

    private fun applyStreamProofFlag() {
        val view = aimOverlayView ?: return
        val oParams = aimOverlayParams ?: return
        val mView = floatingRoot ?: return
        val mParams = menuParams ?: return

        if (hideFromRecording) {
            oParams.flags = oParams.flags or WindowManager.LayoutParams.FLAG_SECURE
            mParams.flags = mParams.flags or WindowManager.LayoutParams.FLAG_SECURE
        } else {
            oParams.flags = oParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            mParams.flags = mParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
        windowManager?.updateViewLayout(view, oParams)
        windowManager?.updateViewLayout(mView, mParams)
    }

    private fun setManualAimInteractive(interactive: Boolean) {
        val view = aimOverlayView ?: return
        val params = aimOverlayParams ?: return
        view.isManualTouchEnabled = interactive

        var flags = if (interactive) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        if (hideFromRecording) {
            flags = flags or WindowManager.LayoutParams.FLAG_SECURE
        }
        params.flags = flags
        windowManager?.updateViewLayout(view, params)
        view.invalidate()
    }

    // Continuous Background Scanner: Detects Pucks, Pocket Size & Syncs Striker Position Live
    private fun runLiveBoardRadarLoop() {
        if (!liveRadarRunning) return

        captureBoardToRam { bmp ->
            val overlay = aimOverlayView
            if (bmp != null && overlay != null) {
                val out = BoardVision.scanFullBoard(bmp, overlay.freestyleSmallPockets)
                bmp.recycle()

                // Auto-sync Striker X position if detected on baseline and user isn't manually dragging
                if (out.detectedStrikerPos != null && !overlay.isManualTouchEnabled) {
                    val dx = overlay.pullHandlePos.x - overlay.strikerPos.x
                    val dy = overlay.pullHandlePos.y - overlay.strikerPos.y
                    overlay.strikerPos.x = out.detectedStrikerPos.x
                    overlay.pullHandlePos.x = overlay.strikerPos.x + dx
                    overlay.pullHandlePos.y = overlay.strikerPos.y + dy
                }

                if (autoPlayActive) {
                    val minX = overlay.boardLeft + 120f
                    val maxX = overlay.boardRight - 120f
                    val engine = CarromPhysicsEngine(overlay.baselineY, minX, maxX)
                    val best = engine.findBestHumanShot(out.pucks, out.pockets, myColor, out.detectedMode)
                    if (best != null) {
                        overlay.strikerPos = Vec2(best.strikerStartX, overlay.baselineY)
                        overlay.pullHandlePos = best.pullBackPos
                        overlay.invalidate()
                        val centerX = (minX + maxX) * 0.5f
                        executeHumanShot(centerX, best.strikerStartX, overlay.baselineY, best.pullBackPos)
                    }
                } else {
                    overlay.invalidate()
                }
            }

            if (liveRadarRunning) {
                val delay = if (autoPlayActive) Random.nextLong(5200L, 6800L) else 950L
                mainHandler.postDelayed({ runLiveBoardRadarLoop() }, delay)
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

    private fun chipBtn(txt: String, hex: String): Button {
        return Button(this).apply {
            text = txt
            isAllCaps = false
            textSize = 10f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(16, 6, 16, 6)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hex))
                cornerRadius = 14f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(5, 2, 5, 2) }
        }
    }

    private fun setChipBg(btn: Button, hex: String) {
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
        val thinkDelay = Random.nextLong(450L, 950L)
        mainHandler.postDelayed({
            slideStrikerHumanly(currentStrikerX, targetStrikerX, baselineY) {
                val aimPause = Random.nextLong(320L, 600L)
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
        val arcOffsetY = Random.nextDouble(-11.0, 11.0).toFloat()
        path.quadTo(midX, y + arcOffsetY, endX, y)

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
        val wobbleX = pullBack.x + Random.nextInt(-12, 13).toFloat()
        val wobbleY = pullBack.y + Random.nextInt(-9, 10).toFloat()
        val ctrlX = ((startX + wobbleX) / 2f) + Random.nextInt(-7, 8).toFloat()
        val ctrlY = ((startY + wobbleY) / 2f) + Random.nextInt(-7, 8).toFloat()

        path.quadTo(ctrlX, ctrlY, wobbleX, wobbleY)
        path.lineTo(pullBack.x, pullBack.y)

        val aimDuration = Random.nextLong(550L, 920L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, aimDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}