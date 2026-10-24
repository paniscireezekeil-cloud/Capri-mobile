
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
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeBackend
import com.google.firebase.ai.ai
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val history = mutableListOf<Pair<String, String>>()
    private lateinit var chatLayout: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendButton: Button

    private val model by lazy {
        Firebase.ai(
            backend = GenerativeBackend.googleAI()
        ).generativeModel("gemini-3.8-flash")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 16)
        }

        val title = TextView(this).apply {
            text = "Capri Mobile"
            textSize = 26f
            gravity = Gravity.CENTER
        }

        val description = TextView(this).apply {
            text = "Your companion, always nearby."
            textSize = 15f
            gravity = Gravity.CENTER
        }

        val permissionButton = Button(this).apply {
            text = "1. Allow floating overlay"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        val startButton = Button(this).apply {
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
        }

        val stopButton = Button(this).apply {
            text = "3. Dismiss companion"
            setOnClickListener {
                stopService(
                    Intent(
                        this@MainActivity,
                        FloatingCompanionService::class.java
                    )
                )
            }
        }

        val chatTitle = TextView(this).apply {
            text = "Chat with Capri"
            textSize = 20f
            setPadding(0, 12, 0, 8)
        }

        chatLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        val scroll = ScrollView(this).apply {
            addView(
                chatLayout,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        input = EditText(this).apply {
            hint = "Talk to Capri..."
            setSingleLine(false)
            maxLines = 3
        }

        sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener { sendMessage() }
        }

        root.addView(title)
        root.addView(description)
        root.addView(permissionButton)
        root.addView(startButton)
        root.addView(stopButton)
        root.addView(chatTitle)

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

        addMessage(
            "Capri",
            "Hey! I'm here. What do you want to talk about?"
        )
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isEmpty()) return

        input.text.clear()
        sendButton.isEnabled = false

        addMessage("You", message)
        addMessage("Capri", "Thinking...")

        val prompt = buildString {
            append(
                "You are Capri, a friendly, expressive AI companion. " +
                "Speak naturally and warmly. Keep replies conversational. " +
                "You are an AI, not a human.\n\n"
            )

            history.forEach { (role, content) ->
                append("$role: $content\n")
            }

            append("User: $message\nCapri:")
        }

        executor.execute {
            try {
                model.generateContent(prompt)
                    .addOnSuccessListener { response ->
                        runOnUiThread {
                            removeLastMessage()

                            val reply = response.text
                                ?: "I couldn't think of a reply. Try again?"

                            history.add("User" to message)
                            history.add("Capri" to reply)

                            addMessage("Capri", reply)
                            sendButton.isEnabled = true
                        }
                    }
                    .addOnFailureListener { error ->
                        runOnUiThread {
                            removeLastMessage()
                            addMessage(
                                "Capri",
                                "I couldn't connect just now. Check your connection and Firebase setup."
                            )
                            Toast.makeText(
                                this,
                                error.message ?: "AI request failed",
                                Toast.LENGTH_LONG
                            ).show()
                            sendButton.isEnabled = true
                        }
                    }
            } catch (error: Exception) {
                runOnUiThread {
                    removeLastMessage()
                    addMessage(
                        "Capri",
                        "Something went wrong. Please try again."
                    )
                    sendButton.isEnabled = true
                }
            }
        }
    }

    private fun addMessage(who: String, text: String) {
        val view = TextView(this).apply {
            this.text = "$who: $text"
            textSize = 16f
            setPadding(8, 8, 8, 8)
        }
        chatLayout.addView(view)
    }

    private fun removeLastMessage() {
        if (chatLayout.childCount > 0) {
            chatLayout.removeViewAt(chatLayout.childCount - 1)
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
