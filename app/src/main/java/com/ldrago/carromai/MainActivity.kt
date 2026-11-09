package com.ldrago.carromai

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val startBtn = Button(this)
        startBtn.text = "Start L-Drago Bot (Enable Accessibility)"
        setContentView(startBtn)

        startBtn.setOnClickListener {
            // Button dabate hi phone ki Settings khulegi permission dene ke liye
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "L-Drago Carrom Bot ko ON karein", Toast.LENGTH_LONG).show()
        }
    }
}