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

            if (firebaseApp == null) {
                Log.e("CapriAppCheck", "Firebase initialization failed")
                return
            }

            FirebaseAppCheck.getInstance(firebaseApp)
                .installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )

            Log.d("CapriAppCheck", "Debug App Check provider installed")
        } catch (e: Exception) {
            Log.e("CapriAppCheck", "Failed to initialize App Check: ${e.message}", e)
        }
    }
}
