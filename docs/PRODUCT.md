# Product Intent & Feature Map (`PRODUCT.md`)
> **1-Line Summary:** The permanent source of truth for user experience intent, active player/bookmark features, and pending feature wishes for mpvRx.

## 1. Core Vision & User Experience Intent
* **What This App Does (Plain English):** mpvRx is an advanced Android media player combining the native playback power of mpv with a modern Jetpack Compose UI, responsive split-screen bookmark workflows, cross-video tagging, and an isolated biometric Secure Folder sandbox.
* **How It Must Feel to Use:** Fluid, high-density, and non-intrusive. Video playback remains unhindered while inspecting bookmarks, private media never leaks into public history or profile statistics, and layout budgets adapt strictly across portrait and landscape screens without clipping or awkward letterboxing.

---

## 2. Active Features (Do Not Break)

### [FEATURE: Sketch-Bookmark-Cards-And-Split-Panel] | Aliases: bookmark cards, split panel, 16:9 cards, panel size, card count
> **1-Line Summary:** Split-screen bookmark drawer displaying non-stretched 16:9 frame thumbnails with toggleable panel widths (20%–35%) and selectable visible card counts (3, 4, 5).
* **User Intention (Non-Negotiable):** Video viewport and bookmark drawer must coexist without letterboxing or horizontal stretching. Bookmark card height must strictly derive from panel width so cards stay exactly 16:9.
* **Visual / UI Behavior:** In landscape, user can toggle sidebar width between 20%, 25%, 30%, and 35% (video occupies the remainder). In portrait, video takes 45% and drawer takes 55% with a strict 2-column grid. Single-tap seeks; long-press opens edit/delete dialog.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/PlayerControls.kt` -> `BookmarkSplitPanel`, `videoWeight`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` -> `BookmarkGridCard`, `cycleBookmarkPanelSizePercent`, `cycleBookmarkGridCardCount`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/PlayerViewModel.kt` -> `bookmarkPanelSizePercent`, `bookmarkGridCardCount`

### [FEATURE: Smart-Fingerprint-Sync-And-Preset-Packs] | Aliases: auto fingerprint, preset packs, bookmark archive, zip backup
> **1-Line Summary:** Automatically tracks bookmarks across renamed or moved media via file metrics, and exports/imports self-contained `.mpvrx-bookmarks.zip` preset packs with frame thumbnails.
* **User Intention (Non-Negotiable):** Moving a video, renaming a release, or replacing a low-res file with a high-res copy must preserve its bookmarks without manual re-tagging. Users can also transfer bookmark sets between devices.
* **Visual / UI Behavior:** Presets dialog is accessible from the fingerprint button on the bookmark panel. Displays assigned preset codes and provides "Save file" and "Load file" ZIP actions.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkMediaMatcher.kt` -> `exactFingerprint`, `normalizedStem`, `isUpgradeMatch`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkPresetArchive.kt` -> `writeArchive`, `readArchive`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkPresetDialog.kt` -> `BookmarkPresetDialog`

### [FEATURE: Multi-Tag-Bookmarks-And-Vocabulary-Memory] | Aliases: bookmark tags, tag chips, tag suggestions, tag memory
> **1-Line Summary:** Assign multiple searchable tag chips to bookmarks with persistent autocomplete suggestions remembered even after individual bookmarks are deleted.
* **User Intention (Non-Negotiable):** Organizing scenes, highlights, and dialogues using reusable tags across the entire media library without retyping long strings.
* **Visual / UI Behavior:** While editing or creating a bookmark, tag input provides suggestions from previously used tags. Cards display compact tag chips alongside timestamps.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/database/dao/BookmarkTagDao.kt` -> `observeTagsForBookmark`, `observeAllKnownTags`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkTagRules.kt` -> `normalize`, `splitTags`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` -> `BookmarkTagChipRow`

### [FEATURE: Cross-Video-Bookmark-Gallery] | Aliases: bookmark gallery, all bookmarks, tag filter modal, gallery columns
> **1-Line Summary:** Fullscreen modal browsing bookmarks across every video in the library with tag filtering, scope pills, and orientation-specific column caps (max 3 portrait, max 4 landscape).
* **User Intention (Non-Negotiable):** Search and jump directly to any tagged scene across the entire library. In landscape, 34% height for controls and 33%/33% for two thumbnail rows guarantees zero vertical scrolling.
* **Visual / UI Behavior:** Header provides `All Videos` vs `🔒 Secure` toggle, search input with quick tag suggestion chips, and column count selector (Auto, 2, 3, 4). Tapping any bookmark immediately launches that video at the exact timestamp.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/bookmarks/BookmarkGalleryScreen.kt` -> `BookmarkGalleryScreen`, `resolveColumnCount`, `formatGalleryTimestamp`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkGalleryRepository.kt` -> `observeGalleryItems`

### [FEATURE: Zero-Leak-Secure-Folder-Sandbox] | Aliases: secure sandbox, private media filter, secret playback gate
> **1-Line Summary:** Complete quarantine of Secure Folder media preventing private titles and bookmarks from ever appearing in public history, watch statistics, or unauthenticated playback.
* **User Intention (Non-Negotiable):** Private videos must leave zero trace in public UI. Attempting to play a private file directly via intent or gallery bookmark must trigger the system PIN/biometric challenge first.
* **Visual / UI Behavior:** Normal profile shows zero watch stats for secure files. Recently Played never stores secure rows. Launching private media routes through `SecureUnlockActivity`.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/secure/SecureMediaRegistry.kt` -> `isProtectedReference`, `update`
  * `app/src/main/java/app/gyrolet/mpvrx/database/repository/RecentlyPlayedSecureFilter.kt` -> `isProtected`
  * `app/src/main/java/app/gyrolet/mpvrx/repository/WatchStatsRepository.kt` -> `sanitized`, `isProtected`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/securefolder/SecureUnlockActivity.kt` -> `launch`
  * `app/src/main/java/app/gyrolet/mpvrx/utils/media/MediaUtils.kt` -> `startGuarded`

### [FEATURE: Five-Tap-Stealth-Secret-Mode] | Aliases: stealth mode, 5 tap logo, secret statistics, profile secret mode
> **1-Line Summary:** Tapping the "mpvRx" header wordmark on Profile five times initiates credential authentication to reveal private watch statistics, with instant re-lock on leaving.
* **User Intention (Non-Negotiable):** Private statistics are only reachable through stealth interaction; accidental taps do not reveal vault presence, and leaving the screen purges decrypted stats from memory immediately.
* **Visual / UI Behavior:** 5 rapid taps within 3 seconds prompts Biometric/Device PIN. When unlocked, displays `🔒 Secret Mode` badge and a Lock button. Tapping Lock, backgrounding the app, or tapping 5 times again locks instantly.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/securefolder/SecretTapDetector.kt` -> `SecretTapDetector`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/browser/profile/ProfileScreen.kt` -> `ProfileScreen`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/preferences/WatchStatsScreen.kt` -> `WatchStatsScreen`

### [FEATURE: Robust-Path-Normalization-And-Flow-Guard] | Aliases: playback identity, normalize local path, unicode filename fix
> **1-Line Summary:** Pure-string local path segment normalization ensuring Bengali/Unicode filenames, content URIs, and opaque IDs never throw NullPointerException in Room Flow queries.
* **User Intention (Non-Negotiable):** Playing media with non-ASCII characters or content provider URIs must never crash background query dispatchers or Room emission flows.
* **Visual / UI Behavior:** Seamless playback start and history tracking regardless of filename language or URI scheme.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/PlaybackModels.kt` -> `PlaybackIdentity`, `normalizeLocalPath`, `canonicalizeUri`
  * `app/src/test/java/app/gyrolet/mpvrx/ui/player/PlaybackIdentityTest.kt` -> `forLocalPathHandlesUnicodeBengaliFilenamesWithoutNpe`

---

## 3. Planned Features & App-Merge Wishlist

### [WISHLIST: Music-Player-Two-Line-Lyrics-And-Artwork-Scale] | Aliases: 2 line lyrics, dynamic artwork scale, 144dp art, lyrics font size
> **1-Line Summary:** Default the expanded music player to 2-line lyrics with automatically downscaled 144dp album art and preference toggles for transport chip visibility.
* **User Intention:** Show sufficient lyrics context without pushing play/pause or progress sliders below the fold on compact phone screens.
* **Target Integration Point:**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/AudioPlayerControls.kt`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/preferences/PlayerControlsPreferencesScreen.kt`

### [WISHLIST: In-Player-Floating-Tag-Filter-And-Live-Clock] | Aliases: floating tag popup, landscape 3-tier toolbar, live player clock
> **1-Line Summary:** In-player floating tag filter checklist overlay and live `HH:mm:ss` system clock in the bookmark toolbar.
* **User Intention:** Filter visible bookmark cards on the fly without covering the video surface or opening full-screen modals.
* **Target Integration Point:**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt`
