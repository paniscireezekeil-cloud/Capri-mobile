package com.example.caprimobile

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote

class MainActivity : AppCompatActivity() {

    private val CLIENT_ID = "YOUR_CLIENT_ID" // Replace with your Spotify Client ID if needed
    private val REDIRECT_URI = "com.example.caprimobile://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onStart() {
        super.onStart()
        val connectionParams = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            onConnect(spotifyAppRemote: SpotifyAppRemote) {
                this@MainActivity.spotifyAppRemote = spotifyAppRemote
                Log.d("MainActivity", "Connected to Spotify! Yay!")
                // You can start playing music here
                connected()
            }

            override fun onFailure(throwable: Throwable) {
                Log.e("MainActivity", throwable.message, throwable)
            }
        })
    }

    private fun connected() {
        // Play a test track
        spotifyAppRemote?.playerApi?.play("spotify:track:7qiZfU4dY1lWllzX7mPBI3")
    }

    override fun onStop() {
        super.onStop()
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }
    }
}
