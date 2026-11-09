package com.ldrago.carromai

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#090D16"))
            setPadding(55, 90, 55, 55)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "🐉 L-DRAGO AIM & AUTOPLAY"
            textSize = 22f
            setTextColor(Color.parseColor("#00F5D4"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }

        val subTitle = TextView(this).apply {
            text = "RAM Frame Cache • Laser Rebound Lines • Auto Bot"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 65)
        }

        accStatusTv = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 15, 0, 15)
        }

        accBtn = createButton("1. Open Accessibility Settings", "#1E293B") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        overlayStatusTv = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 35, 0, 15)
        }

        overlayBtn = createButton("2. Allow Floating Overlay Permission", "#1E293B") {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        launchBtn = createButton("🚀 START FLOATING LASER MENU", "#00B4D8") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Pehle Step 2 (Floating Overlay) ON karein!", Toast.LENGTH_SHORT).show()
                return@createButton
            }
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "Pehle Step 1 (Accessibility) me L-Drago ON karein!", Toast.LENGTH_SHORT).show()
                return@createButton
            }

            val service = HumanTouchAccessibilityService.instance
            if (service != null) {
                service.showFloatingController()
                Toast.makeText(this, "🐉 Floating Icon & Laser Lines Active! Carrom kholein.", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Accessibility me L-Drago ko ek baar OFF karke wapas ON karein!", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 12, 0, 12) }

        root.addView(title)
        root.addView(subTitle)
        root.addView(accStatusTv)
        root.addView(accBtn, lp)
        root.addView(overlayStatusTv)
        root.addView(overlayBtn, lp)

        val launchLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 75, 0, 0) }
        root.addView(launchBtn, launchLp)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val accOk = isAccessibilityEnabled()
        if (accOk) {
            accStatusTv.text = "✅ Step 1: Accessibility ENABLED"
            accStatusTv.setTextColor(Color.parseColor("#00F554"))
            accBtn.text = "Accessibility Active ✓"
        } else {
            accStatusTv.text = "❌ Step 1: Accessibility NOT Enabled"
            accStatusTv.setTextColor(Color.parseColor("#FF2A85"))
            accBtn.text = "1. Open Accessibility Settings"
        }

        val overlayOk = Settings.canDrawOverlays(this)
        if (overlayOk) {
            overlayStatusTv.text = "✅ Step 2: Floating Overlay ENABLED"
            overlayStatusTv.setTextColor(Color.parseColor("#00F554"))
            overlayBtn.text = "Floating Overlay Active ✓"
        } else {
            overlayStatusTv.text = "❌ Step 2: Floating Overlay NOT Enabled"
            overlayStatusTv.setTextColor(Color.parseColor("#FF2A85"))
            overlayBtn.text = "2. Allow Floating Overlay Permission"
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (HumanTouchAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName, ignoreCase = true)
    }

    private fun createButton(txt: String, hex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = txt
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(30, 36, 30, 36)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hex))
                cornerRadius = 24f
            }
            setOnClickListener { onClick() }
        }
    }
}