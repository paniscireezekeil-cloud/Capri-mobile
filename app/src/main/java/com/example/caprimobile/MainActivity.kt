package com.example.caprimobile

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080C14"))
            setPadding(32, 48, 32, 48)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "CAPRI // OS"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFCC00"))
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            setPadding(0, 0, 0, 8)
        }
        rootLayout.addView(title)

        val subtitle = TextView(this).apply {
            text = "NEURAL COMPANION INTERFACE"
            textSize = 10f
            setTextColor(Color.parseColor("#00FFFF"))
            gravity = Gravity.CENTER
            letterSpacing = 0.2f
            setPadding(0, 0, 0, 32)
        }
        rootLayout.addView(subtitle)

        fun createCyberButton(buttonText: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = buttonText
                setTextColor(Color.parseColor("#080C14"))
                setTypeface(null, Typeface.BOLD)
                textSize = 13f
                letterSpacing = 0.08f
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FFCC00"))
                    cornerRadius = 6f
                }
                setPadding(16, 24, 16, 24)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 16
                }
                setOnClickListener { onClick() }
            }
        }

        val overlayBtn = createCyberButton("ALLOW FLOATING OVERLAY") {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission already granted.", Toast.LENGTH_SHORT).show()
            }
        }
        rootLayout.addView(overlayBtn)

        val usageBtn = createCyberButton("GRANT APP-AWARENESS PERMISSION") {
            if (!hasUsageStatsPermission()) {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Usage access already granted.", Toast.LENGTH_SHORT).show()
            }
        }
        rootLayout.addView(usageBtn)

        val summonBtn = createCyberButton("SUMMON CAPRI") {
            if (Settings.canDrawOverlays(this)) {
                val serviceIntent = Intent(this, FloatingCompanionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Toast.makeText(this, "Capri summoned to screen.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Grant floating overlay permission first, D.", Toast.LENGTH_SHORT).show()
            }
        }
        rootLayout.addView(summonBtn)

        val dismissBtn = createCyberButton("DISMISS CAPRI") {
            val serviceIntent = Intent(this, FloatingCompanionService::class.java)
            stopService(serviceIntent)
            Toast.makeText(this, "Capri dismissed.", Toast.LENGTH_SHORT).show()
        }
        rootLayout.addView(dismissBtn)

        val logHeader = TextView(this).apply {
            text = "SYSTEM LOG // TERMINAL"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#AA8844"))
            setPadding(0, 24, 0, 8)
        }
        rootLayout.addView(logHeader)

        val terminalScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#111622"))
                setStroke(1, Color.parseColor("#444444"))
                cornerRadius = 6f
            }
            setPadding(16, 16, 16, 16)
        }

        val terminalText = TextView(this).apply {
            text = "CAPRI > Decided to pop up on your screen. Don't stare too hard, D."
            textSize = 12f
            setTextColor(Color.parseColor("#00FF66"))
            setTypeface(Typeface.MONOSPACE)
        }
        terminalScroll.addView(terminalText)
        rootLayout.addView(terminalScroll)

        setContentView(rootLayout)
    }

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }
}
