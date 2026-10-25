package com.example.caprimobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
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

        fun send(context: Context, action: String) {
            val intent = Intent(
                context,
                FloatingCompanionService::class.java
            ).setAction(action)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                action == ACTION_SUMMON
            ) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var wm: WindowManager

    private var hudParams: WindowManager.LayoutParams? = null
    private var inputPanel: LinearLayout? = null
    private var inputParams: WindowManager.LayoutParams? = null
    private var messageInput: EditText? = null

    private var dismissed = false
    private var sending = false

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val history = mutableListOf<JSONObject>()

    private val systemPrompt = """
        You are Capri, D's personal AI companion.
        Address the user as D naturally.
        Be warm, witty, helpful, and conversational.
        Keep responses reasonably concise unless D asks for detail.
    """.trimIndent()

    override fun onCreate() {
        super.onCreate()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, createNotification())
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
        history.clear()

        try {
            val view = CapriHudView(this).apply {
                portrait = getDrawable(R.drawable.capri_closed)
            }

            val params = WindowManager.LayoutParams(
                dp(300),
                dp(250),
                overlayType(),
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

            wm.addView(view, params)
            hud = view

            createInputPanel()

            view.respond(
                "",
                "Online, D. What are we doing today?"
            )

        } catch (e: Exception) {
            e.printStackTrace()
            dismiss()
            stopSelf()
        }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun installHudTouch(
        view: CapriHudView,
        params: WindowManager.LayoutParams
    ) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var downTime = 0L
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
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

                    params.x = initialX + dx
                    params.y = initialY + dy

                    try {
                        wm.updateViewLayout(view, params)
                        repositionInput()
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
                        toggleInput()
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
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }

        val input = EditText(this).apply {
            hint = "Message Capri..."
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFAAAAAA.toInt())
            textSize = 15f
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND

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
            text = "Send"
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
            overlayType(),
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

        wm.addView(panel, params)
        repositionInput()
    }

    private fun toggleInput() {
        val panel = inputPanel ?: return
        val input = messageInput ?: return

        if (panel.visibility == View.VISIBLE) {
            closeInput()
            return
        }

        panel.visibility = View.VISIBLE
        repositionInput()
        input.requestFocus()

        val imm = getSystemService(INPUT_METHOD_SERVICE)
            as InputMethodManager

        input.postDelayed({
            imm.showSoftInput(
                input,
                InputMethodManager.SHOW_IMPLICIT
            )
        }, 150)
    }

    private fun repositionInput() {
        val panel = inputPanel ?: return
        val params = inputParams ?: return
        val hudLayout = hudParams ?: return

        params.x = hudLayout.x
        params.y = hudLayout.y + dp(255)

        try {
            wm.updateViewLayout(panel, params)
        } catch (_: Exception) {
        }
    }

    private fun closeInput() {
        val input = messageInput

        if (input != null) {
            val imm = getSystemService(INPUT_METHOD_SERVICE)
                as InputMethodManager

            imm.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }

        inputPanel?.visibility = View.GONE
    }

    private fun submitMessage() {
        if (sending || dismissed) return

        val input = messageInput ?: return
        val message = input.text.toString().trim()

        if (message.isEmpty()) return

        input.text.clear()
        closeInput()

        history.add(
            JSONObject()
                .put("role", "user")
                .put("content", message)
        )

        sending = true
        hud?.think()

        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                requestReply()
            }

            sending = false

            if (!dismissed) {
                history.add(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", reply)
                )

                hud?.respond(message, reply)
            }
        }
    }

    private fun requestReply(): String {
        var connection: HttpURLConnection? = null

        return try {
            val messages = JSONArray()

            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt)
            )

            history.forEach { messages.put(it) }

            val body = JSONObject()
                .put("model", "auto")
                .put("messages", messages)
                .put("stream", false)

            connection = (
                URL("https://vireonix.ai/v1/chat/completions")
                    .openConnection() as HttpURLConnection
                ).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 60000
                    doOutput = true

                    setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )
                }

            // Vireonix does not require an API key or
            // an Authorization header.
            connection.outputStream.use { output ->
                output.write(
                    body.toString().toByteArray(Charsets.UTF_8)
                )
            }

            val status = connection.responseCode

            val responseStream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText =
                responseStream?.bufferedReader()?.use {
                    it.readText()
                }.orEmpty()

            if (status !in 200..299) {
                return "Vireonix returned HTTP $status. Please try again."
            }

            JSONObject(responseText)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: "I got an empty reply, D. Try again?"

        } catch (e: Exception) {
            e.printStackTrace()
            "I couldn't connect to Vireonix. Please try again."
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
                description = "Floating Capri companion service"
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
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
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }

        inputPanel = null
        messageInput = null
        inputParams = null

        hud?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }

        hud = null
        hudParams = null

        scope.cancel()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        dismiss()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}