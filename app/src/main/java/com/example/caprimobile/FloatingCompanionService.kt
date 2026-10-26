package com.example.caprimobile

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote

class FloatingCompanionService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var hudView: CapriHudView

    private var spotifyAppRemote: SpotifyAppRemote? = null
    private val CLIENT_ID = "51c61988c9464b689446166f26650f3b"
    private val REDIRECT_URI = "com.example.caprimobile://callback"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        hudView = CapriHudView(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        hudView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(hudView, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(hudView, params)
        connectSpotify()
    }

    private fun connectSpotify() {
        val connectionParams = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(applicationContext, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                Log.d("FloatingService", "Connected to Spotify App Remote!")
                
                // Play test track on startup
                playTrack("spotify:track:7qiZfU4dY1lWllzX7mPBI3")
            }

            override fun onFailure(throwable: Throwable) {
                Log.e("FloatingService", "Spotify connection failed: ${throwable.message}", throwable)
            }
        })
    }

    // Call this whenever you want her to play a specific URI or track
    fun playTrack(uri: String) {
        spotifyAppRemote?.playerApi?.play(uri)
    }

    override fun onDestroy() {
        super.onDestroy()
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
        if (::hudView.isInitialized) {
            windowManager.removeView(hudView)
        }
    }
}
