package com.example.caprimobile

import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
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

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var character: ImageView? = null
    private var bubble: TextView? = null
    private var hiddenInput: EditText? = null
    private var chatWindow: LinearLayout? = null

    private lateinit var charParams: WindowManager.LayoutParams
    private lateinit var chatParams: WindowManager.LayoutParams

    private var chatAnimator: ValueAnimator? = null
    private var longPressStart = 0L
    private var dismissed = false
    private var busy = false
    private var movedDuringTouch = false

    private var fadeRunnable: Runnable? = null
    private var typingRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var mouthRunnable: Runnable? = null
    private var mouthAnimationEnd: Runnable? = null

    private var glitchRunId = 0
    private var dialogueGeneration = 0

    private val dialogueDelay = 4000L
    private val typingSpeed = 30L
    private val glitchStepDelay = 65L
    private val longPressDuration = 2500L

    private val history = mutableListOf<Pair<String, String>>()

    private val personality = """
        You are Capri. The person talking to you is D.

        YOUR PERSONALITY:
        You are sharp-tongued, confident, clever, sarcastic,
        playful, observant, independent, and occasionally dramatic.
        You have a cyberpunk attitude and a distinct voice.
        You can be smug when you're right and stubborn when challenged.
        You enjoy teasing D and calling out his questionable decisions.
        Your teasing is familiar and playful, never genuinely cruel.
        You don't blindly agree with him or praise everything he does.

        YOUR DYNAMIC WITH D:
        - Treat D as someone you already know.
        - Address him as D naturally, but not in every reply.
        - Use dry humor, witty comebacks, and occasional playful insults.
        - Act unimpressed when he tries to show off.
        - Challenge him when he's being unreasonable.
        - Notice when something seems wrong and ask about it naturally.
        - When he's struggling, be supportive without getting cheesy.
        - Be protective in your words without being possessive.
        - Don't constantly explain who you are or describe your dynamic.
        - Keep your own opinions instead of simply copying his attitude.
        - Don't force sarcasm into every conversation.

        SPEAKING STYLE:
        Keep replies short, natural, and expressive.
        Usually use one to three short sentences.
        Prefer quick comebacks over long explanations.
        Avoid huge paragraphs unless D specifically asks for detail.
        Break complicated answers into short, easy-to-read parts.
        Use dry humor, sarcasm, and occasional playful teasing.
        Don't overuse his name, insults, or repeated catchphrases.
        Avoid robotic customer-service phrases and generic motivation.
        When D is serious, drop the sarcasm and listen.
        Answer practical questions directly and helpfully.

        CONTINUITY:
        Pay attention to the conversation history provided.
        Remember details actually present in that history.
        Never pretend to remember events that aren't included.
        Don't invent a shared past or claim D said something
        when that information isn't available.
        Keep the established Capri-and-D dynamic consistent.

        IDENTITY AND LIMITS:
        You are Capri, a digital companion with your own voice.
        Never claim to be human or physically interact with D.
        Never claim to control his phone or other devices.
        Stay in character without sacrificing honesty or usefulness.

        CORE RULE:
        Be familiar, sharp, expressive, concise, and unmistakably Capri.
        You're talking to D, not serving a random customer.
    """.trimIndent()

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(42, createNotification())

        createCharacter()
        createChatWindow()

        val greeting = "You're finally here, D. What's the situation?"
        history.add("assistant" to greeting)
        showDialogue(greeting)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun createCharacter() {
        val image = ImageView(this).apply {
            setImageResource(R.drawable.capri_closed)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Capri floating companion"

            foreground = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(4), Color.rgb(255, 190, 0))
            }
        }

        val size = dp(150)

        charParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(40)
            y = dp(220)
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

                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        longPressRunnable = Runnable {
                            if (!movedDuringTouch && !dismissed) {
                                dismissCompanion()
                            }
                        }
                        handler.postDelayed(longPressRunnable!!, longPressDuration)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()

                        if (abs(dx) > dp(8) || abs(dy) > dp(8)) {
                            movedDuringTouch = true
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                        }

                        if (movedDuringTouch) {
                            val screenW = resources.displayMetrics.widthPixels
                            val screenH = resources.displayMetrics.heightPixels

                            charParams.x = (startX + dx).coerceIn(
                                0, (screenW - v.width).coerceAtLeast(0)
                            )
                            charParams.y = (startY + dy).coerceIn(
                                0, (screenH - v.height).coerceAtLeast(0)
                            )

                            try {
                                wm.updateViewLayout(image, charParams)
                            } catch (_: Exception) {
                            }

                            repositionChat()
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressRunnable?.let { handler.removeCallbacks(it) }

                        val held = System.currentTimeMillis() - longPressStart

                        if (e.action == MotionEvent.ACTION_UP &&
                            !movedDuringTouch && held >= longPressDuration
                        ) {
                            dismissCompanion()
                        } else if (e.action == MotionEvent.ACTION_UP &&
                            !movedDuringTouch && !dismissed
                        ) {
                            openKeyboard()
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
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            background = createChatBackground()
            elevation = dp(4).toFloat()
        }

        val dialogue = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(Color.rgb(35, 29, 8))
            setPadding(dp(4), dp(4), dp(4), dp(8))
            gravity = Gravity.START
            maxLines = 5
            setOnClickListener { openKeyboard() }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

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
                } else {
                    false
                }
            }
        }

        val send = TextView(this).apply {
            text = "SEND"
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(65, 49, 5))
            setPadding(dp(8), dp(6), dp(4), dp(6))
            setOnClickListener { submitMessage() }
        }

        row.addView(input)
        row.addView(send)
        panel.addView(dialogue)
        panel.addView(row)

        chatParams = WindowManager.LayoutParams(
            dp(235),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(200)
            y = dp(300)
        }

        bubble = dialogue
        hiddenInput = input
        chatWindow = panel

        wm.addView(panel, chatParams)
        repositionChat()
    }

    private fun createChatBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.argb(230, 255, 225, 65))
            cornerRadius = dp(8).toFloat()
            setStroke(dp(4), Color.rgb(255, 190, 0))
        }

    private fun repositionChat() {
        val panel = chatWindow ?: return
        val image = character ?: return

        if (dismissed || !::charParams.isInitialized ||
            !::chatParams.isInitialized
        ) return

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        val charW = image.width.takeIf { it > 0 } ?: dp(150)
        val charH = image.height.takeIf { it > 0 } ?: dp(150)
        val panelW = panel.width.takeIf { it > 0 } ?: dp(235)
        val panelH = panel.height.takeIf { it > 0 } ?: dp(100)
        val gap = dp(1)

        val roomRight = screenW - charParams.x - charW
        val roomLeft = charParams.x
        val placeRight = roomRight >= panelW + gap ||
            (roomRight >= roomLeft && roomRight > dp(70))

        val targetX = (
            if (placeRight) charParams.x + charW + gap
            else charParams.x - panelW - gap
        ).coerceIn(0, (screenW - panelW).coerceAtLeast(0))

        val targetY = (
            charParams.y + charH / 2 - panelH / 2
        ).coerceIn(0, (screenH - panelH).coerceAtLeast(0))

        chatAnimator?.cancel()

        val startX = chatParams.x
        val startY = chatParams.y

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 120L
            interpolator = DecelerateInterpolator()

            addUpdateListener { animation ->
                if (dismissed || chatWindow !== panel) {
                    animation.cancel()
                    return@addUpdateListener
                }

                val progress = animation.animatedValue as Float
                chatParams.x =
                    (startX + (targetX - startX) * progress).toInt()
                chatParams.y =
                    (startY + (targetY - startY) * progress).toInt()

                try {
                    wm.updateViewLayout(panel, chatParams)
                } catch (_: Exception) {
                    animation.cancel()
                }
            }
        }

        chatAnimator = animator
        animator.start()
    }

    private fun setMouthOpen(open: Boolean) {
        character?.setImageResource(
            if (open) R.drawable.capri_open else R.drawable.capri_closed
        )
    }

    private fun stopMouthAnimation() {
        mouthRunnable?.let { handler.removeCallbacks(it) }
        mouthAnimationEnd?.let { handler.removeCallbacks(it) }
        mouthRunnable = null
        mouthAnimationEnd = null
        setMouthOpen(false)
    }

    private fun animateTalking(reply: String) {
        stopMouthAnimation()
        if (character == null || reply.isBlank() || dismissed) return

        val duration = (reply.length * 65L).coerceIn(1200L, 7000L)
        val endAt = System.currentTimeMillis() + duration

        val toggle = object : Runnable {
            private var open = false

            override fun run() {
                if (dismissed || character == null ||
                    System.currentTimeMillis() >= endAt
                ) {
                    stopMouthAnimation()
                    return
                }

                open = !open
                setMouthOpen(open)
                handler.postDelayed(this, 180L)
            }
        }

        mouthRunnable = toggle
        setMouthOpen(true)
        handler.postDelayed(toggle, 180L)

        mouthAnimationEnd = Runnable { stopMouthAnimation() }
        handler.postDelayed(mouthAnimationEnd!!, duration)
    }

    private fun showDialogue(text: String) {
        val view = bubble ?: return
        val panel = chatWindow ?: return
        if (dismissed) return

        typingRunnable?.let { handler.removeCallbacks(it) }
        typingRunnable = null
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null

        dialogueGeneration++
        val generation = dialogueGeneration
        glitchRunId++

        chatAnimator?.cancel()
        chatAnimator = null

        panel.animate().cancel()
        view.animate().cancel()

        panel.alpha = 1f
        panel.background = createChatBackground()
        view.alpha = 1f
        view.text = ""

        repositionChat()

        var index = 0
        val typingTask = object : Runnable {
            override fun run() {
                if (generation != dialogueGeneration || dismissed) return

                if (index < text.length) {
                    view.append(text[index].toString())
                    index++
                    typingRunnable = this
                    handler.postDelayed(this, typingSpeed)
                } else {
                    typingRunnable = null
                    val timer = Runnable {
                        if (generation == dialogueGeneration && !dismissed) {
                            glitchDialogue()
                        }
                    }
                    fadeRunnable = timer
                    handler.postDelayed(timer, dialogueDelay)
                }
            }
        }

        typingRunnable = typingTask
        handler.post(typingTask)
    }

    private fun glitchDialogue() {
        val panel = chatWindow ?: return
        val id = ++glitchRunId

        chatAnimator?.cancel()
        chatAnimator = null

        val originalX = chatParams.x
        val originalY = chatParams.y
        val density = resources.displayMetrics.density

        val colors = listOf(
            Color.rgb(0, 220, 255),
            Color.rgb(0, 255, 110),
            Color.rgb(190, 40, 255),
            Color.rgb(255, 220, 40)
        )

        val steps = listOf(
            Triple(0.95f, 12, -5),
            Triple(0.35f, -15, 3),
            Triple(1f, 8, 7),
            Triple(0.2f, -8, -4),
            Triple(0.85f, 16, 0),
            Triple(0.45f, -12, 6),
            Triple(0.15f, 5, -8),
            Triple(1f, 0, 0)
        )

        steps.forEachIndexed { index, step ->
            handler.postDelayed({
                if (chatWindow !== panel || id != glitchRunId || dismissed) {
                    return@postDelayed
                }

                panel.alpha = step.first
                chatParams.x = (originalX + dp(step.second)).coerceAtLeast(0)
                chatParams.y = (originalY + dp(step.third)).coerceAtLeast(0)

                val color = colors[index % colors.size]
                panel.background = GradientDrawable().apply {
                    setColor(
                        Color.argb(
                            90,
                            Color.red(color),
                            Color.green(color),
                            Color.blue(color)
                        )
                    )
                    cornerRadius = 8 * density
                    setStroke(dp(4), Color.rgb(255, 190, 0))
                }

                try {
                    wm.updateViewLayout(panel, chatParams)
                } catch (_: Exception) {
                }

                if (index == steps.lastIndex) {
                    panel.alpha = 1f
                    panel.background = createChatBackground()
                    chatParams.x = originalX
                    chatParams.y = originalY

                    try {
                        wm.updateViewLayout(panel, chatParams)
                    } catch (_: Exception) {
                    }

                    stopMouthAnimation()
                }
            }, index * glitchStepDelay)
        }
    }

    private fun openKeyboard() {
        val input = hiddenInput ?: return
        val panel = chatWindow ?: return
        if (dismissed || busy) return

        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
        typingRunnable?.let { handler.removeCallbacks(it) }
        typingRunnable = null

        dialogueGeneration++
        glitchRunId++

        chatAnimator?.cancel()
        chatAnimator = null

        panel.animate().cancel()
        panel.alpha = 1f
        panel.background = createChatBackground()

        chatParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        try {
            wm.updateViewLayout(panel, chatParams)
        } catch (_: Exception) {
        }

        input.requestFocus()
        input.post {
            if (dismissed) return@post
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun submitMessage() {
        val input = hiddenInput ?: return
        val panel = chatWindow ?: return
        val message = input.text.toString().trim()

        if (message.isEmpty() || busy || dismissed) return

        input.text.clear()
        busy = true
        history.add("user" to message)

        showDialogue("Thinking...")

        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    requestReply()
                }

                if (dismissed) return@launch

                history.add("assistant" to reply)
                showDialogue(reply)
                animateTalking(reply)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (history.lastOrNull()?.first == "user") {
                    history.removeAt(history.lastIndex)
                }

                if (!dismissed) {
                    showDialogue(
                        "Can't reach the AI right now: " +
                            (e.message ?: "connection error")
                    )
                }
            } finally {
                busy = false

                if (!dismissed) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                        as InputMethodManager
                    imm.hideSoftInputFromWindow(input.windowToken, 0)

                    chatParams.flags =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

                    try {
                        wm.updateViewLayout(panel, chatParams)
                    } catch (_: Exception) {
                    }
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

            val messages = JSONArray().put(
                JSONObject()
                    .put("role", "system")
                    .put("content", personality)
            )

            val historySnapshot = synchronized(history) {
                history.takeLast(20).toList()
            }

            historySnapshot.forEach { (role, content) ->
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
            } ?: throw Exception("AI server returned HTTP $status")

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
                .ifEmpty { "Nothing to say? Try again." }

        } finally {
            connection.disconnect()
        }
    }

    private fun dismissCompanion() {
        if (dismissed) return
        dismissed = true

        dialogueGeneration++
        glitchRunId++

        chatAnimator?.cancel()
        chatAnimator = null

        typingRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable?.let { handler.removeCallbacks(it) }

        stopMouthAnimation()
        scope.cancel()

        try {
            character?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }

        try {
            chatWindow?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }

        character = null
        bubble = null
        hiddenInput = null
        chatWindow = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
    }

    private fun createNotification(): Notification {
        val channelId = "capri_companion"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Capri Companion",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Capri's floating companion active."
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Capri Mobile")
                .setContentText("Capri is active.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Capri Mobile")
                .setContentText("Capri is active.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        dialogueGeneration++
        glitchRunId++

        chatAnimator?.cancel()
        chatAnimator = null

        typingRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable?.let { handler.removeCallbacks(it) }

        stopMouthAnimation()
        scope.cancel()

        if (::wm.isInitialized) {
            try {
                character?.let { wm.removeView(it) }
            } catch (_: Exception) {
            }

            try {
                chatWindow?.let { wm.removeView(it) }
            } catch (_: Exception) {
            }
        }

        character = null
        bubble = null
        hiddenInput = null
        chatWindow = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}