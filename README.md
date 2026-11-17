# Isle 2.0: a dynamic-island overlay for Android

A floating island at the top of your screen (think iPhone's Dynamic Island) that counts reels, runs a
focus timer, controls music, nags you to drink water, and has a little buddy that reacts to what you do.

## What's new in 2.0

**Works on every phone shape**
- Detects the camera hole or notch (`Display.cutout`) and **wraps the island around it**, so the camera sits
  in a gap in the middle instead of covering your content. Off-centre camera, wide notch, or no cutout all
  handled (it sits below the camera when it can't wrap).
- Rotation aware: position is stored per orientation, re-computed on rotate / fold, and clamped to the screen.
- Optional **manual camera** calibration for phones that hide or fake the cutout.
- The app itself is edge-to-edge, cutout-safe, rotates, and caps its column width on tablets / landscape.

**Customize everything** (app → Customize tab, changes show live on the real island)
- 8 themes + fully custom colours, size (S/M/L), roundness, opacity, accent glow
- Anchor to camera / centre / left / right, fine offsets, or just drag it (snaps back to the camera)
- Choose what the compact pill shows: music bars, timer, reels, clock, battery, buddy
- Fade when idle, haptics, vibrate on alerts, hide in landscape

**New features**
- Live **preview** of your phone with the island
- **Buddy**: an egg that hatches and evolves (🥚→🐣→🐥→🦆→🦉→🦅→🐉) as you finish focus sessions; its face reacts to focusing, reels, music, night
- **Daily reel limit** with a progress bar and a **breathing shield** that pauses you when you hit it
- **Breathing exercise**, 🎲 dice, 🪙 coin flip, 🔮 magic 8-ball
- Weekly reels chart, today's stats, focus streak
- Long-press the island to start / pause the timer; animated expand; music equaliser bars
- Start on boot

**Bugs fixed**
- Island hidden behind the camera / wrong place on other phones or after rotating (positions were raw pixels from screen centre)
- Pomodoro timer drifted (now wall-clock based); breaks and phases are correct after settings change
- Tapping the panel background collapsed it by accident
- 24-hour clock was hard-coded (now follows system setting)
- Settings needed a manual "Save" and accepted junk numbers (now steppers, autosave, applied instantly)
- Reminder intervals didn't re-arm after changing them
- Torch state could get out of sync with other apps; vibrator API modernised; receiver flags fixed
- Horizontal carousel scrolls no longer count as reels
- Panel could be wider than small screens

## Build & install

1. Open the `Isle` folder in **Android Studio** (Koala or newer) and let Gradle sync, or push to GitHub and
   grab the APK from the *Build APK* workflow artifact.
2. Open **Isle** → follow *Finish setup*: **Display over other apps**, **Accessibility** (reel counter),
   **Notification access** (now-playing music).
3. Tap **Start Isle**. Open **Customize** to make it yours.

> **Switches greyed out?** Android 13+ blocks accessibility / notification access for sideloaded apps.
> Settings → *Open app info* → ⋮ → **Allow restricted settings**, then retry.

## If the island is slightly off on your phone

Customize → Position: change *Around the camera* (Wrap / Below / Ignore) and the offsets.
If the camera isn't detected at all: Customize → Camera → *Set camera manually*.

## Reel detection may need tuning

Instagram and YouTube rename internal view IDs between updates. Detection rules live in `ReelConfig.kt`.
If the counter doesn't move: `adb logcat -s IsleReels`, scroll some reels, and paste the right ID into
`ReelConfig.VIEW_IDS`.

## Privacy

The accessibility service only checks which app is in front and whether a reels/shorts player is on screen.
No text or content is read, stored or sent. There is no networking code and no internet permission.

## Layout

```
OverlayService.kt   the island: UI, placement, timer, reminders, music, torch, buddy, tools
Placement.kt        themes + camera detection + camera-aware position math (shared with the preview)
BreathOverlay.kt    full-screen breathing / limit shield
Store.kt            settings, daily + weekly stats, buddy, reel budget
Widgets.kt          iOS-style switch, slider, segmented control, glyphs, preview, chart
Kit.kt              grouped-list UI builder for the app screens
MainActivity.kt     Home / Customize / Settings
```
