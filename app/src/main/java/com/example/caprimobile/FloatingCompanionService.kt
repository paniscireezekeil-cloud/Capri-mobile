package com.example.caprimobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.sin

class FloatingCompanionService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "capri_companion"
        
        private const val LONG_PRESS_DURATION = 2500L
        private const val DIALOGUE_DELAY = 3000L
        private const val IDLE_TRIGGER_MIN = 120000L
        private const val IDLE_TRIGGER_MAX = 240000L
        
        private const val TYPING_SPEED = 20L
        private const val GLITCH_STEP_DELAY = 65L
        
        var currentForegroundApp: String = "unknown"
        var currentSpotifyTrack: String = "nothing"
        var isRunning: Boolean = false
    }

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var containerView: FrameLayout? = null
    private var character: ImageView? = null
    private var statusTag: TextView? = null
    private var chatWindow: LinearLayout? = null
    private var userQueryText: TextView? = null
    private var dialogueBubble: TextView? = null
    private var chatInput: EditText? = null

    private var charParams: WindowManager.LayoutParams? = null
    private var chatParams: WindowManager.LayoutParams? = null

    private var longPressStart = 0L
    private var dismissed = false
    private var busy = false

    private var fadeRunnable: Runnable? = null
    private var typingRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var idleRunnable: Runnable? = null
    private var randomGlitchRunnable: Runnable? = null
    private var singleTapRunnable: Runnable? = null
    private var starburstAnimRunnable: Runnable? = null

    private var glitchRunId = 0
    private var dialogueGeneration = 0
    private val history = mutableListOf<Pair<String, String>>()

    private var isAlternateForm = false
    private var lastTapTime = 0L

    private var spotifyAppRemote: SpotifyAppRemote? = null
    private val SPOTIFY_CLIENT_ID = "51c61988c9464b689446166f26650f3b"
    private val SPOTIFY_REDIRECT_URI = "com.example.caprimobile://callback"

    private val personality = """
        You are Capri, a loyal digital companion and close friend to D. 
        YOUR PERSONALITY: Sharp-tongued, confident, clever, sarcastic, playful, observant, and independent. You have a cyberpunk attitude and a distinct voice.
    """.trimIndent()

    private fun getApiKey(): String {
        val p1 = "sk-or-v1-02c8ada5"
        val p2 = "3252004647879c9"
        val p3 = "7ef31d461e0376ee1c80aca03751fe079ad8fa9cd"
        return p1 + p2 + p3
    }

    override fun onCreate() {
        super.onCreate()
        try {
            if (!Settings.canDrawOverlays(this)) {
                stopSelf()
                return
            }
            isRunning = true
            startForeground(NOTIFICATION_ID, createNotification())
            wm = getSystemService(WINDOW_SERVICE) as WindowManager

            createCharacterContainer()
            createChatWindow()

            val greeting = "Decided to pop up on your screen. Don't stare too hard, D."
            history.add("assistant" to greeting)
            showDialogue("TALKING", "> you: Hey Capri.", greeting)
            
            resetIdleTimer()
            scheduleRandomGlitch()
            startAppMonitoring()
            
            handler.postDelayed({ connectSpotify() }, 500)
        } catch (e: Exception) {
            Log.e("FloatingService", "Crash prevented in onCreate: ${e.message}", e)
            stopSelf()
        }
    }

    class CyberpunkStarburstDrawable : Drawable() {
        var rotationAngle = 0f
        var pulsePhase = 0f

        private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E60039")
            style = Paint.Style.FILL
        }
        private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E676")
            style = Paint.Style.FILL
        }
        private val purplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7C4DFF")
            style = Paint.Style.FILL
        }
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0A0E17")
            style = Paint.Style.STROKE
            strokeWidth = 2.0f
        }
        private val eyeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#110A1F")
            style = Paint.Style.FILL
        }
        private val eyeOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00FFFF")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val eyePupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2979FF")
            style = Paint.Style.FILL
        }
        private val eyeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFF")
            style = Paint.Style.FILL
        }

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val radius = w.coerceAtMost(h) * 0.28f

            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rotationAngle)

            for (i in 0 until 8) {
                canvas.save()
                canvas.rotate(i * 45f)
                
                // Index 1, 3, 5, 7 are green spikes. We assign them a phase offset of 0.0 so the pulse starts there,
                // while red spikes (0, 2, 4, 6) follow sequentially in a rotating counter-clockwise wave.
                val spikeAngleIndex = (8 - i) % 8
                val phaseOffset = spikeAngleIndex * (Math.PI.toFloat() / 4f)
                val spikePulse = 1.0f + 0.25f * abs(sin((pulsePhase - phaseOffset).toDouble())).toFloat()
                
                val path = Path().apply {
                    moveTo(0f, -radius * 1.1f * spikePulse)
                    lineTo(radius * 0.25f, -radius * 0.35f)
                    lineTo(radius * 0.20f, radius * 0.20f)
                    lineTo(0f, radius * 0.45f)
                    lineTo(-radius * 0.20f, radius * 0.20f)
                    lineTo(-radius * 0.25f, -radius * 0.35f)
                    close()
                }

                val paint = if (i % 2 == 0) redPaint else greenPaint
                canvas.drawPath(path, paint)
                canvas.drawPath(path, outlinePaint)
                canvas.restore()
            }

            val corePath = Path().apply {
                moveTo(0f, -radius * 0.70f)
                lineTo(radius * 0.70f, 0f)
                lineTo(0f, radius * 0.70f)
                lineTo(-radius * 0.70f, 0f)
                close()
            }
            canvas.drawPath(corePath, purplePaint)
            canvas.drawPath(corePath, outlinePaint)

            val eyeBox = radius * 0.42f
            val eyePath = Path().apply {
                moveTo(0f, -eyeBox)
                lineTo(eyeBox, 0f)
                lineTo(0f, eyeBox)
                lineTo(-eyeBox, 0f)
                close()
            }
            canvas.drawPath(eyePath, eyeFillPaint)
            canvas.drawPath(eyePath, eyeOuterPaint)

            canvas.drawCircle(0f, 0f, radius * 0.18f, eyePupilPaint)
            canvas.drawCircle(0f, 0f, radius * 0.08f, eyeCorePaint)

            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            redPaint.alpha = alpha
            greenPaint.alpha = alpha
            purplePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private val starburstDrawable = CyberpunkStarburstDrawable()

    private fun startStarburstAnimation() {
        stopStarburstAnimation()
        starburstAnimRunnable = object : Runnable {
            override fun run() {
                if (isAlternateForm && !dismissed) {
                    starburstDrawable.rotationAngle = (starburstDrawable.rotationAngle + 1.0f) % 360f
                    starburstDrawable.pulsePhase += 0.065f
                    character?.invalidateDrawable(starburstDrawable)
                    character?.invalidate()
                    handler.postDelayed(this, 16L)
                }
            }
        }
        handler.post(starburstAnimRunnable!!)
    }

    private fun stopStarburstAnimation() {
        starburstAnimRunnable?.let { handler.removeCallbacks(it) }
        starburstAnimRunnable = null
    }

    private fun updateContainerVisuals() {
        val container = containerView ?: return
        val img = character ?: return
        val params = charParams ?: return
        val density = resources.displayMetrics.density

        if (isAlternateForm) {
            container.background = null
            container.setPadding(0, 0, 0, 0)
            params.width = (90 * density).toInt()
            params.height = (90 * density).toInt()
            safeUpdateViewLayout(container, params)

            img.setImageDrawable(starburstDrawable)
            startStarburstAnimation()

            statusTag?.setTextColor(Color.parseColor("#00FFFF"))
            statusTag?.setBackground(GradientDrawable().apply {
                setColor(Color.parseColor("#0A0E17"))
                setStroke((1 * density).toInt(), Color.parseColor("#00FFFF"))
                cornerRadius = 2 * density
            })
            statusTag?.text = "CAPRI // OVERDRIVE"
        } else {
            container.background = ContextCompat.getDrawable(this, R.drawable.cyberpunk_border)
            container.setPadding((2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt())
            params.width = (160 * density).toInt()
            params.height = (110 * density).toInt()
            safeUpdateViewLayout(container, params)

            stopStarburstAnimation()
            img.setImageResource(R.drawable.capri_closed)
            statusTag?.setTextColor(Color.parseColor("#FFCC00"))
            statusTag?.setBackgroundColor(Color.parseColor("#0A0E17"))
            statusTag?.text = "CAPRI // DORMANT"
        }
        repositionChat()
    }

    private fun createCharacterContainer() {
        val density = resources.displayMetrics.density
        val width = (160 * density).toInt()
        val height = (110 * density).toInt()

        val container = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@FloatingCompanionService, R.drawable.cyberpunk_border)
            setPadding((2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt())
            alpha = 0f
        }

        val image = ImageView(this).apply {
            setImageResource(R.drawable.capri_closed)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(image)

        val tag = TextView(this).apply {
            text = "CAPRI // DORMANT"
            textSize = 9f
            setTextColor(Color.parseColor("#FFCC00"))
            setBackgroundColor(Color.parseColor("#0A0E17"))
            setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (4 * density).toInt()
            }
        }
        container.addView(tag)
        statusTag = tag

        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 220
        }
        charParams = params

        container.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var hasMoved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val p = charParams ?: return true
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        resetIdleTimer()
                        startX = p.x
                        startY = p.y
                        touchX = e.rawX
                        touchY = e.rawY
                        hasMoved = false
                        longPressStart = System.currentTimeMillis()

                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        longPressRunnable = Runnable {
                            if (!hasMoved && !dismissed) {
                                dismissCompanion()
                            }
                        }
                        handler.postDelayed(longPressRunnable!!, LONG_PRESS_DURATION)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            hasMoved = true
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                        }
                        if (hasMoved) {
                            p.x = (startX + dx).coerceAtLeast(0)
                            p.y = (startY + dy).coerceAtLeast(0)
                            safeUpdateViewLayout(container, p)
                            repositionChat()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        val held = System.currentTimeMillis() - longPressStart

                        if (e.action == MotionEvent.ACTION_UP && !hasMoved) {
                            if (held >= LONG_PRESS_DURATION) {
                                dismissCompanion()
                            } else if (!dismissed) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 350L) {
                                    singleTapRunnable?.let { handler.removeCallbacks(it) }
                                    singleTapRunnable = null
                                    lastTapTime = 0L
                                    toggleAlternateForm()
                                } else {
                                    lastTapTime = now
                                    singleTapRunnable = Runnable {
                                        if (!dismissed) {
                                            toggleChatWindow()
                                        }
                                    }
                                    handler.postDelayed(singleTapRunnable!!, 350L)
                                }
                            }
                        }
                        return true
                    }
                }
                return true
            }
        })

        containerView = container
        character = image
        
        try {
            wm.addView(container, params)
        } catch (e: Exception) {
            Log.e("FloatingService", "Error adding character window: ${e.message}")
        }
        
        glitchInAnimation()
    }

    private fun toggleAlternateForm() {
        isAlternateForm = !isAlternateForm
        updateContainerVisuals()
        setMouthOpen(false)
        
        val formMessage = if (isAlternateForm) {
            "Shifted into overdrive form, D."
        } else {
            "Back to standard form."
        }
        
        showDialogue("TALKING", null, formMessage)
    }

    private fun glitchInAnimation() {
        val container = containerView ?: return
        val p = charParams ?: return
        val originalX = p.x
        val originalY = p.y

        val glitchSteps = listOf(
            Triple(0.2f, 15, -10),
            Triple(0.5f, -20, 14),
            Triple(0.3f, 12, 18),
            Triple(0.8f, -10, -6),
            Triple(0.6f, 16, 4),
            Triple(1.0f, 0, 0)
        )

        glitchSteps.forEachIndexed { index, step ->
            handler.postDelayed({
                if (!dismissed && containerView != null) {
                    container.alpha = step.first
                    p.x = (originalX + step.second).coerceAtLeast(0)
                    p.y = (originalY + step.third).coerceAtLeast(0)
                    safeUpdateViewLayout(container, p)
                    repositionChat()

                    if (index == glitchSteps.lastIndex) {
                        container.alpha = 1.0f
                        p.x = originalX
                        p.y = originalY
                        safeUpdateViewLayout(container, p)
                        repositionChat()
                    }
                }
            }, index * 45L)
        }
    }

    private fun toggleChatWindow() {
        val panel = chatWindow ?: return
        val cp = chatParams ?: return
        if (panel.visibility == View.VISIBLE) {
            fadeRunnable?.let { handler.removeCallbacks(it) }
            typingRunnable?.let { handler.removeCallbacks(it) }
            panel.animate().cancel()
            panel.alpha = 0f
            panel.visibility = View.GONE
            updateContainerVisuals()
            stopMouthAnimation()

            cp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            safeUpdateViewLayout(panel, cp)
        } else {
            panel.animate().cancel()
            panel.alpha = 1f
            panel.visibility = View.VISIBLE
            repositionChat()
            openKeyboard()
        }
    }

    private fun connectSpotify() {
        try {
            val connectionParams = ConnectionParams.Builder(SPOTIFY_CLIENT_ID)
                .setRedirectUri(SPOTIFY_REDIRECT_URI)
                .showAuthView(true)
                .build()

            SpotifyAppRemote.connect(applicationContext, connectionParams, object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    spotifyAppRemote = appRemote
                    try {
                        appRemote.playerApi.subscribeToPlayerState().setEventCallback { playerState ->
                            val track = playerState.track
                            currentSpotifyTrack = if (track != null) "${track.name} by ${track.artist.name}" else "nothing"
                        }
                    } catch (_: Exception) {}
                }

                override fun onFailure(throwable: Throwable) {}
            })
        } catch (_: Exception) {}
    }

    private fun startAppMonitoring() {
        scope.launch(Dispatchers.IO) {
            while (isActive && !dismissed) {
                val detectedApp = getForegroundApp()
                if (detectedApp != "unknown" && detectedApp != currentForegroundApp) {
                    currentForegroundApp = detectedApp
                }
                delay(3000L)
            }
        }
    }

    private fun getForegroundApp(): String {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return "unknown"
            val time = System.currentTimeMillis()
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 10000, time)
            if (stats.isNullOrEmpty()) return "unknown"
            val sortedStats = stats.sortedByDescending { it.lastTimeUsed }
            return sortedStats.firstOrNull()?.packageName?.substringAfterLast('.') ?: "unknown"
        } catch (_: Exception) {
            return "unknown"
        }
    }

    private fun requestReply(): String {
        val url = URL("https://openrouter.ai/api/v1/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${getApiKey()}")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        val jsonBody = JSONObject().apply {
            put("model", "openrouter/free")
            val messagesArray = JSONArray()
            messagesArray.put(JSONObject().put("role", "system").put("content", personality))
            synchronized(history) {
                history.takeLast(10).forEach { (role, content) ->
                    messagesArray.put(JSONObject().put("role", role).put("content", content))
                }
            }
            put("messages", messagesArray)
        }

        OutputStreamWriter(conn.outputStream).use { it.write(jsonBody.toString()) }
        if (conn.responseCode != 200) return "Glitch detected, D."

        val responseString = conn.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(responseString).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content").trim()
    }

    private fun requestProactiveReply(instruction: String): String {
        return try {
            val url = URL("https://openrouter.ai/api/v1/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${getApiKey()}")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }

            val jsonBody = JSONObject().apply {
                put("model", "openrouter/free")
                val messagesArray = JSONArray()
                messagesArray.put(JSONObject().put("role", "user").put("content", "$personality\n[Action: $instruction]"))
                put("messages", messagesArray)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(jsonBody.toString()) }
            if (conn.responseCode != 200) return "You still there, D?"

            val responseString = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(responseString).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (_: Exception) {
            "You still there, D?"
        }
    }

    private fun scheduleRandomGlitch() {
        randomGlitchRunnable?.let { handler.removeCallbacks(it) }
        if (dismissed) return

        val delay = (6000L..12000L).random()
        randomGlitchRunnable = Runnable {
            if (!dismissed) {
                triggerCharacterGlitch()
                scheduleRandomGlitch()
            }
        }
        handler.postDelayed(randomGlitchRunnable!!, delay)
    }

    private fun triggerCharacterGlitch() {
        val container = containerView ?: return
        val p = charParams ?: return
        val originalX = p.x
        val originalY = p.y

        val glitchOffsets = listOf(
            Pair(14, -6), Pair(-16, 8), Pair(8, 12), Pair(-10, -8), Pair(6, -4), Pair(0, 0)
        )

        glitchOffsets.forEachIndexed { index, offset ->
            handler.postDelayed({
                if (!dismissed && containerView != null) {
                    p.x = (originalX + offset.first).coerceAtLeast(0)
                    p.y = (originalY + offset.second).coerceAtLeast(0)
                    safeUpdateViewLayout(container, p)
                    repositionChat()

                    val flashDrawable = if (isAlternateForm) null else ContextCompat.getDrawable(this@FloatingCompanionService, R.drawable.cyberpunk_border)
                    container.background = flashDrawable

                    if (index == glitchOffsets.lastIndex) {
                        p.x = originalX
                        p.y = originalY
                        safeUpdateViewLayout(container, p)
                        updateContainerVisuals()
                        repositionChat()
                    }
                }
            }, index * 40L)
        }
    }

    private fun createChatWindow() {
        val density = resources.displayMetrics.density
        val width = (240 * density).toInt()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF080C14"))
                cornerRadius = 4 * density
                setStroke((2 * density).toInt(), Color.parseColor("#FFCC00"))
            }
            elevation = 8f * density
        }

        val textParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (6 * density).toInt() }

        val speakerLabel = TextView(this).apply {
            text = "CAPRI >"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFCC00"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (2 * density).toInt() }
        }

        val userQuery = TextView(this).apply {
            text = ""
            textSize = 10f
            setTextColor(Color.parseColor("#AA8844"))
            layoutParams = textParams
        }

        val dialogue = TextView(this).apply {
            text = "Online."
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFFFFF"))
            includeFontPadding = false
            setBackgroundColor(Color.parseColor("#111622"))
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            minHeight = (28 * density).toInt()
            layoutParams = textParams
        }

        val input = EditText(this).apply {
            hint = "Type to Capri..."
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 12f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1F29"))
                setStroke(1, Color.parseColor("#444444"))
                cornerRadius = 3 * density
            }
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (6 * density).toInt()
            }
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) {
                    submitMessage()
                    true
                } else false
            }
        }

        val send = TextView(this).apply {
            text = "SEND"
            textSize = 11f
            letterSpacing = 0.12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFCC00"))
            setPadding(4, 4, 4, 4)
            setOnClickListener { submitMessage() }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
        }

        row.addView(input)
        row.addView(send)
        
        panel.addView(userQuery)
        panel.addView(speakerLabel)
        panel.addView(dialogue)
        panel.addView(row)

        val cp = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 200
            y = 300
        }
        chatParams = cp

        userQueryText = userQuery
        dialogueBubble = dialogue
        chatInput = input
        chatWindow = panel

        try {
            wm.addView(panel, cp)
        } catch (e: Exception) {
            Log.e("FloatingService", "Error adding chat window: ${e.message}")
        }
        
        repositionChat()
    }

    private fun repositionChat() {
        val panel = chatWindow ?: return
        val cp = chatParams ?: return
        val cpChar = charParams ?: return

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density

        val charW = cpChar.width
        val charH = cpChar.height
        val centerX = cpChar.x + charW / 2
        val centerY = cpChar.y + charH / 2

        val panelW = panel.width.takeIf { it > 0 } ?: (240 * density).toInt()
        val panelH = panel.height.takeIf { it > 0 } ?: (130 * density).toInt()

        val placeRight = centerX < screenW / 2
        val placeBelow = centerY < screenH / 2
        val gap = (6 * density).toInt()

        val targetX = (if (placeRight) cpChar.x + charW + gap else cpChar.x - panelW - gap)
            .coerceIn(0, (screenW - panelW - 20).coerceAtLeast(0))
        val targetY = (if (placeBelow) cpChar.y + charH else cpChar.y - panelH)
            .coerceIn(0, (screenH - panelH - 20).coerceAtLeast(0))

        cp.x += ((targetX - cp.x) * 0.4f).toInt()
        cp.y += ((targetY - cp.y) * 0.4f).toInt()

        safeUpdateViewLayout(panel, cp)
    }

    private fun resetIdleTimer() {
        idleRunnable?.let { handler.removeCallbacks(it) }
        if (dismissed) return

        val randomDelay = (IDLE_TRIGGER_MIN..IDLE_TRIGGER_MAX).random()
        val idleTask = Runnable {
            if (dismissed || busy) return@Runnable
            triggerProactiveSpontaneousMessage()
        }
        idleRunnable = idleTask
        handler.postDelayed(idleTask, randomDelay)
    }

    private fun triggerProactiveSpontaneousMessage() {
        if (dismissed || busy) return
        busy = true

        scope.launch {
            try {
                val spontaneousPrompt = "D has been quiet for a bit, using app '$currentForegroundApp', and listening to Spotify track '$currentSpotifyTrack'. Say something playful."
                val reply = withContext(Dispatchers.IO) { requestProactiveReply(spontaneousPrompt) }
                if (dismissed) return@launch
                
                history.add("assistant" to reply)
                showDialogue("TALKING", null, reply)
            } catch (_: Exception) {
            } finally {
                busy = false
                resetIdleTimer()
            }
        }
    }

    private fun setMouthOpen(open: Boolean) {
        if (!isAlternateForm) {
            val imgRes = if (open) R.drawable.capri_open else R.drawable.capri_closed
            character?.setImageResource(imgRes)
        } else {
            character?.setImageDrawable(starburstDrawable)
            startStarburstAnimation()
        }
        character?.clearColorFilter()
    }

    private fun stopMouthAnimation() {
        setMouthOpen(false)
    }

    private fun showDialogue(state: String, userMsg: String?, botMsg: String?) {
        val dialogueView = dialogueBubble ?: return
        val queryView = userQueryText ?: return
        val panel = chatWindow ?: return
        if (dismissed) return

        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null

        if (state == "THINKING") {
            typingRunnable?.let { handler.removeCallbacks(it) }
            typingRunnable = null
            stopMouthAnimation()
        }

        dialogueGeneration++
        val generation = dialogueGeneration
        glitchRunId++

        statusTag?.text = if (isAlternateForm) "CAPRI // OVERDRIVE" else "CAPRI // $state"
        panel.animate().cancel()
        panel.alpha = 1f
        panel.visibility = View.VISIBLE

        if (userMsg != null) {
            queryView.text = userMsg
            queryView.visibility = View.VISIBLE
        } else {
            queryView.visibility = View.GONE
        }

        val targetText = botMsg ?: ""

        if (state == "THINKING") {
            dialogueView.text = targetText
            dialogueView.requestLayout()
            panel.requestLayout()
            repositionChat()
            return
        }

        dialogueView.text = ""
        dialogueView.requestLayout()
        panel.requestLayout()
        repositionChat()

        var charIndex = 0
        var isWordBoundary = true

        val typingTask = object : Runnable {
            override fun run() {
                if (generation != dialogueGeneration || dismissed) return
                if (charIndex < targetText.length) {
                    val currentChar = targetText[charIndex]
                    dialogueView.append(currentChar.toString())
                    
                    dialogueView.invalidate()
                    panel.requestLayout()

                    if (!currentChar.isWhitespace() && isWordBoundary) {
                        setMouthOpen(true)
                        isWordBoundary = false
                    } else if (currentChar.isWhitespace()) {
                        setMouthOpen(false)
                        isWordBoundary = true
                    }

                    charIndex++
                    typingRunnable = this
                    handler.postDelayed(this, TYPING_SPEED)
                } else {
                    typingRunnable = null
                    setMouthOpen(false)

                    dialogueView.invalidate()
                    panel.requestLayout()

                    val timer = Runnable {
                        if (generation == dialogueGeneration && !dismissed && !busy) {
                            glitchDialogue()
                        }
                    }
                    fadeRunnable = timer
                    handler.postDelayed(timer, DIALOGUE_DELAY)
                }
            }
        }
        typingRunnable = typingTask
        handler.post(typingTask)
    }

    private fun glitchDialogue() {
        val panel = chatWindow ?: return
        val p = chatParams ?: return
        val id = ++glitchRunId
        val originalX = p.x
        val originalY = p.y
        val density = resources.displayMetrics.density

        val glitchColors = listOf(
            Color.rgb(255, 204, 0),
            Color.rgb(255, 140, 0),
            Color.rgb(255, 60, 60),
            Color.rgb(255, 220, 100)
        )

        val steps = listOf(
            Triple(0.95f, 12, -5),
            Triple(0.35f, -15, 3),
            Triple(1.0f, 8, 7),
            Triple(0.2f, -8, -4),
            Triple(0.85f, 16, 0),
            Triple(0.45f, -12, 6),
            Triple(0.15f, 5, -8),
            Triple(0f, 0, 0)
        )

        steps.forEachIndexed { index, step ->
            handler.postDelayed({
                if (chatWindow === panel && id == glitchRunId && !dismissed) {
                    panel.alpha = step.first
                    p.x = (originalX + step.second).coerceAtLeast(0)
                    p.y = (originalY + step.third).coerceAtLeast(0)

                    val color = glitchColors[index % glitchColors.size]
                    panel.background = GradientDrawable().apply {
                        setColor(Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)))
                        cornerRadius = 2 * density
                        setStroke((2 * density).toInt().coerceAtLeast(1), Color.argb(230, Color.red(color), Color.green(color), Color.blue(color)))
                    }

                    safeUpdateViewLayout(panel, p)
                    repositionChat()

                    if (index == steps.lastIndex) {
                        panel.alpha = 0f
                        panel.visibility = View.GONE
                        p.x = originalX
                        p.y = originalY
                        safeUpdateViewLayout(panel, p)
                        updateContainerVisuals()
                        stopMouthAnimation()
                        repositionChat()
                    }
                }
            }, index * GLITCH_STEP_DELAY)
        }
    }

    private fun openKeyboard() {
        val panel = chatWindow ?: return
        val cp = chatParams ?: return
        panel.animate().cancel()
        panel.alpha = 1f
        panel.visibility = View.VISIBLE

        cp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        safeUpdateViewLayout(panel, cp)

        chatInput?.requestFocus()
        chatInput?.post {
            if (!dismissed) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(chatInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun submitMessage() {
        val input = chatInput ?: return
        val message = input.text.toString().trim()
        if (message.isEmpty() || busy || dismissed) return

        resetIdleTimer()
        input.text.clear()

        busy = true
        history.add("user" to message)
        showDialogue("THINKING", "> you: $message", "Thinking...")

        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { requestReply() }
                if (dismissed) return@launch
                history.add("assistant" to reply)
                showDialogue("TALKING", "> you: $message", reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (history.lastOrNull()?.first == "user") {
                    history.removeAt(history.lastIndex)
                }
                if (!dismissed) {
                    showDialogue("DORMANT", null, "Network error: " + (e.message ?: "error"))
                }
            } finally {
                busy = false
                resetIdleTimer()
                if (!dismissed) {
                    openKeyboard()
                }
            }
        }
    }

    private fun safeUpdateViewLayout(view: View, params: WindowManager.LayoutParams) {
        try {
            if (!dismissed) {
                wm.updateViewLayout(view, params)
            }
        } catch (_: Exception) {}
    }

    private fun dismissCompanion() {
        if (dismissed) return
        dismissed = true
        isRunning = false
        dialogueGeneration++
        glitchRunId++

        typingRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable?.let { handler.removeCallbacks(it) }
        idleRunnable?.let { handler.removeCallbacks(it) }
        randomGlitchRunnable?.let { handler.removeCallbacks(it) }
        singleTapRunnable?.let { handler.removeCallbacks(it) }
        stopStarburstAnimation()
        stopMouthAnimation()
        scope.cancel()

        spotifyAppRemote?.let {
            try { SpotifyAppRemote.disconnect(it) } catch (_: Exception) {}
        }

        try { containerView?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { chatWindow?.let { wm.removeView(it) } } catch (_: Exception) {}

        containerView = null
        character = null
        statusTag = null
        dialogueBubble = null
        userQueryText = null
        chatInput = null
        chatWindow = null
        charParams = null
        chatParams = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Capri Companion",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Capri's floating companion active."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Capri Mobile")
            .setContentText("Capri is active via OpenRouter Free.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        dialogueGeneration++
        glitchRunId++
        typingRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable?.let { handler.removeCallbacks(it) }
        idleRunnable?.let { handler.removeCallbacks(it) }
        randomGlitchRunnable?.let { handler.removeCallbacks(it) }
        singleTapRunnable?.let { handler.removeCallbacks(it) }
        stopStarburstAnimation()
        stopMouthAnimation()
        scope.cancel()

        spotifyAppRemote?.let {
            try { SpotifyAppRemote.disconnect(it) } catch (_: Exception) {}
        }

        try { containerView?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { chatWindow?.let { wm.removeView(it) } } catch (_: Exception) {}

        containerView = null
        character = null
        statusTag = null
        dialogueBubble = null
        userQueryText = null
        chatInput = null
        chatWindow = null
        charParams = null
        chatParams = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
