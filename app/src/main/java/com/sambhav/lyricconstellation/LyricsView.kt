package com.sambhav.lyricconstellation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.min
import kotlin.random.Random

class LyricsView(ctx: Context) : View(ctx) {
    var hasAccess = false
    var onNeedAccess: (() -> Unit)? = null

    /** -1 = shuffle all styles, 0..4 = lock to one style. Long-press cycles. */
    private var mode = -1

    private val np = NowPlaying(ctx)
    private var lines: List<Line> = emptyList()
    private var state = 0 // 0 nothing playing, 1 loading, 2 ready, 3 no lyrics
    private var trackKey = ""
    private var lastPoll = 0L

    private var current: Scene? = null
    private var previous: Scene? = null
    private var curIdx = -2
    private var curStyle = 0
    private var curSeed = 0L
    private var fadeStart = 0L

    private val bag = ArrayDeque<Int>()
    private var lastStyle = -1

    private var toast = ""
    private var toastAt = 0L

    private val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Fonts.medium }

    init {
        setBackgroundColor(Color.BLACK)
        setOnClickListener { if (!hasAccess) onNeedAccess?.invoke() }
        setOnLongClickListener { cycleMode(); true }
    }

    private fun nextStyle(): Int {
        if (mode >= 0) return mode
        if (bag.isEmpty()) {
            // Constellation twice as likely: it is the signature look.
            val l = listOf(0, 0, 1, 2, 3, 4).shuffled().toMutableList()
            if (l.first() == lastStyle) { val f = l.removeAt(0); l.add(f) }
            bag.addAll(l)
        }
        val s = bag.removeFirst()
        lastStyle = s
        return s
    }

    private fun cycleMode() {
        mode = if (mode >= STYLE_NAMES.size - 1) -1 else mode + 1
        toast = if (mode < 0) "Shuffle" else STYLE_NAMES[mode]
        toastAt = SystemClock.uptimeMillis()
        if (mode >= 0) curStyle = mode
        rebuildCurrent()
    }

    private fun rebuildCurrent() {
        previous = null
        current = lines.getOrNull(curIdx)?.takeIf { width > 0 }?.let {
            makeScene(curStyle, it, width.toFloat(), height.toFloat(), curSeed)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildCurrent() // fold / unfold / rotate
    }

    private fun indexAt(t: Long): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val m = (lo + hi) / 2
            if (lines[m].startMs <= t) { ans = m; lo = m + 1 } else hi = m - 1
        }
        if (ans >= 0 && t > lines[ans].endMs + 2500L) return -1
        return ans
    }

    private fun pollTrack() {
        np.refresh()
        val tr = np.track
        val key = tr?.key ?: ""
        if (key == trackKey) return
        trackKey = key
        lines = emptyList(); current = null; previous = null; curIdx = -2
        if (tr == null) { state = 0; return }
        state = 1
        val app = context.applicationContext
        Thread {
            val ls = try { Lrc.fetch(app, tr) } catch (e: Exception) { null }
            post {
                if (trackKey == key) {
                    if (ls.isNullOrEmpty()) state = 3 else { lines = ls; state = 2 }
                }
            }
        }.start()
    }

    private fun message(c: Canvas, u: Float, big: String, small: String = "") {
        msgPaint.col(0xFF8A8A8A.toInt(), 1f)
        msgPaint.textSize = 38f * u
        c.drawText(big, width / 2f, height / 2f, msgPaint)
        if (small.isNotEmpty()) {
            msgPaint.col(0xFF555555.toInt(), 1f)
            msgPaint.textSize = 26f * u
            var y = height / 2f + 60f * u
            for (ln in small.split("\n")) { c.drawText(ln, width / 2f, y, msgPaint); y += 38f * u }
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val now = SystemClock.uptimeMillis()
        val u = min(width, height) / 1000f
        if (hasAccess && now - lastPoll > 300L) { lastPoll = now; pollTrack() }

        if (!hasAccess) {
            message(c, u, "Tap to allow notification access",
                "Needed to see what song is playing.\nIf the switch is greyed out: App info > ⋮ > Allow restricted settings.")
        } else if (np.track == null) {
            message(c, u, "Play a song in Spotify or YT Music")
        } else when (state) {
            1 -> message(c, u, "Finding lyrics…")
            3 -> message(c, u, "No synced lyrics for this song")
            2 -> drawLyrics(c, now)
        }

        np.track?.let {
            val s = "${it.title} — ${it.artist}"
            msgPaint.col(0xFF777777.toInt(), 0.5f)
            msgPaint.textSize = 20f * u
            val n = msgPaint.breakText(s, true, width * 0.8f, null)
            c.drawText(s.take(n), width / 2f, height - 48f * u, msgPaint)
        }
        if (toast.isNotEmpty() && now - toastAt < 2000L) {
            msgPaint.col(0xFFCCCCCC.toInt(), 0.8f)
            msgPaint.textSize = 26f * u
            c.drawText(toast, width / 2f, 90f * u, msgPaint)
        }
        postInvalidateOnAnimation()
    }

    private fun drawLyrics(c: Canvas, now: Long) {
        val t = np.positionMs()
        val idx = indexAt(t)
        if (idx != curIdx) {
            if (current != null) { previous = current; fadeStart = now }
            curIdx = idx
            if (idx >= 0) {
                curStyle = nextStyle()
                curSeed = Random.nextLong()
                current = makeScene(curStyle, lines[idx], width.toFloat(), height.toFloat(), curSeed)
            } else {
                current = null
            }
        }
        previous?.let {
            val p = (now - fadeStart) / 500f
            if (p >= 1f) previous = null else it.draw(c, it.line.endMs, 1f - p)
        }
        current?.draw(c, t, 1f)
    }
}
