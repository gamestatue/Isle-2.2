package com.example.isle

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * Full-screen calm-down screen: a breathing circle (in 4s, hold 2s, out 6s).
 * Used by the breathing tool and by the daily reel-limit shield.
 */
class BreathOverlay(private val ctx: Context, private val onClosed: () -> Unit) {

    private val h = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private lateinit var circle: View
    private lateinit var phase: TextView
    private lateinit var btn: TextView
    private var cycles = 0
    private var total = 3
    private var running = false

    val isShowing: Boolean get() = root != null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show(title: String, subtitle: String, cyclesToRun: Int, shield: Boolean) {
        if (root != null) return
        val accent = Themes.current().accent
        total = cyclesToRun
        cycles = 0
        running = true

        val fl = FrameLayout(ctx).apply {
            setBackgroundColor(0xF2000000.toInt())
            isClickable = true
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(TextView(ctx).apply {
            text = title
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        })
        col.addView(TextView(ctx).apply {
            text = subtitle
            textSize = 15f
            setTextColor(0xFF8E8E93.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(8), dp(32), 0)
        })
        val box = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(260), dp(260)).apply { topMargin = dp(36); bottomMargin = dp(24) }
        }
        circle = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(accent, 90))
                setStroke(dp(3), accent)
            }
            layoutParams = FrameLayout.LayoutParams(dp(260), dp(260))
            scaleX = 0.45f
            scaleY = 0.45f
        }
        phase = TextView(ctx).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(260), dp(260))
        }
        box.addView(circle)
        box.addView(phase)
        col.addView(box)

        btn = TextView(ctx).apply {
            text = if (shield) "I need a moment…" else "Skip"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(26).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(52))
            alpha = 0.5f
            setOnClickListener { if (alpha > 0.9f) close() }
        }
        col.addView(btn)
        fl.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val lp = WindowManager.LayoutParams(
            -1, -1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        fl.alpha = 0f
        try {
            wm.addView(fl, lp)
        } catch (e: Exception) {
            running = false
            return
        }
        root = fl
        fl.animate().alpha(1f).setDuration(300).start()

        // The button unlocks after one breath so it can't be reflexively dismissed.
        h.postDelayed({ btn.animate().alpha(1f).setDuration(300).start() }, 12_000)
        h.postDelayed({ runCycle() }, 500)
    }

    private fun runCycle() {
        if (!running) return
        if (cycles >= total) {
            phase.text = "Nicely done ✓"
            circle.animate().scaleX(0.7f).scaleY(0.7f).setDuration(600).start()
            h.postDelayed({ close() }, 1400)
            return
        }
        cycles++
        phase.text = "Breathe in"
        circle.animate().scaleX(1f).scaleY(1f).setDuration(4000)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
        h.postDelayed({
            if (!running) return@postDelayed
            phase.text = "Hold"
            h.postDelayed({
                if (!running) return@postDelayed
                phase.text = "Breathe out"
                circle.animate().scaleX(0.45f).scaleY(0.45f).setDuration(6000)
                    .setInterpolator(AccelerateDecelerateInterpolator()).start()
                h.postDelayed({ runCycle() }, 6000)
            }, 2000)
        }, 4000)
    }

    fun close() {
        running = false
        h.removeCallbacksAndMessages(null)
        val r = root ?: return
        root = null
        r.animate().alpha(0f).setDuration(250).withEndAction {
            try { wm.removeView(r) } catch (e: Exception) { }
        }.start()
        onClosed()
    }

    /** Immediate removal, used when the service is destroyed. */
    fun destroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        val r = root ?: return
        root = null
        try { wm.removeView(r) } catch (e: Exception) { }
    }
}
