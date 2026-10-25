private fun createCharacterContainer() {
    val density = resources.displayMetrics.density
    val width = (160 * density).toInt()  // Sideways landscape width
    val height = (110 * density).toInt() // Sideways landscape height

    val container = FrameLayout(this).apply {
        // Tactical Yellow Tech Border
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#110A1F"))
            setStroke((2 * density).toInt(), Color.parseColor("#FFCC00"))
            cornerRadius = 4f * density
        }
        // CRITICAL: This forces Android to clip the ImageView inside the rounded border corners
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            clipToOutline = true
        }
    }

    val image = ImageView(this).apply {
        setImageResource(R.drawable.capri_closed)
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
    container.addView(image)

    val tag = TextView(this).apply {
        text = "CAPRI // DORMANT"
        textSize = 9f
        setTextColor(Color.parseColor("#FFCC00"))
        setBackgroundColor(Color.parseColor("#0A0E17"))
        setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (4 * density).toInt()
        }
    }
    container.addView(tag)
    statusTag = tag

    charParams = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 40
        y = 220
    }

    container.setOnTouchListener(object : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    resetIdleTimer()
                    startX = charParams.x
                    startY = charParams.y
                    touchX = e.rawX
                    touchY = e.rawY
                    longPressStart = System.currentTimeMillis()
                    movedDuringTouch = false

                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    longPressRunnable = Runnable {
                        if (!movedDuringTouch && !dismissed) {
                            dismissCompanion()
                        }
                    }
                    handler.postDelayed(longPressRunnable!!, LONG_PRESS_DURATION)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        movedDuringTouch = true
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                    }
                    if (movedDuringTouch) {
                        charParams.x = (startX + dx).coerceAtLeast(0)
                        charParams.y = (startY + dy).coerceAtLeast(0)
                        safeUpdateViewLayout(container, charParams)
                        repositionChat()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    val held = System.currentTimeMillis() - longPressStart

                    if (e.action == MotionEvent.ACTION_UP && !movedDuringTouch && held >= LONG_PRESS_DURATION) {
                        dismissCompanion()
                    } else if (e.action == MotionEvent.ACTION_UP && !movedDuringTouch && !dismissed) {
                        toggleChatWindow()
                    }
                    return true
                }
            }
            return true
        }
    })

    containerView = container
    character = image
    wm.addView(container, charParams)
}
