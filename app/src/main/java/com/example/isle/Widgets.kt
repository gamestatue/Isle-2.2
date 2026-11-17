package com.example.isle

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// -------------------------------------------------------------------------------------------
// Switch
// -------------------------------------------------------------------------------------------
class IosSwitch(c: Context) : View(c) {
    private val d = resources.displayMetrics.density
    private var pos = 0f
    var isOn = false
        private set
    var onChange: ((Boolean) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var anim: ValueAnimator? = null

    fun set(on: Boolean, animate: Boolean = false) {
        isOn = on
        val t = if (on) 1f else 0f
        anim?.cancel()
        if (animate) {
            anim = ValueAnimator.ofFloat(pos, t).apply {
                duration = 200
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            pos = t
            invalidate()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((51 * d).toInt(), (31 * d).toInt())

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = ColorUtils.blendARGB(0xFF39393D.toInt(), 0xFF30D158.toInt(), pos)
        rect.set(0f, 0f, w, h)
        cv.drawRoundRect(rect, h / 2, h / 2, p)
        val pad = 2 * d
        val r = h / 2 - pad
        val cx = pad + r + pos * (w - 2 * pad - 2 * r)
        p.color = 0x33000000
        cv.drawCircle(cx, h / 2 + d, r, p)
        p.color = 0xFFFFFFFF.toInt()
        cv.drawCircle(cx, h / 2, r, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) {
                    set(!isOn, true)
                    onChange?.invoke(isOn)
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

// -------------------------------------------------------------------------------------------
// Slider
// -------------------------------------------------------------------------------------------
class IosSlider(c: Context, private val min: Int, private val max: Int) : View(c) {
    private val d = resources.displayMetrics.density
    var value = min
        private set
    var onChange: ((Int) -> Unit)? = null
    var accent = 0xFF0A84FF.toInt()
    var trackColors: IntArray? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setValue(v: Int) {
        value = v.coerceIn(min, max)
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize((200 * d).toInt(), w), (40 * d).toInt())
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val tr = 14 * d
        val left = tr
        val right = w - tr
        val th = 6 * d
        val frac = if (max > min) (value - min).toFloat() / (max - min) else 0f
        val x = left + frac * (right - left)
        val cy = h / 2
        val colors = trackColors
        if (colors != null) {
            p.shader = LinearGradient(left, 0f, right, 0f, colors, null, Shader.TileMode.CLAMP)
            rect.set(left, cy - th / 2, right, cy + th / 2)
            cv.drawRoundRect(rect, th / 2, th / 2, p)
            p.shader = null
        } else {
            p.color = 0xFF48484A.toInt()
            rect.set(left, cy - th / 2, right, cy + th / 2)
            cv.drawRoundRect(rect, th / 2, th / 2, p)
            p.color = accent
            rect.set(left, cy - th / 2, x, cy + th / 2)
            cv.drawRoundRect(rect, th / 2, th / 2, p)
        }
        p.color = 0x44000000
        cv.drawCircle(x, cy + d, tr - 2 * d, p)
        p.color = 0xFFFFFFFF.toInt()
        cv.drawCircle(x, cy, tr - 2 * d, p)
    }

    private fun fromX(x: Float) {
        val tr = 14 * d
        val frac = ((x - tr) / (width - 2 * tr)).coerceIn(0f, 1f)
        val v = (min + frac * (max - min)).toInt().coerceIn(min, max)
        if (v != value) {
            value = v
            invalidate()
            onChange?.invoke(v)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                fromX(e.x)
            }
            MotionEvent.ACTION_MOVE -> fromX(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}

// -------------------------------------------------------------------------------------------
// Segmented control
// -------------------------------------------------------------------------------------------
class Segmented(c: Context, options: List<String>) : LinearLayout(c) {
    private val d = resources.displayMetrics.density
    var index = 0
        private set
    var onSelect: ((Int) -> Unit)? = null
    private val tvs = ArrayList<TextView>()

    init {
        orientation = HORIZONTAL
        background = GradientDrawable().apply {
            setColor(0xFF2C2C2E.toInt()); cornerRadius = 9 * d
        }
        val pad = (2 * d).toInt()
        setPadding(pad, pad, pad, pad)
        options.forEachIndexed { i, o ->
            val tv = TextView(c).apply {
                text = o
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LayoutParams(0, (30 * d).toInt(), 1f)
                setOnClickListener { select(i, true) }
            }
            tvs.add(tv)
            addView(tv)
        }
        select(0, false)
    }

    fun select(i: Int, fire: Boolean) {
        index = i
        tvs.forEachIndexed { idx, tv ->
            val on = idx == i
            tv.background = if (on) GradientDrawable().apply {
                setColor(0xFF636366.toInt()); cornerRadius = 7 * d
            } else null
            tv.typeface = if (on) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        }
        if (fire) onSelect?.invoke(i)
    }
}

// -------------------------------------------------------------------------------------------
// Vector-style glyphs drawn with Canvas (crisp on every phone, no emoji font differences)
// -------------------------------------------------------------------------------------------
enum class Glyph { PLAY, PAUSE, PREV, NEXT, RESET, SKIP, PLUS, MINUS, GEAR, CHEVRON, CHECK, HOME, SLIDERS }

class GlyphView(
    c: Context,
    private var glyph: Glyph,
    private var tint: Int = 0xFFFFFFFF.toInt(),
    private val scale: Float = 0.28f
) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rf = RectF()

    fun setGlyph(g: Glyph) {
        if (g != glyph) { glyph = g; invalidate() }
    }

    fun setTintColor(c: Int) {
        if (c != tint) { tint = c; invalidate() }
    }

    private fun fill() { p.style = Paint.Style.FILL; p.color = tint }
    private fun stroke(w: Float) {
        p.style = Paint.Style.STROKE; p.color = tint; p.strokeWidth = w
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
    }

    private fun drawNext(cv: Canvas, cx: Float, cy: Float, s: Float) {
        fill()
        path.reset()
        path.moveTo(cx - s * 0.85f, cy - s * 0.85f)
        path.lineTo(cx + s * 0.3f, cy)
        path.lineTo(cx - s * 0.85f, cy + s * 0.85f)
        path.close()
        cv.drawPath(path, p)
        rf.set(cx + s * 0.45f, cy - s * 0.85f, cx + s * 0.85f, cy + s * 0.85f)
        cv.drawRoundRect(rf, s * 0.15f, s * 0.15f, p)
    }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val s = min(width, height) * scale
        when (glyph) {
            Glyph.PLAY -> {
                fill()
                path.reset()
                path.moveTo(cx - s * 0.6f, cy - s)
                path.lineTo(cx + s * 0.9f, cy)
                path.lineTo(cx - s * 0.6f, cy + s)
                path.close()
                cv.drawPath(path, p)
            }
            Glyph.PAUSE -> {
                fill()
                val bw = s * 0.6f
                val g = s * 0.5f
                rf.set(cx - g / 2 - bw, cy - s, cx - g / 2, cy + s)
                cv.drawRoundRect(rf, bw * 0.3f, bw * 0.3f, p)
                rf.set(cx + g / 2, cy - s, cx + g / 2 + bw, cy + s)
                cv.drawRoundRect(rf, bw * 0.3f, bw * 0.3f, p)
            }
            Glyph.NEXT -> drawNext(cv, cx, cy, s)
            Glyph.PREV -> {
                cv.save()
                cv.scale(-1f, 1f, cx, cy)
                drawNext(cv, cx, cy, s)
                cv.restore()
            }
            Glyph.SKIP -> drawNext(cv, cx, cy, s)
            Glyph.RESET -> {
                stroke(s * 0.3f)
                val r = s * 0.85f
                rf.set(cx - r, cy - r, cx + r, cy + r)
                cv.drawArc(rf, -50f, -290f, false, p)
                val th = Math.toRadians(20.0)
                val ex = cx + r * cos(th).toFloat()
                val ey = cy + r * sin(th).toFloat()
                val tx = sin(th).toFloat()
                val ty = -cos(th).toFloat()
                val nx = cos(th).toFloat()
                val ny = sin(th).toFloat()
                fill()
                path.reset()
                path.moveTo(ex + tx * s * 0.7f, ey + ty * s * 0.7f)
                path.lineTo(ex + nx * s * 0.55f, ey + ny * s * 0.55f)
                path.lineTo(ex - nx * s * 0.55f, ey - ny * s * 0.55f)
                path.close()
                cv.drawPath(path, p)
            }
            Glyph.PLUS -> {
                stroke(s * 0.3f)
                cv.drawLine(cx - s, cy, cx + s, cy, p)
                cv.drawLine(cx, cy - s, cx, cy + s, p)
            }
            Glyph.MINUS -> {
                stroke(s * 0.3f)
                cv.drawLine(cx - s, cy, cx + s, cy, p)
            }
            Glyph.GEAR -> {
                stroke(s * 0.34f)
                cv.drawCircle(cx, cy, s * 0.55f, p)
                fill()
                for (i in 0 until 8) {
                    cv.save()
                    cv.rotate(i * 45f, cx, cy)
                    rf.set(cx - s * 0.17f, cy - s * 1.0f, cx + s * 0.17f, cy - s * 0.62f)
                    cv.drawRoundRect(rf, s * 0.1f, s * 0.1f, p)
                    cv.restore()
                }
            }
            Glyph.CHEVRON -> {
                stroke(s * 0.3f)
                path.reset()
                path.moveTo(cx - s * 0.35f, cy - s * 0.7f)
                path.lineTo(cx + s * 0.35f, cy)
                path.lineTo(cx - s * 0.35f, cy + s * 0.7f)
                cv.drawPath(path, p)
            }
            Glyph.CHECK -> {
                stroke(s * 0.3f)
                path.reset()
                path.moveTo(cx - s * 0.8f, cy)
                path.lineTo(cx - s * 0.2f, cy + s * 0.6f)
                path.lineTo(cx + s * 0.85f, cy - s * 0.6f)
                cv.drawPath(path, p)
            }
            Glyph.HOME -> {
                fill()
                path.reset()
                path.moveTo(cx, cy - s)
                path.lineTo(cx + s, cy - s * 0.05f)
                path.lineTo(cx + s * 0.72f, cy - s * 0.05f)
                path.lineTo(cx + s * 0.72f, cy + s)
                path.lineTo(cx + s * 0.2f, cy + s)
                path.lineTo(cx + s * 0.2f, cy + s * 0.3f)
                path.lineTo(cx - s * 0.2f, cy + s * 0.3f)
                path.lineTo(cx - s * 0.2f, cy + s)
                path.lineTo(cx - s * 0.72f, cy + s)
                path.lineTo(cx - s * 0.72f, cy - s * 0.05f)
                path.lineTo(cx - s, cy - s * 0.05f)
                path.close()
                cv.drawPath(path, p)
            }
            Glyph.SLIDERS -> {
                val ys = floatArrayOf(cy - s * 0.7f, cy, cy + s * 0.7f)
                val xs = floatArrayOf(cx - s * 0.4f, cx + s * 0.45f, cx - s * 0.1f)
                for (i in 0 until 3) {
                    stroke(s * 0.2f)
                    cv.drawLine(cx - s, ys[i], cx + s, ys[i], p)
                    fill()
                    cv.drawCircle(xs[i], ys[i], s * 0.3f, p)
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Thin progress bar
// -------------------------------------------------------------------------------------------
class BarView(c: Context) : View(c) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var fillColor = 0xFF0A84FF.toInt()
        set(v) { field = v; invalidate() }
    var trackColor = 0x33FFFFFF
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(cv: Canvas) {
        val h = height.toFloat()
        p.color = trackColor
        rect.set(0f, 0f, width.toFloat(), h)
        cv.drawRoundRect(rect, h / 2, h / 2, p)
        if (fraction > 0f) {
            p.color = fillColor
            rect.set(0f, 0f, max(h, width * fraction), h)
            cv.drawRoundRect(rect, h / 2, h / 2, p)
        }
    }
}

// -------------------------------------------------------------------------------------------
// Music equalizer bars
// -------------------------------------------------------------------------------------------
class EqView(c: Context) : View(c) {
    var color = 0xFF30D158.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var phase = 0f
    private var anim: ValueAnimator? = null

    fun setRunning(on: Boolean) {
        if (on && anim == null) {
            anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (!on) {
            anim?.cancel()
            anim = null
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val n = 3
        val bw = w / (n * 2 - 1)
        p.color = color
        for (i in 0 until n) {
            val f = if (anim == null) 0.35f else 0.3f + 0.7f * abs(sin(phase + i * 1.4f))
            val bh = h * f
            val x = i * bw * 2
            rect.set(x, h - bh, x + bw, h)
            cv.drawRoundRect(rect, bw / 2, bw / 2, p)
        }
    }
}

// -------------------------------------------------------------------------------------------
// Weekly bar chart
// -------------------------------------------------------------------------------------------
class WeekChart(c: Context) : View(c) {
    private val d = resources.displayMetrics.density
    var days: List<Store.Day> = emptyList()
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize((300 * d).toInt(), w), (150 * d).toInt())
    }

    override fun onDraw(cv: Canvas) {
        if (days.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val n = days.size
        val slot = w / n
        val bw = slot * 0.5f
        val top = 22 * d
        val bottom = h - 22 * d
        val peak = max(1, days.maxOf { it.reels })
        p.textAlign = Paint.Align.CENTER
        p.textSize = 11 * d
        for ((i, day) in days.withIndex()) {
            val cx = slot * i + slot / 2
            val bh = max(4 * d, (bottom - top) * day.reels / peak)
            val today = i == n - 1
            p.color = if (today) 0xFF0A84FF.toInt() else 0xFF3A3A3C.toInt()
            rect.set(cx - bw / 2, bottom - bh, cx + bw / 2, bottom)
            cv.drawRoundRect(rect, 6 * d, 6 * d, p)
            p.color = 0xFF8E8E93.toInt()
            cv.drawText(day.label, cx, h - 5 * d, p)
            if (day.reels > 0) {
                p.color = if (today) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt()
                cv.drawText(day.reels.toString(), cx, bottom - bh - 5 * d, p)
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Live preview of the island on top of a phone silhouette
// -------------------------------------------------------------------------------------------
class PreviewView(c: Context) : View(c) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize((300 * d).toInt(), w), (150 * d).toInt())
    }

    override fun onDraw(cv: Canvas) {
        val dm = resources.displayMetrics
        val swP = min(dm.widthPixels, dm.heightPixels)
        val padX = 40 * d
        val bezel = 5 * d
        val sl = padX
        val sr = width - padX
        val st = 14 * d
        val sb = height + 60 * d
        val k = (sr - sl) / swP

        // body + screen
        p.shader = null
        p.color = 0xFF2C2C2E.toInt()
        rect.set(sl - bezel, st - bezel, sr + bezel, sb)
        cv.drawRoundRect(rect, 38 * d, 38 * d, p)
        p.shader = LinearGradient(0f, st, 0f, height.toFloat(), 0xFF2B3158.toInt(), 0xFF0B0C1A.toInt(), Shader.TileMode.CLAMP)
        rect.set(sl, st, sr, sb)
        cv.drawRoundRect(rect, 33 * d, 33 * d, p)
        p.shader = null

        // fake status bar
        p.color = 0x99FFFFFF.toInt()
        p.textSize = 9 * d
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.DEFAULT_BOLD
        cv.drawText("9:41", sl + 24 * d, st + 21 * d, p)
        rect.set(sr - 46 * d, st + 14 * d, sr - 24 * d, st + 21 * d)
        cv.drawRoundRect(rect, 3 * d, 3 * d, p)
        p.typeface = Typeface.DEFAULT

        // camera
        val cut = Cam.previewRect(swP, dm.density)
        if (cut != null) {
            p.color = 0xFF000000.toInt()
            cv.drawCircle(sl + cut.centerX() * k, st + cut.centerY() * k, cut.width() / 2f * k, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1 * d
            p.color = 0xFF2A2A2C.toInt()
            cv.drawCircle(sl + cut.centerX() * k, st + cut.centerY() * k, cut.width() / 2f * k, p)
            p.style = Paint.Style.FILL
        }

        // island
        val theme = Themes.current()
        val sc = when (Store.int("size")) { 0 -> 0.85f; 2 -> 1.15f; else -> 1f }
        val anchor = Store.int("anchor")
        val camMode = Store.int("camMode")
        val offX = Store.int("offXP")
        val offY = Store.int("offYP")
        val real = dm.density
        val gap = Placement.gapFor(cut, swP, anchor, camMode, offX, offY, real)
        val baseH = (36 * real * sc).toInt()
        val rowH = Placement.rowHeightFor(cut, gap, baseH, real)
        val side = (50 * real * sc).toInt()
        val rw = if (gap > 0) gap + side * 2 else (side * 2.1f).toInt()
        val pos = Placement.position(swP, 4000, rw, rowH, rowH, cut, anchor, camMode, gap, offX, offY, real)
        val px = sl + pos.x * k
        val py = st + pos.y * k
        val pw = rw * k
        val ph = rowH * k
        val alpha = (255 * Store.int("opacity") / 100f).toInt().coerceIn(60, 255)
        p.color = ColorUtils.setAlphaComponent(theme.bg, alpha)
        rect.set(px, py, px + pw, py + ph)
        cv.drawRoundRect(rect, ph / 2, ph / 2, p)
        if (Store.bool("glow")) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.2f * d
            p.color = ColorUtils.setAlphaComponent(theme.accent, 150)
            rect.set(px + 0.6f * d, py + 0.6f * d, px + pw - 0.6f * d, py + ph - 0.6f * d)
            cv.drawRoundRect(rect, ph / 2, ph / 2, p)
            p.style = Paint.Style.FILL
        }
        val cy = py + ph / 2
        p.color = theme.accent
        cv.drawCircle(px + 14 * d * sc, cy, 3.2f * d * sc, p)
        p.color = theme.text
        p.textSize = 10 * d * sc
        p.typeface = Typeface.DEFAULT_BOLD
        val tx = if (gap > 0) px + pw - 12 * d * sc else px + pw - 12 * d * sc
        p.textAlign = Paint.Align.RIGHT
        cv.drawText("24:59", tx, cy + 3.5f * d * sc, p)
        p.typeface = Typeface.DEFAULT
        p.textAlign = Paint.Align.LEFT
    }
}
