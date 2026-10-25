
    private fun dismissCompanion() {
        if (dismissed) return
        dismissed = true

        dialogueGeneration++
        glitchRunId++

        typingRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable?.let { handler.removeCallbacks(it) }

        stopMouthAnimation()

        scope.cancel()

        try {
            character?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }

        try {
            chatWindow?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }

        character = null
        bubble = null
        hiddenInput = null
        chatWindow = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun roundedBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = 18 * resources.displayMetrics.density
        }
    }

    private fun createNotification(): Notification {
        val channelId = "capri_companion"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Capri Companion",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Capri's floating companion active."
            }

            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager

            manager.createNotificationChannel(channel)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Capri Mobile")
                .setContentText("Capri is active.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Capri Mobile")
                .setContentText("Capri is active.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        dialogueGeneration++
        glitchRunId++

        typingRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable?.let { handler.removeCallbacks(it) }

        stopMouthAnimation()
        scope.cancel()

        try {
            character?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }

        try {
            chatWindow?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }

        character = null
        bubble = null
        hiddenInput = null
        chatWindow = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
