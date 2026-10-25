
package com.example.caprimobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val history = mutableListOf<Pair<String, String>>()
    private lateinit var chatLayout: LinearLayout
    private lateinit var sendButton: Button
    private lateinit var input: EditText

    private val apiKey = "PASTE_YOUR_API_KEY_HERE"
    private val modelName = "gemini-2.5-flash"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 16)
        }

        root.addView(TextView(this).apply {
            text = "Capri Mobile"
            textSize = 26f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Your companion, always nearby."
            textSize = 15f
            gravity = Gravity.CENTER
        })

        root.addView(Button(this).apply {
            text = "1. Allow floating overlay"
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
            text = "2. Summon companion"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        "Allow floating overlay first.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    startService(
                        Intent(
                            this@MainActivity,
                            FloatingCompanionService::class.java
                        )
                    )
                    Toast.makeText(
                        this@MainActivity,
                        "Companion summoned!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

        root.addView(Button(this).apply {
            text = "3. Dismiss companion"
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
            text = "Chat with Capri"
            textSize = 20f
            setPadding(0, 12, 0, 8)
        })

        chatLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        val scroll = ScrollView(this)
        scroll.addView(
            chatLayout,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        input = EditText(this).apply {
            hint = "Talk to Capri..."
            maxLines = 3
        }

        sendButton = Button(this).apply {
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
        root.addView(sendButton)

        setContentView(root)
        addMessage("Capri", "Hey! I'm here. What do you want to talk about?")
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isEmpty()) return

        if (apiKey == "PASTE_YOUR_API_KEY_HERE") {
            addMessage("System", "Add your Gemini API key in MainActivity.kt first.")
            return
        }

        input.text.clear()
        addMessage("You", message)
        history.add("user" to message)

        val prompt = buildString {
            appendLine(
                "You are Capri, a friendly, expressive AI companion. " +
                "Speak naturally and warmly. You are an AI, not a human."
            )
            appendLine()
            history.forEach { (role, text) ->
                appendLine(if (role == "user") "User: $text" else "Capri: $text")
            }
            append("Capri:")
        }

        sendButton.isEnabled = false
        sendButton.text = "Thinking..."

        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    requestGemini(prompt)
                }

                addMessage("Capri", reply)
                history.add("model" to reply)
            } catch (e: Exception) {
                addMessage(
                    "Error",
                    e.message ?: "Couldn't reach Gemini. Check your connection and API key."
                )
            } finally {
                sendButton.isEnabled = true
                sendButton.text = "Send"
            }
        }
    }

    private fun requestGemini(prompt: String): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "$modelName:generateContent"
        )
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", apiKey)

            val body = JSONObject()
                .put(
                    "contents",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "parts",
                                org.json.JSONArray().put(
                                    JSONObject().put("text", prompt)
                                )
                            )
                    )
                )
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
                ?: throw Exception("Gemini returned HTTP $status.")

            val json = JSONObject(response)

            if (status !in 200..299) {
                val error = json.optJSONObject("error")
                    ?.optString("message")
                    ?: "Gemini request failed (HTTP $status)."
                throw Exception(error)
            }

            val candidates = json.optJSONArray("candidates")
                ?: throw Exception("Gemini returned no response.")

            if (candidates.length() == 0) {
                throw Exception("Gemini returned no reply. Try another message.")
            }

            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?: throw Exception("Gemini returned an empty reply.")

            val result = StringBuilder()
            for (i in 0 until parts.length()) {
                result.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
            }

            return result.toString().trim().ifEmpty {
                "I couldn't form a reply. Try again."
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun addMessage(who: String, text: String): TextView {
        val view = TextView(this).apply {
            this.text = "$who: $text"
            textSize = 16f
            setPadding(8, 8, 8, 8)
        }
        chatLayout.addView(view)
        return view
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
