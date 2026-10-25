package com.example.caprimobile

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class SpotifyListenerService : NotificationListenerService() {

    companion object {
        var currentSong: String = "Nothing playing"
            private set
        var songChanged: Boolean = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn?.packageName == "com.spotify.music") {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val artist = extras.getCharSequence("android.text")?.toString() ?: ""
            
            if (title.isNotBlank() && artist.isNotBlank()) {
                val newTrack = "$title by $artist"
                if (newTrack != currentSong) {
                    currentSong = newTrack
                    songChanged = true // Signal to Capri that a new song started
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName == "com.spotify.music") {
            currentSong = "Nothing playing"
            songChanged = false
        }
    }
}
