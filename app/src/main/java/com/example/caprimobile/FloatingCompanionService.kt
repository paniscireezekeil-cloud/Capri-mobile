
package com.example.caprimobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast

class FloatingCompanionService : Service() {

    private lateinit var windowManager: WindowManager
    private var character: ImageView? = null

    private val lines = listOf(
        "Seriously? You summoned me again?",
        "What's up?",
        "Need something?",
        "Don't just stare at me.",
        "I'm right here, you know.",
        "Bored already?",
        "You called?"
    )

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        startForeground(42, createNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val view = ImageView(this).apply {
            setImageResource(R.drawable.capri)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Capri floating companion"
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

        view.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var moved = false

            override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                            moved = true
                        }

                        if (moved) {
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager.updateViewLayout(view, params)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            val line = lines.random()
                            Toast.makeText(
                                this@FloatingCompanionService,
                                line,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return true
                    }
                }
                return false
            }
        })

        character = view
        windowManager.addView(view, params)
    }

    private fun createNotification(): Notification {
        val channelId = "capri_companion"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Capri Mobile",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Capri Mobile is running")
                .setContentText("Your floating companion is active.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Capri Mobile is running")
                .setContentText("Your floating companion is active.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        }
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
