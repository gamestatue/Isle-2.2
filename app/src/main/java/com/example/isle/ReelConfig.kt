package com.example.isle

/**
 * Which apps count and how Isle recognises their "reels" screen.
 *
 * Apps change their internal view IDs between updates, so this is the file to tune.
 * Run `adb logcat -s IsleReels` while scrolling reels: when no rule matches, the service logs
 * candidate view IDs (anything containing clip/reel/short/pager/viewer) that you can paste below.
 */
object ReelConfig {
    private const val IG = "com.instagram.android"
    private const val YT = "com.google.android.youtube"

    /** Minimum gap between two counted swipes. */
    const val DEBOUNCE_MS = 900L

    /** If any of these view IDs exists on screen, the user is in the reels/shorts player. */
    val VIEW_IDS: Map<String, List<String>> = mapOf(
        IG to listOf(
            "$IG:id/clips_viewer_view_pager",
            "$IG:id/clips_swipe_refresh_container",
            "$IG:id/clips_viewer_video_layout"
        ),
        YT to listOf(
            "$YT:id/reel_recycler",
            "$YT:id/reel_player_page_container",
            "$YT:id/reel_watch_fragment_root"
        )
    )

    /** Fallback: text / content-description fragments that only appear in the reels player. */
    val TEXT_HINTS: Map<String, List<String>> = mapOf(
        IG to listOf("Reel by")
    )
}
