
package com.example.caprimobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Capri Mobile"
            textSize = 28f
            gravity = Gravity.CENTER
        }

        val description = TextView(this).apply {
            text = "Your companion, always nearby."
            textSize = 16f
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

        layout.addView(title)
        layout.addView(description)
        layout.addView(permissionButton)
        layout.addView(startButton)
        layout.addView(stopButton)

        setContentView(layout)
    }
}
