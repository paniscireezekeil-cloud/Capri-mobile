package com.example.caprimobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class FloatingCompanionService : Service() {

    companion object {
        const val ACTION_SUMMON = "capri.SUMMON"
        const val ACTION_DISMISS = "capri.DISMISS"

        private const val CHANNEL_ID = "capri_companion"
        private const val NOTIFICATION_ID = 4102

        var hud: CapriHudView? = null
            private set

        fun send(ctx: Context, action: String) {
            val intent = Intent(ctx, FloatingCompanionService::class.java)
                .setAction(action)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                action == ACTION_SUMMON
            ) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    private lateinit var windowManager: WindowManager

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var inputPanel: LinearLayout? = null
    private var messageInput: EditText? = null

    private var hudParams: WindowManager.LayoutParams? = null
    private var inputParams: WindowManager.LayoutParams? = null

    private var dismissed = false
    private var isSending = false

    private val chatHistory = mutableListOf<JSONObject>()

    private val apiKey: String
        get() = BuildConfig.VIREONIX_API_KEY

    private val systemPrompt = """
        You are Capri, D's personal AI companion.
        Address the user as D when it feels natural.
        Be warm, witty, helpful, and conversational.
        Keep responses reasonably concise unless D asks for detail.
        Never claim to have performed actions you cannot actually perform.
    """.trimIndent()

    override fun onCreate() {
        super.onCreate()

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(
                NOTIFICATION_ID,
                createNotification()
            )
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_SUMMON -> summon()
            ACTION_DISMISS -> {
                dismiss()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun summon() {
        if (hud != null) return

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        dismissed = false
        chatHistory.clear()

        try {
            val view = CapriHudView(this).apply {
                portrait = getDrawable(R.drawable.capri_closed)
            }

            val params = WindowManager.LayoutParams(
                dp(300),
                dp(250),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(24)
                y = dp(180)
            }

            hudParams = params

            installHudTouch(view, params)

            windowManager.addView(view, params)
            hud = view

            createInputPanel()

            view.sleep()

            mainHandler.postDelayed({
                if (!dismissed) {
                    view.respond(
                        "",
                        "Online, D. What are we doing today?"
                    )
                }
            }, 500)

        } catch (e: Exception) {
            e.printStackTrace()
            dismiss()
            stopSelf()
        }
    }

    private fun installHudTouch(
        view: CapriHudView,
        params: WindowManager.LayoutParams
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var downTime = 0L
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    downTime = System.currentTimeMillis()
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()

                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) {
                        moved = true
                    }

                    params.x = startX + dx
                    params.y = startY + dy

                    try {
                        windowManager.updateViewLayout(view, params)
                        repositionInputPanel()
                    } catch (_: Exception) {
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    val held =
                        System.currentTimeMillis() - downTime

                    if (!moved && held >= 900L) {
                        dismiss()
                        stopSelf()
                    } else if (!moved) {
                        toggleInputPanel()
                    }

                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    private fun createInputPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xEE10131F.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF00E5FF.toInt())
            }
            visibility = View.GONE
        }

        val input = EditText(this).apply {
            hint = "Message Capri..."
            setHintTextColor(0xFF9CA8C8.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            singleLine = false
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xFF202638.toInt())
                cornerRadius = dp(8).toFloat()
            }

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitMessage()
                    true
                } else {
                    false
                }
            }
        }

        val sendButton = Button(this).apply {
            text = "SEND"
            setTextColor(0xFF00E5FF.toInt())
            setOnClickListener {
                submitMessage()
            }
        }

        panel.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        panel.addView(
            sendButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val params = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(24)
            y = dp(440)
        }

        inputPanel = panel
        messageInput = input
        inputParams = params

        try {
            windowManager.addView(panel, params)
            repositionInputPanel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleInputPanel() {
        val panel = inputPanel ?: return
        val input = messageInput ?: return

        if (panel.visibility == View.VISIBLE) {
            closeInput()
        } else {
            panel.visibility = View.VISIBLE
            repositionInputPanel()

            input.requestFocus()

            val imm =
                getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

            input.postDelayed({
                imm.showSoftInput(
                    input,
                    InputMethodManager.SHOW_IMPLICIT
                )
            }, 150)
        }
    }

    private fun repositionInputPanel() {
        val panel = inputPanel ?: return
        val inputLayout = inputParams ?: return
        val hudLayout = hudParams ?: return

        inputLayout.x = hudLayout.x
        inputLayout.y = hudLayout.y + dp(258)

        try {
            windowManager.updateViewLayout(panel, inputLayout)
        } catch (_: Exception) {
        }
    }

    private fun closeInput() {
        val panel = inputPanel ?: return
        val input = messageInput

        input?.let {
            val imm =
                getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

            imm.hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }

        panel.visibility = View.GONE
    }

    private fun submitMessage() {
        if (isSending || dismissed) return

        val input = messageInput ?: return
        val message = input.text.toString().trim()

        if (message.isBlank()) return

        input.text.clear()
        closeInput()

        chatHistory.add(
            JSONObject()
                .put("role", "user")
                .put("content", message)
        )

        isSending = true
        hud?.think()

        serviceScope.launch {
            val answer = withContext(Dispatchers.IO) {
                requestReply()
            }

            isSending = false

            if (!dismissed) {
                chatHistory.add(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", answer)
                )

                hud?.respond(message, answer)
            }
        }
    }

    private fun requestReply(): String {
        var connection: HttpURLConnection? = null

        return try {
            if (apiKey.isBlank()) {
                return "D, my Vireonix API key isn't configured yet."
            }

            val messages = JSONArray()

            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt)
            )

            chatHistory.forEach {
                messages.put(it)
            }

            val payload = JSONObject()
                .put("model", "auto")
                .put("messages", messages)
                .put("stream", false)

            connection =
                (URL("https://vireonix.ai/v1/chat/completions")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 60000
                    doOutput = true
                    setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )
                    setRequestProperty(
                        "Authorization",
                        "Bearer $apiKey"
                    )
                }

            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode

            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = stream?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()

            if (status !in 200..299) {
                return "The API returned error $status. Check the API key and connection."
            }

            val response = JSONObject(responseText)

            response
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: "I received an empty reply, D. Try again?"

        } catch (e: Exception) {
            e.printStackTrace()
            "I couldn't reach Vireonix right now, D. Check your connection and try again."
        } finally {
            connection?.disconnect()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Capri Companion",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Capri available as a floating companion."
            }

            val manager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Capri is online")
            .setContentText("Your floating companion is running.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun dismiss() {
        if (dismissed) return
        dismissed = true

        closeInput()

        inputPanel?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }

        inputPanel = null
        messageInput = null
        inputParams = null

        hud?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }

        hud = null
        hudParams = null

        serviceScope.cancel()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        dismiss()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}