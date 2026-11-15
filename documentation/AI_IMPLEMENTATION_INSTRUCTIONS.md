# Comprehensive AI Implementation Specification (`AI_IMPLEMENTATION_INSTRUCTIONS.md`)
> **1-Line Summary:** Complete specifications and acceptance criteria for security sandboxing, gallery column limits, in-player drawer proportions, and music player controls.

## 1. Zero-Cross-Contamination Security Sandbox

### [SUBSYSTEM: Security-Sandbox-Quarantine] | Aliases: private media isolation, public filter, credential gate
> **1-Line Summary:** Strict quarantine ensuring private media leaves zero trace in public history, watch statistics, or unauthenticated playback.
* **Public / Normal Mode Filter:** In `BookmarkGalleryRepository`, `WatchStatsRepository`, `RecentlyPlayedRepository`, exclude any item matching `SecureMediaRegistry.isProtectedReference()`.
* **Direct Playback Guard:** In `MediaUtils.playFile` and `BookmarkGalleryScreen`, intercept private URIs with `SecureUnlockActivity.launch()`.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/secure/SecureMediaRegistry.kt` -> `isProtectedReference`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/securefolder/SecureUnlockActivity.kt` -> `launch`

---

## 2. Universal 5+ Tap Stealth Security Protocol

### [FEATURE: Five-Tap-Stealth-Logo-Access] | Aliases: 5 tap secret mode, secret stats, stealth gesture
> **1-Line Summary:** Tapping the Profile header wordmark ≥ 5 times prompts biometric authentication to reveal secret statistics.
* **Multi-Tap Detector:** Requires ≥ 5 rapid taps within a 3-second sliding window on the "mpvRx" logo in `ProfileScreen.kt`.
* **Immediate Re-Lock:** Purges secret statistics from memory upon tapping 5 times again, tapping Lock, or when `ON_STOP` lifecycle fires.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/securefolder/SecretTapDetector.kt` -> `secretTaps`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/browser/profile/ProfileScreen.kt` -> `ProfileScreen`

---

## 3. Bookmark Gallery: Dual-Orientation Layout & Column Caps

### [FEATURE: Bookmark-Gallery-Orientation-Limits] | Aliases: gallery column limits, landscape gallery, portrait gallery
> **1-Line Summary:** Restricts gallery columns to max 4 in landscape and max 3 in portrait with strict height budgets.
* **Landscape Mode:** Controls Area = 34%, Row 1 = 33%, Row 2 = 33%. Columns: 2, 3, 4, Auto (Max 4).
* **Portrait Mode:** Columns: 1, 2, 3, Auto (Max 3).
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/bookmarks/BookmarkGalleryScreen.kt` -> `PORTRAIT_MAX_COLUMNS`, `LANDSCAPE_MAX_COLUMNS`

---

## 4. In-Player Bookmarks: Dual-Orientation Specifications

### [SUBSYSTEM: In-Player-Bookmark-Drawer-Proportions] | Aliases: portrait 45-55, landscape 20-35, 16:9 cards
> **1-Line Summary:** Dual-orientation drawer budgeting video vs bookmark drawer with exact 16:9 thumbnail dimensions.
* **Portrait Mode:** 45% Video / 55% Drawer (7% Toolbar, 24% Row 1, 24% Row 2).
* **Landscape Mode:** Variable 20%, 25%, 30%, 35% sidebar width with non-overflow toolbar.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/PlayerControls.kt` -> `BookmarkSplitPanel`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` -> `BookmarkGridCard`

---

## 5. Music Player: 2-Line Lyrics Default & Button Customization

### [WISHLIST: Audio-Player-Lyrics-Default] | Aliases: 2-line lyrics, 144dp artwork, music preferences
> **1-Line Summary:** Default 2-line lyrics with dynamic 144dp album artwork scaling and button visibility preferences.
* **Default Multi-Line Lyrics:** Default to 2 lines simultaneously.
* **Dynamic Artwork Scaling:** Scales to `144dp x 144dp` in 2-line mode.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/AudioPlayerControls.kt` -> `AudioPlayerControls`
