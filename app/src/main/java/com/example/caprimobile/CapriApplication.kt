package com.example.caprimobile

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

class CapriApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        try {
            val firebaseApp = FirebaseApp.initializeApp(this)
            if (firebaseApp != null) {
                FirebaseAppCheck.getInstance(firebaseApp)
                    .installAppCheckProviderFactory(
                        DebugAppCheckProviderFactory.getInstance()
                    )
            }
        } catch (e: Exception) {
            Log.e("CapriAppCheck", "Init failed: ${e.message}")
        }
    }
}
