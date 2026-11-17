package com.example.isle

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val RED = 0xFFFF453A.toInt()
private val GREEN = 0xFF32D74B.toInt()
private val ORANGE = 0xFFFF9F0A.toInt()

/**
 * The dynamic-island pill.
 *  - collapsed: a capsule showing the most relevant live info; wraps around the camera hole
 *  - tap: expands into the full panel; drag: move it (it snaps back to the camera)
 *  - long press: start / pause the focus timer
 *  - alerts, reminders, buddy, tools, breathing screen, reel budget
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.isle.STOP"
        private const val CHANNEL = "isle_fg"
        private const val NOTIF_ID = 42

        @Volatile
        var isRunning = false

        fun start(c: Context) {
            c.startForegroundService(Intent(c, OverlayService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, OverlayService::class.java))
        }
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: LinearLayout
    private var added = false
    private var expanded = false

    // look
    private var th = Themes.current()
    private var sc = 1f
    private var panelW = 0
    private lateinit var bg: GradientDrawable
    private var strokeBase = 0
    private var pulseAnim: ValueAnimator? = null
    private var expandAnim: ValueAnimator? = null

    // views
    private lateinit var capsuleWrap: FrameLayout
    private lateinit var capsule: LinearLayout
    private lateinit var leftSec: LinearLayout
    private lateinit var rightSec: LinearLayout
    private lateinit var gapView: View
    private lateinit var dotBg: GradientDrawable
    private lateinit var tvBuddyMini: TextView
    private lateinit var eq: EqView
    private lateinit var tvMini: TextView
    private lateinit var pillProgress: BarView
    private lateinit var alertView: TextView
    private lateinit var panel: LinearLayout
    private lateinit var tvBuddy: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvGoal: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnPlay: GlyphView
    private lateinit var timerBar: BarView
    private lateinit var tvReels: TextView
    private lateinit var tvSession: TextView
    private lateinit var budgetBar: BarView
    private lateinit var tvBudget: TextView
    private lateinit var tvTrack: TextView
    private lateinit var btnMusicPlay: GlyphView
    private lateinit var chipTorch: TextView
    private lateinit var chipEye: TextView
    private lateinit var chipWater: TextView
    private lateinit var chipStretch: TextView

    private var curGap = 0
    private var lastSig = ""

    // pomodoro
    private var isFocus = true
    private var pomoRunning = false
    private var pomoTouched = false
    private var pausedRemaining = 0
    private var endAt = 0L
    private var celebrateUntil = 0L

    // reminders: elapsedRealtime at which they next fire, 0 = off
    private var eyeAt = 0L
    private var waterAt = 0L
    private var stretchAt = 0L
    private val armedMins = IntArray(3)

    // music
    private var controller: MediaController? = null
    private var trackText = ""
    private var musicPlaying = false
    private var musicAccess = true

    // torch
    private var torchOn = false
    private var torchId: String? = null
    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) {
                torchOn = enabled
                if (added) refreshUi()
            }
        }
    }

    private var breath: BreathOverlay? = null
    private var rollRun: Runnable? = null
    private var tickCount = 0
    private var clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    // gesture state
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var longFired = false

    private val longPress = Runnable {
        longFired = true
        haptic()
        togglePomo()
        showAlert(if (pomoRunning) "▶ Timer started" else "⏸ Timer paused", false, 1500)
    }
    private val hideAlert = Runnable {
        if (added) {
            alertView.visibility = View.GONE
            place()
            scheduleDim()
        }
    }
    private val dim = Runnable {
        if (added && !expanded && alertView.visibility != View.VISIBLE) {
            root.animate().alpha(Store.int("idleOpacity") / 100f).setDuration(400).start()
        }
    }
    private val rebuildRun = Runnable { rebuild() }
    private val settingsListener: () -> Unit = {
        h.removeCallbacks(rebuildRun)
        h.postDelayed(rebuildRun, 120)
    }
    private val uiListener: () -> Unit = { if (added) refreshUi() }

    private val tick = object : Runnable {
        override fun run() {
            tickCount++
            tickPomo()
            Store.tickReelTime()
            tickReminders()
            if (tickCount % 2 == 0) refreshMusic()
            refreshUi()
            h.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------------------------
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        pausedRemaining = fullSec()
        findTorch()
        buildUi()
        place()
        wm.addView(root, params)
        added = true
        place()
        Store.alertSink = { showAlert(it) }
        Store.commandSink = { cmd ->
            when (cmd) {
                "breathe" -> showBreath(false)
                "shield" -> showBreath(true)
            }
        }
        Store.addListener(uiListener)
        Store.addSettingsListener(settingsListener)
        armReminders()
        refreshMusic()
        h.post(tick)
        scheduleDim()
        Store.notifyState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation / fold: rebuild for the new screen size, then once more when the cutout info settles.
        h.postDelayed({ rebuild() }, 200)
        h.postDelayed({ place() }, 700)
    }

    override fun onDestroy() {
        isRunning = false
        h.removeCallbacksAndMessages(null)
        Store.alertSink = null
        Store.commandSink = null
        Store.removeListener(uiListener)
        Store.removeSettingsListener(settingsListener)
        Store.flush()
        breath?.destroy()
        try {
            val cm = getSystemService(CameraManager::class.java)
            cm.unregisterTorchCallback(torchCb)
            if (torchOn) torchId?.let { cm.setTorchMode(it, false) }
        } catch (e: Exception) { }
        pulseAnim?.cancel()
        expandAnim?.cancel()
        if (added) {
            try { wm.removeView(root) } catch (e: Exception) { }
            added = false
        }
        Store.notifyState()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Isle overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Isle is running")
            .setContentText("Tap to open settings")
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Stop", stop).build()
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------
    /** dp scaled by the island size setting. */
    private fun dp(v: Int) = (v * resources.displayMetrics.density * sc).roundToInt()

    /** Plain dp (not scaled by island size). */
    private fun dpr(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    private fun screen(): Point {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            Point(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun rect(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun lbl(t: String, size: Float, color: Int = th.text, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size * sc
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    private fun haptic() {
        if (added && Store.bool("haptics")) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else getSystemService(Vibrator::class.java)

    private fun buzz() {
        if (!Store.bool("alertVibrate")) return
        try {
            vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))
        } catch (e: Exception) { }
    }

    private fun collapsedRadius(): Float = (dp(6) + (dp(40) - dp(6)) * Store.int("corner") / 100f)
    private fun expandedRadius(): Float = (dp(10) + dp(24) * Store.int("corner") / 100f)

    // ---------------------------------------------------------------------------------------
    // UI building
    // ---------------------------------------------------------------------------------------
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rect(th.surface, 18)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
    }

    private fun hrow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun glyphBtn(g: Glyph, size: Int, accent: Boolean = false, onClick: () -> Unit) =
        GlyphView(this, g, if (accent) 0xFFFFFFFF.toInt() else th.text).apply {
            background = oval(if (accent) th.accent else th.button)
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginStart = dp(8) }
            setOnClickListener { haptic(); onClick() }
        }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 17f * sc
        gravity = Gravity.CENTER
        background = rect(th.button, 14)
        layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) }
        setOnClickListener { haptic(); onClick() }
    }

    private fun buildUi() {
        th = Themes.current()
        sc = when (Store.int("size")) { 0 -> 0.85f; 2 -> 1.15f; else -> 1f }
        clockFmt = SimpleDateFormat(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm", Locale.getDefault())
        val sw = screen().x
        panelW = min(dp(340), sw - dpr(16))
        lastSig = ""

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        bg = GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(th.bg, (255 * Store.int("opacity") / 100f).roundToInt().coerceIn(60, 255)))
            cornerRadius = collapsedRadius()
            setStroke(max(1, dp(1)), ColorUtils.setAlphaComponent(th.accent, 0))
        }
        root.background = bg

        // ---- capsule: [ left | gap (camera) | right ] ----
        capsuleWrap = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, WRAP) }
        capsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(36)
        }
        leftSec = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(14), 0, dp(8), 0)
        }
        dotBg = oval(th.sub)
        leftSec.addView(View(this).apply {
            background = dotBg
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        })
        tvBuddyMini = lbl("🥚", 14f).apply { setPadding(dp(6), 0, 0, 0) }
        leftSec.addView(tvBuddyMini)
        eq = EqView(this).apply {
            color = th.accent
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(12)).apply { marginStart = dp(8) }
            visibility = View.GONE
        }
        leftSec.addView(eq)

        gapView = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1) }

        rightSec = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(8), 0, dp(14), 0)
        }
        tvMini = lbl("", 13f, th.text, true).apply { fontFeatureSettings = "tnum" }
        rightSec.addView(tvMini)

        capsule.addView(leftSec, LinearLayout.LayoutParams(0, WRAP, 1f))
        capsule.addView(gapView)
        capsule.addView(rightSec, LinearLayout.LayoutParams(0, WRAP, 1f))
        capsuleWrap.addView(capsule, FrameLayout.LayoutParams(MATCH, WRAP))

        pillProgress = BarView(this).apply {
            fillColor = th.accent
            trackColor = ColorUtils.setAlphaComponent(th.text, 40)
            visibility = View.GONE
        }
        capsuleWrap.addView(
            pillProgress,
            FrameLayout.LayoutParams(MATCH, dp(2), Gravity.BOTTOM).apply {
                setMargins(dp(18), 0, dp(18), dp(4))
            }
        )
        root.addView(capsuleWrap)

        // ---- alert ----
        alertView = lbl("", 13f).apply {
            setPadding(dp(16), dp(2), dp(16), dp(12))
            minWidth = min(dp(240), sw - dpr(24))
            maxWidth = min(dp(320), sw - dpr(24))
            maxLines = 3
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(alertView, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- expanded panel ----
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(4))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(panelW, WRAP)
        }

        // focus card
        val focus = card()
        val head = hrow()
        tvBuddy = lbl("🥚", 28f).apply { setPadding(0, 0, dp(10), 0) }
        tvBuddy.setOnClickListener { haptic(); cheer() }
        val headCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val modeRow = hrow()
        tvMode = lbl("FOCUS", 11f, th.accent, true)
        tvClock = lbl("", 11f, th.sub)
        modeRow.addView(tvMode, LinearLayout.LayoutParams(0, WRAP, 1f))
        modeRow.addView(tvClock)
        tvGoal = lbl("", 13f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) }
        }
        headCol.addView(modeRow)
        headCol.addView(tvGoal)
        val gear = GlyphView(this, Glyph.GEAR, th.sub).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(6) }
            setOnClickListener {
                haptic()
                startActivity(Intent(this@OverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                setExpanded(false)
            }
        }
        head.addView(tvBuddy)
        head.addView(headCol)
        head.addView(gear)
        focus.addView(head)

        val timerRow = hrow().apply { setPadding(0, dp(8), 0, 0) }
        tvTimer = lbl("25:00", 38f, th.text, true).apply {
            fontFeatureSettings = "tnum"
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        btnPlay = glyphBtn(Glyph.PLAY, 46, true) { togglePomo() }
        timerRow.addView(tvTimer)
        timerRow.addView(glyphBtn(Glyph.RESET, 38) { resetPomo() })
        timerRow.addView(btnPlay)
        timerRow.addView(glyphBtn(Glyph.SKIP, 38) { skipPomo() })
        focus.addView(timerRow)
        timerBar = BarView(this).apply {
            fillColor = th.accent
            trackColor = ColorUtils.setAlphaComponent(th.text, 30)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(3)).apply { topMargin = dp(10) }
        }
        focus.addView(timerBar)
        panel.addView(focus)

        // reels card
        val reels = card()
        val reelsRow = hrow()
        val reelsCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        tvReels = lbl("", 14f, th.text, true)
        tvSession = lbl("", 12f, th.sub).apply { setPadding(0, dp(3), 0, 0) }
        reelsCol.addView(tvReels)
        reelsCol.addView(tvSession)
        reelsRow.addView(reelsCol)
        reelsRow.addView(glyphBtn(Glyph.MINUS, 32) { Store.adjustReels(-1) })
        reelsRow.addView(glyphBtn(Glyph.PLUS, 32) { Store.adjustReels(1) })
        reels.addView(reelsRow)
        budgetBar = BarView(this).apply {
            trackColor = ColorUtils.setAlphaComponent(th.text, 30)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(4)).apply { topMargin = dp(10) }
        }
        tvBudget = lbl("", 11f, th.sub).apply { setPadding(0, dp(4), 0, 0) }
        reels.addView(budgetBar)
        reels.addView(tvBudget)
        panel.addView(reels)

        // music card
        val music = card()
        val musicRow = hrow()
        tvTrack = lbl("", 13f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        btnMusicPlay = glyphBtn(Glyph.PLAY, 40) { musicCmd(1) }
        musicRow.addView(tvTrack)
        musicRow.addView(glyphBtn(Glyph.PREV, 36) { musicCmd(0) })
        musicRow.addView(btnMusicPlay)
        musicRow.addView(glyphBtn(Glyph.NEXT, 36) { musicCmd(2) })
        music.addView(musicRow)
        panel.addView(music)

        // tools card
        val tools = card()
        val row1 = hrow()
        chipTorch = chip("🔦") { toggleTorch() }
        chipEye = chip("👁") { toggleReminder("eyeOn") }
        chipWater = chip("💧") { toggleReminder("waterOn") }
        chipStretch = chip("🧘") { toggleReminder("stretchOn") }
        (chipTorch.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        row1.addView(chipTorch); row1.addView(chipEye); row1.addView(chipWater); row1.addView(chipStretch)
        tools.addView(row1)
        val row2 = hrow().apply { setPadding(0, dp(8), 0, 0) }
        val dice = chip("🎲") { rollDice() }
        (dice.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        row2.addView(dice)
        row2.addView(chip("🪙") { flipCoin() })
        row2.addView(chip("🔮") { askBall() })
        row2.addView(chip("🌬️") { showBreath(false) })
        tools.addView(row2)
        panel.addView(tools)

        root.addView(panel)

        params = WindowManager.LayoutParams(
            WRAP, WRAP,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        root.setOnTouchListener { _, e -> handleTouch(e) }
        refreshUi()
    }

    /** Rebuilds the whole island (after a settings change or rotation), keeping timer state. */
    private fun rebuild() {
        if (!added) return
        val wasExpanded = expanded
        expandAnim?.cancel()
        pulseAnim?.cancel()
        try { wm.removeView(root) } catch (e: Exception) { }
        added = false
        expanded = false
        buildUi()
        place()
        wm.addView(root, params)
        added = true
        if (wasExpanded) {
            expanded = true
            panel.visibility = View.VISIBLE
            bg.cornerRadius = expandedRadius()
        }
        armReminders()
        refreshMusic()
        refreshUi()
        place()
        scheduleDim()
    }

    // ---------------------------------------------------------------------------------------
    // placement (camera-aware, orientation-aware)
    // ---------------------------------------------------------------------------------------
    private fun applyGap(gap: Int, rowH: Int) {
        curGap = gap
        val glp = gapView.layoutParams as LinearLayout.LayoutParams
        glp.width = gap
        gapView.layoutParams = glp
        capsule.minimumHeight = rowH
        leftSec.minimumWidth = 0
        rightSec.minimumWidth = 0
        if (gap > 0) {
            val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            leftSec.measure(spec, spec)
            rightSec.measure(spec, spec)
            val w = max(leftSec.measuredWidth, rightSec.measuredWidth)
            leftSec.minimumWidth = w
            rightSec.minimumWidth = w
        }
    }

    private fun place(withGap: Boolean = true) {
        val sz = screen()
        val sw = sz.x
        val sh = sz.y
        val land = sw > sh
        val d = resources.displayMetrics.density
        val cut = Cam.topCutout(this, sw, sh)
        val sfx = if (land) "L" else "P"
        val offX = Store.int("offX$sfx")
        val offY = Store.int("offY$sfx")
        val anchor = Store.int("anchor")
        val camMode = Store.int("camMode")
        val gap = if (withGap) Placement.gapFor(cut, sw, anchor, camMode, offX, offY, d) else curGap
        val rowH = if (withGap) Placement.rowHeightFor(cut, gap, dp(36), d) else capsule.minimumHeight
        if (withGap) applyGap(gap, rowH)

        root.measure(
            View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST)
        )
        val pt = Placement.position(
            sw, sh, root.measuredWidth, root.measuredHeight, rowH, cut, anchor, camMode, gap, offX, offY, d
        )
        params.x = pt.x
        params.y = pt.y
        root.visibility = if (land && Store.bool("hideLandscape")) View.GONE else View.VISIBLE
        if (added) {
            try { wm.updateViewLayout(root, params) } catch (e: Exception) { }
        }
    }

    // ---------------------------------------------------------------------------------------
    // touch: tap / drag / long-press
    // ---------------------------------------------------------------------------------------
    private fun handleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                startX = params.x; startY = params.y
                moved = false
                longFired = false
                wake()
                h.postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!moved && hypot(dx, dy) > dpr(8)) {
                    moved = true
                    h.removeCallbacks(longPress)
                }
                if (moved) {
                    val sz = screen()
                    params.x = (startX + dx).toInt().coerceIn(0, max(0, sz.x - root.width))
                    params.y = (startY + dy).toInt().coerceIn(0, max(0, sz.y - root.height))
                    try { wm.updateViewLayout(root, params) } catch (ex: Exception) { }
                }
            }
            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(longPress)
                if (moved) finishDrag()
                else if (!longFired && e.y <= capsuleWrap.bottom) onTap()
                scheduleDim()
            }
            MotionEvent.ACTION_CANCEL -> h.removeCallbacks(longPress)
            MotionEvent.ACTION_OUTSIDE -> if (expanded) setExpanded(false)
            else -> return false
        }
        return true
    }

    private fun finishDrag() {
        val sz = screen()
        val sw = sz.x
        val sh = sz.y
        val d = resources.displayMetrics.density
        val land = sw > sh
        val sfx = if (land) "L" else "P"
        val cut = Cam.topCutout(this, sw, sh)
        val base = Placement.position(
            sw, sh, root.width, root.height, capsule.minimumHeight, cut,
            Store.int("anchor"), Store.int("camMode"), curGap, 0, 0, d
        )
        var ox = ((params.x - base.x) / d).roundToInt()
        var oy = ((params.y - base.y) / d).roundToInt()
        if (abs(ox) < 16 && abs(oy) < 12) {
            if (ox != 0 || oy != 0) haptic()
            ox = 0; oy = 0
        }
        Store.setIntQuiet("offX$sfx", ox)
        Store.setIntQuiet("offY$sfx", oy)
        place()
    }

    private fun onTap() {
        haptic()
        if (alertView.visibility == View.VISIBLE) {
            h.removeCallbacks(hideAlert)
            hideAlert.run()
        } else {
            setExpanded(!expanded)
        }
    }

    private fun wake() {
        if (!added) return
        h.removeCallbacks(dim)
        root.animate().cancel()
        root.alpha = 1f
    }

    private fun scheduleDim() {
        if (!added) return
        h.removeCallbacks(dim)
        if (Store.int("idleOpacity") < 100) h.postDelayed(dim, 4000)
    }

    private fun setExpanded(v: Boolean) {
        if (!added || v == expanded) return
        wake()
        expandAnim?.cancel()
        expanded = v
        if (v) {
            refreshMusic()
            refreshUi()
        }
        panel.visibility = View.VISIBLE
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val full = max(1, panel.measuredHeight)
        val lp = panel.layoutParams
        val from = if (v) 0 else full
        val to = if (v) full else 0
        val r0 = collapsedRadius()
        val r1 = expandedRadius()
        expandAnim = ValueAnimator.ofInt(from, to).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val hh = it.animatedValue as Int
                val t = hh / full.toFloat()
                lp.height = max(1, hh)
                panel.layoutParams = lp
                panel.alpha = t
                bg.cornerRadius = r0 + (r1 - r0) * t
                place(false)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    lp.height = WRAP
                    panel.layoutParams = lp
                    panel.alpha = 1f
                    if (!expanded) panel.visibility = View.GONE
                    bg.cornerRadius = if (expanded) r1 else r0
                    place()
                    scheduleDim()
                }
            })
            start()
        }
    }

    // ---------------------------------------------------------------------------------------
    // alerts
    // ---------------------------------------------------------------------------------------
    private fun showAlert(msg: String, vibrate: Boolean = true, ms: Long = 7000) {
        if (!added) return
        wake()
        alertView.text = msg
        if (alertView.visibility != View.VISIBLE) {
            alertView.alpha = 0f
            alertView.visibility = View.VISIBLE
            alertView.animate().alpha(1f).setDuration(200).start()
        }
        if (vibrate) { buzz(); pulse() }
        place()
        h.removeCallbacks(hideAlert)
        h.postDelayed(hideAlert, ms)
    }

    private fun setStrokeAlpha(a: Int) {
        bg.setStroke(max(1, dp(1)), ColorUtils.setAlphaComponent(th.accent, a.coerceIn(0, 255)))
    }

    private fun pulse() {
        pulseAnim?.cancel()
        pulseAnim = ValueAnimator.ofInt(strokeBase, 255, strokeBase).apply {
            duration = 900
            addUpdateListener { setStrokeAlpha(it.animatedValue as Int) }
            start()
        }
    }

    // ---------------------------------------------------------------------------------------
    // pomodoro (wall-clock based, so it never drifts)
    // ---------------------------------------------------------------------------------------
    private fun fullSec() = Store.int(if (isFocus) "focusMin" else "breakMin") * 60

    private fun remainingSec(): Int =
        if (pomoRunning) ((endAt - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)
        else pausedRemaining

    private fun togglePomo() {
        if (pomoRunning) {
            pausedRemaining = remainingSec()
            pomoRunning = false
        } else {
            pomoTouched = true
            pomoRunning = true
            endAt = SystemClock.elapsedRealtime() + pausedRemaining * 1000L
        }
        refreshUi()
    }

    private fun resetPomo() {
        pomoRunning = false
        pomoTouched = false
        isFocus = true
        pausedRemaining = fullSec()
        refreshUi()
    }

    private fun skipPomo() {
        val wasRunning = pomoRunning
        isFocus = !isFocus
        pausedRemaining = fullSec()
        pomoRunning = wasRunning
        if (wasRunning) endAt = SystemClock.elapsedRealtime() + pausedRemaining * 1000L
        refreshUi()
    }

    private fun tickPomo() {
        if (!pomoRunning) {
            if (!pomoTouched) pausedRemaining = fullSec()
            return
        }
        if (remainingSec() <= 0) finishPhase()
    }

    private fun finishPhase() {
        if (isFocus) {
            Store.addPomo()
            celebrateUntil = SystemClock.elapsedRealtime() + 12_000
            isFocus = false
            pausedRemaining = fullSec()
            pomoRunning = true
            endAt = SystemClock.elapsedRealtime() + pausedRemaining * 1000L
            showAlert("🍅 Focus session done! ${Store.int("breakMin")} min break. Step away from the screen.")
        } else {
            isFocus = true
            pausedRemaining = fullSec()
            pomoRunning = false
            pomoTouched = false
            showAlert("☕ Break's over. Ready for the next focus session?")
        }
    }

    // ---------------------------------------------------------------------------------------
    // reminders (20-20-20 eyes, water, stretch)
    // ---------------------------------------------------------------------------------------
    private fun interval(key: String) = Store.int(key) * 60_000L

    private fun toggleReminder(key: String) {
        Store.setBoolQuiet(key, !Store.bool(key))
        armReminders()
        Store.notifyState()
        refreshUi()
    }

    private fun armReminders() {
        val now = SystemClock.elapsedRealtime()
        val mins = intArrayOf(Store.int("eyeMin"), Store.int("waterMin"), Store.int("stretchMin"))
        val ch = BooleanArray(3) { mins[it] != armedMins[it] }
        for (i in 0 until 3) armedMins[i] = mins[i]
        eyeAt = if (!Store.bool("eyeOn")) 0L else if (eyeAt == 0L || ch[0]) now + interval("eyeMin") else eyeAt
        waterAt = if (!Store.bool("waterOn")) 0L else if (waterAt == 0L || ch[1]) now + interval("waterMin") else waterAt
        stretchAt = if (!Store.bool("stretchOn")) 0L else if (stretchAt == 0L || ch[2]) now + interval("stretchMin") else stretchAt
    }

    private fun tickReminders() {
        val now = SystemClock.elapsedRealtime()
        if (eyeAt != 0L && now >= eyeAt) {
            eyeAt = now + interval("eyeMin")
            showAlert("👁 20-20-20: look at something 20 ft away for 20 seconds")
        }
        if (waterAt != 0L && now >= waterAt) {
            waterAt = now + interval("waterMin")
            showAlert("💧 Time for a few sips of water")
        }
        if (stretchAt != 0L && now >= stretchAt) {
            stretchAt = now + interval("stretchMin")
            showAlert("🧘 Stand up, stretch, relax your shoulders")
        }
    }

    // ---------------------------------------------------------------------------------------
    // music
    // ---------------------------------------------------------------------------------------
    private fun refreshMusic() {
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            val list = msm.getActiveSessions(ComponentName(this, MediaListener::class.java))
            controller = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list.firstOrNull()
            musicAccess = true
        } catch (e: SecurityException) {
            controller = null
            musicAccess = false
        }
        val c = controller
        val md = c?.metadata
        musicPlaying = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        trackText = when {
            !musicAccess -> "Enable notification access for track info"
            title.isNullOrBlank() -> "Nothing playing"
            artist.isNullOrBlank() -> title
            else -> "$title · $artist"
        }
    }

    /** kind: 0 = previous, 1 = play/pause, 2 = next */
    private fun musicCmd(kind: Int) {
        val c = controller
        if (c != null) {
            val t = c.transportControls
            when (kind) {
                0 -> t.skipToPrevious()
                1 -> if (musicPlaying) t.pause() else t.play()
                else -> t.skipToNext()
            }
        } else {
            val code = when (kind) {
                0 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                1 -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                else -> KeyEvent.KEYCODE_MEDIA_NEXT
            }
            val am = getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        h.postDelayed({ refreshMusic(); refreshUi() }, 300)
    }

    // ---------------------------------------------------------------------------------------
    // torch
    // ---------------------------------------------------------------------------------------
    private fun findTorch() {
        try {
            val cm = getSystemService(CameraManager::class.java)
            val withFlash = cm.cameraIdList.filter {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            torchId = withFlash.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: withFlash.firstOrNull()
            cm.registerTorchCallback(torchCb, h)
        } catch (e: Exception) { }
    }

    private fun toggleTorch() {
        val id = torchId ?: return
        try {
            getSystemService(CameraManager::class.java).setTorchMode(id, !torchOn)
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------------------------------
    // playful bits: buddy, dice, coin, 8-ball, breathing
    // ---------------------------------------------------------------------------------------
    private fun buddyEmoji(): String {
        val now = SystemClock.elapsedRealtime()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            now < celebrateUntil -> "🥳"
            pomoRunning && isFocus -> "🤓"
            pomoRunning && !isFocus -> "😌"
            Store.inReels -> if (Store.sessionReels > 15) "🥴" else "👀"
            musicPlaying -> "🎧"
            hour < 6 -> "😴"
            else -> Store.BUDDY_EMOJI[Store.buddyLevel()]
        }
    }

    private fun cheer() {
        val lines = listOf(
            "You've got this 💪",
            "Tiny steps still count ✨",
            "Water break? 💧",
            "Proud of you for showing up 🌟",
            "One more focus round? 🍅",
            "Shoulders down. Jaw unclenched 😌"
        )
        showAlert(lines.random(), false, 3500)
    }

    private fun rolling(frames: List<String>, finalText: String) {
        rollRun?.let { h.removeCallbacks(it) }
        var i = 0
        val r = object : Runnable {
            override fun run() {
                if (!added) return
                if (i < frames.size) {
                    showAlert(frames[i], false, 2000)
                    i++
                    h.postDelayed(this, 70)
                } else {
                    showAlert(finalText, true, 5000)
                }
            }
        }
        rollRun = r
        h.post(r)
    }

    private fun rollDice() {
        val n = (1..6).random()
        rolling(List(8) { "🎲 ${(1..6).random()}" }, "🎲 You rolled a $n")
    }

    private fun flipCoin() {
        val heads = (0..1).random() == 0
        rolling(List(8) { if (it % 2 == 0) "🪙 Heads…" else "🪙 Tails…" }, if (heads) "🪙 Heads!" else "🪙 Tails!")
    }

    private fun askBall() {
        val answers = listOf(
            "It is certain", "Without a doubt", "Yes, definitely", "Most likely", "Signs point to yes",
            "Ask again later", "Cannot predict now", "Don't count on it", "My sources say no",
            "Very doubtful", "Go for it", "Maybe take a break first"
        )
        rolling(List(6) { "🔮 …" }, "🔮 ${answers.random()}")
    }

    private fun showBreath(shield: Boolean) {
        if (breath == null) breath = BreathOverlay(this) { Store.notifyState() }
        if (expanded) setExpanded(false)
        if (shield) {
            breath?.show("Take a breath", "You've reached today's reel limit.\nLet's reset for a moment.", 2, true)
        } else {
            breath?.show("Breathe", "Follow the circle. Three slow breaths.", 3, false)
        }
    }

    // ---------------------------------------------------------------------------------------
    // rendering
    // ---------------------------------------------------------------------------------------
    private fun fmt(s: Int): String {
        val v = s.coerceAtLeast(0)
        return "%02d:%02d".format(v / 60, v % 60)
    }

    private fun clock() = clockFmt.format(Date())

    private fun battery(): Int =
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun setActive(v: TextView, on: Boolean) {
        v.background = rect(if (on) th.accent else th.button, 14)
        v.alpha = if (on) 1f else 0.9f
    }

    private fun refreshUi() {
        if (!added) return
        val timeStr = fmt(remainingSec())

        // collapsed capsule
        val parts = ArrayList<String>()
        if (Store.bool("showTimer") && (pomoRunning || pomoTouched)) {
            parts.add((if (isFocus) "" else "☕ ") + timeStr)
        }
        if (Store.bool("showReels") && Store.inReels) parts.add("🎞 ${Store.sessionReels}")
        if (parts.isEmpty() && Store.bool("showClock")) parts.add(clock())
        if (Store.bool("showBattery")) parts.add("${battery()}%")
        tvMini.text = parts.joinToString("  ·  ")
        val hasRight = parts.isNotEmpty()
        rightSec.visibility = if (hasRight) View.VISIBLE else if (curGap > 0) View.INVISIBLE else View.GONE
        leftSec.setPadding(dp(14), 0, if (hasRight || curGap > 0) dp(8) else dp(14), 0)

        val showBuddy = Store.bool("showBuddy")
        tvBuddyMini.visibility = if (showBuddy) View.VISIBLE else View.GONE
        if (showBuddy) tvBuddyMini.text = buddyEmoji()
        val eqOn = Store.bool("showMusic") && musicPlaying
        eq.visibility = if (eqOn) View.VISIBLE else View.GONE
        eq.setRunning(eqOn)

        dotBg.setColor(
            when {
                pomoRunning && isFocus -> RED
                pomoRunning && !isFocus -> GREEN
                Store.inReels -> ORANGE
                else -> th.sub
            }
        )

        val full = max(1, fullSec())
        val frac = 1f - remainingSec().toFloat() / full
        pillProgress.visibility = if (pomoRunning && curGap == 0) View.VISIBLE else View.GONE
        pillProgress.fraction = frac

        // glow outline
        strokeBase = if (!Store.bool("glow")) 0 else if (pomoRunning || Store.inReels) 190 else 70
        if (pulseAnim?.isRunning != true) setStrokeAlpha(strokeBase)

        val sig = "${tvMini.text.length}|$showBuddy|$eqOn|$hasRight|${alertView.visibility}"
        if (sig != lastSig) {
            lastSig = sig
            place(true)
        }

        if (!expanded) return

        // expanded panel
        tvBuddy.text = buddyEmoji()
        tvMode.text = (if (isFocus) "FOCUS" else "BREAK") + "  ·  🍅 ${Store.pomosToday()} today"
        tvMode.setTextColor(if (isFocus) th.accent else GREEN)
        val g = Store.goal()
        tvGoal.text = if (g.isBlank()) "Set a focus goal in the Isle app" else g
        tvClock.text = "${clock()}  ·  🔋 ${battery()}%"
        tvTimer.text = timeStr
        btnPlay.setGlyph(if (pomoRunning) Glyph.PAUSE else Glyph.PLAY)
        timerBar.fillColor = if (isFocus) th.accent else GREEN
        timerBar.fraction = frac

        tvReels.text = "🎞 ${Store.reelsToday()} reels today · ${Store.reelMinutesToday()} min"
        tvSession.text = "This session: ${Store.sessionReels} reels · ${Store.sessionSec / 60} min"
        val lim = Store.int("reelLimit")
        if (lim > 0) {
            val f = Store.reelsToday().toFloat() / lim
            budgetBar.visibility = View.VISIBLE
            tvBudget.visibility = View.VISIBLE
            budgetBar.fraction = f
            budgetBar.fillColor = if (f >= 1f) RED else if (f >= 0.8f) ORANGE else th.accent
            tvBudget.text = "${Store.reelsToday()} of $lim daily reels"
        } else {
            budgetBar.visibility = View.GONE
            tvBudget.visibility = View.GONE
        }

        tvTrack.text = trackText
        btnMusicPlay.setGlyph(if (musicPlaying) Glyph.PAUSE else Glyph.PLAY)

        setActive(chipTorch, torchOn)
        setActive(chipEye, Store.bool("eyeOn"))
        setActive(chipWater, Store.bool("waterOn"))
        setActive(chipStretch, Store.bool("stretchOn"))
    }
}
