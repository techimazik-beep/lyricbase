package com.sambhav.lyricconstellation

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

val ACCENTS = intArrayOf(
    0xFFFF9DB8.toInt(), 0xFF8FE3FF.toInt(), 0xFFFFD27A.toInt(), 0xFFC3A6FF.toInt(), 0xFF9BFFD0.toInt()
)
val STYLE_NAMES = listOf("Constellation", "Orbit", "Wave", "Kinetic", "Spotlight")

object Fonts {
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val black: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
}

fun Paint.col(color: Int, a: Float): Paint {
    this.color = color
    this.alpha = (a.coerceIn(0f, 1f) * 255f).toInt()
    return this
}

fun appear(t: Long, start: Long, dur: Long = 320L): Float = ((t - start).toFloat() / dur).coerceIn(0f, 1f)

fun ease(p: Float): Float { val q = 1f - p; return 1f - q * q * q }

fun activeIdx(line: Line, t: Long): Int {
    var a = -1
    for (i in line.words.indices) { if (line.words[i].startMs <= t) a = i else break }
    return a
}

fun makeScene(style: Int, line: Line, w: Float, h: Float, seed: Long): Scene {
    val accent = ACCENTS[Random(seed).nextInt(ACCENTS.size)]
    return when (style) {
        0 -> ConstellationScene(line, w, h, accent, seed)
        1 -> OrbitScene(line, w, h, accent, seed)
        2 -> WaveScene(line, w, h, accent, seed)
        3 -> KineticScene(line, w, h, accent, seed)
        else -> SpotlightScene(line, w, h, accent, seed)
    }
}

abstract class Scene(val line: Line, protected val w: Float, protected val h: Float, protected val accent: Int) {
    protected val u: Float = min(w, h) / 1000f
    /** t = song position in ms, a = overall opacity (used for fading out). */
    abstract fun draw(c: Canvas, t: Long, a: Float)

    protected fun glowAt(c: Canvas, p: Paint, x: Float, y: Float, r: Float, a: Float) {
        p.shader = RadialGradient(
            x, y, r,
            intArrayOf((accent and 0xFFFFFF) or 0x73000000, accent and 0xFFFFFF),
            null, Shader.TileMode.CLAMP
        )
        p.alpha = (a.coerceIn(0f, 1f) * 255f).toInt()
        c.drawCircle(x, y, r, p)
    }
}

class Wrapped(val xs: FloatArray, val ys: FloatArray, val size: Float, val totalH: Float)

fun wrapWords(
    words: List<Word>, p: Paint, maxW: Float, maxH: Float,
    startSize: Float, minSize: Float, center: Boolean, gap: Float
): Wrapped {
    val n = words.size
    var size = startSize
    while (true) {
        p.textSize = size
        val space = p.measureText(" ")
        val ws = FloatArray(n) { p.measureText(words[it].text) }
        val rowOf = IntArray(n)
        val xr = FloatArray(n)
        val rowW = ArrayList<Float>()
        var row = 0
        var x = 0f
        for (i in 0 until n) {
            if (x > 0f && x + ws[i] > maxW) { rowW.add(x - space); row++; x = 0f }
            rowOf[i] = row
            xr[i] = x
            x += ws[i] + space
        }
        rowW.add(x - space)
        val totalH = (row + 1) * size * gap
        val widest = ws.maxOrNull() ?: 0f
        if ((totalH <= maxH && widest <= maxW) || size <= minSize) {
            val xs = FloatArray(n) { xr[it] + if (center) (maxW - rowW[rowOf[it]]) / 2f else 0f }
            val ys = FloatArray(n) { rowOf[it] * size * gap + size * 0.85f }
            return Wrapped(xs, ys, size, totalH)
        }
        size *= 0.93f
    }
}

// ───────────────────────── 1. Constellation ─────────────────────────

class ConstellationScene(line: Line, w: Float, h: Float, accent: Int, seed: Long) : Scene(line, w, h, accent) {
    private val n = line.words.size
    private val px = FloatArray(n)
    private val py = FloatArray(n)
    private val big = BooleanArray(n)
    private val edges = ArrayList<IntArray>()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * u; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dust = FloatArray(80)
    private val unitStar = Path()

    init {
        for (k in 0 until 8) {
            val rad = if (k % 2 == 1) 0.28f else 1f
            val ang = k * PI.toFloat() / 4f - PI.toFloat() / 2f
            val x = cos(ang) * rad
            val y = sin(ang) * rad
            if (k == 0) unitStar.moveTo(x, y) else unitStar.lineTo(x, y)
        }
        unitStar.close()

        val r = Random(seed)
        val vertical = h > w * 1.1f
        val along = if (vertical) h else w
        val cross = if (vertical) w else h
        val m = along * 0.1f
        text.typeface = Fonts.bold
        for (i in 0 until n) {
            big[i] = line.words[i].text.length >= 5 && r.nextFloat() > 0.35f
            text.textSize = (if (big[i]) 64f else 30f) * u
            val tw = text.measureText(line.words[i].text)
            val a = m + (along - 2 * m) * (if (n == 1) 0.5f else i / (n - 1f)) +
                (r.nextFloat() - 0.5f) * (along - 2 * m) / n * 0.7f
            val cr = cross * (0.5f + (r.nextFloat() - 0.5f) * 0.62f)
            if (vertical) {
                px[i] = cr.coerceAtLeast(w * 0.08f).coerceAtMost(w - w * 0.08f - tw)
                py[i] = a
            } else {
                px[i] = a.coerceAtLeast(m).coerceAtMost(w - m - tw)
                py[i] = cr
            }
        }
        for (i in 0 until n - 1) edges.add(intArrayOf(i, i + 1))
        for (i in 0 until n - 2) if (r.nextFloat() > 0.55f) edges.add(intArrayOf(i, i + 2))
        for (k in dust.indices) dust[k] = r.nextFloat()
    }

    private fun star(c: Canvas, x: Float, y: Float, s: Float, color: Int, alpha: Float) {
        c.save(); c.translate(x, y); c.scale(s, s)
        fill.col(color, alpha)
        c.drawPath(unitStar, fill)
        c.restore()
    }

    override fun draw(c: Canvas, t: Long, a: Float) {
        fill.col(Color.WHITE, 0.22f * a)
        for (k in 0 until dust.size / 2) {
            val x = dust[2 * k] * w
            val y = dust[2 * k + 1] * h
            c.drawRect(x, y, x + 1.5f * u, y + 1.5f * u, fill)
        }
        val ai = activeIdx(line, t)
        if (ai < 0) return
        stroke.col(0xFFD2D7FF.toInt(), 0.75f * a)
        for (e in edges) {
            val k = ease(appear(t, line.words[e[1]].startMs, 300L))
            if (k <= 0f) continue
            val ax = px[e[0]]; val ay = py[e[0]]
            c.drawLine(ax, ay, ax + (px[e[1]] - ax) * k, ay + (py[e[1]] - ay) * k, stroke)
        }
        for (i in 0..ai) {
            val p = ease(appear(t, line.words[i].startMs, 320L))
            val act = i == ai
            val x = px[i]; val y = py[i]
            if (act) glowAt(c, glow, x, y, 70f * u, a * p)
            star(c, x, y, (if (act) 20f else 12f) * u * (0.4f + 0.6f * p), if (act) accent else 0xFFE8EBFF.toInt(), p * a)
            text.typeface = if (big[i] || act) Fonts.bold else Fonts.medium
            text.textSize = (if (big[i]) 64f else if (act) 46f else 30f) * u
            text.col(if (act) Color.WHITE else 0xFFC9CDE8.toInt(), min(1f, p * 1.4f) * a)
            c.drawText(line.words[i].text, x - (if (big[i]) 10f * u else 0f), y + (if (big[i]) 70f else 44f) * u, text)
        }
    }
}

// ───────────────────────── 2. Orbit ─────────────────────────

class OrbitScene(line: Line, w: Float, h: Float, accent: Int, seed: Long) : Scene(line, w, h, accent) {
    private val n = line.words.size
    private val frac = FloatArray(n)
    private val ang = FloatArray(n)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * u }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        val base = Random(seed).nextFloat() * 6.28f
        for (i in 0 until n) {
            frac[i] = sqrt((i + 0.7f) / (n + 0.7f))
            ang[i] = base + i * 2.39996f
        }
    }

    override fun draw(c: Canvas, t: Long, a: Float) {
        val ai = activeIdx(line, t)
        if (ai < 0) return
        val cx = w / 2f; val cy = h / 2f
        val rx = w * 0.38f; val ry = h * 0.30f
        val rot = t * 0.00006f
        fill.col(accent, 0.9f * a)
        c.drawCircle(cx, cy, 5f * u, fill)
        for (i in 0..ai) {
            val p = ease(appear(t, line.words[i].startMs, 380L))
            val act = i == ai
            val f = frac[i]
            val th = ang[i] + rot
            val x = cx + rx * f * cos(th)
            val y = cy + ry * f * sin(th)
            rect.set(cx - rx * f, cy - ry * f, cx + rx * f, cy + ry * f)
            ring.col(Color.WHITE, 0.13f * p * a)
            c.drawOval(rect, ring)
            ring.col(accent, (if (act) 0.55f else 0.12f) * p * a)
            c.drawLine(cx, cy, x, y, ring)
            if (act) glowAt(c, glow, x, y, 60f * u, a * p)
            fill.col(if (act) accent else 0xFFE8EBFF.toInt(), p * a)
            c.drawCircle(x, y, (if (act) 9f else 5f) * u * p, fill)
            text.typeface = if (act) Fonts.bold else Fonts.medium
            text.textSize = (if (act) 52f else 30f) * u
            text.col(if (act) Color.WHITE else 0xFFB5B9D0.toInt(), min(1f, p * 1.4f) * a)
            val tw = text.measureText(line.words[i].text) / 2f
            val tx = x.coerceIn(tw + 20f * u, w - tw - 20f * u)
            c.drawText(line.words[i].text, tx, y - (if (act) 22f else 16f) * u, text)
        }
    }
}

// ───────────────────────── 3. Wave ─────────────────────────

class WaveScene(line: Line, w: Float, h: Float, accent: Int, seed: Long) : Scene(line, w, h, accent) {
    private val n = line.words.size
    private val px = FloatArray(n)
    private val py = FloatArray(n)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        val r = Random(seed)
        val vertical = h > w * 1.1f
        val along = if (vertical) h else w
        val cross = if (vertical) w else h
        val m = along * 0.1f
        val phase = r.nextFloat() * 6.28f
        for (i in 0 until n) {
            val a = m + (along - 2 * m) * (if (n == 1) 0.5f else i / (n - 1f))
            val cr = cross * 0.5f + cross * 0.2f * sin(i * 1.15f + phase) * (0.7f + 0.5f * r.nextFloat())
            if (vertical) { px[i] = cr; py[i] = a } else { px[i] = a; py[i] = cr }
        }
    }

    override fun draw(c: Canvas, t: Long, a: Float) {
        val ai = activeIdx(line, t)
        if (ai < 0) return
        if (ai >= 1) {
            path.reset()
            path.moveTo(px[0], py[0])
            var ex = px[0]; var ey = py[0]
            for (i in 1 until ai) {
                ex = (px[i] + px[i + 1]) / 2f; ey = (py[i] + py[i + 1]) / 2f
                path.quadTo(px[i], py[i], ex, ey)
            }
            val k = ease(appear(t, line.words[ai].startMs, 350L))
            path.lineTo(ex + (px[ai] - ex) * k, ey + (py[ai] - ey) * k)
            stroke.strokeWidth = 10f * u
            stroke.col(accent, 0.16f * a)
            c.drawPath(path, stroke)
            stroke.strokeWidth = 3f * u
            stroke.col(accent, 0.95f * a)
            c.drawPath(path, stroke)
        }
        for (i in 0..ai) {
            val p = ease(appear(t, line.words[i].startMs, 350L))
            val act = i == ai
            val x = px[i]; val y = py[i]
            if (act) {
                val span = (line.endMs - line.words[i].startMs).coerceAtLeast(1L)
                val ph = ((t - line.words[i].startMs).toFloat() / span).coerceIn(0f, 1f)
                stroke.strokeWidth = 2f * u
                stroke.col(accent, (1f - ph) * 0.8f * a)
                c.drawCircle(x, y, (10f + 26f * ph) * u, stroke)
            }
            fill.col(if (act) accent else Color.WHITE, p * a)
            c.drawCircle(x, y, (if (act) 10f else 6f) * u * p, fill)
            text.typeface = if (act) Fonts.bold else Fonts.medium
            text.textSize = (if (act) 54f else 34f) * u
            text.col(if (act) Color.WHITE else 0xFFD5D8EA.toInt(), min(1f, p * 1.4f) * a)
            val tw = text.measureText(line.words[i].text) / 2f
            val tx = x.coerceIn(tw + 20f * u, w - tw - 20f * u)
            val up = i % 2 == 0
            c.drawText(line.words[i].text, tx, if (up) y - 30f * u else y + 62f * u, text)
        }
    }
}

// ───────────────────────── 4. Kinetic ─────────────────────────

class KineticScene(line: Line, w: Float, h: Float, accent: Int, seed: Long) : Scene(line, w, h, accent) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.black }
    private val wrapped = wrapWords(line.words, paint, w * 0.84f, h * 0.70f, 260f * u, 30f * u, false, 1.06f)
    private val x0 = w * 0.08f
    private val y0 = (h - wrapped.totalH) / 2f

    override fun draw(c: Canvas, t: Long, a: Float) {
        val ai = activeIdx(line, t)
        paint.textSize = wrapped.size
        for (i in 0..ai) {
            val p = ease(appear(t, line.words[i].startMs, 420L))
            paint.col(if (i == ai) accent else Color.WHITE, p * a)
            c.drawText(line.words[i].text, x0 + wrapped.xs[i], y0 + wrapped.ys[i] + (1f - p) * 0.3f * wrapped.size, paint)
        }
    }
}

// ───────────────────────── 5. Spotlight ─────────────────────────

class SpotlightScene(line: Line, w: Float, h: Float, accent: Int, seed: Long) : Scene(line, w, h, accent) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold }
    private val wrapped = wrapWords(line.words, paint, w * 0.80f, h * 0.60f, 120f * u, 36f * u, true, 1.25f)
    private val x0 = w * 0.10f
    private val y0 = (h - wrapped.totalH) / 2f

    override fun draw(c: Canvas, t: Long, a: Float) {
        val ai = activeIdx(line, t)
        paint.textSize = wrapped.size
        for (i in line.words.indices) {
            val word = line.words[i]
            val x = x0 + wrapped.xs[i]
            val y = y0 + wrapped.ys[i]
            if (i > ai) {
                paint.col(0xFF4A4A4A.toInt(), a)
                c.drawText(word.text, x, y, paint)
                continue
            }
            val p = appear(t, word.startMs, 420L)
            val act = i == ai
            val s = 1f + 0.12f * sin(PI.toFloat() * p)
            val cx = x + paint.measureText(word.text) / 2f
            val cy = y - wrapped.size * 0.3f
            c.save()
            c.scale(s, s, cx, cy)
            if (act) paint.setShadowLayer(28f * u, 0f, 0f, accent)
            paint.col(if (act) Color.WHITE else 0xFFE6E6E6.toInt(), a)
            c.drawText(word.text, x, y, paint)
            if (act) paint.clearShadowLayer()
            c.restore()
        }
    }
}
