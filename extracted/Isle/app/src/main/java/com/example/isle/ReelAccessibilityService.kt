package com.example.isle

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat

/**
 * Watches only two things: which app is in front, and vertical scroll events inside the
 * configured reels/shorts players. It never reads, stores or transmits on-screen text.
 */
class ReelAccessibilityService : AccessibilityService() {

    private val tag = "IsleReels"
    private val h = Handler(Looper.getMainLooper())
    private var currentPkg: String? = null
    private var lastCountAt = 0L
    private var lastDumpAt = 0L

    /** While reels are open, re-check every 3s so switching to another tab inside the app is noticed. */
    private val recheck = object : Runnable {
        override fun run() {
            if (!Store.inReels) return
            val pkg = currentPkg
            if (pkg == null || !onReelsScreen(pkg)) {
                Store.setInReels(false)
                return
            }
            h.postDelayed(this, 3000)
        }
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = setReels(false)
    }

    override fun onServiceConnected() {
        Store.init(this)
        ContextCompat.registerReceiver(
            this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        try { unregisterReceiver(screenOff) } catch (e: Exception) { }
        setReels(false)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        // Ignore our own overlay, system UI and keyboards so they don't count as "leaving".
        if (pkg == packageName ||
            pkg.startsWith("com.android.systemui") ||
            pkg.contains("inputmethod", true) ||
            pkg.contains("keyboard", true)
        ) return

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                currentPkg = pkg
                setReels(pkg in ReelConfig.VIEW_IDS && onReelsScreen(pkg))
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (pkg !in ReelConfig.VIEW_IDS) return
                // Horizontal-only scrolls (carousels, story rings) are not reel swipes.
                if (Build.VERSION.SDK_INT >= 28 && e.scrollDeltaY == 0 && e.scrollDeltaX != 0) return
                currentPkg = pkg
                val on = onReelsScreen(pkg)
                setReels(on)
                if (on) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastCountAt > ReelConfig.DEBOUNCE_MS) {
                        lastCountAt = now
                        Store.addReel()
                    }
                }
            }
        }
    }

    private fun setReels(on: Boolean) {
        if (on == Store.inReels) return
        Store.setInReels(on)
        h.removeCallbacks(recheck)
        if (on) h.postDelayed(recheck, 3000)
    }

    @Suppress("DEPRECATION")
    private fun onReelsScreen(pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            if (root.packageName?.toString() != pkg) return false
            for (id in ReelConfig.VIEW_IDS[pkg].orEmpty()) {
                if (root.findAccessibilityNodeInfosByViewId(id).isNotEmpty()) return true
            }
            for (t in ReelConfig.TEXT_HINTS[pkg].orEmpty()) {
                if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return true
            }
            maybeDump(root, pkg)
            return false
        } finally {
            root.recycle()
        }
    }

    /** Debug aid: log candidate view IDs when nothing matched (at most every 8 s). */
    private fun maybeDump(root: AccessibilityNodeInfo, pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpAt < 8000) return
        lastDumpAt = now
        val rx = Regex("clip|reel|short|pager|viewer", RegexOption.IGNORE_CASE)
        val ids = LinkedHashSet<String>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen < 500) {
            val n = q.removeFirst()
            seen++
            n.viewIdResourceName?.let { if (rx.containsMatchIn(it)) ids.add(it) }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        Log.d(tag, "no rule matched in $pkg; candidate ids: $ids")
    }
}
