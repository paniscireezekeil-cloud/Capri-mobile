package com.example.caprimobile

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class TrampolineActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetIntent = intent.getParcelableExtra<Intent>("TARGET_INTENT")
        if (targetIntent != null) {
            try {
                // Pass flags so Spotify parses the URI without user action animations
                targetIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
                )
                startActivity(targetIntent)
            } catch (_: Exception) {}
        }

        // Push task to back instantly rather than returning home
        moveTaskToBack(true)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
