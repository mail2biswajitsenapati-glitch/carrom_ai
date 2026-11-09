package com.ldrago.carromai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var accStatusTv: TextView
    private lateinit var overlayStatusTv: TextView
    private lateinit var accBtn: Button
    private lateinit var overlayBtn: Button
    private lateinit var launchBtn: Button

    private val SCREEN_CAPTURE_REQ = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A")) // Dark Slate Background
            setPadding(60, 100, 60, 60)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "🐉 L-DRAGO CARROM AI"
            textSize = 24f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        val subTitle = TextView(this).apply {
            text = "Humanized Box2D Physics + Auto Touch Engine"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 70)
        }

        accStatusTv = TextView(this).apply {
            textSize = 15f
            setPadding(0, 20, 0, 20)
        }

        accBtn = createStyledButton("1. Enable Accessibility (Bot Hand)", "#1E293B") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        overlayStatusTv = TextView(this).apply {
            textSize = 15f
            setPadding(0, 40, 0, 20)
        }

        overlayBtn = createStyledButton("2. Enable Floating Overlay", "#1E293B") {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        launchBtn = createStyledButton("🚀 LAUNCH FLOATING BOT", "#10B981") {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "Pehle Step 1 (Accessibility) ON karein!", Toast.LENGTH_SHORT).show()
                return@createStyledButton
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Pehle Step 2 (Floating Overlay) ON karein!", Toast.LENGTH_SHORT).show()
                return@createStyledButton
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), SCREEN_CAPTURE_REQ)
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 15, 0, 15) }

        root.addView(title)
        root.addView(subTitle)
        root.addView(accStatusTv)
        root.addView(accBtn, lp)
        root.addView(overlayStatusTv)
        root.addView(overlayBtn, lp)

        val launchLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 80, 0, 0) }
        root.addView(launchBtn, launchLp)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val accOk = isAccessibilityEnabled()
        if (accOk) {
            accStatusTv.text = "✅ Step 1: Accessibility Service ENABLED"
            accStatusTv.setTextColor(Color.parseColor("#4ADE80"))
            accBtn.text = "Accessibility Active ✓"
        } else {
            accStatusTv.text = "❌ Step 1: Accessibility NOT Enabled"
            accStatusTv.setTextColor(Color.parseColor("#F87171"))
            accBtn.text = "1. Enable Accessibility (Bot Hand)"
        }

        val overlayOk = Settings.canDrawOverlays(this)
        if (overlayOk) {
            overlayStatusTv.text = "✅ Step 2: Floating Overlay ENABLED"
            overlayStatusTv.setTextColor(Color.parseColor("#4ADE80"))
            overlayBtn.text = "Floating Overlay Active ✓"
        } else {
            overlayStatusTv.text = "❌ Step 2: Floating Overlay NOT Enabled"
            overlayStatusTv.setTextColor(Color.parseColor("#F87171"))
            overlayBtn.text = "2. Enable Floating Overlay"
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expectedService = "$packageName/${HumanTouchAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedService, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun createStyledButton(textStr: String, bgHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = textStr
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(30, 35, 30, 35)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = 24f
            }
            setOnClickListener { onClick() }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQ && resultCode == Activity.RESULT_OK && data != null) {
            val service = HumanTouchAccessibilityService.instance
            if (service != null) {
                service.initScreenCapture(resultCode, data)
                service.showFloatingController()
                Toast.makeText(this, "🐉 Floating Bot Chalu! Ab Carrom Pool kholein.", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Accessibility Service restart karein (Turn OFF and ON)", Toast.LENGTH_LONG).show()
            }
        }
    }
}