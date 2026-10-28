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
                startActivity(targetIntent)
            } catch (_: Exception) {}
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
