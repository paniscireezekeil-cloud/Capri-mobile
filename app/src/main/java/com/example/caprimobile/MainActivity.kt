package com.example.caprimobile

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote

class MainActivity : Activity() {

    private val black = Color.BLACK
    private val white = Color.WHITE
    private val darkGray = Color.rgb(40, 40, 40)

    private lateinit var overlayButton: Button
    private lateinit var notificationButton: Button
    private lateinit var spawnButton: Button

    private val CLIENT_ID = "51c61988c9464b689446166f26650f3b"
    private val REDIRECT_URI = "com.example.caprimobile://callback"

    private var spotifyAppRemote: SpotifyAppRemote? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = black
        window.navigationBarColor = black
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(18), dp(30), dp(18), dp(18))
            setBackgroundColor(black)
        }

        val title = TextView(this).apply {
            text = "CAPRI"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(white)
        }

        val subtitle = TextView(this).apply {
            text = "Your digital companion."
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }

        root.addView(title, layoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
        ))

        root.addView(subtitle, layoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        ))

        overlayButton = createButton("ALLOW FLOATING OVERLAY") {
            openOverlaySettings()
        }

        notificationButton = createButton("ALLOW NOTIFICATION ACCESS") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        spawnButton = createButton("SPAWN CAPRI") {
            spawnCapri()
        }

        root.addView(overlayButton, buttonParams())
        root.addView(notificationButton, buttonParams())
        root.addView(spawnButton, buttonParams())

        val note = TextView(this).apply {
            text = "Enable floating overlay permission, then spawn Capri."
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(8))
        }

        root.addView(note, layoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        refreshPermissionStatus()
    }

    override fun onStart() {
        super.onStart()

        val connectionParams = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()

        try {
            SpotifyAppRemote.connect(
                this,
                connectionParams,
                object : Connector.ConnectionListener {
                    override fun onConnected(appRemote: SpotifyAppRemote) {
                        spotifyAppRemote = appRemote
                        connected()
                    }

                    override fun onFailure(throwable: Throwable) {
                        Log.e(
                            "MainActivity",
                            "Spotify connection failed",
                            throwable
                        )
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Spotify startup failed", e)
        }
    }

    private fun connected() {
        try {
            spotifyAppRemote?.playerApi
                ?.play("spotify:track:7qiZfU4dY1lWllzX7mPBI3")
        } catch (e: Exception) {
            Log.e("MainActivity", "Spotify playback failed", e)
        }
    }

    override fun onStop() {
        spotifyAppRemote?.let {
            try {
                SpotifyAppRemote.disconnect(it)
            } catch (e: Exception) {
                Log.e("MainActivity", "Spotify disconnect failed", e)
            }
        }
        spotifyAppRemote = null
        super.onStop()
    }

    private fun spawnCapri() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }

        if (FloatingCompanionService.isRunning) {
            refreshPermissionStatus()
            return
        }

        try {
            val serviceIntent = Intent(
                this,
                FloatingCompanionService::class.java
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to spawn Capri", e)
        }
    }

    private fun createButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setTextColor(white)

            background = GradientDrawable().apply {
                setColor(darkGray)
                cornerRadius = dp(5).toFloat()
            }

            setOnClickListener { action() }
        }
    }

    private fun buttonParams() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        ).apply {
            topMargin = dp(9)
        }

    private fun layoutParams(width: Int, height: Int) =
        LinearLayout.LayoutParams(width, height)

    private fun openOverlaySettings() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun refreshPermissionStatus() {
        if (!::overlayButton.isInitialized) return

        overlayButton.text =
            if (Settings.canDrawOverlays(this)) {
                "FLOATING OVERLAY: ENABLED"
            } else {
                "ALLOW FLOATING OVERLAY"
            }

        notificationButton.text =
            if (isNotificationAccessEnabled()) {
                "NOTIFICATION ACCESS: ENABLED"
            } else {
                "ALLOW NOTIFICATION ACCESS"
            }

        spawnButton.isEnabled = Settings.canDrawOverlays(this)
        spawnButton.alpha = if (spawnButton.isEnabled) 1f else 0.5f
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return enabled.split(":").any { entry ->
            ComponentName.unflattenFromString(entry)
                ?.packageName == packageName
        }
    }

    override fun onResume() {
        super.onResume()
        if (::overlayButton.isInitialized) {
            refreshPermissionStatus()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
package com.example.caprimobile

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote

class MainActivity : Activity() {

    private val black = Color.BLACK
    private val white = Color.WHITE
    private val darkGray = Color.rgb(40, 40, 40)

    private lateinit var overlayButton: Button
    private lateinit var notificationButton: Button
    private lateinit var spawnButton: Button

    private val CLIENT_ID = "51c61988c9464b689446166f26650f3b"
    private val REDIRECT_URI = "com.example.caprimobile://callback"

    private var spotifyAppRemote: SpotifyAppRemote? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = black
        window.navigationBarColor = black
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(18), dp(30), dp(18), dp(18))
            setBackgroundColor(black)
        }

        val title = TextView(this).apply {
            text = "CAPRI"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(white)
        }

        val subtitle = TextView(this).apply {
            text = "Your digital companion."
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }

        root.addView(title, layoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
        ))

        root.addView(subtitle, layoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        ))

        overlayButton = createButton("ALLOW FLOATING OVERLAY") {
            openOverlaySettings()
        }

        notificationButton = createButton("ALLOW NOTIFICATION ACCESS") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        spawnButton = createButton("SPAWN CAPRI") {
            spawnCapri()
        }

        root.addView(overlayButton, buttonParams())
        root.addView(notificationButton, buttonParams())
        root.addView(spawnButton, buttonParams())

        val note = TextView(this).apply {
            text = "Enable floating overlay permission, then spawn Capri."
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(8))
        }

        root.addView(note, layoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        refreshPermissionStatus()
    }

    override fun onStart() {
        super.onStart()

        val connectionParams = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()

        try {
            SpotifyAppRemote.connect(
                this,
                connectionParams,
                object : Connector.ConnectionListener {
                    override fun onConnected(appRemote: SpotifyAppRemote) {
                        spotifyAppRemote = appRemote
                        connected()
                    }

                    override fun onFailure(throwable: Throwable) {
                        Log.e(
                            "MainActivity",
                            "Spotify connection failed",
                            throwable
                        )
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Spotify startup failed", e)
        }
    }

    private fun connected() {
        try {
            spotifyAppRemote?.playerApi
                ?.play("spotify:track:7qiZfU4dY1lWllzX7mPBI3")
        } catch (e: Exception) {
            Log.e("MainActivity", "Spotify playback failed", e)
        }
    }

    override fun onStop() {
        spotifyAppRemote?.let {
            try {
                SpotifyAppRemote.disconnect(it)
            } catch (e: Exception) {
                Log.e("MainActivity", "Spotify disconnect failed", e)
            }
        }
        spotifyAppRemote = null
        super.onStop()
    }

    private fun spawnCapri() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }

        if (FloatingCompanionService.isRunning) {
            refreshPermissionStatus()
            return
        }

        try {
            val serviceIntent = Intent(
                this,
                FloatingCompanionService::class.java
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to spawn Capri", e)
        }
    }

    private fun createButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setTextColor(white)

            background = GradientDrawable().apply {
                setColor(darkGray)
                cornerRadius = dp(5).toFloat()
            }

            setOnClickListener { action() }
        }
    }

    private fun buttonParams() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        ).apply {
            topMargin = dp(9)
        }

    private fun layoutParams(width: Int, height: Int) =
        LinearLayout.LayoutParams(width, height)

    private fun openOverlaySettings() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun refreshPermissionStatus() {
        if (!::overlayButton.isInitialized) return

        overlayButton.text =
            if (Settings.canDrawOverlays(this)) {
                "FLOATING OVERLAY: ENABLED"
            } else {
                "ALLOW FLOATING OVERLAY"
            }

        notificationButton.text =
            if (isNotificationAccessEnabled()) {
                "NOTIFICATION ACCESS: ENABLED"
            } else {
                "ALLOW NOTIFICATION ACCESS"
            }

        spawnButton.isEnabled = Settings.canDrawOverlays(this)
        spawnButton.alpha = if (spawnButton.isEnabled) 1f else 0.5f
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return enabled.split(":").any { entry ->
            ComponentName.unflattenFromString(entry)
                ?.packageName == packageName
        }
    }

    override fun onResume() {
        super.onResume()
        if (::overlayButton.isInitialized) {
            refreshPermissionStatus()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
