package com.example.caprimobile

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

class MainActivity : Activity() {

    private val black = Color.BLACK
    private val white = Color.WHITE
    private val darkGray = Color.rgb(40, 40, 40)

    private lateinit var overlayButton: Button
    private lateinit var notificationButton: Button
    private lateinit var spotifyAuthButton: Button
    private lateinit var spawnButton: Button

    companion object {
        private const val CLIENT_ID = "51c61988c9464b689446166f26650f3b"
        private const val REDIRECT_URI = "com.example.caprimobile://callback"
        private var codeVerifier: String? = null
    }

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

        root.addView(title, layoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        root.addView(subtitle, layoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))

        overlayButton = createButton("ALLOW FLOATING OVERLAY") { openOverlaySettings() }
        notificationButton = createButton("ALLOW NOTIFICATION ACCESS") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        spotifyAuthButton = createButton("CONNECT SPOTIFY PREMIUM") { authorizeSpotify() }
        spawnButton = createButton("SPAWN CAPRI") { spawnCapri() }

        root.addView(overlayButton, buttonParams())
        root.addView(notificationButton, buttonParams())
        root.addView(spotifyAuthButton, buttonParams())
        root.addView(spawnButton, buttonParams())

        val note = TextView(this).apply {
            text = "Enable permissions & authorize Spotify, then spawn Capri."
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(8))
        }

        root.addView(note, layoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        refreshPermissionStatus()
        handleSpotifyCallback(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleSpotifyCallback(intent)
    }

    private fun authorizeSpotify() {
        val verifier = generateCodeVerifier()
        codeVerifier = verifier
        val challenge = generateCodeChallenge(verifier)

        val scopes = "user-modify-playback-state user-read-playback-state user-read-currently-playing streaming"
        val authUrl = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", scopes)
            .appendQueryParameter("show_dialog", "true")
            .build()

        val intent = Intent(Intent.ACTION_VIEW, authUrl)
        startActivity(intent)
    }

    private fun handleSpotifyCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.toString().startsWith(REDIRECT_URI)) {
            val code = uri.getQueryParameter("code")
            val verifier = codeVerifier
            if (code != null && verifier != null) {
                exchangeCodeForTokens(code, verifier)
            }
        }
    }

    private fun exchangeCodeForTokens(code: String, verifier: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://accounts.spotify.com/api/token")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                val body = "grant_type=authorization_code" +
                        "&code=$code" +
                        "&redirect_uri=${Uri.encode(REDIRECT_URI)}" +
                        "&client_id=$CLIENT_ID" +
                        "&code_verifier=$verifier"

                OutputStreamWriter(conn.outputStream).use { it.write(body) }

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val accessToken = json.getString("access_token")
                    val refreshToken = json.optString("refresh_token", "")

                    val prefs = getSharedPreferences("capri_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("spotify_user_token", accessToken)
                        .putString("spotify_refresh_token", refreshToken)
                        .apply()

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Spotify Re-connected!", Toast.LENGTH_SHORT).show()
                        refreshPermissionStatus()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Token exchange error: ${e.message}", e)
            }
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
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
            val serviceIntent = Intent(this, FloatingCompanionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to spawn Capri", e)
        }
    }

    private fun createButton(label: String, action: () -> Unit): Button {
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

    private fun buttonParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
        topMargin = dp(9)
    }

    private fun layoutParams(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)

    private fun openOverlaySettings() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun refreshPermissionStatus() {
        if (!::overlayButton.isInitialized) return
        overlayButton.text = if (Settings.canDrawOverlays(this)) "FLOATING OVERLAY: ENABLED" else "ALLOW FLOATING OVERLAY"
        notificationButton.text = if (isNotificationAccessEnabled()) "NOTIFICATION ACCESS: ENABLED" else "ALLOW NOTIFICATION ACCESS"

        val prefs = getSharedPreferences("capri_prefs", Context.MODE_PRIVATE)
        val hasSpotifyToken = !prefs.getString("spotify_user_token", "").isNullOrEmpty()

        spotifyAuthButton.text = if (hasSpotifyToken) "SPOTIFY PREMIUM: CONNECTED" else "CONNECT SPOTIFY PREMIUM"
        spawnButton.isEnabled = Settings.canDrawOverlays(this)
        spawnButton.alpha = if (spawnButton.isEnabled) 1f else 0.5f
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    override fun onResume() {
        super.onResume()
        if (::overlayButton.isInitialized) refreshPermissionStatus()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
