package com.example.isle

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var k: Kit
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private val pages = ArrayList<View>()
    private val tabIcons = ArrayList<GlyphView>()
    private val tabLabels = ArrayList<TextView>()
    private var tab = 0

    private lateinit var homePreview: PreviewView
    private lateinit var customPreview: PreviewView
    private lateinit var statusPill: TextView
    private lateinit var mainBtn: TextView
    private lateinit var setupBox: LinearLayout
    private lateinit var permBox: LinearLayout
    private lateinit var tvReels: TextView
    private lateinit var tvMin: TextView
    private lateinit var tvFocus: TextView
    private lateinit var tvStreak: TextView
    private lateinit var chart: WeekChart
    private lateinit var buddyEmoji: TextView
    private lateinit var buddyTitle: TextView
    private lateinit var buddySub: TextView
    private lateinit var buddyBar: BarView
    private lateinit var customBox: LinearLayout
    private lateinit var manualBox: LinearLayout
    private val swatches = ArrayList<ThemeSwatch>()

    private val stateListener: () -> Unit = { refresh() }
    private val settingsListener: () -> Unit = {
        homePreview.invalidate()
        customPreview.invalidate()
    }

    // ---------------------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        k = Kit(this)
        tab = savedInstanceState?.getInt("tab") ?: 0
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ctl = WindowInsetsControllerCompat(window, window.decorView)
        ctl.isAppearanceLightStatusBars = false
        ctl.isAppearanceLightNavigationBars = false
        setContentView(buildRoot())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onStart() {
        super.onStart()
        Store.addListener(stateListener)
        Store.addSettingsListener(settingsListener)
        refresh()
    }

    override fun onStop() {
        Store.removeListener(stateListener)
        Store.removeSettingsListener(settingsListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------------------------------------------------------------------------------
    // skeleton: pages + tab bar + insets
    // ---------------------------------------------------------------------------------------
    private fun buildRoot(): View {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Kit.BG)
        }
        content = FrameLayout(this)
        pages.add(buildHome())
        pages.add(buildCustomize())
        pages.add(buildSettings())
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH)) }
        rootLayout.addView(content, LinearLayout.LayoutParams(Kit.MATCH, 0, 1f))
        tabBar = buildTabBar()
        rootLayout.addView(tabBar, LinearLayout.LayoutParams(Kit.MATCH, Kit.WRAP))
        selectTab(tab)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            content.setPadding(bars.left, bars.top, bars.right, if (imeVisible) imeBottom else 0)
            tabBar.visibility = if (imeVisible) View.GONE else View.VISIBLE
            tabBar.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        return rootLayout
    }

    private fun buildTabBar(): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111113.toInt())
        }
        wrap.addView(
            View(this).apply { setBackgroundColor(Kit.SEP) },
            LinearLayout.LayoutParams(Kit.MATCH, max(1, (0.5f * k.d).toInt()))
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val defs = listOf(
            Pair("Home", Glyph.HOME),
            Pair("Customize", Glyph.SLIDERS),
            Pair("Settings", Glyph.GEAR)
        )
        defs.forEachIndexed { i, def ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { selectTab(i) }
            }
            val icon = GlyphView(this, def.second, Kit.SUB, 0.36f).apply {
                layoutParams = LinearLayout.LayoutParams(k.dp(30), k.dp(28))
            }
            val label = k.text(def.first, 10f, Kit.SUB, 1).apply { setPadding(0, k.dp(2), 0, 0) }
            item.addView(icon)
            item.addView(label)
            tabIcons.add(icon)
            tabLabels.add(label)
            row.addView(item, LinearLayout.LayoutParams(0, k.dp(54), 1f))
        }
        wrap.addView(row)
        return wrap
    }

    private fun selectTab(i: Int) {
        tab = i
        pages.forEachIndexed { idx, p -> p.visibility = if (idx == i) View.VISIBLE else View.GONE }
        for (idx in tabIcons.indices) {
            val c = if (idx == i) Kit.BLUE else Kit.SUB
            tabIcons[idx].setTintColor(c)
            tabLabels[idx].setTextColor(c)
        }
        currentFocus?.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(content.windowToken, 0)
    }

    /** Keeps the column readable on tablets / landscape. */
    private class MaxWidthColumn(c: Context, private val maxW: Int) : LinearLayout(c) {
        override fun onMeasure(w: Int, h: Int) {
            val size = MeasureSpec.getSize(w)
            super.onMeasure(if (size > maxW) MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.EXACTLY) else w, h)
        }
    }

    private fun page(build: (LinearLayout) -> Unit): ScrollView {
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val frame = FrameLayout(this)
        val col = MaxWidthColumn(this, k.dp(600)).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(k.dp(16), k.dp(4), k.dp(16), k.dp(40))
        }
        frame.addView(col, FrameLayout.LayoutParams(Kit.MATCH, Kit.WRAP, Gravity.CENTER_HORIZONTAL))
        sv.addView(frame, ViewGroup.LayoutParams(Kit.MATCH, Kit.WRAP))
        build(col)
        return sv
    }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(Kit.MATCH, Kit.WRAP).apply { topMargin = k.dp(top) }

    private fun previewCard(pv: PreviewView): FrameLayout = FrameLayout(this).apply {
        background = k.round(Kit.GROUP, 22)
        clipToOutline = true
        addView(pv, FrameLayout.LayoutParams(Kit.MATCH, Kit.WRAP))
    }

    // ---------------------------------------------------------------------------------------
    // HOME
    // ---------------------------------------------------------------------------------------
    private fun tile(label: String, value: TextView, color: Int): LinearLayout = k.vbox().apply {
        background = k.round(Kit.GROUP, 14)
        setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12))
        addView(k.text(label, 12f, color, 1))
        value.setPadding(0, k.dp(6), 0, 0)
        addView(value)
    }

    private fun buildHome(): View = page { col ->
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(k.title("Isle", "Your pocket dynamic island"), LinearLayout.LayoutParams(0, Kit.WRAP, 1f))
        statusPill = k.text("", 13f, Kit.SUB, 1).apply {
            setPadding(k.dp(12), k.dp(6), k.dp(12), k.dp(6))
        }
        top.addView(statusPill)
        col.addView(top, lp())

        homePreview = PreviewView(this)
        col.addView(previewCard(homePreview), lp(8))

        mainBtn = k.button("Start Isle", Kit.BLUE) { toggleIsle() }
        col.addView(mainBtn, lp(16))
        col.addView(k.button("Send test alert", Kit.GROUP2) { testAlert() }, lp(10))

        setupBox = k.vbox()
        col.addView(setupBox)

        col.addView(k.header("Today"))
        tvReels = k.text("0", 28f, Kit.LABEL, 2)
        tvMin = k.text("0", 28f, Kit.LABEL, 2)
        tvFocus = k.text("0", 28f, Kit.LABEL, 2)
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.addView(tile("REELS", tvReels, Kit.ORANGE), LinearLayout.LayoutParams(0, Kit.WRAP, 1f))
        tiles.addView(
            tile("MINUTES", tvMin, Kit.BLUE),
            LinearLayout.LayoutParams(0, Kit.WRAP, 1f).apply { marginStart = k.dp(10) }
        )
        tiles.addView(
            tile("FOCUS 🍅", tvFocus, Kit.GREEN),
            LinearLayout.LayoutParams(0, Kit.WRAP, 1f).apply { marginStart = k.dp(10) }
        )
        col.addView(tiles, lp())

        tvStreak = k.text("", 17f, Kit.SUB)
        col.addView(k.group(k.row("🔥  Focus streak", null, tvStreak)), lp(10))

        col.addView(k.header("Reels this week"))
        chart = WeekChart(this)
        val chartBox = FrameLayout(this).apply { setPadding(k.dp(14), k.dp(14), k.dp(14), k.dp(6)) }
        chartBox.addView(chart, FrameLayout.LayoutParams(Kit.MATCH, Kit.WRAP))
        col.addView(k.group(chartBox))

        col.addView(k.header("Your buddy"))
        buddyEmoji = k.text("🥚", 44f)
        buddyTitle = k.text("", 17f, Kit.LABEL, 1)
        buddySub = k.text("", 13f, Kit.SUB)
        buddyBar = BarView(this).apply {
            fillColor = Kit.GREEN
            trackColor = 0xFF3A3A3C.toInt()
        }
        val buddyCol = k.vbox().apply {
            addView(buddyTitle)
            addView(buddyBar, LinearLayout.LayoutParams(Kit.MATCH, k.dp(6)).apply {
                topMargin = k.dp(8); bottomMargin = k.dp(6)
            })
            addView(buddySub)
        }
        val buddyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(k.dp(16), k.dp(14), k.dp(16), k.dp(14))
        }
        buddyRow.addView(buddyEmoji, LinearLayout.LayoutParams(Kit.WRAP, Kit.WRAP).apply { marginEnd = k.dp(16) })
        buddyRow.addView(buddyCol, LinearLayout.LayoutParams(0, Kit.WRAP, 1f))
        col.addView(k.group(buddyRow))
        col.addView(k.footer("Finish focus sessions to hatch and evolve your buddy. It reacts to what you do on your phone."))
    }

    private fun testAlert() {
        if (OverlayService.isRunning) Store.alert("👋 Hello from Isle!")
        else Toast.makeText(this, "Start Isle first", Toast.LENGTH_SHORT).show()
    }

    private fun toggleIsle() {
        if (OverlayService.isRunning) {
            OverlayService.stop(this)
            return
        }
        if (!hasOverlay()) {
            Toast.makeText(this, "Allow “Display over other apps” first", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        OverlayService.start(this)
    }

    // ---------------------------------------------------------------------------------------
    // CUSTOMIZE
    // ---------------------------------------------------------------------------------------
    private class ThemeSwatch(c: Context, val theme: IsleTheme?) : View(c) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var picked = false
            set(v) { field = v; invalidate() }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((54 * d).toInt(), (54 * d).toInt())

        override fun onDraw(cv: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = 19 * d
            p.style = Paint.Style.FILL
            if (theme == null) {
                val cols = intArrayOf(
                    0xFFFF453A.toInt(), 0xFFFFD60A.toInt(), 0xFF30D158.toInt(),
                    0xFF40C8E0.toInt(), 0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF453A.toInt()
                )
                p.shader = SweepGradient(cx, cy, cols, null)
                cv.drawCircle(cx, cy, r, p)
                p.shader = null
            } else {
                p.color = theme.bg
                cv.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1 * d
                p.color = 0xFF48484A.toInt()
                cv.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.FILL
                p.color = theme.accent
                cv.drawCircle(cx, cy, r * 0.42f, p)
            }
            if (picked) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f * d
                p.color = 0xFFFFFFFF.toInt()
                cv.drawCircle(cx, cy, r + 5 * d, p)
                p.style = Paint.Style.FILL
            }
        }
    }

    private fun refreshSwatches() {
        val cur = Store.int("theme")
        swatches.forEachIndexed { i, s -> s.picked = (i == cur) }
        customBox.visibility = if (cur == Themes.PRESETS.size) View.VISIBLE else View.GONE
    }

    private fun themePicker(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(k.dp(10), k.dp(12), k.dp(10), k.dp(10))
        }
        val all: List<IsleTheme?> = Themes.PRESETS + listOf<IsleTheme?>(null)
        all.forEachIndexed { i, t ->
            val sw = ThemeSwatch(this, t)
            swatches.add(sw)
            val item = k.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
            item.addView(sw)
            item.addView(k.text(t?.name ?: "Custom", 11f, Kit.SUB).apply { setPadding(0, k.dp(2), 0, 0) })
            item.setOnClickListener {
                Store.setInt("theme", i)
                refreshSwatches()
            }
            row.addView(item, LinearLayout.LayoutParams(k.dp(66), Kit.WRAP))
        }
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        hs.addView(row)
        return hs
    }

    private fun hsvSlider(title: String, max: Int, initial: Int, colors: IntArray?, onChange: (Int) -> Unit): LinearLayout {
        val r = k.vbox().apply { setPadding(k.dp(16), k.dp(10), k.dp(16), k.dp(4)) }
        r.addView(k.text(title, 17f))
        val s = IosSlider(this, 0, max)
        s.setValue(initial)
        s.trackColors = colors
        s.onChange = onChange
        r.addView(s, LinearLayout.LayoutParams(Kit.MATCH, Kit.WRAP))
        return r
    }

    private fun customEditor(): LinearLayout {
        val bg = FloatArray(3)
        val ac = FloatArray(3)
        Color.colorToHSV(Store.int("customBg"), bg)
        Color.colorToHSV(Store.int("customAccent"), ac)
        val hues = IntArray(7) { Themes.hsv(it * 60f, 1f, 1f) }
        fun saveBg() = Store.setInt("customBg", Themes.hsv(bg[0], bg[1], bg[2]))
        fun saveAc() = Store.setInt("customAccent", Themes.hsv(ac[0], 0.75f, 1f))
        return k.group(
            hsvSlider("Island hue", 360, bg[0].toInt(), hues) { bg[0] = it.toFloat(); saveBg() },
            hsvSlider("Island saturation", 100, (bg[1] * 100).toInt(), null) { bg[1] = it / 100f; saveBg() },
            hsvSlider("Island brightness", 100, (bg[2] * 100).toInt(), null) { bg[2] = it / 100f; saveBg() },
            hsvSlider("Accent hue", 360, ac[0].toInt(), hues) { ac[0] = it.toFloat(); saveAc() }
        )
    }

    private fun isLandscape() = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels

    private fun buildCustomize(): View = page { col ->
        col.addView(k.title("Customize", "Make the island yours"))
        customPreview = PreviewView(this)
        col.addView(previewCard(customPreview), lp(4))

        // theme
        col.addView(k.header("Theme"))
        col.addView(k.group(themePicker()))
        customBox = k.vbox().apply { setPadding(0, k.dp(10), 0, 0) }
        customBox.addView(customEditor())
        col.addView(customBox)
        refreshSwatches()

        // shape
        col.addView(k.header("Shape & size"))
        col.addView(
            k.group(
                k.segRow("Size", listOf("Small", "Medium", "Large"), "size"),
                k.sliderRow("Roundness", "corner", 0, 100, { "$it%" }),
                k.sliderRow("Opacity", "opacity", 40, 100, { "$it%" }),
                k.switchRow("Accent glow", "glow", "Outline lights up while the timer runs or you watch reels")
            )
        )

        // position
        val sfx = if (isLandscape()) "L" else "P"
        col.addView(k.header("Position"))
        col.addView(
            k.group(
                k.segRow("Anchor", listOf("Camera", "Center", "Left", "Right"), "anchor"),
                k.segRow("Around the camera", listOf("Wrap", "Below", "Ignore"), "camMode"),
                k.sliderRow("Horizontal", "offX$sfx", -200, 200, { "$it dp" }),
                k.sliderRow("Vertical", "offY$sfx", -20, 200, { "$it dp" }),
                k.row("Reset position", null, null, { resetPosition() }, Kit.BLUE)
            )
        )
        col.addView(
            k.footer(
                "Drag the island anywhere on your screen. It snaps back to the camera when you get close. " +
                    "Portrait and landscape positions are saved separately."
            )
        )

        // camera
        col.addView(k.header("Camera"))
        manualBox = k.vbox()
        manualBox.addView(
            k.group(
                k.sliderRow("Horizontal position", "camXPct", 0, 100, { "$it%" }),
                k.sliderRow("Size", "camSizeDp", 8, 60, { "$it dp" }),
                k.sliderRow("Distance from top", "camTopDp", 0, 60, { "$it dp" })
            ),
            lp(10)
        )
        manualBox.visibility = if (Store.bool("camManual")) View.VISIBLE else View.GONE
        col.addView(
            k.group(
                k.switchRow("Set camera manually", "camManual", "Only needed if the island lines up wrong") { on ->
                    manualBox.visibility = if (on) View.VISIBLE else View.GONE
                }
            )
        )
        col.addView(manualBox)
        col.addView(
            k.footer(
                "Isle detects your punch-hole or notch automatically. If your phone hides the cutout " +
                    "(for example “black bar” display mode), turn this on and match the circle in the preview."
            )
        )

        // compact view
        col.addView(k.header("Compact view"))
        col.addView(
            k.group(
                k.switchRow("Music bars", "showMusic"),
                k.switchRow("Focus timer", "showTimer"),
                k.switchRow("Reel counter", "showReels"),
                k.switchRow("Clock", "showClock"),
                k.switchRow("Battery", "showBattery"),
                k.switchRow("Buddy", "showBuddy")
            )
        )

        // behavior
        col.addView(k.header("Behavior"))
        col.addView(
            k.group(
                k.sliderRow("Fade when idle", "idleOpacity", 30, 100, { "$it%" }),
                k.switchRow("Haptics", "haptics"),
                k.switchRow("Vibrate on alerts", "alertVibrate"),
                k.switchRow("Hide in landscape", "hideLandscape")
            )
        )
        col.addView(k.footer("Tip: long-press the island to start or pause the focus timer."))
    }

    private fun resetPosition() {
        Store.resetPosition()
        // Sliders are bound to the saved values, so rebuild this page to show them back at zero.
        val old = pages[1]
        val idx = content.indexOfChild(old)
        swatches.clear()
        val fresh = buildCustomize()
        fresh.visibility = old.visibility
        content.removeViewAt(idx)
        content.addView(fresh, idx, FrameLayout.LayoutParams(Kit.MATCH, Kit.MATCH))
        pages[1] = fresh
        Toast.makeText(this, "Position reset", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------------------------
    // SETTINGS
    // ---------------------------------------------------------------------------------------
    private fun goalRow(): View {
        val et = EditText(this).apply {
            setText(Store.goal())
            hint = "What are you focusing on?"
            setHintTextColor(0xFF636366.toInt())
            setTextColor(Kit.LABEL)
            textSize = 17f
            background = null
            setPadding(0, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(k.dp(200), Kit.WRAP)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    Store.setGoal(s?.toString() ?: "")
                }
            })
            setOnEditorActionListener { v, _, _ ->
                v.clearFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
                true
            }
        }
        return k.row("Goal", null, et)
    }

    private fun buildSettings(): View = page { col ->
        col.addView(k.title("Settings"))

        col.addView(k.header("Permissions"))
        permBox = k.vbox()
        col.addView(permBox)
        col.addView(
            k.footer(
                "If Android says “restricted setting” for Accessibility or Notification access " +
                    "(common for apps installed outside the Play Store): App info → ⋮ → Allow restricted settings."
            )
        )
        col.addView(k.group(k.row("Open app info", null, k.chevron(), { openAppInfo() })), lp(10))

        col.addView(k.header("Focus timer"))
        col.addView(
            k.group(
                k.stepperRow("Focus", "focusMin", 5, 120, 5, "min"),
                k.stepperRow("Break", "breakMin", 1, 30, 1, "min"),
                goalRow()
            )
        )

        col.addView(k.header("Reels"))
        col.addView(
            k.group(
                k.stepperRow("Nudge every", "reelEveryN", 0, 100, 5, "reels", "Off"),
                k.stepperRow("Time check every", "reelEveryMin", 0, 60, 5, "min", "Off"),
                k.stepperRow("Daily limit", "reelLimit", 0, 500, 10, "reels", "Off"),
                k.switchRow("Breathing shield", "shield", "Pauses you with a calm screen when you hit the limit")
            )
        )

        col.addView(k.header("Health reminders"))
        col.addView(
            k.group(
                k.switchRow("Eyes · 20-20-20", "eyeOn"),
                k.stepperRow("Every", "eyeMin", 5, 60, 5, "min"),
                k.switchRow("Water", "waterOn"),
                k.stepperRow("Every", "waterMin", 15, 120, 15, "min"),
                k.switchRow("Stretch", "stretchOn"),
                k.stepperRow("Every", "stretchMin", 15, 180, 15, "min")
            )
        )

        col.addView(k.header("General"))
        col.addView(
            k.group(
                k.switchRow("Start when phone boots", "autoStart"),
                k.row("Try the breathing exercise", null, k.chevron(), {
                    if (OverlayService.isRunning) Store.command("breathe")
                    else Toast.makeText(this, "Start Isle first", Toast.LENGTH_SHORT).show()
                })
            )
        )

        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        col.addView(k.header("About"))
        col.addView(k.group(k.row("Version", null, k.text(ver, 17f, Kit.SUB))))
        col.addView(
            k.footer(
                "Isle only looks at which screen is showing in Instagram and YouTube to count reels. " +
                    "Everything stays on your device."
            )
        )
    }

    // ---------------------------------------------------------------------------------------
    // permissions
    // ---------------------------------------------------------------------------------------
    private fun hasOverlay() = Settings.canDrawOverlays(this)

    private fun hasAccessibility(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.split(':').any { it.startsWith(packageName) && it.contains("ReelAccessibilityService") }
    }

    private fun hasListener() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun hasNotifs(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun permRow(title: String, sub: String, granted: Boolean, onClick: () -> Unit): View {
        val trailing: View = if (granted) {
            GlyphView(this, Glyph.CHECK, Kit.GREEN, 0.3f).apply {
                layoutParams = LinearLayout.LayoutParams(k.dp(22), k.dp(22))
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(k.text("Enable", 15f, Kit.ORANGE))
                addView(k.chevron(Kit.ORANGE), LinearLayout.LayoutParams(k.dp(14), k.dp(20)).apply { marginStart = k.dp(6) })
            }
        }
        return k.row(title, sub, trailing, onClick)
    }

    private fun permRows(): List<View> {
        val rows = ArrayList<View>()
        rows.add(permRow("Display over other apps", "Shows the island on top", hasOverlay()) { openOverlaySettings() })
        rows.add(permRow("Accessibility", "Counts reels in Instagram & YouTube", hasAccessibility()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        rows.add(permRow("Notification access", "Shows now-playing music", hasListener()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            rows.add(permRow("Notifications", "Lets Isle run quietly in the background", hasNotifs()) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            })
        }
        return rows
    }

    // ---------------------------------------------------------------------------------------
    // refresh
    // ---------------------------------------------------------------------------------------
    private fun refresh() {
        if (!::mainBtn.isInitialized || !::permBox.isInitialized) return
        val running = OverlayService.isRunning
        mainBtn.text = if (running) "Stop Isle" else "Start Isle"
        mainBtn.background = k.round(if (running) 0xFF3A1F1F.toInt() else Kit.BLUE, 14)
        mainBtn.setTextColor(if (running) Kit.RED else Kit.LABEL)
        statusPill.text = if (running) "● Running" else "Stopped"
        statusPill.setTextColor(if (running) Kit.GREEN else Kit.SUB)
        statusPill.background = k.round(if (running) 0x2630D158 else Kit.GROUP2, 16)

        // setup card (only while something is missing)
        setupBox.removeAllViews()
        val missing = ArrayList<View>()
        if (!hasOverlay()) missing.add(permRow("Display over other apps", "Required to show the island", false) { openOverlaySettings() })
        if (!hasAccessibility()) missing.add(permRow("Accessibility", "Needed to count reels", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (!hasListener()) missing.add(permRow("Notification access", "Optional · shows now-playing music", false) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        if (missing.isNotEmpty()) {
            setupBox.addView(k.header("Finish setup"))
            setupBox.addView(k.groupOf(missing))
        }

        // permissions list in settings
        permBox.removeAllViews()
        permBox.addView(k.groupOf(permRows()))

        // stats
        tvReels.text = Store.reelsToday().toString()
        tvMin.text = Store.reelMinutesToday().toString()
        tvFocus.text = Store.pomosToday().toString()
        val streak = Store.focusStreak()
        tvStreak.text = if (streak == 0) "Start today" else if (streak == 1) "1 day" else "$streak days"
        chart.days = Store.week()

        // buddy
        val lv = Store.buddyLevel()
        buddyEmoji.text = Store.BUDDY_EMOJI[lv]
        buddyTitle.text = "Lv ${lv + 1} · ${Store.BUDDY_NAME[lv]}"
        buddyBar.fraction = Store.buddyProgress()
        val need = Store.sessionsToNext()
        buddySub.text = if (need < 0) "Fully evolved. Legend." else
            "$need more focus session${if (need == 1) "" else "s"} until ${Store.BUDDY_NAME[lv + 1]}"

        homePreview.invalidate()
        customPreview.invalidate()
    }
}
