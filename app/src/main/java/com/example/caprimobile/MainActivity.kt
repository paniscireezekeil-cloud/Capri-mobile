
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
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.CancellationException
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
    private lateinit var scroll: ScrollView

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

        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                chatLayout,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        input = EditText(this).apply {
            hint = "Talk to Capri..."
            maxLines = 3
        }
        root.addView(input)

        sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener { sendMessage() }
        }
        root.addView(sendButton)

        setContentView(root)

        addMessage("Capri", "Hey! I'm here. What do you want to talk about?")
    }

    private fun addMessage(who: String, message: String): TextView {
        val view = TextView(this).apply {
            text = "$who: $message"
            textSize = 16f
            setPadding(8, 8, 8, 8)
        }

        chatLayout.addView(view)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        return view
    }

    private fun sendMessage() {
        if (!sendButton.isEnabled) return

        val message = input.text.toString().trim()
        if (message.isEmpty()) return

        input.text.clear()
        sendButton.isEnabled = false
        addMessage("You", message)

        val replyView = addMessage("Capri", "")
        val fullReply = StringBuilder()

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

        scope.launch {
            try {
                model.generateContentStream(prompt).collect { chunk ->
                    val piece = chunk.text.orEmpty()

                    if (piece.isNotEmpty()) {
                        fullReply.append(piece)

                        replyView.text = "Capri: $fullReply"

                        scroll.post {
                            scroll.fullScroll(ScrollView.FOCUS_DOWN)
                        }
                    }
                }

                val finalReply = fullReply.toString().trim()

                if (finalReply.isNotEmpty()) {
                    history.add("User" to message)
                    history.add("Capri" to finalReply)
                } else {
                    replyView.text =
                        "Capri received no text from the AI. Please try again."
                }

            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val details = error.localizedMessage
                    ?: error.javaClass.simpleName

                replyView.text =
                    if (fullReply.isNotEmpty()) {
                        "Capri: $fullReply\n\n(Connection interrupted: $details)"
                    } else {
                        "Capri couldn't get a response: $details"
                    }
            } finally {
                sendButton.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
