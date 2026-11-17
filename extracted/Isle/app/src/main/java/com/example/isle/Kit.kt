package com.example.isle

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/** Small helper that builds Apple-style "inset grouped" settings UI out of plain views. */
class Kit(val c: Context) {

    companion object {
        val BG = 0xFF000000.toInt()
        val GROUP = 0xFF1C1C1E.toInt()
        val GROUP2 = 0xFF2C2C2E.toInt()
        val SEP = 0xFF38383A.toInt()
        val LABEL = 0xFFFFFFFF.toInt()
        val SUB = 0xFF8E8E93.toInt()
        val BLUE = 0xFF0A84FF.toInt()
        val GREEN = 0xFF30D158.toInt()
        val RED = 0xFFFF453A.toInt()
        val ORANGE = 0xFFFF9F0A.toInt()
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    val d = c.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).roundToInt()

    fun round(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    fun text(t: CharSequence, sp: Float, color: Int = LABEL, weight: Int = 0): TextView = TextView(c).apply {
        text = t
        textSize = sp
        setTextColor(color)
        includeFontPadding = false
        typeface = when (weight) {
            1 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            2 -> Typeface.create("sans-serif", Typeface.BOLD)
            else -> Typeface.DEFAULT
        }
    }

    fun vbox(): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    fun title(t: String, sub: String? = null): LinearLayout = vbox().apply {
        setPadding(dp(4), dp(16), dp(4), dp(8))
        addView(text(t, 34f, LABEL, 2))
        if (sub != null) addView(text(sub, 15f, SUB).apply { setPadding(0, dp(4), 0, 0) })
    }

    fun header(t: String): TextView = text(t.uppercase(), 13f, SUB).apply {
        setPadding(dp(16), dp(24), dp(16), dp(8))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    fun footer(t: String): TextView = text(t, 13f, SUB).apply {
        setPadding(dp(16), dp(8), dp(16), 0)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setLineSpacing(0f, 1.1f)
    }

    fun group(vararg rows: View): LinearLayout = groupOf(rows.toList())

    fun groupOf(rows: List<View>): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = round(GROUP, 14)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        rows.forEachIndexed { i, r ->
            if (i > 0) {
                addView(View(c).apply {
                    setBackgroundColor(SEP)
                    layoutParams = LinearLayout.LayoutParams(MATCH, max(1, (0.5f * d).toInt())).apply { marginStart = dp(16) }
                })
            }
            addView(r)
        }
    }

    private fun ripple(): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), null, ColorDrawable(0xFFFFFFFF.toInt()))

    fun row(
        title: CharSequence,
        sub: CharSequence? = null,
        trailing: View? = null,
        onClick: (() -> Unit)? = null,
        titleColor: Int = LABEL
    ): LinearLayout {
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        col.addView(text(title, 17f, titleColor))
        if (sub != null) col.addView(text(sub, 13f, SUB).apply { setPadding(0, dp(3), 0, 0) })
        r.addView(col)
        if (trailing != null) {
            val lp = (trailing.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(WRAP, WRAP)
            lp.marginStart = dp(12)
            r.addView(trailing, lp)
        }
        if (onClick != null) {
            r.background = ripple()
            r.isClickable = true
            r.setOnClickListener { onClick() }
        }
        return r
    }

    fun chevron(tint: Int = 0xFF636366.toInt()): GlyphView =
        GlyphView(c, Glyph.CHEVRON, tint, 0.3f).apply { layoutParams = LinearLayout.LayoutParams(dp(14), dp(20)) }

    fun switchRow(title: String, key: String, sub: String? = null, onChange: ((Boolean) -> Unit)? = null): LinearLayout {
        val sw = IosSwitch(c)
        sw.set(Store.bool(key), false)
        sw.onChange = { v ->
            Store.setBool(key, v)
            onChange?.invoke(v)
        }
        return row(title, sub, sw, onClick = {
            sw.set(!sw.isOn, true)
            Store.setBool(key, sw.isOn)
            onChange?.invoke(sw.isOn)
        })
    }

    fun stepperRow(
        title: String, key: String, lo: Int, hi: Int, step: Int, unit: String, zeroLabel: String? = null
    ): LinearLayout {
        val value = text("", 17f, SUB)
        fun render() {
            val x = Store.int(key)
            value.text = if (x == 0 && zeroLabel != null) zeroLabel else "$x $unit".trim()
        }
        render()
        val minus = GlyphView(c, Glyph.MINUS, LABEL, 0.2f).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(32))
            setOnClickListener { Store.setInt(key, (Store.int(key) - step).coerceAtLeast(lo)); render() }
        }
        val plus = GlyphView(c, Glyph.PLUS, LABEL, 0.2f).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(32))
            setOnClickListener { Store.setInt(key, (Store.int(key) + step).coerceAtMost(hi)); render() }
        }
        val pill = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(0xFF3A3A3C.toInt(), 9)
            addView(minus)
            addView(View(c).apply {
                setBackgroundColor(0xFF636366.toInt())
                layoutParams = LinearLayout.LayoutParams(max(1, d.toInt()), dp(16))
            })
            addView(plus)
        }
        val trailing = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(value, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(12) })
            addView(pill)
        }
        return row(title, null, trailing)
    }

    fun sliderRow(
        title: String, key: String, min: Int, max: Int, fmt: (Int) -> String, onChange: ((Int) -> Unit)? = null
    ): LinearLayout {
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(4))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val value = text(fmt(Store.int(key)), 15f, SUB)
        top.addView(text(title, 17f), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(value)
        val s = IosSlider(c, min, max)
        s.setValue(Store.int(key))
        s.onChange = { v ->
            Store.setInt(key, v)
            value.text = fmt(v)
            onChange?.invoke(v)
        }
        r.addView(top)
        r.addView(s, LinearLayout.LayoutParams(MATCH, WRAP))
        return r
    }

    fun segRow(title: String, options: List<String>, key: String): LinearLayout {
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        r.addView(text(title, 17f))
        val seg = Segmented(c, options)
        seg.select(Store.int(key).coerceIn(0, options.size - 1), false)
        seg.onSelect = { i -> Store.setInt(key, i) }
        r.addView(seg, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        return r
    }

    fun button(label: String, fill: Int, textColor: Int = LABEL, onClick: () -> Unit): TextView = TextView(c).apply {
        text = label
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(textColor)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = round(fill, 14)
        minimumHeight = dp(50)
        setOnClickListener { onClick() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.85f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
            }
            false
        }
    }
}
