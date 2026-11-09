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

    private lateinit var statusTv: TextView
    private lateinit var overlayBtn: Button
    private lateinit var accBtn: Button
    private lateinit var startGameBtn: Button

    private val carromPackages = listOf(
        "com.miniclip.carrom",
        "com.miniclip.carrompool"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#12151C"))
            setPadding(50, 80, 50, 50)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val header = TextView(this).apply {
            text = "🎯 CARROM KING HELPER"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val subHeader = TextView(this).apply {
            text = "Live Box2D Trajectory • Stream-Proof • Auto Mode"
            textSize = 12f
            setTextColor(Color.parseColor("#8A94A6"))
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 50)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C212C"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#2D3546"))
            }
        }

        statusTv = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 30)
        }

        overlayBtn = makeBtn("1. Display Over Other Apps Permission", "#2B3343") {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        accBtn = makeBtn("2. Enable Live Board Reader & Auto-Touch", "#2B3343") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        startGameBtn = makeBtn("🎮 LAUNCH CARROM POOL", "#00C853") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Pehle Step 1 (Overlay Permission) allow karein!", Toast.LENGTH_SHORT).show()
                return@makeBtn
            }
            if (!isServiceReady()) {
                Toast.makeText(this, "Pehle Step 2 (Accessibility) me app ON karein!", Toast.LENGTH_SHORT).show()
                return@makeBtn
            }

            val service = HumanTouchAccessibilityService.instance
            if (service != null) {
                service.showFloatingController()
                launchCarromPoolApp()
            } else {
                Toast.makeText(this, "Accessibility service ko ek baar OFF karke ON karein", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        card.addView(statusTv)
        card.addView(overlayBtn)
        card.addView(accBtn)

        root.addView(header)
        root.addView(subHeader)
        root.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val launchParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 50, 0, 0) }
        root.addView(startGameBtn, launchParams)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val ovOk = Settings.canDrawOverlays(this)
        val accOk = isServiceReady()

        overlayBtn.text = if (ovOk) "✅ 1. Overlay Permission: Ready" else "1. Allow Overlay Permission"
        accBtn.text = if (accOk) "✅ 2. Live Board Service: Ready" else "2. Enable Live Board Service"

        statusTv.text = if (ovOk && accOk) {
            "🟢 System Ready! Niche button daba kar Carrom Pool start karein."
        } else {
            "🟡 Setup Required: Dono permissions ON karein."
        }
    }

    private fun isServiceReady(): Boolean {
        if (HumanTouchAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName, ignoreCase = true)
    }

    private fun launchCarromPoolApp() {
        for (pkg in carromPackages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                return
            }
        }
        Toast.makeText(this, "🎯 Floating Aim Active! Ab Carrom Pool open karein.", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    private fun makeBtn(label: String, colorHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(28, 32, 28, 32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = 20f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 12) }
            setOnClickListener { onClick() }
        }
    }
}