package com.ldrago.carromai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.random.Random

class HumanTouchAccessibilityService : AccessibilityService() {

    companion object {
        var instance: HumanTouchAccessibilityService? = null
    }

    private var windowManager: WindowManager? = null
    private var floatingLayout: LinearLayout? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var myColor: Int = 1 // 1 = White, 2 = Black
    private var gameMode: String = "DISC"
    private var autoLoop: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenW = 1080
    private var screenH = 2400

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        removeFloatingController()
    }

    fun initScreenCapture(resultCode: Int, data: Intent) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "LDragoVision",
            screenW, screenH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    fun showFloatingController() {
        if (floatingLayout != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60F172A"))
                cornerRadius = 28f
                setStroke(3, Color.parseColor("#38BDF8"))
            }
        }

        val headerTv = TextView(this).apply {
            text = "🐉 L-DRAGO AI (Drag)"
            textSize = 12f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }

        val colorBtn = createMiniButton("Puck: ⚪ WHITE", "#334155")
        colorBtn.setOnClickListener {
            myColor = if (myColor == 1) 2 else 1
            colorBtn.text = if (myColor == 1) "Puck: ⚪ WHITE" else "Puck: ⚫ BLACK"
        }

        val modeBtn = createMiniButton("Mode: DISC POOL", "#334155")
        modeBtn.setOnClickListener {
            gameMode = when (gameMode) {
                "DISC" -> "CARROM"
                "CARROM" -> "FREESTYLE"
                else -> "DISC"
            }
            modeBtn.text = "Mode: $gameMode"
        }

        val fireBtn = createMiniButton("🎯 FIRE 1 SHOT", "#10B981")
        fireBtn.setOnClickListener {
            scanAndPlayOneShot()
        }

        val autoBtn = createMiniButton("🤖 AUTO: OFF", "#475569")
        autoBtn.setOnClickListener {
            autoLoop = !autoLoop
            if (autoLoop) {
                autoBtn.text = "🤖 AUTO: ON"
                (autoBtn.background as GradientDrawable).setColor(Color.parseColor("#EF4444"))
                runAutoLoop()
            } else {
                autoBtn.text = "🤖 AUTO: OFF"
                (autoBtn.background as GradientDrawable).setColor(Color.parseColor("#475569"))
            }
        }

        val closeBtn = createMiniButton("✖ Close", "#7F1D1D")
        closeBtn.setOnClickListener {
            autoLoop = false
            removeFloatingController()
        }

        container.addView(headerTv)
        container.addView(colorBtn)
        container.addView(modeBtn)
        container.addView(fireBtn)
        container.addView(autoBtn)
        container.addView(closeBtn)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 220
        }

        // Make Panel Draggable
        headerTv.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x
                        initY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initX + (event.rawX - touchX).toInt()
                        params.y = initY + (event.rawY - touchY).toInt()
                        windowManager?.updateViewLayout(container, params)
                        return true
                    }
                }
                return false
            }
        })

        floatingLayout = container
        windowManager?.addView(container, params)
    }

    private fun createMiniButton(txt: String, hex: String): Button {
        return Button(this).apply {
            text = txt
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(20, 12, 20, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hex))
                cornerRadius = 16f
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 6, 0, 6) }
            layoutParams = lp
        }
    }

    private fun removeFloatingController() {
        floatingLayout?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
        }
        floatingLayout = null
    }

    private fun runAutoLoop() {
        if (!autoLoop) return
        scanAndPlayOneShot()
        mainHandler.postDelayed({ runAutoLoop() }, Random.nextLong(6500L, 8500L))
    }

    private fun scanAndPlayOneShot() {
        val bmp = captureCurrentBitmap()
        if (bmp == null) {
            Toast.makeText(this, "Screen frame ready nahi hai, 1 sec rukein", Toast.LENGTH_SHORT).show()
            return
        }

        val (pucks, pockets) = BoardVision.scanBoardFromBitmap(bmp)
        bmp.recycle()

        val baselineY = screenH * 0.64f
        val minBaseX = screenW * 0.24f
        val maxBaseX = screenW * 0.76f
        val centerBaseX = screenW * 0.50f

        val engine = CarromPhysicsEngine(baselineY, minBaseX, maxBaseX)
        val bestShot = engine.findBestHumanShot(pucks, pockets, myColor, gameMode)

        if (bestShot != null) {
            executeHumanShot(centerBaseX, bestShot.strikerStartX, baselineY, bestShot.pullBackPos)
        } else {
            // Safe Straight Break Shot if no clean pocket line found
            executeHumanShot(centerBaseX, centerBaseX, baselineY, Vec2(centerBaseX, baselineY + 185f))
        }
    }

    private fun captureCurrentBitmap(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenW

        val bitmap = Bitmap.createBitmap(
            screenW + rowPadding / pixelStride,
            screenH,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()
        return Bitmap.createBitmap(bitmap, 0, 0, screenW, screenH)
    }

    fun executeHumanShot(
        currentStrikerX: Float,
        targetStrikerX: Float,
        baselineY: Float,
        pullBack: Vec2
    ) {
        val thinkDelay = Random.nextLong(600L, 1400L)

        mainHandler.postDelayed({
            slideStrikerHumanly(currentStrikerX, targetStrikerX, baselineY) {
                val aimPause = Random.nextLong(350L, 700L)
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
        val arcOffsetY = Random.nextDouble(-14.0, 14.0).toFloat()
        val endJitterY = Random.nextInt(-2, 3).toFloat()
        path.quadTo(midX, y + arcOffsetY, endX, y + endJitterY)

        val duration = Random.nextLong(260L, 420L)
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

        val wobbleX = pullBack.x + Random.nextInt(-16, 17).toFloat()
        val wobbleY = pullBack.y + Random.nextInt(-10, 11).toFloat()

        val ctrlX = ((startX + wobbleX) / 2f) + Random.nextInt(-10, 11).toFloat()
        val ctrlY = ((startY + wobbleY) / 2f) + Random.nextInt(-10, 11).toFloat()

        path.quadTo(ctrlX, ctrlY, wobbleX, wobbleY)
        path.lineTo(pullBack.x, pullBack.y)

        val aimDuration = Random.nextLong(650L, 1150L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, aimDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
    }
}