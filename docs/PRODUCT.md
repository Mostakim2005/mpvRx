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

### [FEATURE: Zero-Bloat-Single-Folder-Direct-Hosting] | Aliases: single folder storage, zero bloat, live room db on external storage, survive uninstall, no duplicate backups
> **1-Line Summary:** Hosts the live Room SQLite database, Secure Vault, bookmark thumbnails, configurations, music, companion videos, and lyrics directly in a single user-selected root folder without duplicate backup bloat.
* **User Intention (Non-Negotiable):** The user selects a single directory in basic storage (e.g. `/sdcard/Documents/mpvRx/`). The active Room database (`mpvrx.db`), private media vault, thumbnails, and settings live directly inside this folder. No separate or redundant backup files are created, avoiding storage duplication and bloat. If the app is uninstalled, updated, or wiped, reinstalling and pointing to this folder instantly restores the complete library and state.
* **Directory Layout:**
  * `<UserRoot>/.nomedia` (blocks media scanner from indexing vault / internal assets)
  * `<UserRoot>/database/mpvrx.db` (LIVE active Room database, WAL and SHM files)
  * `<UserRoot>/vault/` (encrypted Secure Folder media)
  * `<UserRoot>/thumbnails/` (16:9 bookmark thumbnail image cache)
  * `<UserRoot>/config/` (PIN verification salt & user player settings)
  * `<UserRoot>/music/` (audio files with clean metadata-matched filenames)
  * `<UserRoot>/companion_videos/` (downloaded audio-less / full MP4 companion videos)
  * `<UserRoot>/lyrics/` (dedicated `.lrc` files)
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/database/AppDatabase.kt` -> `buildExternalDatabase`
  * `app/src/main/java/app/gyrolet/mpvrx/database/repository/SecureFolderRepository.kt` -> `SecureFolderRepository`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/storage/DurableStorageManager.kt` -> `getLiveDatabasePath`, `resolveRootDirectory`

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
