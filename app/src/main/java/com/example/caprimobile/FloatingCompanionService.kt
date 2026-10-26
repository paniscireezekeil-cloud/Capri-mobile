    private fun showDialogue(state: String, userMsg: String?, botMsg: String?) {
        val dialogueView = dialogueBubble ?: return
        val queryView = userQueryText ?: return
        val panel = chatWindow ?: return
        if (dismissed) return

        typingRunnable?.let { handler.removeCallbacks(it) }
        typingRunnable = null
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
        stopMouthAnimation()

        dialogueGeneration++
        val generation = dialogueGeneration
        glitchRunId++

        statusTag?.text = "CAPRI // $state"
        panel.alpha = 1f
        panel.visibility = View.VISIBLE

        if (userMsg != null) {
            queryView.text = userMsg
            queryView.visibility = View.VISIBLE
        } else {
            queryView.visibility = View.GONE
        }

        val targetText = botMsg ?: ""
        dialogueView.text = ""
        dialogueView.requestLayout()
        repositionChat()

        var charIndex = 0
        var mouthState = false

        val typingTask = object : Runnable {
            override fun run() {
                if (generation != dialogueGeneration || dismissed) return
                if (charIndex < targetText.length) {
                    val currentChar = targetText[charIndex]
                    dialogueView.append(currentChar.toString())
                    
                    // Force the view and panel to measure and draw immediately so text doesn't vanish
                    dialogueView.invalidate()
                    panel.requestLayout()
                    
                    charIndex++

                    if (!currentChar.isWhitespace()) {
                        mouthState = !mouthState
                        setMouthOpen(mouthState)
                    } else {
                        setMouthOpen(false)
                        mouthState = false
                    }

                    typingRunnable = this
                    handler.postDelayed(this, TYPING_SPEED)
                } else {
                    typingRunnable = null
                    setMouthOpen(false)

                    // Ensure final text is fully drawn
                    dialogueView.invalidate()
                    panel.requestLayout()

                    // Wait 3 full seconds after typing completes before triggering the glitch out
                    val timer = Runnable {
                        if (generation == dialogueGeneration && !dismissed) {
                            glitchDialogue()
                        }
                    }
                    fadeRunnable = timer
                    handler.postDelayed(timer, DIALOGUE_DELAY)
                }
            }
        }
        typingRunnable = typingTask
        handler.post(typingTask)
    }
