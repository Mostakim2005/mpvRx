# Feature Analysis & Proposed Changes (`FEATURE_ANALYSIS_AND_PROPOSED_CHANGES.md`)
> **1-Line Summary:** Definitive architectural specifications for layout calibrations, UI enhancements, and zero-cross-contamination security sandboxing across mpvRx.

## 1. In-Player Bookmark Section: Dual-Orientation Layout Specifications

### [SUBSYSTEM: Portrait-Video-Player-Layout] | Aliases: portrait split, 45-55 layout, portrait bookmark drawer
> **1-Line Summary:** Calibrates portrait video playback surface to 45% height and bookmark drawer to 55% height with a strict 2-column grid.
* **Video Playback Area (45% Height):**
  * Video rendering surface, title bar, MPV controls, and timeline scrubber.
* **In-Player Bookmark Drawer (55% Total Height):**
  * **Compact Auxiliary Toolbar (7% Height):**
    * Live system clock badge (`🕒 HH:mm:ss`).
    * Audio Fingerprint / Preset button (`🔲 FP`).
    * Row selector button (`⊞ 2R`).
    * Full gallery modal launcher (`↗ Gal`).
    * Tag filter popup launcher (`🏷️`).
    * Compact single toggleable scope icon (`🎬 Vid` ⇄ `🏷️ Tags`).
    * Close drawer button (`✕`).
  * **Thumbnail Row 1 (24% Height):** Cards 1 and 2 in a 2-column grid with timestamp badge and tag.
  * **Thumbnail Row 2 (24% Height):** Cards 3 and 4 in a 2-column grid.
  * **Proportion Budget:** 45% Video + 7% Toolbar + 24% Row 1 + 24% Row 2 = 100% Height with zero vertical clipping.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/PlayerControls.kt` -> `BookmarkSplitPanel`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` -> `BookmarkGridCard`

### [SUBSYSTEM: Landscape-Video-Player-Layout] | Aliases: landscape sidebar, variable width, 3-tier toolbar
> **1-Line Summary:** Variable 20% to 35% bookmark sidebar width with a non-overflow 3-tier toolbar and floating tag filter popup.
* **Variable Sidebar Width:**
  * User-toggleable across `20%`, `25%`, `30%`, and `35%` (video fills remaining `80%`, `75%`, `70%`, or `65%`).
* **Multi-Row Non-Overflow Toolbar:**
  * **Row 1 (Header Tier):** Live system clock + Quick Add (`➕`) + Overlay (`⛶`) + Close (`✕`).
  * **Row 2 (Existing Tools Tier):** Audio Fingerprint (`🔲 FP`), Row Selector (`⊞ 3R`), Width Switcher (`20%`–`35%`), and Full Gallery button (`↗ Gal`).
  * **Row 3 (Scope & Tag Tier):** Compact Scope Switcher (`🎬 This Video` ⇄ `🏷️ All by Tags`) + Tag Filter button (`🏷️ Tags...`).
* **Floating Tag Filter Popup:**
  * Modal dialog floating over video playback area enabling search and tag filtering without leaving playback.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` -> `BookmarkListContent`

---

## 2. Bookmark Gallery: Dual-Orientation Column & Height Limits

### [FEATURE: Landscape-Bookmark-Gallery] | Aliases: gallery landscape, 4 columns, gallery height budget
> **1-Line Summary:** Max 4 columns with a 34% controls / 33% row 1 / 33% row 2 height budget so both rows fit on screen.
* **Column Limit:** Up to 4 columns (`2`, `3`, `4`, and `Auto (Max 4)`).
* **Height Allocation (100% Total):**
  * Controls & Search Area: 34% (minimal title/pill header + padded search row).
  * Thumbnail Row 1: 33% max height.
  * Thumbnail Row 2: 33% max height.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/bookmarks/BookmarkGalleryScreen.kt` -> `BookmarkGalleryScreen`, `LANDSCAPE_MAX_COLUMNS`

### [FEATURE: Portrait-Bookmark-Gallery] | Aliases: gallery portrait, 3 columns max, phone gallery
> **1-Line Summary:** Hard-capped at 3 columns with an ultra-compact top bar ensuring 6 cards remain visible above the fold.
* **Column Limit:** Hard-capped at 3 columns (`1`, `2`, `3`, and `Auto (Max 3)`). Anything over 3 is blocked on phone screens.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/bookmarks/BookmarkGalleryScreen.kt` -> `PORTRAIT_MAX_COLUMNS`

---

## 3. Music Player: 2-Line Lyrics Default & Compact Settings

### [WISHLIST: Music-Player-Lyrics-And-Artwork] | Aliases: 2-line lyrics, 144dp album art, lyrics slider
> **1-Line Summary:** Defaults music player to 2-line lyrics with dynamic 144dp album art scaling and button visibility preferences.
* **Default Lyrics Mode:** 2 lines displayed simultaneously.
* **Dynamic Artwork Scaling:** Scales down to `144dp x 144dp` in 2-line mode so lyrics and transport buttons never collide.
* **Compact Settings:** Slider for lyrics font size (`11sp`–`20sp`) and button visibility toggles.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/AudioPlayerControls.kt` -> `AudioPlayerControls`

---

## 4. Universal 5-Tap Stealth Security Architecture

### [FEATURE: Universal-Five-Tap-Stealth-Activation] | Aliases: 5 tap secret, stealth mode, profile secret stats
> **1-Line Summary:** Stealth multi-tap gesture requiring ≥ 5 rapid taps on the "mpvRx" header logo to unlock secret statistics.
* **Universal 5+ Tap Activation Protocol:**
  * Replaces legacy double-tap or long-press gestures.
  * Tapping the "mpvRx" header logo ≥ 5 times prompts `BiometricPrompt` / `DeviceCredentialPrompt`.
  * Instant re-lock upon tapping 5 times again, tapping Lock, or backgrounding the app.
* **Zero-Cross-Contamination Enforcement:**
  * `WatchStatsRepository.kt`: Quarantines private file statistics.
  * `RecentlyPlayedRepository.kt`: Excludes private playback from public history.
  * `MediaUtils.kt`: Intercepts direct playback of private URIs with credential challenge.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/securefolder/SecretTapDetector.kt` -> `SecretTapDetector`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/secure/SecureMediaRegistry.kt` -> `isProtectedReference`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/securefolder/SecureUnlockActivity.kt` -> `launch`
