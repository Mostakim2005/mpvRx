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
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/PlayerControls.kt` :: `BookmarkSplitPanel`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` :: `BookmarkGridCard`, `BookmarkSplitPanel`

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
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` :: `BookmarkSplitPanel`

---

### [FEATURE: Chapter-Style-Bookmark-Sync-To-Video] | Aliases: sync to video, latest bookmark, previous bookmark, chapter follow, scroll back to bookmark
> **1-Line Summary:** Highlights the latest bookmark at or before current playback time (`<= currentPositionMs`) YouTube-chapter-style across Portrait, Landscape, and Overlay sheets, with a one-tap **Sync to video** button that scrolls back to it after browsing up or down.
* **User Intention (Non-Negotiable):** While playing a video in Portrait drawer, Landscape sidebar, or Overlay (`ChaptersSheet`), users can freely browse up or down the bookmark/chapter list without auto-scroll fighting their swipe. Tapping **Sync to video** immediately scrolls smoothly to (and highlights) the latest/previous bookmark at or before the current playback position—ignoring other videos in Global scope—or falls back to the earliest bookmark / active chapter when before the first bookmark.
* **Links:** `[SUBSYSTEM: Dynamic-Portrait-Video-And-Bookmark-Drawer]`, `[SUBSYSTEM: Landscape-Sidebar-With-Global-Scope-Toggle]`
* **Verified By:** `app/src/test/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkPositionSyncTest.kt :: selectsLatestBookmarkAtOrBeforeCurrentPosition`, `BookmarkPositionSyncTest.kt :: beforeFirstBookmarkLeavesActiveNullButTargetsEarliestForScroll`, `BookmarkPositionSyncTest.kt :: handlesUnsortedAndGlobalScopeFilteringByCurrentMediaId`, `BookmarkPositionSyncTest.kt :: fallsBackToActiveChapterWhenCurrentMediaHasNoMatchingBookmarks`
* **Visual / UI Behavior:**
  * **Portrait & Landscape Split Panels (`BookmarkSplitPanel`):** Highlights the active bookmark (`<= currentPositionMs`) with a primary border and high-contrast primary timestamp badge; provides a horizontally scrollable Tier-2 chip strip with **Sync to video** (Portrait) / **Sync** (Landscape sidebar) so toolbars never overflow at `20%` width or on `360dp` phones.
  * **Overlay (`ChaptersSheet`):** Scrolls once to the active bookmark/chapter on open, preserves free scrolling while browsing, and exposes a **Sync to video** pill button at the top of the sheet.

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

### [FEATURE: Zero-Bloat-Single-Folder-Direct-Hosting] | Aliases: single folder storage, zero bloat, live room db on external storage, survive uninstall, no duplicate backups, mvx1 encrypted vault
> **1-Line Summary:** Hosts hard-segregated live Room databases (`.store/library.dat` and `.vault/v_idx.dat`), AES-128-CTR (`MVX1`) encrypted vault videos, thumbnails, watch stats, and mirrored settings/PIN directly in `Documents/mpvRx/` without internal storage duplication.
* **User Intention (Non-Negotiable):** Everything durable lives under `/sdcard/Documents/mpvRx/` split into `.store/` (normal library) and `.vault/` (Secure Folder), encrypted with lightweight hardware-accelerated AES-128-CTR (`MVX1`) so third-party apps and file browsers cannot inspect vault videos, thumbnails, or metadata, while keeping CPU/battery impact minimal on MediaTek Helio G90T (`Realme 6 RMX2001`). Redundant internal copies (`filesDir/secure_media`, `bookmark-thumbnails`, `thumbnails`, internal `mpvrx.db`) are deleted after verified migration so Android App Data stays tiny and 100% of data — including the Secure Folder PIN, watch stats, resume positions, thumbnails, and UI/player settings — survives Clear Data or Uninstall/Reinstall.
* **Directory Layout:**
  * `<UserRoot>/.nomedia` (plus `.nomedia` in every subfolder and explicit exclusion from all mpvRx scanners and file browsers)
  * `<UserRoot>/.store/library.dat` (live normal Room database `MpvRxDatabase`, WAL/SHM)
  * `<UserRoot>/.store/thumbs/` & `thumb_cache/` (`MVX1` encrypted normal bookmark and video thumbnails)
  * `<UserRoot>/.store/watch_stats.mvx` & `settings.mvx` (`MVX1` encrypted normal watch stats and non-personal shared UI/player settings)
  * `<UserRoot>/.vault/.vkey` (PBKDF2-HMAC-SHA256 wrapped 128-bit master key)
  * `<UserRoot>/.vault/v_idx.dat` (isolated `SecureVaultDatabase` with encrypted string columns and HMAC lookup keys)
  * `<UserRoot>/.vault/media/<uuid>.dat` (`MVX1` AES-128-CTR encrypted Secure Folder videos with opaque UUID filenames)
  * `<UserRoot>/.vault/thumbs/` & `thumb_cache/` (`MVX1` encrypted Secure Folder bookmark and video thumbnails)
  * `<UserRoot>/.vault/v_stats.mvx` & `v_cfg.mvx` (`MVX1` encrypted Secure Folder watch stats and PIN/recovery settings)
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/di/DatabaseModule.kt` -> `MpvRxDatabase`, `SecureVaultDatabase`, `SecureBookmarkStore`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/secure/VaultCrypto.kt` -> `encryptStream`, `readDecryptedAt`, `openDecryptedRangeStream`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/storage/DurableStorageManager.kt` -> `prepare`, `normalDatabaseFile`, `secureDatabaseFile`, `isAppDurablePath`, `isVaultPath`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/storage/DurableVaultMigration.kt` -> `run`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/storage/DurablePreferencesSync.kt` -> `restoreIfNeeded`, `flushNow`

### [FEATURE: Player-Controls-Pencil-Tag-Editor-And-Auto-Fetch] | Aliases: pencil edit icon, metadata editor, auto fetch artwork, tag editor, id3 edit, fullscreen tag editor
> **1-Line Summary:** Dedicated pencil icon in player controls opening an in-app fullscreen metadata editor with multi-artwork carousel and multi-source metadata candidate browsing.
* **User Intention (Non-Negotiable):** Direct access from active playback controls to edit song title, artist, album, genre, year, and track number. When auto-fetching, user can swipe through multiple artwork options (Artwork 1, Artwork 2...) to pick their favorite, and select from multiple candidate metadata sets (Metadata 1, Metadata 2...) to 1-tap populate all fields cleanly. Includes automatic physical file renaming on disk and Musicolet-style container tag injection.
* **Visual / UI Behavior:**
  * **Pencil Button:** Rendered directly alongside player action controls in both portrait and landscape modes.
  * **Fullscreen Tag Editor Modal:** Top area displays an artwork candidate carousel; middle provides form fields and auto-rename toggle; bottom provides candidate metadata selector cards.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/sheets/FullscreenMetadataEditorSheet.kt` -> `FullscreenMetadataEditorSheet`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/metadata/OnlineMetadataScraper.kt` -> `searchCandidates`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/metadata/AudioTaggingEngine.kt` -> `updateMetadataAndRename`

### [FEATURE: Portrait-Art-Mode-Full-Lyrics-Artwork-Background] | Aliases: lyrics artwork background, immersive full lyrics, blurred album art lyrics, remove blank lyric background
> **1-Line Summary:** In Portrait Art Mode, expanding to full-screen lyrics displays the active album artwork in the background with a soft dark blur scrim instead of a blank canvas.
* **User Intention (Non-Negotiable):** In portrait mode when switching from the 2-line lyric preview to the full-screen scrolling lyric view, the background must no longer be solid black/blank. The album artwork remains rendered behind the lyrics, dimmed and subtly blurred, giving an immersive aesthetic while maintaining perfect text legibility. In landscape mode, the existing 60/40 and 50/50 dual-panel layout remains intact.
* **Visual / UI Behavior:**
  * **Portrait Mode:** Tapping the 2-line lyrics expands to full view where album cover art is scaled to fill the background (`blur(20px)` + 60% opacity dark overlay) with synced lyrics scrolling over it.
  * **Landscape Mode:** Left panel displays artwork/video; right panel displays scrolling lyrics without change.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/AudioPlayerControls.kt` -> `FullLyricsOverlay`, `ImmersiveArtworkBackground`

### [FEATURE: Granular-Quality-Downloader-And-Configurable-Presets] | Aliases: yt-dlp quality spectrum, 1080p 720p 480p 360p 240p, compact video sizes, download resolution settings
> **1-Line Summary:** yt-dlp downloader offering a full resolution spectrum (1080p down to 240p) with estimated file sizes and user-configurable quick-selection toggles in app settings.
* **User Intention (Non-Negotiable):** Users can download compact, low-size companion videos (e.g. 240p/360p for low storage usage) or full HD (720p/1080p). App preferences allow users to toggle which resolution presets appear in the quick download sheet.
* **Visual / UI Behavior:**
  * **Download Sheet:** Displays buttons for available streams (1080p, 720p, 480p, 360p, 240p) paired with estimated sizes (~45MB, ~22MB, ~12MB, ~6MB, ~3.5MB).
  * **Preferences Screen:** Toggle switches allowing user to enable or disable specific resolution tiers in the quick picker.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/CompanionVideoDownloaderSheet.kt` -> `CompanionVideoDownloaderSheet`, `ResolutionOptionGrid`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/preferences/DownloadPreferencesScreen.kt` -> `QualityPresetsConfig`

### [FEATURE: Smart-Audio-Replacement-And-Cleanup-Prompt] | Aliases: replace old audio, delete old audio file, full video with audio prompt, cleanup duplicate media
> **1-Line Summary:** Prompts a confirmation dialog to delete redundant standalone audio files when downloading a full video with audio, automatically repointing database links.
* **User Intention (Non-Negotiable):** When a user who already possesses a local audio file decides to download "Full Video with Audio", the app intercepts with a clean dialog: *"Replace old audio file to save storage?"* Selecting Replace safely deletes the standalone audio file, migrates metadata and lyric bindings to the new video container, and eliminates duplicate file bloat.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/CompanionVideoDownloaderSheet.kt` -> `AudioReplacementConfirmDialog`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/storage/MediaMigrationEngine.kt` -> `replaceAudioWithUnifiedVideo`

### [FEATURE: Metadata-Driven-File-Renaming-And-Triad-Database] | Aliases: rename file to match metadata, clean file names, song and artist mapping db, auto rename on disk
> **1-Line Summary:** Automatically renames physical audio and companion video files on disk to match updated metadata tags and maintains lock-step timestamp synchronization.
* **User Intention (Non-Negotiable):** Gibberish or messy filenames (e.g. `download_8271.opus`) are automatically renamed to clean, standardized names (e.g. `Synthwave Collective - Midnight City Echoes.opus`) when metadata is updated, with the live Room database updating its URI pointers. Switching between audio and video maintains exact millisecond playback continuity ($T_{\text{video}} = T_{\text{audio}}$).
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/metadata/FileRenamingEngine.kt` -> `renameMediaFromMetadata`
  * `app/src/main/java/app/gyrolet/mpvrx/database/entity/MusicTrackEntity.kt` -> `updateFileUri`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/PlayerViewModel.kt` -> `switchAudioVideoTrackPreservingPosition`

### [FEATURE: Single-Point-Anchor-Lyric-Offset-Calibration] | Aliases: 1-point lyric sync, anchor lyric line, global lyric offset, match lyric to playhead, sqlite unique id mapping
> **1-Line Summary:** Calibrates entire song lyric timing by anchoring a single lyric line to the active playhead, writing the offset permanently to the .lrc file, audio container tags, and Room database mapping.
* **User Intention (Non-Negotiable):** Users do not need to manually edit every timestamp when a lyric file starts earlier or later due to video intro variations. Highlighting any line (e.g. *"Let's Go"*) in the full lyric view and tapping "Anchor Match" computes $\Delta t$ and uniformly shifts all timestamps across the track. Clicking "Apply & Close" permanently saves the adjusted lyrics to the `.lrc` file, embeds them into audio ID3/Vorbis tags, and saves the record in the local SQLite database mapping Artist + Song ↔ Audio Path ↔ YouTube Link ↔ LRC with a unique ID.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/sheets/LyricAnchorDialog.kt` -> `LyricAnchorDialog`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/lyrics/LyricAnchorCalibrator.kt` -> `calibrate`
  * `app/src/main/java/app/gyrolet/mpvrx/database/dao/MusicCompanionDao.kt` -> `insertOrReplace`

### [FEATURE: Embedded-In-File-Lyrics-Tagging] | Aliases: musicolet style lyrics, embed lyrics in mp3, uslt frame, vorbis lyrics tag, direct tag injection
> **1-Line Summary:** Embeds synchronized LRC lyrics and unsynced plain text directly into the physical audio file container tags (`USLT`, `LYRICS`, `©lyr`) without re-encoding audio.
* **User Intention (Non-Negotiable):** Mirrors the Musicolet capability where lyrics travel inside the music file itself. Even if the track is copied to another device, flash drive, or music player without sidecar files, lyrics remain intact and readable.
* **Supported Formats:**
  * **MP3 (ID3v2):** `USLT` frame (with timestamped text) and `SYLT` frame.
  * **FLAC / OGG / Opus:** Vorbis Comment `LYRICS` / `UNSYNCEDLYRICS` tags.
  * **M4A / MP4:** `©lyr` atom.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/metadata/AudioTaggingEngine.kt` -> `embedLyricsIntoAudioFile`, `extractEmbeddedLyrics`

---

## 4. Open Repair Backlog (v3.2.0 → Next Patch)

> **1-Line Summary:** Confirmed regressions and missing affordances reported from real-device testing on realme RMX2001 (Android 11), kept here so every fix is judged against the same user-visible outcome in **both** orientations.

> **Status:** `OPEN` until the acceptance check is demonstrated. Nothing in this section changes an existing [FEATURE] contract; each entry restores one that v3.2.0 broke or never finished.

> **Visual preview:** [`docs/v3_2_1_fixes_preview.html`](v3_2_1_fixes_preview.html) — every item below drawn in **portrait and landscape** with independent layout budgets, per `TRAP: Dual-Orientation-UI-Consistency-Rule`.

| Task | Priority | Surface | Status |
| --- | --- | --- | --- |
| `R-01` | P1 | Music player dismissal | `SHIPPED` (`34ef23be`, `4a1d29ba`) |
| `R-02` | P2 | Music layout visibility menu | `SHIPPED` (`b187233d`) |
| `R-03` | P2 | Bookmark editor orientation | `SHIPPED` (`afbc30b0`) |
| `R-04` | P1 | Landscape status-bar insets (player + gallery) | `SHIPPED` (`e106d62b`, `9c3f01c4`) |
| `R-05` | P1 | Secure Folder double credential prompt | `SHIPPED` (`a86f06b3`) |
| `R-06` | P2 | yt-dlp / video download discoverability | `SHIPPED` (`b187233d`) |
| `R-07` | P1 | Bookmark scope toggle (`Video` ⇄ `Global`) | `SHIPPED` (`5ceb475a`) |
| `R-08` | P3 | Now Playing sheet compactness | `SHIPPED` (`e2859d14`) |
| `R-09` | P2 | Landscape music layout: browse left, now playing right | `SHIPPED` (`b187233d`) |
| `R-10` | P1 | Bookmark scope + foreign playback + tags in every surface | `SHIPPED` (`v3.2.2`) |
| `R-11` | P1 | Secure/non-secure bookmark segregation | `SHIPPED` (`v3.2.2`) |
| `R-12` | P1 | Secure playback asks for the app PIN, once per session | `SHIPPED` (`v3.2.2`) |
| `R-13` | P1 | Library, vault and thumbnails survive uninstall | `SHIPPED` (`v3.2.2`) |

**Handover:** current state, verification workflow and per-item implementation plans live in
[`HANDOVER.md`](HANDOVER.md). Read it before resuming this table.

**Implementation order:** `R-04` (insets, in progress) → `R-01` (dismiss semantics) → `R-07` (scope + tags) → `R-02` (button visibility) → `R-06` (download button) → `R-09` (landscape split) → `R-03` (portrait editor) → `R-05` (single unlock) → `R-08` (compact queue). Each lands alone, with the existing layout untouched unless the entry says otherwise.

### [ISSUE: R-01-Music-Collapses-To-Mini-Player-On-Swipe-Down] | Aliases: swipe to close music, music keeps playing minimized, dismiss stops audio
> **1-Line Summary:** Swiping the music player down dismisses playback entirely; it must collapse to the mini player and keep the audio running.
* **Reported Behavior (v3.2.0):** With music playing from the Music tab, a downward swipe (or the sheet's dismiss gesture) ends the session; the mini player disappears and audio stops. Only the explicit `✕` should end playback.
* **Root Cause (investigated):** Tossing the mini-player card is wired to `MediaPlaybackService.ACTION_NOTIFICATION_STOP`, a hard session stop; it does **not** consult the audio background-playback preference (`AudioPreferences.audioBackgroundPlayback`). So the setting is not the cause — the gesture itself is wired to the wrong action.
* **User Intention (Non-Negotiable):** A gesture that closes the *surface* must never close the *session*. Swipe-down = collapse to the existing mini player docked above the bottom navigation; playback position, queue, and notification survive. `✕` and the notification's stop action remain the only terminal actions.
* **Acceptance Criteria:**
  * Swipe-down on the expanded music player leaves the mini player visible with the same track and a moving progress state; audio never gaps.
  * Re-expanding restores the exact position and queue.
  * `✕` still ends the session and clears the mini player.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/InAppMusicPlayer.kt` -> `dismiss`, `collapse`, `endDrag`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/browser/components/MiniPlayer.kt` -> `dismissPlayer`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/MediaPlaybackService.kt` -> session stop path

### [ISSUE: R-02-Music-Player-Needs-Layout-Visibility-Menu] | Aliases: hide music layout, show music regions, music layout editor, compact music player
> **1-Line Summary:** The video player exposes a per-region control layout editor; the music player has no equivalent hide/show menu.
* **Reported Behavior (v3.2.0):** `ControlLayoutEditorScreen` / `ControlRegion` let users choose which control regions the video player shows, but nothing similar exists for the music player, so artwork, lyrics, seek/time, transport, or the utility bar cannot be hidden.
* **User Intention (Non-Negotiable):** Music playback gets the same customization contract as video, applied to the **buttons that already exist in the music player's action row** — `Equalizer`, `Lyrics`, `Shuffle`, `Repeat`, artwork/visualizer toggle, `Edit tags`, `Background playback`, `Queue`. Hiding one removes only that button; the bar's structure does not change. Portrait and landscape keep **independent** visibility sets (see `TRAP: Dual-Orientation-UI-Consistency-Rule`).
* **Acceptance Criteria:**
  * Toggling a button hides/shows it immediately without recomposing playback or interrupting audio.
  * Portrait and landscape keep independent sets; both persist across rotation and process restart.
  * Hiding every optional button still leaves play/pause, seek, queue and the download entry reachable (no unreachable state).
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/preferences/ControlLayoutEditorScreen.kt` -> `ControlLayoutEditorScreen`, `ControlRegion`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/AudioPlayerControls.kt` -> `AudioPlayerControls`

### [ISSUE: R-03-Bookmark-Editor-Forces-Portrait-While-Editing] | Aliases: bookmark edit portrait, keyboard covers landscape editor, rotate to type, restore orientation after edit
> **1-Line Summary:** The bookmark editor must open **in portrait even from landscape playback**, because the keyboard otherwise covers the whole landscape editor, and it must return to the previous orientation when editing ends.
* **Reported Behavior (v3.2.0):** Editing a bookmark while the player is landscape shows a landscape editor; the IME then fills most of the screen, leaving too little room to see the fields being edited.
* **User Intention (Non-Negotiable):** Editing is a portrait task. Opening the editor rotates to portrait so the fields and the keyboard are both usable; saving or cancelling restores exactly the orientation the player had before (including a manual rotation-button choice). Playback continues across the rotation.
* **Acceptance Criteria:**
  * Landscape player → Edit: editor appears in portrait with the focused field visible above the keyboard.
  * Save / Cancel / Back: player returns to landscape, playback position intact, no duplicate prompt or reload.
  * Portrait player → Edit → close: stays portrait.
  * Works for both new-bookmark and existing-bookmark editing.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/PlayerActivity.kt` -> `setRequestedOrientation`, `setOrientation`, `PlayerOrientationPolicy.manualOrientation`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/PlayerSheets.kt` -> `Sheets.BookmarkEditor`

### [ISSUE: R-04-Landscape-Top-Controls-Sit-Under-The-Status-Bar] | Aliases: top buttons not clickable, status bar overlap landscape, gallery Auto button dead, player plus button dead
> **1-Line Summary:** In landscape the player's top action row/chips and the bookmark gallery header render beneath the status bar, so the topmost buttons never receive taps.
* **Reported Behavior (v3.2.0):** Landscape player: the `⋯`, filter, bookmark, `+`, fullscreen and `✕` row plus the `Video` / `20%` / `3` chips sit in the status-bar strip and the top-row `+` does not respond. **Fullscreen bookmark gallery** (the bookmark-only screen opened from the player): the `Auto` column control and the `All videos` / `Secure videos` tabs sit under the clock/battery and cannot be tapped in landscape; the tabs are also cramped to hit.
* **User Intention (Non-Negotiable):** Every interactive control is fully inside the safe area. Nothing a finger must hit may share space with the system clock, battery, cutout, or gesture pill.
* **Acceptance Criteria:**
  * All top-edge controls are inset by `WindowInsets.statusBars` (horizontal cutout insets included) and are hit-testable in landscape.
  * The gallery `Auto` control opens the column picker; the player `+` adds a bookmark.
  * No control's touch target overlaps the status-bar strip on a notched device at 20% sidebar width.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/PlayerControls.kt` -> existing `windowInsetsPadding(WindowInsets.statusBars)` branches (inset coverage is uneven)
  * `app/src/main/java/app/gyrolet/mpvrx/ui/bookmarks/BookmarkGalleryScreen.kt` -> gallery header (no `WindowInsets` handling today)

### [ISSUE: R-05-Secure-Folder-Asks-For-The-Password-Twice] | Aliases: double password prompt, secure folder asks twice, repeated vault unlock
> **1-Line Summary:** Entering the Secure Folder and then opening protected media challenges the same identity twice instead of reusing the unlocked session.
* **Reported Behavior (v3.2.0):** The vault asks for the lock-screen/device password, and after entering it the next protected step asks again (and again when playback is requested).
* **User Intention (Non-Negotiable):** One successful challenge unlocks the vault for the session. The secure-folder credential is the gate; the phone/device password must not be asked again for each video. While the vault is open the user browses freely: list → video → back → another video → playlist next/previous all pass without a prompt. Only an explicit lock, process death, or timeout re-arms the gate.
* **Acceptance Criteria:**
  * Unlock vault → open a protected video → exactly one credential prompt total (no second phone-password dialog).
  * Back out of a video and open a different protected video: no prompt.
  * Switch between protected videos through the playlist/next controls, and return from the player: no prompt.
  * Backgrounding and returning keeps the unlocked session; pressing Lock re-arms the prompt.
  * A cold start from a protected notification still requires one prompt.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/securefolder/SecureUnlockActivity.kt` -> `launch`
  * `app/src/main/java/app/gyrolet/mpvrx/utils/media/MediaUtils.kt` -> `startGuarded`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/securefolder/SecureFolderViewModel.kt` -> vault gate state

### [ISSUE: R-06-Download-And-Ytdlp-Affordance-Is-Missing] | Aliases: where is download, no yt-dlp button, download video missing
> **1-Line Summary:** There is no visible download action for videos and no trace of the yt-dlp engine anywhere in the UI.
* **Reported Behavior (v3.2.0):** The player overflow/menus and the downloads surface expose no download entry for the current video, and nothing indicates whether the yt-dlp engine is available.
* **User Intention (Non-Negotiable):** When the build ships `ENABLE_YTDLP`, a download action is reachable from the **video player and the music player** and lists the configured resolution presets; the music player downloads the *music video* (audio-less companion where local audio exists) and registers the result in the library/database so the song can show its video without re-downloading. When the engine is missing it explains that once and offers the install path instead of silently hiding the feature.
* **Music-Side Spec (see `MUSIC_PLAYER_CUSTOMIZATION.md` § [CUSTOM: Integrated-Media-Downloader-And-Databasing]):** a **⬇ download button inside the music player action row** (next to equalizer/lyrics/etc., subject to the R-02 visibility menu) opens the download sheet; resolution spectrum; “attach to this track” binding written as `MusicCompanionDao` row (artist + song ↔ audio path ↔ video path/URL ↔ resolution), auto-deleted audio replacement prompt when a full video with audio is chosen.
* **Acceptance Criteria:**
  * `ENABLE_YTDLP=true` build: download entry visible from the player, presets open, enqueue works.
  * Engine absent: the entry still exists and routes to install/status messaging.
  * `ENABLE_YTDLP=false` build: no dead download UI.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/download/YtdlpDownloadEngine.kt` -> engine/status
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/PlayerSheets.kt` -> `ytdlpEngine` usage
  * `app/src/main/java/app/gyrolet/mpvrx/ui/browser/sheets/PlaylistActionSheet.kt` -> `BuildConfig.ENABLE_YTDLP`

### [ISSUE: R-07-Bookmark-Scope-Toggle-Does-Not-Switch-The-List] | Aliases: global bookmark toggle broken, all videos scope, video scope not switching
> **1-Line Summary:** The in-player `Video` ⇄ `Global` scope chip changes its label but the drawer keeps showing only the current video's bookmarks.
* **Reported Behavior (v3.2.0):** In the landscape sidebar (and the portrait drawer), toggling scope to `Global` does not add bookmarks from other videos to the list.
* **User Intention (Non-Negotiable):** This is the [FEATURE: Landscape-Sidebar-With-Global-Scope-Toggle] contract: `Global` lists every library bookmark with its media title and thumbnail, and tapping a row from another video opens that media at the saved position instead of seeking inside the current file.
* **Acceptance Criteria:**
  * With bookmarks on two or more videos, toggling to `Global` immediately lists the other videos' rows (title + thumbnail retained).
  * Tag filters still apply to global rows; tag lookups resolve for remapped rows.
  * Tapping another video's bookmark launches that media at its timestamp; the current video's rows still seek locally.
  * The chip state survives a sheet close/reopen during the same session.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` -> `isGlobalScope`, `baseBookmarks`, `displayedBookmarks`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkGalleryRepository.kt` -> `observeItems(BookmarkGalleryScope.AllVideos)`

### [ISSUE: R-09-Landscape-Music-Layout-Puts-Both-Panels-On-One-Side] | Aliases: landscape music split, browse left now playing right, same side layout
> **1-Line Summary:** In landscape the music player must stop splitting current track and mini player across opposite edges: browsing goes on the **left**, current playback on the **right**.
* **Reported Behavior (v3.2.0):** Landscape now-playing shows the current track against one edge and the mini/queue entry against the other, wasting the middle and forcing the eye to jump sides.
* **User Intention (Non-Negotiable):** One side (left) is the browsable music list; the other (right) holds the current track, its action row and the mini player. Nothing in the existing panel structure changes beyond this placement.
* **Acceptance Criteria:**
  * Left pane scrolls the library/queue; right pane keeps artwork, title, action row and transport.
  * Portrait is untouched.
  * Both panes stay inside the safe area with the landscape status-bar inset.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/AudioPlayerControls.kt` -> landscape split branch, `isCompactLandscape`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/InAppMusicPlayer.kt` -> sheet host

### [ISSUE: R-08-Now-Playing-Sheet-Wastes-Vertical-Space] | Aliases: playlist sheet too tall, empty area under queue, compact now playing
> **1-Line Summary:** The Now Playing sheet announces 6 items but renders one row inside a mostly empty panel.
* **Reported Behavior (v3.2.0):** Landscape sheet header reads `Now Playing · 6 items`; below the first entry the panel is empty, so the sheet consumes screen area without showing the remaining queue.
* **User Intention (Non-Negotiable):** Either the queue is drawn (all 6 compact rows visible without scrolling in landscape) or the sheet shrinks to its content. Blank space inside a bottom sheet is a defect, not padding. The visible rows must carry real content (thumbnail, title, duration, resolution, active-row state) rather than one card floating in an empty panel.
* **Acceptance Criteria:**
  * Six queued items render as compact rows that fit in landscape without scrolling; the current row stays highlighted.
  * When the queue is short, the sheet height wraps content instead of reserving empty space.
  * Portrait keeps a scrollable full-height variant; no row is clipped by the grabber or the sheet's bottom inset.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/sheets/PlaylistSheet.kt` -> `PlaylistSheet`, `ui_now_playing` header
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/PlayerSheets.kt` -> sheet host sizing

### [ISSUE: R-10-Bookmark-Scope-Must-Work-In-Every-Surface] | Aliases: scope toggle chapters sheet, full gallery video scope, global bookmark does nothing, bookmark tags missing
> **1-Line Summary:** `Video ⇄ Global` must work in the landscape sidebar, portrait drawer, Chapters sheet and full-window gallery, and a global card must open its own video.
* **Reported Behavior (v3.2.1):** The chip exists only in the split sidebar; the Chapters sheet always shows the current video, and the full gallery has no current-video scope. Global cards from other videos can be shown but may not play, and tags can disappear when the media title replaces the bookmark name.
* **User Intention (Non-Negotiable):** One scope choice shared by every bookmark surface. Global lists every bookmark with its tag chips; tapping another video's card launches that video at the saved timestamp. Losing the bookmark name on a global card is acceptable; losing its tags is not.
* **Acceptance Criteria:**
  * The toggle appears in both orientations, in the Chapters sheet and in the full-window gallery (`This video` / `All videos` / `Secure videos`).
  * A foreign card starts the correct media at its timestamp; a row with no replay reference reports unavailable rather than seeking the current file.
  * Global cards and Chapters rows show tag chips; tag filters still apply.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/PlayerViewModel.kt` -> `openBookmark`, `isCurrentMediaSecure`, `recordBookmarkMediaRef`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/PlayerSheets.kt` -> `ChaptersSheetWithBookmarks`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` -> `galleryScope`, `onCardClick`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/bookmarks/BookmarkGalleryScreen.kt` -> `onlyCurrentVideo`

### [ISSUE: R-11-Secure-And-Public-Bookmarks-Must-Never-Mix] | Aliases: secure bookmark in global, public bookmark in secure, vault leak, moved file bookmark
> **1-Line Summary:** A bookmark follows its media's whole link group, so moving a file into or out of the Secure Folder moves the bookmark with it in every list.
* **Reported Behavior (v3.2.1):** The gallery compared only the bookmark row's stored `mediaId` with the secure file's identity. Moving a file into the vault kept the old group key, so its bookmark stayed in the public gallery and vanished from the secure one.
* **User Intention (Non-Negotiable):** Secure and non-secure content are segregated everywhere. A secure bookmark or video never appears in public/global mode; a public bookmark never appears in secure mode.
* **Acceptance Criteria:**
  * Move-in: the bookmark disappears from All/Global and appears in Secure.
  * Restore: the reverse happens, and the replay URI points at the restored file.
  * A secure current video scopes Global to secure bookmarks only; the player gallery opens with `secureOnly = true`.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkGalleryRepository.kt` -> `secureKeys`, `observeAllLinks`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/secure/SecureMediaRegistry.kt` -> `identitiesForPath`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/PlaybackBookmarkOps.kt` -> `onMediaMoved`, `newPlayableUri`

### [ISSUE: R-12-Secure-Playback-Must-Ask-For-The-App-PIN-Once] | Aliases: phone screen lock again, device credential in player, double prompt vault, secure video asks password twice
> **1-Line Summary:** Playback of protected media asks for the Secure Folder PIN the user set, not the phone's lock-screen credential, and only once per unlocked session.
* **Reported Behavior (v3.2.1):** Opening the vault and then a video could bring up the phone screen-lock prompt again because the playback gate used `BiometricPrompt` with `DEVICE_CREDENTIAL` independently of the folder gate.
* **User Intention (Non-Negotiable):** One app-owned password. Entering the vault unlocks the session; opening a video, going back and opening another video must not prompt again. The device PIN/pattern/password is never requested by playback.
* **Acceptance Criteria:**
  * Vault unlock → protected video: exactly one app-PIN prompt in total.
  * The playback gate renders the app PIN entry; cancelling cancels playback.
  * Biometric entry, when enabled, is strong-biometric only and offers the app PIN as the negative action.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/securefolder/SecureUnlockActivity.kt` -> `SecurePinPrompt`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/securefolder/SecureFolderGateScreen.kt` -> `showBiometricPrompt`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/secure/SecureFolderSession.kt` -> `markUnlocked`

### [ISSUE: R-13-Library-Must-Survive-App-Uninstall] | Aliases: bookmarks lost after uninstall, vault deleted, thumbnails gone, persistent database
> **1-Line Summary:** The live Room database, Secure Vault and bookmark thumbnails live in `Documents/mpvRx/`, outside the app's private data, so uninstalling the app cannot delete them.
* **Reported Behavior (v3.2.1):** The documented durable-storage feature was not wired: Room still opened `databases/mpvrx.db`, the vault used `filesDir/secure_media`, and thumbnails used `filesDir/bookmark-thumbnails`. All three are deleted with the app.
* **User Intention (Non-Negotiable):** Database, bookmarks, thumbnails and secure-folder files survive uninstall or app-data deletion. Reinstalling the app finds the library again.
* **Acceptance Criteria:**
  * Standard build: `Documents/mpvRx/database/mpvrx.db`, `vault/` and `thumbnails/` are populated; a reinstall opens the same database.
  * Existing internal files are copied once and Room paths are rebased without losing rows.
  * If all-files access is unavailable, the app still starts on internal storage and retries the durable root on a later launch.
  * Play Store scoped-storage builds keep internal storage and include the same files in Auto Backup.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/storage/DurableStorageManager.kt` -> `prepare`, `copyMediaDirectories`
  * `app/src/main/java/app/gyrolet/mpvrx/App.kt` -> `migrateDurableStorage`
  * `app/src/main/java/app/gyrolet/mpvrx/di/DatabaseModule.kt` -> `databaseBuilder`
  * `app/src/main/java/app/gyrolet/mpvrx/database/repository/SecureFolderRepository.kt` -> `secureDir`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkThumbnailStore.kt` -> `privateDirectory`

---

## 3. Namida UI/UX & Backend Features (v3.3.0)

### [FEATURE: Music-Folders-Back-Navigation-Preservation] | Aliases: music folder back, back to folders, preserve music subtab
> **1-Line Summary:** Preserves the active `MusicTab` (such as `MusicTab.FOLDERS`) when navigating back from within a folder, preventing reset to Songs or Home.
* **User Intention (Non-Negotiable):** Navigating deep into folder hierarchies in the Music browser and pressing Back must back out folder-by-folder and preserve the Folders sub-tab selection rather than bouncing the user back to the top-level Songs list or Home tab.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/browser/MainScreen.kt` -> `MainScreen`, `BackHandler`

### [FEATURE: Uncropped-Bookmark-Thumbnails-And-Parallel-Capture] | Aliases: fit bookmarks, uncropped thumbnails, fast bookmark capture, 220dp landscape sidebar
> **1-Line Summary:** Renders full uncropped frames (`ContentScale.Fit`), raises landscape sidebar thumbnail height cap to 220dp, and parallelizes frame capture with Room writes and memory cache.
* **User Intention (Non-Negotiable):** Bookmarks on non-16:9 videos (21:9 ultrawide, 4:3, anime) must show the complete frame without cropping subtitles or key visuals. Creating a quick bookmark must feel instantaneous without capture hitching.
* **Visual / UI Behavior:**
  * Uses `ContentScale.Fit` across portrait grid, landscape sidebar, and gallery.
  * Landscape sidebar thumbnail height limit raised from `140.dp` to `220.dp`.
  * Capture pipeline caches `Bitmap` immediately in memory while running Room database operations and AES encryption concurrently in background I/O.
  * Added `bookmarkQuickAddMinimalist` and `bookmarkPanelMinimalistView` preferences for instant chapter-style bookmarking.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkThumbnailStore.kt` -> `save`, `load`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/PlayerViewModel.kt` -> `createQuickBookmark`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` -> `BookmarkGridCard`

### [FEATURE: Fullscreen-Lyrics-Tap-To-Seek-And-Compact-Scale] | Aliases: tap lyrics seek, fullscreen lyrics seek, namida lyrics typography
> **1-Line Summary:** Enables tapping any synced lyric line in fullscreen lyrics mode to seek immediately, paired with Namida's compact typography scale.
* **User Intention (Non-Negotiable):** Users can tap any synced lyric line in fullscreen mode to jump directly to that point in the song. Typography follows Namida's compact scale (20/18/15/14 sp with 10dp line spacing) so 7+ lines fit comfortably on screen.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/LyricsView.kt` -> `LyricsView`

### [FEATURE: Namida-Music-Player-Default-And-Section-Toggles] | Aliases: namida music layout, music player style, mpvrx classic layout, green section toggles
> **1-Line Summary:** Defaults to Namida music player layout with compact title/artist scale and inline timestamps, while offering mpvRx Classic style and granular section visibility checkboxes.
* **User Intention (Non-Negotiable):** Music playback screen defaults to modern Namida aesthetics with centered artwork, compact typography, and inline transport row. Classic mpvRx style is preserved as an option (placing Row 2 above Row 1), along with toggles to hide/show Green section header bars and lyrics source indicators.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/preferences/AppearancePreferences.kt` -> `MusicPlayerUiStyle`, `musicPlayerUiStyle`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/AudioPlayerControls.kt` -> `AudioPlayerControls`, `MusicLayoutDialog`

### [FEATURE: Compact-Media-Notification-Subtext] | Aliases: notification elapsed duplicate, compact notification, clean notification subtext
> **1-Line Summary:** Removes redundant elapsed/remaining subtext from the notification title row when the system notification seekbar is active.
* **User Intention (Non-Negotiable):** Modern Android media notifications already render an interactive seekbar with current position and duration. Removing duplicate text keeps notification bars concise and readable.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/MediaPlaybackService.kt` -> `buildNotification`

### [FEATURE: Unicode-TextNormalizer-And-JVM-Tests] | Aliases: namida text normalizer, diacritic cleaner, search normalizer
> **1-Line Summary:** Ports Namida's Unicode NFKD cleaner to strip combining diacritics, zero-width characters, and extra whitespace for reliable search and lyrics matching.
* **User Intention (Non-Negotiable):** Accented track titles, artist names, or search queries (e.g. `Café`, `Über`) match seamlessly with their ASCII and diacritic variants across local music indexing and online lyrics queries.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/utils/text/TextNormalizer.kt` -> `normalizeForSearch`
  * `app/src/test/java/app/gyrolet/mpvrx/utils/text/TextNormalizerTest.kt` -> `stripsDiacriticsZeroWidthAndExtraWhitespace`

### [FEATURE: Unified-Bookmark-Preset-V2-Oshash-LAN-Sync-And-Orphan-Linker] | Aliases: bookmark lan sync, av manager sync, stash bookmark sync, preset zip v2, orphan point to video, oshash, javid sync, usb sync, choose thumbnail, merge timestamps, canonical javid
> **1-Line Summary:** Adds `oshash` + strict 1-group-per-canonical-JAVID (`MIMK-232`, `FC2-PPV-1234567`) consolidation, `sourceUrl` video-link preservation (`MpvRxDatabase` v37), rolling-window overlapping timestamp deduplication (`mergeOverlappingBookmarksForGroup` via PC Hub + Stash or offline), standalone `.json`/`.zip` preset import, Wi-Fi auto-discovery (`/api/sync/ping`) & USB ADB-reverse mode, true 2-way metadata/position/thumbnail sync, Echo-2 automatic orphan matching (on Gallery open, Activity resume, and `MediaStore` library events), and manual **Link / JAVID** binding on every gallery card across `mpvRx`, `AV Manager`, and `Stash`.
* **User Intention (Non-Negotiable):** Users can synchronize timestamps (including merged position updates), multi-tags (`primaryTag` = first tag), durations (`endPositionMs`), underlying video URL links (`sourceUrl`), and multi-source JPEG thumbnails across **mpvRx**, **AV Manager**, and **Stash** under one permanent unique canonical JAVID regardless of filename/size/resolution/duration changes, auto-connect over Wi-Fi LAN or USB cable without hitting stale hardcoded IP timeouts, import `.zip` or standalone `.json` exports (`Preset v2`, `AV Vault`, `Stash scene_markers`), auto-link waiting orphan groups whenever new videos arrive on device, consolidate any duplicate groups for the same JAVID, merge overlapping nearby timestamps on PC/phone cleanly, and manually link/re-link any gallery card's video file or assign its JAVID / source URL. Secure Folder bookmarks remain strictly isolated from public DB and LAN sync (`R-11`).
* **Links:** `[SUBSYSTEM: Three-Way-Unified-Bookmark-Sync-And-Oshash-Matcher]`, `[TRAP: Stale-Rename-Update-Timestamps-And-Loopback-USB-Conflict]`
* **Verified By:** `app/src/test/java/app/gyrolet/mpvrx/domain/bookmark/UnifiedBookmarkSyncTest.kt :: oshashMatchesKnownVectorAndRejectsTinyInputs`, `UnifiedBookmarkSyncTest.kt :: bookmarkPresetArchiveRoundTripsV2MetadataAndReadsLegacyV1`, `UnifiedBookmarkSyncTest.kt :: jvidExtractionHandlesExtraCharactersAndFc2`, `UnifiedBookmarkSyncTest.kt :: presetArchiveReadsStandalonePresetAndStashMarkersJson`, `UnifiedBookmarkSyncTest.kt :: syncDiagnosticsExplainLoopbackAndTimeouts`, `UnifiedBookmarkSyncTest.kt :: syncGroupDtoPreservesSourceUrlField`, `UnifiedBookmarkSyncTest.kt :: consolidateDuplicateJvidGroupsCollapsesVariantsIntoSingleCanonicalEntity`, `UnifiedBookmarkSyncTest.kt :: rollingWindowTimestampMergeResolvesEarliestAverageAndWeightedAverageCleanly`
* **Visual / UI Behavior:**
  * **Media Servers Settings (`BookmarkSyncPreferencesSection`):** Clears stale `192.168.1.100` default, adds **Find PC (Wi-Fi)** auto-scanner (`/api/sync/ping`), **Use USB cable (ADB reverse)** checkbox (`http://127.0.0.1:8000` with ADB guidance instead of a false phone-loopback warning), `X-Sync-Token` field, Test Connection, and Sync Now.
  * **Bookmark Gallery & Player Menus (`BookmarkGalleryScreen`, `BookmarkListContent`, `PlayerSheets`):** Adds **Auto-match videos** button + automatic Echo-2 triggers (`LaunchedEffect`, `App.onActivityResumed`, `MediaLibraryEvents.changes`) scanning Android `MediaFileRepository` videos by JAVID/stem whenever unlinked groups exist, **Link / JAVID** (`ManageBookmarkGroupLinkDialog`) on every non-secure gallery card with clickable/editable source video URL link + **Merge nearby timestamps (10s)** action, plus **Choose thumbnail** in `BookmarkActionMenuDialog` (`BookmarkThumbnailPickerDialog`).
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkMediaMatcher.kt` :: `extractJvidCode`, `normalizePresetCode`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkGroupConsolidator.kt` :: `extractCanonicalJavid`, `planDuplicateJvidConsolidation`, `planRollingWindowTimestampMerges`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/OshashCalculator.kt` :: `computeOshash`, `computeFromBytes`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkPresetArchive.kt` :: `writeArchive`, `readArchive`, `parseJsonImport`
  * `app/src/main/java/app/gyrolet/mpvrx/repository/bookmark/PcBookmarkSyncRepository.kt` :: `discoverPcHubOnLan`, `syncWithPcHub`, `consolidateDuplicateJvidGroupsInDb`, `mergeOverlappingBookmarksForGroup`, `autoMatchOrphanGroupsWithLocalLibrary`, `autoMatchOrphanGroupsIfNeeded`, `fetchThumbnailCandidates`, `selectRemoteThumbnailCandidate`, `linkOrphanGroupToUri`, `assignGroupPresetCodeAndAutoMatch`, `getGroupForMedia`
  * `app/src/main/java/app/gyrolet/mpvrx/database/dao/PlaybackBookmarkDao.kt` :: `reassignBookmarks`, `reassignLinks`, `updatePositionAndTitle`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/bookmarks/BookmarkGalleryScreen.kt` :: `BookmarkGalleryContent`, `BookmarkGalleryCard`, `ManageBookmarkGroupLinkDialog`
  * `app/src/main/java/app/gyrolet/mpvrx/App.kt` :: `onCreate`, `onActivityResumed`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` :: `BookmarkActionMenuDialog`, `BookmarkThumbnailPickerDialog`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/preferences/BookmarkSyncPreferencesSection.kt` :: `BookmarkSyncServerRow`, `BookmarkSyncConfigDialog`


