# Product Intent & Feature Map (`PRODUCT.md`)
> **1-Line Summary:** The permanent source of truth for user experience intent, active player/bookmark features, and pending feature wishes for mpvRx.

## 1. Core Vision & User Experience Intent
* **What This App Does (Plain English):** mpvRx is an advanced Android media player combining the native playback power of mpv with a modern Jetpack Compose UI, responsive split-screen bookmark workflows, cross-video tagging, and an isolated biometric Secure Folder sandbox.
* **How It Must Feel to Use:** Fluid, high-density, and non-intrusive. Video playback remains unhindered while inspecting bookmarks, private media never leaks into public history or profile statistics, and layout budgets adapt strictly across portrait and landscape screens without clipping or awkward letterboxing.
* **Dual-Orientation Golden Rule:** Every interface element must have dedicated configurations for Portrait and Landscape orientations, honoring the natural geometry and ergonomic affordances of each mode.

---

## 2. Active Features (Do Not Break)

### [FEATURE: Natural-Scroll-Cross-Video-Bookmark-Gallery] | Aliases: bookmark gallery, all bookmarks, tag filter modal, gallery columns
> **1-Line Summary:** Fullscreen modal browsing bookmarks across every video with natural vertical scrolling, autocomplete tag suggestions, and orientation-specific column caps (max 3 portrait, max 4 landscape).
* **User Intention (Non-Negotiable):** Users can scroll down infinitely and naturally through all library bookmarks. The layout regression that locked bookmarks into two horizontal strips is reverted.
* **Visual / UI Behavior:**
  * **Portrait Mode:** Hard-capped at max 3 columns (`1`, `2`, `3`, `Auto (Max 3)`).
  * **Landscape Mode:** Hard-capped at max 4 columns (`2`, `3`, `4`, `Auto (Max 4)`).
  * **Search & Filter:** Generous search bar with autocomplete suggestions right underneath it, allowing single or multi-tag selection.
  * **Card Presentation:** Exact 16:9 thumbnail cards with title pinned on top gradient and timestamp/tags pinned on bottom gradient.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/bookmarks/BookmarkGalleryScreen.kt` -> `BookmarkGalleryScreen`, `resolveColumnCount`, `formatGalleryTimestamp`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkGalleryRepository.kt` -> `observeGalleryItems`

### [FEATURE: Dynamic-Portrait-Video-And-Bookmark-Drawer] | Aliases: portrait 16:9 video, 1-row 2-row switcher, portrait 2-3 columns
> **1-Line Summary:** Portrait player dynamically budgets video at exact 16:9 ratio and renders 16:9 bookmark cards with a dedicated 1-row vs 2-row visible switcher and 2 or 3 columns only.
* **User Intention (Non-Negotiable):** In portrait mode, video plays at native 16:9 without letterboxing, and the bookmark drawer adapts underneath with a dedicated 1-row or 2-row option instead of the landscape percentage menu.
* **Visual / UI Behavior:**
  * **Video Surface:** Dynamic 16:9 aspect ratio (`width * 9 / 16`).
  * **Bookmark Drawer:** Occupies remaining height. Columns restricted to 2 or 3 columns.
  * **Row Toggle:** Switcher allows user to toggle between showing 1 visible row or 2 visible rows of cards.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/PlayerControls.kt` -> `BookmarkSplitPanel`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` -> `BookmarkGridCard`

### [FEATURE: Landscape-Sidebar-With-Global-Scope-Toggle] | Aliases: landscape sidebar, 20-35 width, global scope toggle, in-player scope
> **1-Line Summary:** Landscape bookmark sidebar maintaining 20% to 35% width steps with a non-overflow 2-tier toolbar and a scope toggle between this video's bookmarks and all library bookmarks.
* **User Intention (Non-Negotiable):** Browse bookmarks without leaving playback. Tapping the in-drawer scope toggle instantly switches between *This Video* and *All Videos (Global)* within the same drawer list.
* **Visual / UI Behavior:**
  * **Width Menu:** Cycles 20%, 25%, 30%, and 35% (video occupies remaining 80%, 75%, 70%, 65%).
  * **2-Tier Toolbar:** Tier 1 has action icons (Gallery, Add, Overlay, Close); Tier 2 has toggle chips (Card Count, Panel Width, Fingerprint/Preset, Scope Switcher).
  * **In-Player Scope Toggle:** Dedicated button switching between `🎬 This Video` and `🌐 All Bookmarks`.
  * **Floating Tag Filter Popup:** Floating overlay over video in landscape to filter bookmarks by tags on the fly.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` -> `BookmarkListContent`

### [FEATURE: Zero-Leak-Secure-Sandbox-And-Dedicated-PIN] | Aliases: secure sandbox, private media filter, secret playback gate, secure folder pin
> **1-Line Summary:** Complete quarantine of Secure Folder media using the Secure Folder's own PIN/password challenge rather than the device PIN, preventing double-prompt regressions.
* **User Intention (Non-Negotiable):** Private videos leave zero trace in public UI or watch stats. Direct attempts to play private files challenge for the Secure Folder's credential, not a duplicate device PIN.
* **Visual / UI Behavior:**
  * **Quarantined Stats:** Public `watch_stats.json` excludes private files; `watch_stats_secure.json` holds private records.
  * **Single Credential Gate:** Direct playback checks if Secure Folder is already unlocked; if locked, prompts Secure Folder PIN/pattern directly.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/secure/SecureMediaRegistry.kt` -> `isProtectedReference`, `update`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/securefolder/SecureUnlockActivity.kt` -> `launch`
  * `app/src/main/java/app/gyrolet/mpvrx/utils/media/MediaUtils.kt` -> `startGuarded`

### [FEATURE: Five-Tap-Stealth-Secret-Mode] | Aliases: stealth mode, 5 tap logo, secret statistics, profile secret mode
> **1-Line Summary:** Tapping the "mpvRx" header wordmark on Profile five times prompts authentication to reveal secret statistics, with instant re-lock on leaving.
* **User Intention (Non-Negotiable):** Secret statistics are invisible unless deliberately unlocked with 5 rapid taps. Backgrounding the app or clicking Lock purges decrypted stats from memory immediately.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/securefolder/SecretTapDetector.kt` -> `SecretTapDetector`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/browser/profile/ProfileScreen.kt` -> `ProfileScreen`

### [FEATURE: Robust-Path-Normalization-And-Flow-Guard] | Aliases: playback identity, normalize local path, unicode bengali filenames
> **1-Line Summary:** Pure-string local path segment normalization ensuring Bengali/Unicode filenames, content URIs, and opaque IDs never throw NullPointerException in Room Flow queries.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/PlaybackModels.kt` -> `PlaybackIdentity`, `normalizeLocalPath`

---

## 3. Active Implementation Roadmap (Next Steps)

### [FEATURE: Music-Player-Multi-Line-Lyrics-And-Dynamic-Artwork] | Aliases: 2-line lyrics, 3-line lyrics, 144dp album art, dynamic art scale
> **1-Line Summary:** Multi-line lyrics (default 2 lines, optional 3 lines) with automatic album artwork downscaling (144dp for 2-line, 112dp for 3-line) to eliminate transport button collisions.
* **User Intention:** Show readable lyrics context without pushing play/pause, seekbar, or bottom buttons off screen.
* **Visual / UI Behavior:**
  * **Lyrics Display:** Defaults to 2 simultaneous synchronized lines (toggleable to 1 or 3 lines).
  * **Artwork Scaling:** Scales from 176dp (1-line) down to 144dp (2-line default) and 112dp (3-line).
  * **Settings Controls:** Lyrics font size slider (`11sp`–`20sp`, default `14sp`) and button visibility toggles (speed chip, A-B repeat, codec tag, bottom icons).
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/AudioPlayerControls.kt` -> `AudioPlayerControls`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/preferences/PlayerControlsPreferencesScreen.kt`

### [FEATURE: Secure-Vault-And-Bookmark-Uninstall-Persistence] | Aliases: persistent vault, external storage migration, survive uninstall
> **1-Line Summary:** Moves or backs up Secure Folder media, bookmark thumbnails, and metadata to durable persistent storage so app uninstallation never destroys private files.
* **User Intention (Non-Negotiable):** Uninstalling mpvRx must not permanently delete the user's private media library, bookmarks, or database setup.
* **Target Integration Point:**
  * `app/src/main/java/app/gyrolet/mpvrx/database/repository/SecureFolderRepository.kt`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkThumbnailStore.kt`
