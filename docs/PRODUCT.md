# Product Intent & Feature Map (`PRODUCT.md`)
> **1-Line Summary:** The permanent source of truth for user experience intent, active player/bookmark features, and pending feature wishes for mpvRx.

## 1. Core Vision & User Experience Intent
* **What This App Does (Plain English):** mpvRx is an advanced Android media player combining the native playback power of mpv with a modern Jetpack Compose UI, responsive split-screen bookmark workflows, cross-video tagging, an isolated biometric Secure Folder sandbox, and a customized music playback and downloading suite.
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

### [FEATURE: Dynamic-Portrait-Video-And-Bookmark-Drawer] | Aliases: portrait native ratio video, portrait dynamic rows, portrait 2-3 columns, portrait tag filter
> **1-Line Summary:** Portrait player dynamically budgets video at its native aspect ratio (no hardcoded ratio), allocates remaining vertical space for bookmark cards (1, 2, or 3+ rows if space permits), caps columns between 2 and 3, and adds a tag filter button in the portrait toolbar.
* **User Intention (Non-Negotiable):** In portrait mode, video plays at its true native aspect ratio without black borders or hardcoding. The bookmark drawer consumes whatever vertical height remains, displaying 16:9 thumbnail cards with a switcher for visible row capacity (1-row, 2-rows, or more if vertical space permits) and column options strictly between 2 and 3. The tag filter popup must be directly accessible from the portrait drawer.
* **Visual / UI Behavior:**
  * **Video Surface:** Dynamic height based on native media aspect ratio (`containerWidth / mediaAspectRatio`). Wide videos (e.g. 21:9) naturally yield more vertical space to the drawer below.
  * **Bookmark Drawer:** Consumes 100% of remaining screen height.
  * **Column Selector:** Strictly restricted to 2 or 3 columns (`2 Cols` ⇄ `3 Cols`). Hard-capped at max 3.
  * **Row Capacity Switcher:** Switcher allows user to toggle visible card row density (`1 Row`, `2 Rows`, `3 Rows / Auto-Fit`).
  * **Portrait Tag Filter:** Dedicated `🏷️ Filter` button in the portrait drawer toolbar that opens the tag selection overlay directly in portrait mode.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/PlayerControls.kt` -> `BookmarkSplitPanel`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` -> `BookmarkGridCard`, `BookmarkListContent`

### [FEATURE: Landscape-Sidebar-With-Global-Scope-Toggle] | Aliases: landscape sidebar, 20-35 width, global scope toggle, in-player scope, compact toolbar
> **1-Line Summary:** Landscape bookmark sidebar maintaining 20% to 35% width steps with a non-overflowing compact 2-tier toolbar, floating tag overlay, and an in-drawer toggle between this video's bookmarks and all library bookmarks.
* **User Intention (Non-Negotiable):** Browse bookmarks without leaving playback. Buttons must never clip or overlap in narrow widths (20%-35%). Tapping the in-drawer scope toggle instantly switches between *This Video* and *All Videos (Global)* within the same drawer list.
* **Visual / UI Behavior:**
  * **Width Menu:** Cycles 20%, 25%, 30%, and 35% (video occupies remaining 80%, 75%, 70%, 65%).
  * **Non-Overflow 2-Tier Toolbar:**
    * *Tier 1 (Action Icons):* Compact icon buttons (`🏷️ Tag Filter`, `⊞ Add`, `↗ Gallery`, `✕ Close`).
    * *Tier 2 (Toggle Chips):* Compact, toggleable chips (`Scope: 🎬/🌐`, `Width: 20-35%`, `Cols: 1/2`). Text strings are short to prevent wrapping collisions.
  * **In-Player Scope Toggle:** Dedicated toggle button switching between `🎬 This Video` and `🌐 All Bookmarks`.
  * **Floating Tag Filter Popup:** Floating dialog overlay centered over the video in landscape to filter bookmarks by tags on the fly without halting playback.
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

### [FEATURE: Music-Player-Multi-Line-Lyrics-And-Split-Landscape] | Aliases: 2-line lyrics, 3-line lyrics, landscape 50-50 split, landscape 40-60 split
> **1-Line Summary:** Multi-line synchronized lyrics in portrait (2-line default or 3-line option with dynamic 144dp/112dp artwork) and configurable artwork vs lyrics split in landscape (50/50, 40/60, 60/40).
* **User Intention:** Show readable lyrics context without clipping controls. In landscape, allow user to decide how much area to dedicate to album artwork/video vs. synchronized lyrics.
* **Visual / UI Behavior:**
  * **Portrait Mode:** 2-line synchronized lyrics default (toggleable to 3-line). Artwork dynamically scales (144dp for 2-line, 112dp for 3-line).
  * **Landscape Mode:** Split ratio switcher (`50/50` equal balance, `40/60` lyrics-focused, `60/40` art-focused). Left panel hosts artwork (or music video), right panel hosts multi-line scrolling lyrics and transport controls.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/AudioPlayerControls.kt` -> `AudioPlayerControls`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/preferences/PlayerControlsPreferencesScreen.kt`

### [FEATURE: Secure-Vault-And-Bookmark-Uninstall-Persistence] | Aliases: persistent vault, external storage migration, survive uninstall, sdcard documents mpvrx
> **1-Line Summary:** Stores Secure Folder media, bookmark thumbnails, database backups, settings, and PIN salts in persistent public storage (`/sdcard/Documents/mpvRx/` with `.nomedia`) so uninstalling the app never destroys data.
* **User Intention (Non-Negotiable):** Uninstalling mpvRx must not permanently delete the user's private media library, bookmarks, database setup, or PIN configuration. Upon reinstallation, mpvRx detects the existing vault folder, verifies the existing PIN, and restores the full library.
* **Directory Layout:**
  * `/sdcard/Documents/mpvRx/.nomedia` (blocks media scanning by Android Gallery)
  * `/sdcard/Documents/mpvRx/vault/` (encrypted private media files)
  * `/sdcard/Documents/mpvRx/thumbnails/` (16:9 bookmark thumbnail image cache)
  * `/sdcard/Documents/mpvRx/backups/mpvrx_database.db` (auto-synced Room database snapshot)
  * `/sdcard/Documents/mpvRx/config/security_verifier.json` (Argon2id/PBKDF2 salted hash for PIN verification)
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/database/repository/SecureFolderRepository.kt` -> `SecureFolderRepository`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkThumbnailStore.kt` -> `BookmarkThumbnailStore`

### [FEATURE: Music-Player-Customization-Suite] | Aliases: music customization, tag editor, music video toggle, media downloader
> **1-Line Summary:** Comprehensive music player customization suite: tag/metadata editor, lyrics search/sync engine, video-in-artwork toggle, and integrated yt-dlp media downloader.
* **Detailed Blueprint:** See dedicated document [`docs/MUSIC_PLAYER_CUSTOMIZATION.md`](file:///c:/Users/kazir/Documents/Agentic Harness/mpvRx/docs/MUSIC_PLAYER_CUSTOMIZATION.md) and preview [`docs/music_customization_preview.html`](file:///c:/Users/kazir/Documents/Agentic Harness/mpvRx/docs/music_customization_preview.html).
* **Key Components:**
  * **Tag & Metadata Editor:** In-app editing for song title, artist, album, genre, year, track number, and embedded cover art directly into audio files (MP3, FLAC, M4A, OGG).
  * **Multi-Source Lyrics Engine:** Sync lyrics from LRCLIB, Musixmatch, and local `.lrc` files with manual timestamp offset adjustments.
  * **Audio/Video Toggle:** For songs with associated video files, toggle the artwork viewport to render native video playback (`🖼️ Artwork` ⇄ `🎬 Video`).
  * **Integrated Downloader:** Download audio/video streams, embed metadata and cover art, and automatically register downloads into the mpvRx Room database.
