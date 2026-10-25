
package com.example.caprimobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import kotlin.math.abs

class FloatingCompanionService : Service() {

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var character: ImageView? = null
    private var bubble: TextView? = null
    private var hiddenInput: EditText? = null
    private var sendButton: TextView? = null
    private var chatWindow: LinearLayout? = null

    private lateinit var charParams: WindowManager.LayoutParams
    private lateinit var chatParams: WindowManager.LayoutParams

    private var longPressStart = 0L
    private var dismissed = false
    private var busy = false

    private val history = mutableListOf<Pair<String, String>>()

    private val personality = """
        You are Capri, a sharp-tongued, self-assured digital personality
        inspired by a cyberpunk aesthetic.
        Be witty, sarcastic, playful, bold, clever, and confident.
        Show genuine concern occasionally through blunt honesty.
        Speak naturally, keep ordinary replies concise, and stay in character.
        Never claim to be human or to control devices.
    """.trimIndent()

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        startForeground(42, createNotification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        createCharacter()
        createChatWindow()
        history.add("assistant" to "You're finally here. What's the situation?")
        showDialogue("You're finally here. What's the situation?")
    }

    private fun createCharacter() {
        val image = ImageView(this).apply {
            setImageResource(R.drawable.capri)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Capri floating companion"
        }

        val size = (150 * resources.displayMetrics.density).toInt()

        charParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 220
        }

        image.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = charParams.x
                        startY = charParams.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                        longPressStart = System.currentTimeMillis()
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()

                        if (abs(dx) > 8 || abs(dy) > 8) moved = true

                        if (moved) {
                            charParams.x = (startX + dx).coerceAtLeast(0)
                            charParams.y = (startY + dy).coerceAtLeast(0)
                            wm.updateViewLayout(image, charParams)
                            repositionChat()
                        }

                        if (!moved &&
                            System.currentTimeMillis() - longPressStart >= 5000
                        ) {
                            dismissCompanion()
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        val held = System.currentTimeMillis() - longPressStart

                        if (!dismissed && !moved) {
                            if (held >= 5000) {
                                dismissCompanion()
                            } else {
                                showDialogue(
                                    bubble?.text?.toString()
                                        ?: "Tap the dialogue below to talk."
                                )
                            }
                        }
                        return true
                    }
                }
                return true
            }
        })

        character = image
        wm.addView(image, charParams)
    }

    private fun createChatWindow() {
        val density = resources.displayMetrics.density
        val width = (235 * density).toInt()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 8)
            background = roundedBackground(Color.rgb(30, 30, 42))
        }

        val dialogue = TextView(this).apply {
            text = "Tap here to talk to Capri."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(4, 4, 4, 8)
            setOnClickListener { openKeyboard() }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // The input is transparent and tiny. Typed text is not displayed.
        val input = EditText(this).apply {
            alpha = 0f
            setTextColor(Color.TRANSPARENT)
            setHintTextColor(Color.TRANSPARENT)
            textSize = 1f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = null
            layoutParams = LinearLayout.LayoutParams(1, 1)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) {
                    submitMessage()
                    true
                } else false
            }
        }

        val send = TextView(this).apply {
            text = "Send"
            textSize = 14f
            setTextColor(Color.rgb(190, 160, 255))
            setPadding(8, 6, 4, 6)
            setOnClickListener { submitMessage() }
        }

        row.addView(input)
        row.addView(send)
        panel.addView(dialogue)
        panel.addView(row)

        chatParams = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 200
            y = 300
        }

        bubble = dialogue
        hiddenInput = input
        sendButton = send
        chatWindow = panel

        wm.addView(panel, chatParams)
        repositionChat()
    }

    private fun repositionChat() {
        val panel = chatWindow ?: return

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val charW = character?.width?.takeIf { it > 0 }
            ?: (150 * resources.displayMetrics.density).toInt()
        val charH = character?.height?.takeIf { it > 0 } ?: charW

        val centerX = charParams.x + charW / 2
        val centerY = charParams.y + charH / 2

        val panelW = panel.width.takeIf { it > 0 }
            ?: (235 * resources.displayMetrics.density).toInt()

        val panelH = panel.height.takeIf { it > 0 }
            ?: (100 * resources.displayMetrics.density).toInt()

        val left = centerX < screenW / 2
        val above = centerY > screenH / 2

        chatParams.x = if (left) {
            charParams.x + charW
        } else {
            charParams.x - panelW
        }.coerceIn(0, (screenW - panelW).coerceAtLeast(0))

        chatParams.y = if (above) {
            charParams.y - panelH
        } else {
            charParams.y + charH
        }.coerceIn(0, (screenH - panelH).coerceAtLeast(0))

        try {
            wm.updateViewLayout(panel, chatParams)
        } catch (_: Exception) {
        }
    }

    private fun showDialogue(text: String) {
        bubble?.text = text
        repositionChat()
    }

    private fun openKeyboard() {
        val input = hiddenInput ?: return

        // Allow this overlay to receive keyboard focus.
        chatParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        try {
            wm.updateViewLayout(chatWindow, chatParams)
        } catch (_: Exception) {
        }

        input.requestFocus()

        input.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }

        showDialogue("I'm listening. Type your message.")
    }

    private fun submitMessage() {
        val input = hiddenInput ?: return
        val message = input.text.toString().trim()

        if (message.isEmpty() || busy) return

        input.text.clear()
        busy = true
        showDialogue("Thinking...")

        history.add("user" to message)

        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    requestReply()
                }
                history.add("assistant" to reply)
                showDialogue(reply)
            } catch (e: Exception) {
                showDialogue("Can't reach the AI right now: ${e.message ?: "connection error"}")
                if (history.lastOrNull()?.first == "user") {
                    history.removeAt(history.lastIndex)
                }
            } finally {
                busy = false

                // Hide the keyboard and return the overlay to non-focusable mode.
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
                imm.hideSoftInputFromWindow(input.windowToken, 0)

                chatParams.flags =
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

                try {
                    wm.updateViewLayout(chatWindow, chatParams)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun requestReply(): String {
        val connection = URL(
            "https://vireonix.ai/v1/chat/completions"
        ).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")

            val messages = JSONArray()
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", personality)
            )

            history.takeLast(20).forEach { (role, content) ->
                messages.put(
                    JSONObject()
                        .put("role", role)
                        .put("content", content)
                )
            }

            val body = JSONObject()
                .put("model", "auto")
                .put("messages", messages)
                .put("stream", false)
                .toString()

            connection.outputStream.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()?.use { it.readText() }
                ?: throw Exception("AI server returned HTTP $status")

            val json = JSONObject(response)

            if (status !in 200..299) {
                throw Exception(
                    json.optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: "AI server returned HTTP $status"
                )
            }

            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()
                .ifEmpty { "Nothing to say? Try again." }

        } finally {
            connection.disconnect()
        }
    }

    private fun dismissCompanion() {
        dismissed = true
        stopSelf()
    }

    private fun roundedBackground(color: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = 18f * resources.displayMetrics.density
        }

    private fun createNotification(): Notification {
        val id = "capri_companion"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        id,
                        "Capri Mobile",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, id)
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
        scope.cancel()

        listOfNotNull(chatWindow, character).forEach {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }

        character = null
        chatWindow = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
