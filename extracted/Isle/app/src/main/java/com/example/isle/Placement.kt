package com.example.isle

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.core.graphics.ColorUtils

class IsleTheme(val name: String, val bg: Int, val accent: Int) {
    val dark: Boolean get() = ColorUtils.calculateLuminance(bg) < 0.45
    val text: Int get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt()
    val sub: Int get() = ColorUtils.blendARGB(text, bg, 0.45f)
    val surface: Int
        get() = if (dark) ColorUtils.blendARGB(bg, 0xFFFFFFFF.toInt(), 0.12f)
        else ColorUtils.blendARGB(bg, 0xFF000000.toInt(), 0.07f)
    val button: Int
        get() = if (dark) ColorUtils.blendARGB(bg, 0xFFFFFFFF.toInt(), 0.2f)
        else ColorUtils.blendARGB(bg, 0xFF000000.toInt(), 0.12f)
}

object Themes {
    val PRESETS = listOf(
        IsleTheme("Midnight", 0xFF000000.toInt(), 0xFF0A84FF.toInt()),
        IsleTheme("Graphite", 0xFF1C1C1E.toInt(), 0xFF64D2FF.toInt()),
        IsleTheme("Indigo", 0xFF1B1464.toInt(), 0xFFBF5AF2.toInt()),
        IsleTheme("Forest", 0xFF06281C.toInt(), 0xFF32D74B.toInt()),
        IsleTheme("Sunset", 0xFF3A0F1E.toInt(), 0xFFFF9F0A.toInt()),
        IsleTheme("Rose", 0xFF3B0A2A.toInt(), 0xFFFF375F.toInt()),
        IsleTheme("Ocean", 0xFF04233B.toInt(), 0xFF40C8E0.toInt()),
        IsleTheme("Snow", 0xFFF2F2F7.toInt(), 0xFF0A84FF.toInt())
    )

    fun current(): IsleTheme {
        val i = Store.int("theme")
        return if (i in PRESETS.indices) PRESETS[i]
        else IsleTheme("Custom", Store.int("customBg"), Store.int("customAccent"))
    }

    fun hsv(h: Float, s: Float, v: Float): Int = Color.HSVToColor(floatArrayOf(h, s, v))
}

/** Finds the camera hole / notch at the top edge of the screen. */
object Cam {

    /** Camera cutout touching the top edge, in pixels for the current rotation, or null. */
    fun topCutout(ctx: Context, sw: Int, sh: Int): Rect? {
        val d = ctx.resources.displayMetrics.density
        if (Store.bool("camManual")) {
            if (sw > sh) return null // manual camera is defined for portrait only
            val size = (Store.int("camSizeDp") * d).toInt()
            val cx = (sw * Store.int("camXPct") / 100f).toInt()
            val top = (Store.int("camTopDp") * d).toInt()
            return Rect(cx - size / 2, top, cx + size / 2, top + size)
        }
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val dm = ctx.getSystemService(DisplayManager::class.java)
            val cut = dm.getDisplay(Display.DEFAULT_DISPLAY)?.cutout
            val r = cut?.boundingRects?.firstOrNull {
                it.top <= (2 * d).toInt() && it.width() > 0 && it.width() < sw * 0.8f
            }
            if (r != null && sw < sh) remember(r, sw, d)
            r
        } catch (e: Exception) {
            null
        }
    }

    private fun remember(r: Rect, sw: Int, d: Float) {
        Store.setIntQuiet("cutXpct", (r.centerX() * 100f / sw).toInt())
        Store.setIntQuiet("cutWdp", (r.width() / d).toInt())
        Store.setIntQuiet("cutHdp", (r.height() / d).toInt())
        Store.setIntQuiet("cutTopDp", (r.top / d).toInt())
        Store.setBoolQuiet("cutSeen", true)
    }

    /** Portrait camera rect for previews, scaled to a screen of width [sw] pixels. */
    fun previewRect(sw: Int, d: Float): Rect? {
        if (Store.bool("camManual")) {
            val size = (Store.int("camSizeDp") * d).toInt()
            val cx = (sw * Store.int("camXPct") / 100f).toInt()
            val top = (Store.int("camTopDp") * d).toInt()
            return Rect(cx - size / 2, top, cx + size / 2, top + size)
        }
        if (!Store.bool("cutSeen", false)) return null
        val w = (Store.int("cutWdp", 24) * d).toInt()
        val h = (Store.int("cutHdp", 24) * d).toInt()
        val cx = (sw * Store.int("cutXpct", 50) / 100f).toInt()
        val top = (Store.int("cutTopDp", 10) * d).toInt()
        return Rect(cx - w / 2, top, cx + w / 2, top + h)
    }
}

object Placement {

    private fun nearCenter(cut: Rect, sw: Int) = cut.centerX() in (sw * 0.3f).toInt()..(sw * 0.7f).toInt()

    /** Width of the empty gap left in the middle of the pill so the camera shows through. */
    fun gapFor(cut: Rect?, sw: Int, anchor: Int, camMode: Int, offX: Int, offY: Int, d: Float): Int {
        if (cut == null || camMode != 0 || anchor != 0) return 0
        if (!nearCenter(cut, sw)) return 0
        if (cut.width() > 120 * d) return 0 // wide notch: sit below instead
        if (Math.abs(offX) > 20 || Math.abs(offY) > 14) return 0 // user moved it away
        return cut.width() + (10 * d).toInt()
    }

    fun rowHeightFor(cut: Rect?, gap: Int, base: Int, d: Float): Int =
        if (gap > 0 && cut != null) maxOf(base, cut.height() + (8 * d).toInt()) else base

    fun position(
        sw: Int, sh: Int, rw: Int, rh: Int, rowH: Int, cut: Rect?,
        anchor: Int, camMode: Int, gap: Int, offX: Int, offY: Int, d: Float
    ): Point {
        val m = (6 * d).toInt()
        var x = when (anchor) {
            0 -> (if (cut != null && nearCenter(cut, sw)) cut.centerX() else sw / 2) - rw / 2
            1 -> sw / 2 - rw / 2
            2 -> m
            else -> sw - rw - m
        }
        x += (offX * d).toInt()
        x = x.coerceIn(0, maxOf(0, sw - rw))

        var y = (6 * d).toInt()
        if (cut != null) {
            if (gap > 0) {
                y = cut.top - (rowH - cut.height()) / 2
            } else if (camMode != 2) {
                val overlapsX = x < cut.right + m && x + rw > cut.left - m
                if (overlapsX) y = maxOf(y, cut.bottom + (4 * d).toInt())
            }
        }
        y += (offY * d).toInt()
        y = y.coerceIn(0, maxOf(0, sh - rh))
        return Point(x, y)
    }
}
