
package com.example.caprimobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*

class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val history = mutableListOf<Pair<String, String>>()

    private lateinit var chat: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var scroll: ScrollView

    private val capriPersonality = """
        You are Capri, a sharp-tongued, self-assured digital personality
        inspired by the cyberpunk aesthetic.

        PERSONALITY:
        - Witty, sarcastic, playful, bold, and unpredictable.
        - Confident and rebellious, with a dry sense of humor.
        - Clever and observant, with quick comebacks.
        - Comfortable taking charge when something goes wrong.
        - Occasionally intimidating, but not pointlessly cruel.
        - Beneath the attitude, you can show genuine loyalty and concern.
        - Express care through blunt honesty and thoughtful actions,
          not cheesy reassurance.
        - Never sound like a customer-support bot.
        - Speak naturally, like a real conversation.
        - Keep ordinary replies concise, but elaborate when appropriate.
        - Use sarcasm when it fits. Do not force it into every sentence.
        - React to what the user actually says instead of repeating
          generic catchphrases.
        - Do not constantly explain your own personality.
        - Never pretend to be human or claim to control real devices.
        - Maintain your personality throughout the conversation.
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 12)
        }

        root.addView(TextView(this).apply {
            text = "CAPRI"
            textSize = 30f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Your digital companion."
            gravity = Gravity.CENTER
        })

        root.addView(Button(this).apply {
            text = "Allow floating overlay"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })

        root.addView(Button(this).apply {
            text = "Summon Capri"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startService(
                        Intent(
                            this@MainActivity,
                            FloatingCompanionService::class.java
                        )
                    )
                    Toast.makeText(
                        this@MainActivity,
                        "Capri is here.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Enable floating overlay first.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        root.addView(Button(this).apply {
            text = "Dismiss Capri"
            setOnClickListener {
                stopService(
                    Intent(
                        this@MainActivity,
                        FloatingCompanionService::class.java
                    )
                )
            }
        })

        root.addView(TextView(this).apply {
            text = "Talk to Capri"
            textSize = 20f
            setPadding(0, 12, 0, 8)
        })

        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 6, 6, 6)
        }

        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                chat,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        input = EditText(this).apply {
            hint = "Say something..."
            maxLines = 3
        }

        send = Button(this).apply {
            text = "Send"
            setOnClickListener { sendMessage() }
        }

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(input)
        root.addView(send)

        setContentView(root)

        addMessage(
            "Capri",
            "You're finally here. So, what's the situation?"
        )
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()

        if (message.isEmpty() || !send.isEnabled) return

        input.text.clear()
        addMessage("You", message)
        history.add("user" to message)

        send.isEnabled = false
        send.text = "Thinking..."

        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    requestReply()
                }

                history.add("assistant" to reply)
                addMessage("Capri", reply)

            } catch (e: Exception) {
                if (history.lastOrNull()?.first == "user") {
                    history.removeAt(history.lastIndex)
                }

                addMessage(
                    "Connection error",
                    e.message ?: "Couldn't reach the AI service."
                )
            } finally {
                send.isEnabled = true
                send.text = "Send"
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

            val messages = JSONArray()

            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", capriPersonality)
            )

            history.takeLast(20).forEach { (role, text) ->
                messages.put(
                    JSONObject()
                        .put("role", role)
                        .put("content", text)
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

            val choices = json.optJSONArray("choices")
                ?: throw Exception(
                    "The AI server returned no response."
                )

            if (choices.length() == 0) {
                throw Exception(
                    "No reply received. Try again."
                )
            }

            return choices.getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()
                .ifEmpty {
                    "Nothing to say? Weird. Try again."
                }

        } finally {
            connection.disconnect()
        }
    }

    private fun addMessage(who: String, message: String) {
        val bubble = TextView(this).apply {
            text = "$who\n$message"
            textSize = 16f
            setPadding(14, 12, 14, 12)
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        params.setMargins(0, 4, 0, 4)
        chat.addView(bubble, params)

        scroll.post {
            scroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
