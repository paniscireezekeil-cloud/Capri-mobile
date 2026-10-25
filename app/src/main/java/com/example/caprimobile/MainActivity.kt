
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 12)
        }

        root.addView(TextView(this).apply {
            text = "Capri"
            textSize = 30f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Your companion, always nearby."
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
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Allow the overlay first.",
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
            text = "Chat with Capri"
            textSize = 20f
            setPadding(0, 12, 0, 8)
        })

        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 6, 6, 6)
        }

        val scroll = ScrollView(this)
        scroll.addView(
            chat,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        input = EditText(this).apply {
            hint = "Talk to Capri..."
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
        addMessage("Capri", "Hey! I'm here. What do you want to talk about?")
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
                history.removeAt(history.lastIndex)
                addMessage(
                    "System",
                    "Couldn't connect: ${e.message ?: "Please try again."}"
                )
            } finally {
                send.isEnabled = true
                send.text = "Send"
            }
        }
    }

    private fun requestReply(): String {
        val connection = URL(
            "https://api.llmfaucet.dev/v1/chat/completions"
        ).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type", "application/json"
            )
            connection.setRequestProperty(
                "Authorization", "Bearer free"
            )

            val messages = JSONArray()
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        "You are Capri, a warm, friendly AI companion. " +
                        "Speak naturally, remember the conversation " +
                        "provided here, and never pretend to be human."
                    )
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
            } ?: throw Exception("HTTP $status")

            val json = JSONObject(response)

            if (status !in 200..299) {
                throw Exception(
                    json.optJSONObject("error")
                        ?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: "Server returned HTTP $status"
                )
            }

            val choices = json.optJSONArray("choices")
                ?: throw Exception("No response from AI server")

            if (choices.length() == 0) {
                throw Exception("AI returned no answer. Try again.")
            }

            return choices.getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()
                .ifEmpty { "I didn't get a reply. Try again." }

        } finally {
            connection.disconnect()
        }
    }

    private fun addMessage(who: String, message: String) {
        chat.addView(TextView(this).apply {
            text = "$who: $message"
            textSize = 16f
            setPadding(8, 8, 8, 8)
        })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
