package com.example.caprimobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.caprimobile.R
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class FloatingCompanionService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isOverdrive = false
    private lateinit var statusText: TextView
    private lateinit var companionIcon: ImageView
    private lateinit var dialogueBubble: TextView

    private val starburstDrawable = CyberpunkStarburstDrawable()
    private var animationJob: Job? = null
    private var idleJob: Job? = null
    private var glitchJob: Job? = null

    private var spotifyAppRemote: SpotifyAppRemote? = null
    private val OPENROUTER_API_KEY = "YOUR_OPENROUTER_API_KEY"
    private val CHANNEL_ID = "CapriFloatingServiceChannel"

    private var lastActiveApp: String = ""
    private var isSpeaking = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildForegroundNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.view_floating_companion, null)
        
        statusText = floatingView.findViewById(R.id.textCompanionStatus)
        companionIcon = floatingView.findViewById(R.id.imageCompanionIcon)
        dialogueBubble = floatingView.findViewById(R.id.textDialogueBubble)

        setupTouchAndGestureListeners()
        windowManager.addView(floatingView, params)

        connectSpotify()
        startContextMonitors()
        startIdleProactiveMessaging()
        triggerGlitchRoutine()
    }

    private fun buildForegroundNotification(): Notification {
        val notificationIntent = Intent(this, com.example.caprimobile.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CAPRI // ACTIVE")
            .setContentText("Cyberpunk HUD overlay running")
            .setSmallIcon(R.drawable.ic_companion_dormant)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Capri Companion Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun setupTouchAndGestureListeners() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var lastTapTime = 0L

        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            stopSelf()
        }

        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    longPressHandler.postDelayed(longPressRunnable, 1200)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.rawX - initialTouchX) > 20 || Math.abs(event.rawY - initialTouchY) > 20) {
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }

                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)

                    val clickDuration = System.currentTimeMillis() - lastTapTime
                    if (clickDuration < 350 && Math.abs(event.rawX - initialTouchX) < 15) {
                        toggleOverdriveMode()
                    }
                    lastTapTime = System.currentTimeMillis()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleOverdriveMode() {
        isOverdrive = !isOverdrive
        mainHandler.post {
            if (isOverdrive) {
                statusText.text = "CAPRI // OVERDRIVE"
                companionIcon.setImageDrawable(starburstDrawable)
                startStarburstAnimation()
                triggerOpenRouterAI("System entered Overdrive mode. Give a snappy, cyberpunk remark.")
            } else {
                statusText.text = "CAPRI // DORMANT"
                stopStarburstAnimation()
                companionIcon.setImageResource(R.drawable.ic_companion_dormant)
                dialogueBubble.visibility = View.GONE
            }
        }
    }

    private fun startStarburstAnimation() {
        animationJob?.cancel()
        animationJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive && isOverdrive) {
                starburstDrawable.rotationAngle = (starburstDrawable.rotationAngle + 2.5f) % 360f
                starburstDrawable.pulsePhase += 0.12f
                starburstDrawable.invalidateSelf()
                delay(16)
            }
        }
    }

    private fun stopStarburstAnimation() {
        animationJob?.cancel()
        animationJob = null
    }

    private fun connectSpotify() {
        val connectionParams = ConnectionParams.Builder("YOUR_CLIENT_ID")
            .setRedirectUri("YOUR_REDIRECT_URI")
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                appRemote.playerApi.subscribeToPlayerState().setCallback { playerState ->
                    val track = playerState.track
                    if (track != null) {
                        triggerOpenRouterAI("Now playing track: ${track.name} by ${track.artist.name}. Give a short reaction.")
                    }
                }
            }
            override fun onFailure(throwable: Throwable) {}
        })
    }

    private fun startContextMonitors() {
        serviceScope.launch {
            while (isActive) {
                val currentApp = fetchForegroundApp()
                if (currentApp != null && currentApp != lastActiveApp) {
                    lastActiveApp = currentApp
                    triggerOpenRouterAI("User just opened app package: $currentApp. Give a quick witty warning or observation.")
                }
                delay(8000)
            }
        }
    }

    private fun fetchForegroundApp(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    private fun startIdleProactiveMessaging() {
        idleJob = serviceScope.launch {
            while (isActive) {
                delay(45000)
                if (!isSpeaking) {
                    triggerOpenRouterAI("User has been idle for a bit. Drop a casual, unprompted cyberpunk check-in line.")
                }
            }
        }
    }

    private fun triggerGlitchRoutine() {
        glitchJob = serviceScope.launch {
            while (isActive) {
                delay((10000..25000).random().toLong())
                mainHandler.post {
                    floatingView.alpha = 0.6f
                    mainHandler.postDelayed({ floatingView.alpha = 1.0f }, 120)
                }
            }
        }
    }

    private fun triggerOpenRouterAI(prompt: String) {
        isSpeaking = true
        serviceScope.launch {
            try {
                val jsonBody = JSONObject().apply {
                    put("model", "openai/gpt-3.5-turbo")
                    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                }

                val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $OPENROUTER_API_KEY")
                    .addHeader("HTTP-Referer", "https://capri.mobile")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseData = response.body?.string()
                        val jsonResponse = JSONObject(responseData ?: "")
                        val reply = jsonResponse.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")

                        mainHandler.post {
                            dialogueBubble.text = reply
                            dialogueBubble.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSpeaking = false
            }
        }
    }

    class CyberpunkStarburstDrawable : Drawable() {

        var rotationAngle = 0f
        var pulsePhase = 0f

        private val outlinePaint = Paint(3).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.MITER
            strokeWidth = 5f
        }

        private val glowPaint = Paint(3).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.MITER
            strokeWidth = 9f
            alpha = 100
        }

        private val fillPaint = Paint(3).apply {
            style = Paint.Style.FILL
        }

        private val eyePaint = Paint(3).apply {
            style = Paint.Style.FILL
        }

        private val eyeOutline = Paint(3).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        private val colors = intArrayOf(
            Color.rgb(255, 20, 70),
            Color.rgb(0, 245, 255),
            Color.rgb(40, 255, 100),
            Color.rgb(150, 35, 255),
            Color.rgb(0, 150, 255),
            Color.rgb(255, 35, 180)
        )

        private val spikeShape = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return

            val save = canvas.save()

            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val size = minOf(b.width(), b.height()) * 0.9f
            val pulse = 1f + kotlin.math.sin(pulsePhase.toDouble()).toFloat() * 0.035f

            canvas.rotate(rotationAngle, cx, cy)
            canvas.scale(pulse, pulse, cx, cy)

            val radii = floatArrayOf(
                1.00f, 0.55f, 0.82f, 0.48f,
                1.08f, 0.58f, 0.88f, 0.42f,
                1.02f, 0.52f, 0.91f, 0.44f,
                1.10f, 0.57f, 0.79f, 0.45f,
                1.03f, 0.50f, 0.86f, 0.40f,
                1.12f, 0.59f, 0.83f, 0.46f,
                0.98f, 0.53f, 0.90f, 0.41f,
                1.07f, 0.56f, 0.80f, 0.43f
            )

            spikeShape.reset()

            for (i in radii.indices) {
                val angle = Math.PI * 2.0 * i / radii.size - Math.PI / 2.0
                val r = size * radii[i]
                val x = cx + kotlin.math.cos(angle).toFloat() * r
                val y = cy + kotlin.math.sin(angle).toFloat() * r

                if (i == 0) spikeShape.moveTo(x, y)
                else spikeShape.lineTo(x, y)
            }
            spikeShape.close()

            glowPaint.color = colors[1]
            canvas.drawPath(spikeShape, glowPaint)
            canvas.drawPath(spikeShape, outlinePaint)

            for (i in radii.indices step 2) {
                val next = (i + 2) % radii.size
                val a1 = Math.PI * 2.0 * i / radii.size - Math.PI / 2.0
                val a2 = Math.PI * 2.0 * next / radii.size - Math.PI / 2.0

                val p = Path().apply {
                    moveTo(cx, cy)
                    lineTo(
                        cx + kotlin.math.cos(a1).toFloat() * size * radii[i],
                        cy + kotlin.math.sin(a1).toFloat() * size * radii[i]
                    )
                    lineTo(
                        cx + kotlin.math.cos(a2).toFloat() * size * radii[next],
                        cy + kotlin.math.sin(a2).toFloat() * size * radii[next]
                    )
                    close()
                }

                fillPaint.color = colors[(i / 2) % colors.size]
                canvas.drawPath(p, fillPaint)
            }

            eyePaint.color = Color.rgb(12, 8, 30)
            canvas.drawCircle(cx, cy, size * 0.39f, eyePaint)

            eyePaint.color = Color.rgb(0, 245, 255)
            canvas.drawOval(
                cx - size * 0.34f,
                cy - size * 0.22f,
                cx + size * 0.34f,
                cy + size * 0.22f,
                eyePaint
            )

            eyePaint.color = Color.rgb(0, 45, 80)
            canvas.drawCircle(cx, cy, size * 0.205f, eyePaint)

            eyePaint.color = Color.rgb(0, 255, 255)
            canvas.drawCircle(cx, cy, size * 0.15f, eyePaint)

            eyePaint.color = Color.BLACK
            canvas.drawCircle(cx, cy, size * 0.075f, eyePaint)

            eyePaint.color = Color.WHITE
            canvas.drawCircle(
                cx - size * 0.065f,
                cy - size * 0.065f,
                size * 0.035f,
                eyePaint
            )

            canvas.drawOval(
                cx - size * 0.34f,
                cy - size * 0.22f,
                cx + size * 0.34f,
                cy + size * 0.22f,
                eyeOutline
            )

            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android graphics API")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopStarburstAnimation()
        serviceScope.cancel()
        idleJob?.cancel()
        glitchJob?.cancel()
        spotifyAppRemote?.let { SpotifyAppRemote.disconnect(it) }
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}
