package com.example.caprimobile

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private val CYAN = 0xFF22E6FF.toInt()
private val YELLOW = 0xFFFFE14D.toInt()
private val RED = 0xFFE8194B.toInt()
private val GREEN = 0xFF2BFF7A.toInt()
private val PURPLE = 0xFFA66BFF.toInt()
private val MAGENTA = 0xFFFF2BD6.toInt()
private val BG = 0xFF070B14.toInt()
private val DIM = 0xFF8A93A6.toInt()

class CapriHudView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    enum class State(val label: String, val color: Int) {
        DORMANT("CAPRI // DORMANT", PURPLE),
        LISTENING("CAPRI // LISTENING", CYAN),
        THINKING("CAPRI // THINKING", YELLOW),
        GLITCH("", CYAN),
        TALKING("CAPRI // TALKING", YELLOW),
        ONLINE("CAPRI // ONLINE", CYAN)
    }

    var portrait: Drawable? = null
        set(v) { field = v; invalidate() }

    var state = State.DORMANT
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val t0 = SystemClock.uptimeMillis()
    private var stateStart = t0
    private var userLine = ""
    private var reply = ""
    private var onlineScheduled = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; color = Color.WHITE
    }
    private val path = Path()

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private val W get() = dp(300f)
    private val H get() = dp(250f)
    private val bubbleH get() = dp(104f)
    private val tailH get() = dp(10f)

    override fun onMeasure(wSpec: Int, hSpec: Int) =
        setMeasuredDimension(W.toInt(), H.toInt())

    // ---------- public API ----------

    fun sleep() { cancel(); go(State.DORMANT) }
    fun listen() { cancel(); go(State.LISTENING) }
    fun think() { cancel(); go(State.THINKING) }

    fun respond(userText: String, replyText: String) { cancel(); startRespond(userText, replyText) }

    private fun startRespond(userText: String, replyText: String) {
        userLine = userText; reply = replyText
        go(State.GLITCH)
        handler.postDelayed({ go(State.TALKING) }, 450)
    }

    fun demo(userText: String = "Hello Capri.",
             replyText: String = "Hey choom! Net's been quiet, too quiet. Time to stir up some trouble.") {
        cancel(); go(State.LISTENING)
        handler.postDelayed({ go(State.THINKING) }, 1600)
        handler.postDelayed({ startRespond(userText, replyText) }, 3200)
    }

    private fun cancel() = handler.removeCallbacksAndMessages(null)

    private fun go(s: State) {
        state = s; stateStart = SystemClock.uptimeMillis(); onlineScheduled = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    // ---------- drawing ----------

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - t0) / 1000f
        when (state) {
            State.DORMANT, State.LISTENING, State.THINKING -> drawIdle(c, t)
            State.GLITCH -> drawGlitch(c, now)
            State.TALKING, State.ONLINE -> drawTalking(c, now)
        }
        postInvalidateOnAnimation()
    }

    private fun drawIdle(c: Canvas, t: Float) {
        val spin = when (state) { State.DORMANT -> 20f; State.LISTENING -> 60f; else -> 220f } * t
        val cx = W / 2; val cy = H / 2 - dp(6f)
        drawSprite(c, cx, cy, t, spin, flicker = state == State.THINKING)

        label.textSize = dp(9.5f)
        val r = chip(c, state.label, state.color, cx, cy + dp(44f), centered = true)
        if (state == State.LISTENING) {
            for (i in 0 until 5) {
                val a = ((sin(t * 6f - i) + 1f) / 2f * 200 + 55).toInt()
                fill.color = CYAN; fill.alpha = a
                c.drawCircle(r.right + dp(8f) + i * dp(7f), r.centerY(), dp(1.6f), fill)
            }
            fill.alpha = 255
        }
    }

    private fun drawSprite(c: Canvas, cx: Float, cy: Float, t: Float, spin: Float, flicker: Boolean) {
        val swap = flicker && ((t * 8).toInt() % 2 == 0)
        c.save(); c.translate(cx, cy); c.rotate(spin)
        for (i in 0 until 8) {
            val len = dp(if (i % 2 == 0) 28f else 21f) * (0.85f + 0.15f * sin(t * 3f + i))
            c.save(); c.rotate(i * 45f)
            path.reset()
            path.moveTo(dp(9f), -dp(4.5f)); path.lineTo(len, 0f); path.lineTo(dp(9f), dp(5f)); path.close()
            fill.color = if ((i % 2 == 0) != swap) RED else GREEN
            c.drawPath(path, fill)
            c.restore()
        }
        c.restore()
        c.save(); c.translate(cx, cy)
        path.reset()
        val d = dp(11f)
        path.moveTo(0f, -d); path.lineTo(d, 0f); path.lineTo(0f, d); path.lineTo(-d, 0f); path.close()
        fill.color = 0xFF0A0614.toInt(); c.drawPath(path, fill)
        stroke.color = PURPLE; stroke.strokeWidth = dp(1.5f); c.drawPath(path, stroke)
        stroke.color = CYAN; c.drawCircle(0f, 0f, dp(6.5f), stroke)
        fill.color = 0xFF2A6BFF.toInt(); c.drawCircle(0f, 0f, dp(4.5f), fill)
        fill.color = Color.WHITE; c.drawCircle(-dp(1.2f), -dp(1.2f), dp(1.4f), fill)
        c.restore()
    }

    private fun drawGlitch(c: Canvas, now: Long) {
        val rnd = Random(now / 50)
        stroke.color = CYAN; stroke.strokeWidth = dp(1.5f)
        c.drawLine(W * 0.15f, H * 0.42f, W * 0.85f, H * 0.42f, stroke)
        repeat(18) {
            val w = rnd.nextFloat() * W * 0.5f
            val x = W * 0.15f + rnd.nextFloat() * (W * 0.7f - w)
            val y = H * 0.38f + rnd.nextFloat() * H * 0.2f
            fill.color = if (rnd.nextBoolean()) CYAN else MAGENTA
            fill.alpha = 60 + rnd.nextInt(160)
            c.drawRect(x, y, x + w, y + dp(1.2f), fill)
        }
        fill.alpha = 255
    }

    private fun drawTalking(c: Canvas, now: Long) {
        val accent = state.color
        val since = now - stateStart

        // portrait frame
        val fr = RectF(dp(20f), bubbleH + tailH, W - dp(20f), H - dp(4f))
        fill.color = BG; c.drawRect(fr, fill)
        portrait?.let { d ->
            val iw = d.intrinsicWidth.toFloat(); val ih = d.intrinsicHeight.toFloat()
            if (iw > 0 && ih > 0) {
                val s = max(fr.width() / iw, fr.height() / ih)
                val w = iw * s; val h = ih * s
                c.save(); c.clipRect(fr)
                d.setBounds((fr.centerX() - w / 2).toInt(), (fr.centerY() - h / 2).toInt(),
                    (fr.centerX() + w / 2).toInt(), (fr.centerY() + h / 2).toInt())
                d.draw(c)
                fill.color = Color.BLACK; fill.alpha = 45
                var y = fr.top
                while (y < fr.bottom) { c.drawRect(fr.left, y, fr.right, y + dp(1f), fill); y += dp(3f) }
                fill.alpha = 255
                c.restore()
            }
        }
        stroke.color = accent; stroke.strokeWidth = dp(1f); c.drawRect(fr, stroke)
        stroke.strokeWidth = dp(3f)
        val k = dp(14f)
        for ((x, y, sx, sy) in listOf(
            arrayOf(fr.left, fr.top, 1f, 1f), arrayOf(fr.right, fr.top, -1f, 1f),
            arrayOf(fr.left, fr.bottom, 1f, -1f), arrayOf(fr.right, fr.bottom, -1f, -1f))) {
            c.drawLine(x, y, x + sx * k, y, stroke); c.drawLine(x, y, x, y + sy * k, stroke)
        }
        label.textSize = dp(9.5f)
        chip(c, state.label, accent, fr.left + dp(14f), fr.top - dp(8f), centered = false)

        // speech bubble
        val b = RectF(dp(2f), dp(2f), W - dp(2f), bubbleH)
        val cut = dp(10f); val tx = W * 0.65f
        path.reset()
        path.moveTo(b.left, b.top); path.lineTo(b.right - cut, b.top); path.lineTo(b.right, b.top + cut)
        path.lineTo(b.right, b.bottom); path.lineTo(tx + dp(8f), b.bottom)
        path.lineTo(tx + dp(3f), b.bottom + tailH); path.lineTo(tx - dp(4f), b.bottom)
        path.lineTo(b.left + cut, b.bottom); path.lineTo(b.left, b.bottom - cut); path.close()
        fill.color = BG; fill.alpha = 235; c.drawPath(path, fill); fill.alpha = 255
        stroke.color = CYAN; stroke.strokeWidth = dp(1.5f); c.drawPath(path, stroke)

        fill.color = RED
        c.drawRect(dp(12f), dp(14f), dp(14f), bubbleH - dp(12f), fill)

        val tx0 = dp(22f)
        body.textSize = dp(10.5f); body.color = DIM
        body.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
        c.drawText("> you: $userLine", tx0, dp(22f), body)

        body.textSize = dp(11f); body.color = YELLOW
        body.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        c.drawText("CAPRI >", tx0, dp(40f), body)

        body.textSize = dp(12f); body.color = Color.WHITE
        body.typeface = Typeface.MONOSPACE
        val typed = min(reply.length, (since / 35).toInt())
        val shown = reply.substring(0, typed)
        val lw = (W - tx0 - dp(14f)).toInt()
        val cursorOn = (now / 500) % 2 == 0L
        var cursorX = tx0; var cursorY = dp(48f)
        if (shown.isNotEmpty()) {
            val lay = StaticLayout.Builder.obtain(shown, 0, shown.length, body, lw)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(3).build()
            c.save(); c.translate(tx0, dp(48f)); lay.draw(c); c.restore()
            val last = lay.lineCount - 1
            cursorX = tx0 + lay.getLineRight(last)
            cursorY = dp(48f) + lay.getLineTop(last)
        }
        if (typed < reply.length || cursorOn) {
            fill.color = Color.WHITE
            c.drawRect(cursorX + dp(1f), cursorY + dp(2f), cursorX + dp(8f), cursorY + dp(15f), fill)
        }

        if (state == State.TALKING && typed >= reply.length && !onlineScheduled) {
            onlineScheduled = true
            handler.postDelayed({ if (state == State.TALKING) go(State.ONLINE) }, 700)
        }
    }

    private fun chip(c: Canvas, text: String, color: Int, x: Float, y: Float, centered: Boolean): RectF {
        val w = label.measureText(text); val padX = dp(6f); val h = dp(16f)
        val left = if (centered) x - w / 2 - padX else x
        val r = RectF(left, y, left + w + padX * 2, y + h)
        fill.color = BG; c.drawRect(r, fill)
        stroke.color = color; stroke.strokeWidth = dp(1f); c.drawRect(r, stroke)
        label.color = color
        c.drawText(text, r.left + padX, r.centerY() - (label.ascent() + label.descent()) / 2, label)
        return r
    }
}