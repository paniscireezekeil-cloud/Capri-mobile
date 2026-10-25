
package com.example.caprimobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import kotlin.math.abs

class FloatingCompanionService : Service() {

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var character: ImageView? = null
    private var bubble: TextView? = null
    private var hiddenInput: EditText? = null
    private var chatWindow: LinearLayout? = null

    private lateinit var charParams: WindowManager.LayoutParams
    private lateinit var chatParams: WindowManager.LayoutParams

    private var longPressStart = 0L
    private var dismissed = false
    private var busy = false
    private var movedDuringTouch = false
    private var fadeRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null

    private val fadeDelay = 2000L
    private val fadeDuration = 700L
    private val longPressDuration = 2500L

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

        val greeting = "You're finally here. What's the situation?"
        history.add("assistant" to greeting)
        showDialogue(greeting)
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

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = charParams.x
                        startY = charParams.y
                        touchX = e.rawX
                        touchY = e.rawY
                        longPressStart = System.currentTimeMillis()
                        movedDuringTouch = false

                        longPressRunnable?.let {
                            handler.removeCallbacks(it)
                        }

                        longPressRunnable = Runnable {
                            if (!movedDuringTouch && !dismissed) {
                                dismissCompanion()
                            }
                        }

                        handler.postDelayed(
                            longPressRunnable!!,
                            longPressDuration
                        )

                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()

                        if (abs(dx) > 8 || abs(dy) > 8) {
                            movedDuringTouch = true
                            longPressRunnable?.let {
                                handler.removeCallbacks(it)
                            }
                        }

                        if (movedDuringTouch) {
                            charParams.x =
                                (startX + dx).coerceAtLeast(0)
                            charParams.y =
                                (startY + dy).coerceAtLeast(0)

                            try {
                                wm.updateViewLayout(image, charParams)
                            } catch (_: Exception) {
                            }

                            repositionChat()
                        }

                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressRunnable?.let {
                            handler.removeCallbacks(it)
                        }

                        val held =
                            System.currentTimeMillis() - longPressStart

                        if (!dismissed &&
                            e.action == MotionEvent.ACTION_UP &&
                            !movedDuringTouch &&
                            held < longPressDuration
                        ) {
                            showDialogue(
                                "Tap the dialogue below to talk."
                            )
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
            setOnClickListener {
                openKeyboard()
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Transparent input: typed characters are not displayed.
        val input = EditText(this).apply {
            alpha = 0f
            setTextColor(Color.TRANSPARENT)
            setHintTextColor(Color.TRANSPARENT)
            textSize = 1f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = null
            layoutParams = LinearLayout.LayoutParams(1, 1)

            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) {
                    submitMessage()
                    true
                } else {
                    false
                }
            }
        }

        val send = TextView(this).apply {
            text = "Send"
            textSize = 14f
            setTextColor(Color.rgb(190, 160, 255))
            setPadding(8, 6, 4, 6)
            setOnClickListener {
                submitMessage()
            }
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

        val placeRight = centerX < screenW / 2
        val placeBelow = centerY < screenH / 2

        chatParams.x = (
            if (placeRight) charParams.x + charW
            else charParams.x - panelW
        ).coerceIn(0, (screenW - panelW).coerceAtLeast(0))

        chatParams.y = (
            if (placeBelow) charParams.y + charH
            else charParams.y - panelH
        ).coerceIn(0, (screenH - panelH).coerceAtLeast(0))

        try {
            wm.updateViewLayout(panel, chatParams)
        } catch (_: Exception) {
        }
    }

    private fun showDialogue(text: String) {
        val view = bubble ?: return

        fadeRunnable?.let { handler.removeCallbacks(it) }
        view.animate().cancel()
        view.alpha = 1f
        view.text = text

        chatWindow?.animate()?.cancel()
        chatWindow?.alpha = 1f

        repositionChat()

        fadeRunnable = Runnable {
            chatWindow?.animate()
                ?.alpha(0f)
                ?.setDuration(fadeDuration)
                ?.start()
        }

        handler.postDelayed(fadeRunnable!!, fadeDelay)
    }

    private fun openKeyboard() {
        val input = hiddenInput ?: return

        fadeRunnable?.let { handler.removeCallbacks(it) }
        chatWindow?.animate()?.cancel()
        chatWindow?.alpha = 1f

        // Temporarily allow the input to receive keyboard focus.
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
        history.add("user" to message)
        showDialogue("Thinking...")

        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    requestReply()
                }

                history.add("assistant" to reply)
                showDialogue(reply)

            } catch (e: Exception) {
                if (history.lastOrNull()?.first == "user") {
                    history.removeAt(history.lastIndex)
                }

                showDialogue(
                    "Can't reach the AI right now: " +
                        (e.message ?: "connection error")
                )
            } finally {
                busy = false

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
            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            val messages = JSONArray().put(
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

            val response = stream?.bufferedReader()?.use {
                it.readText()
            } ?: throw Exception(
                "AI server returned HTTP $status"
            )

            val json = JSONObject(response)

            if (status !in 200..299) {
                throw Exception(
                    json.optJSONObject("error")
                        ?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: "AI server returned HTTP $status"
                )
            }

            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()
                .ifEmpty {
                    "Nothing to say? Try again."
                }

        } finally {
            connection.disconnect()
        }
    }

    private fun dismissCompanion() {
        if (dismissed) return
        dismissed = true
        stopSelf()
    }

    private fun roundedBackground(color: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = 18f * resources.displayMetrics.density
        }

    private fun createNotification(): Notification {
        val channelId = "capri_companion"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "Capri Mobile",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
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
        fadeRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable?.let { handler.removeCallbacks(it) }

        bubble?.animate()?.cancel()
        chatWindow?.animate()?.cancel()
        scope.cancel()

        listOfNotNull(chatWindow, character).forEach {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }

        character = null
        chatWindow = null
        bubble = null
        hiddenInput = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
