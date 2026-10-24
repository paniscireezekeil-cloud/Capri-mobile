
package com.example.caprimobile

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

class FloatingCompanionService : Service() {

    private lateinit var windowManager: WindowManager
    private var character: TextView? = null

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val view = TextView(this).apply {
            text = "✦"
            textSize = 42f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(85, 60, 130))
            setOnClickListener {
                Toast.makeText(
                    this@FloatingCompanionService,
                    "Hey! I'm your companion.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val params = WindowManager.LayoutParams(
            120,
            120,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 250
        }

        character = view
        windowManager.addView(view, params)
    }

    override fun onDestroy() {
        character?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        character = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
