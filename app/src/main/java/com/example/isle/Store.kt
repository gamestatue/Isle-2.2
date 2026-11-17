package com.example.isle

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Shared state between the accessibility service, the overlay service and the app screens.
 * Everything runs on the main thread in one process, so plain fields + listeners are enough.
 */
object Store {

    val DEFAULTS: Map<String, Int> = mapOf(
        "reelEveryN" to 20,
        "reelEveryMin" to 10,
        "reelLimit" to 0,          // daily reel budget, 0 = off
        "focusMin" to 25,
        "breakMin" to 5,
        "eyeMin" to 20,
        "waterMin" to 45,
        "stretchMin" to 60,
        // appearance
        "theme" to 0,
        "customBg" to 0xFF101018.toInt(),
        "customAccent" to 0xFFFF375F.toInt(),
        "opacity" to 100,
        "corner" to 100,           // 0..100 roundness of the expanded panel / pill
        "size" to 1,               // 0 small, 1 medium, 2 large
        "idleOpacity" to 100,
        // position
        "anchor" to 0,             // 0 camera, 1 center, 2 left, 3 right
        "camMode" to 0,            // 0 wrap around camera, 1 sit below it, 2 ignore it
        "offXP" to 0, "offYP" to 0, "offXL" to 0, "offYL" to 0,
        // manual camera
        "camXPct" to 50, "camSizeDp" to 24, "camTopDp" to 10
    )

    val BOOL_DEFAULTS: Map<String, Boolean> = mapOf(
        "showMusic" to true, "showTimer" to true, "showReels" to true,
        "showClock" to true, "showBattery" to false, "showBuddy" to true,
        "haptics" to true, "alertVibrate" to true, "shield" to true,
        "glow" to true, "hideLandscape" to false, "camManual" to false,
        "autoStart" to false
    )

    private val BUDDY_THRESH = intArrayOf(0, 3, 8, 15, 30, 60, 100)
    val BUDDY_EMOJI = listOf("🥚", "🐣", "🐥", "🦆", "🦉", "🦅", "🐉")
    val BUDDY_NAME = listOf("Egg", "Hatchling", "Chick", "Duckling", "Owl", "Eagle", "Dragon")

    class Day(val label: String, val reels: Int, val min: Int, val pomos: Int)

    private lateinit var sp: SharedPreferences
    private var inited = false
    private val listeners = LinkedHashSet<() -> Unit>()
    private val settingsListeners = LinkedHashSet<() -> Unit>()

    /** Set by OverlayService while it is running; pops a message out of the island. */
    var alertSink: ((String) -> Unit)? = null

    /** Set by OverlayService: "breathe" or "shield". */
    var commandSink: ((String) -> Unit)? = null

    // Persisted daily stats
    private var day = ""
    private var reels = 0
    private var reelSec = 0L
    private var pomos = 0
    private var totalPomos = 0
    private var hist = ""

    // Live state
    private var inReelsFlag = false
    val inReels: Boolean get() = inReelsFlag
    var sessionReels = 0
        private set
    var sessionSec = 0
        private set
    private var lastInReelsAt = 0L
    private var ticks = 0

    fun init(c: Context) {
        if (inited) return
        sp = c.applicationContext.getSharedPreferences("isle", Context.MODE_PRIVATE)
        day = sp.getString("day", "") ?: ""
        reels = sp.getInt("reels", 0)
        reelSec = sp.getLong("reelSec", 0L)
        pomos = sp.getInt("pomos", 0)
        totalPomos = sp.getInt("totalPomos", 0)
        hist = sp.getString("hist", "") ?: ""
        inited = true
        rollover()
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun rollover() {
        val t = today()
        if (day != t) {
            if (day.isNotEmpty() && (reels > 0 || reelSec > 0 || pomos > 0)) {
                val e = hist.split(';').filter { it.isNotBlank() }.toMutableList()
                e.add("$day,$reels,$reelSec,$pomos")
                hist = e.takeLast(45).joinToString(";")
            }
            day = t; reels = 0; reelSec = 0L; pomos = 0
            save()
        }
    }

    private fun save() {
        sp.edit().putString("day", day).putInt("reels", reels)
            .putLong("reelSec", reelSec).putInt("pomos", pomos)
            .putInt("totalPomos", totalPomos).putString("hist", hist).apply()
    }

    fun flush() = save()

    // ---- listeners -------------------------------------------------------------------------
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun addSettingsListener(l: () -> Unit) { settingsListeners.add(l) }
    fun removeSettingsListener(l: () -> Unit) { settingsListeners.remove(l) }
    private fun notifyChanged() { listeners.toList().forEach { it() } }
    private fun notifySettings() { settingsListeners.toList().forEach { it() } }

    /** Called by the overlay service when it starts or stops so the app can update its button. */
    fun notifyState() = notifyChanged()

    fun alert(msg: String) { alertSink?.invoke(msg) }
    fun command(cmd: String) { commandSink?.invoke(cmd) }

    // ---- settings --------------------------------------------------------------------------
    fun int(k: String, d: Int = DEFAULTS[k] ?: 0): Int = sp.getInt(k, d)

    fun setInt(k: String, v: Int) {
        if (sp.contains(k) && sp.getInt(k, 0) == v) return
        sp.edit().putInt(k, v).apply()
        notifySettings()
    }

    /** Saves without telling the overlay to rebuild (used while dragging the island). */
    fun setIntQuiet(k: String, v: Int) { sp.edit().putInt(k, v).apply() }

    fun bool(k: String, d: Boolean = BOOL_DEFAULTS[k] ?: false): Boolean = sp.getBoolean(k, d)

    fun setBool(k: String, v: Boolean) {
        sp.edit().putBoolean(k, v).apply()
        notifySettings()
    }

    fun setBoolQuiet(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }

    fun goal(): String = sp.getString("goal", "") ?: ""
    fun setGoal(v: String) { sp.edit().putString("goal", v).apply() }

    fun resetPosition() {
        sp.edit().putInt("offXP", 0).putInt("offYP", 0).putInt("offXL", 0).putInt("offYL", 0).apply()
        notifySettings()
    }

    // ---- stats -----------------------------------------------------------------------------
    fun reelsToday(): Int { rollover(); return reels }
    fun reelMinutesToday(): Int { rollover(); return (reelSec / 60).toInt() }
    fun pomosToday(): Int { rollover(); return pomos }
    fun lifetimePomos(): Int = totalPomos

    fun addPomo() {
        rollover(); pomos++; totalPomos++; save(); notifyChanged()
    }

    fun buddyLevel(): Int {
        var lv = 0
        for (i in BUDDY_THRESH.indices) if (totalPomos >= BUDDY_THRESH[i]) lv = i
        return lv
    }

    /** Sessions still needed for the next evolution, or -1 at max level. */
    fun sessionsToNext(): Int {
        val lv = buddyLevel()
        return if (lv >= BUDDY_THRESH.size - 1) -1 else BUDDY_THRESH[lv + 1] - totalPomos
    }

    /** Progress 0..1 inside the current level. */
    fun buddyProgress(): Float {
        val lv = buddyLevel()
        if (lv >= BUDDY_THRESH.size - 1) return 1f
        val a = BUDDY_THRESH[lv]; val b = BUDDY_THRESH[lv + 1]
        return ((totalPomos - a).toFloat() / (b - a)).coerceIn(0f, 1f)
    }

    private fun historyMap(): Map<String, List<String>> {
        val m = HashMap<String, List<String>>()
        for (e in hist.split(';')) {
            val p = e.split(',')
            if (p.size == 4) m[p[0]] = p
        }
        return m
    }

    /** Last 7 days, oldest first; today is last. */
    fun week(): List<Day> {
        rollover()
        val map = historyMap()
        val key = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val name = SimpleDateFormat("EEE", Locale.getDefault())
        val out = ArrayList<Day>()
        for (i in 6 downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            val lbl = name.format(c.time).take(1).uppercase()
            if (i == 0) {
                out.add(Day(lbl, reels, (reelSec / 60).toInt(), pomos))
            } else {
                val p = map[key.format(c.time)]
                out.add(
                    Day(
                        lbl,
                        p?.get(1)?.toIntOrNull() ?: 0,
                        ((p?.get(2)?.toLongOrNull() ?: 0L) / 60).toInt(),
                        p?.get(3)?.toIntOrNull() ?: 0
                    )
                )
            }
        }
        return out
    }

    /** Consecutive days with at least one finished focus session. */
    fun focusStreak(): Int {
        rollover()
        val map = historyMap()
        val key = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var streak = if (pomos > 0) 1 else 0
        for (i in 1..44) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            val p = map[key.format(c.time)]
            if ((p?.get(3)?.toIntOrNull() ?: 0) > 0) streak++ else break
        }
        return streak
    }

    // ---- reels -----------------------------------------------------------------------------
    fun setInReels(on: Boolean) {
        if (on == inReelsFlag) return
        inReelsFlag = on
        if (on) lastInReelsAt = SystemClock.elapsedRealtime()
        notifyChanged()
    }

    fun addReel() {
        rollover()
        reels++
        sessionReels++
        save()

        var budgetHit = false
        val lim = int("reelLimit")
        if (lim > 0) {
            val warn = maxOf(1, (lim * 0.8f).toInt())
            if (reels == lim) {
                budgetHit = true
                alert("🛑 That's your daily limit of $lim reels.")
                if (bool("shield")) command("shield")
            } else if (reels > lim && (reels - lim) % 10 == 0) {
                budgetHit = true
                if (bool("shield")) command("shield")
            } else if (reels == warn && warn < lim) {
                budgetHit = true
                alert("⚠️ $reels of $lim reels used today")
            }
        }
        val n = int("reelEveryN")
        if (!budgetHit && n > 0 && sessionReels % n == 0) {
            val level = sessionReels / n
            alert(
                when (level) {
                    1 -> "🎞 $sessionReels reels in a row. Maybe wrap it up?"
                    2 -> "🎞 $sessionReels reels. Your to-do list misses you."
                    else -> "🎞 $sessionReels reels. Seriously, put the phone down 🙃"
                }
            )
        }
        notifyChanged()
    }

    /** Manual correction from the island's +/- buttons. */
    fun adjustReels(delta: Int) {
        rollover()
        reels = (reels + delta).coerceAtLeast(0)
        sessionReels = (sessionReels + delta).coerceAtLeast(0)
        save()
        notifyChanged()
    }

    /** Called once per second by the overlay service. */
    fun tickReelTime() {
        val now = SystemClock.elapsedRealtime()
        if (inReels) {
            lastInReelsAt = now
            rollover()
            reelSec++
            sessionSec++
            val m = int("reelEveryMin")
            if (m > 0 && sessionSec % (m * 60) == 0) {
                alert("⏱ ${sessionSec / 60} min of reels this session. Time check!")
            }
        } else if (now - lastInReelsAt > 10 * 60_000L && (sessionReels > 0 || sessionSec > 0)) {
            sessionReels = 0
            sessionSec = 0
        }
        if (++ticks % 15 == 0) save()
    }
}
