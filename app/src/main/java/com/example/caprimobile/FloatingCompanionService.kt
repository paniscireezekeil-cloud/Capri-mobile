package com.example.caprimobile

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager

class FloatingCompanionService : Service() {

    companion object {
        const val ACTION_SUMMON = "capri.SUMMON"
        const val ACTION_DISMISS = "capri.DISMISS"

        var hud: CapriHudView? = null
            private set

        fun send(ctx: Context, action: String) =
            ctx.startService(Intent(ctx, FloatingCompanionService::class.java).setAction(action))
    }

    private lateinit var wm: WindowManager

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            ACTION_SUMMON -> summon()
            ACTION_DISMISS -> { dismiss(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun summon() {
        if (hud != null || !Settings.canDrawOverlays(this)) return

        val view = CapriHudView(this).apply {
            portrait = getDrawable(R.drawable.capri_portrait) // <- your portrait image name
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 300 }

        // drag to move, tap to play the demo
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 12) moved = true
                    if (moved) { lp.x = ox + dx; lp.y = oy + dy; wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) (v as CapriHudView).demo()
            }
            true
        }

        wm.addView(view, lp)
        hud = view
    }

    private fun dismiss() {
        hud?.let { wm.removeView(it) }
        hud = null
    }

    override fun onDestroy() { dismiss(); super.onDestroy() }
}package com.example.caprimobile

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager

class FloatingCompanionService : Service() {

    companion object {
        const val ACTION_SUMMON = "capri.SUMMON"
        const val ACTION_DISMISS = "capri.DISMISS"

        var hud: CapriHudView? = null
            private set

        fun send(ctx: Context, action: String) =
            ctx.startService(Intent(ctx, FloatingCompanionService::class.java).setAction(action))
    }

    private lateinit var wm: WindowManager

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            ACTION_SUMMON -> summon()
            ACTION_DISMISS -> { dismiss(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun summon() {
        if (hud != null || !Settings.canDrawOverlays(this)) return

        val view = CapriHudView(this).apply {
            portrait = getDrawable(R.drawable.capri_portrait) // <- your portrait image name
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 300 }

        // drag to move, tap to play the demo
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 12) moved = true
                    if (moved) { lp.x = ox + dx; lp.y = oy + dy; wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) (v as CapriHudView).demo()
            }
            true
        }

        wm.addView(view, lp)
        hud = view
    }

    private fun dismiss() {
        hud?.let { wm.removeView(it) }
        hud = null
    }

    override fun onDestroy() { dismiss(); super.onDestroy() }
}