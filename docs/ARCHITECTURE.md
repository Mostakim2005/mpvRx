# System Architecture (`ARCHITECTURE.md`)
> **1-Line Summary:** Visual system architecture, subsystem mechanics, and integration recipes for mpvRx Android media player.

## 1. High-Level Visual System Map

```mermaid
flowchart TB
    subgraph UI["📱 UI & Presentation Layer (Jetpack Compose)"]
        PlayerView["Player Activity & Surface (mpv native)"]
        PortraitDrawer["Portrait Bookmark Drawer (Native Video Ratio + ContentScale.Fit + Tag Filter)"]
        LandscapeSidebar["Landscape Bookmark Sidebar (20%-35% + 220dp Fit Thumbnails + Global Scope)"]
        GalleryModal["Bookmark Gallery Screen (Vertical Scroll + Tag Autocomplete)"]
        ProfileSec["Profile Screen (5-Tap Stealth Mode)"]
        AudioUI["Audio Player Controls (Namida Default / Classic + Section Toggles + Tap-to-Seek Lyrics)"]
        MusicCustomUI["Music Customization (Tag Editor + Video-in-Art + Downloader)"]
    end

    subgraph State["🧠 State & ViewModel Layer"]
        PlayerVM["PlayerViewModel (Queue, PlaybackState, Parallel Bitmap Cache, Scope)"]
        GalleryVM["BookmarkGalleryViewModel"]
        AudioVM["AudioPlayerViewModel"]
        SecDetector["SecretTapDetector"]
    end

    subgraph Domain["⚙️ Domain & Security Layer"]
        Registry["SecureMediaRegistry (In-Memory Mirror + SecureFolderSession)"]
        Matcher["BookmarkMediaMatcher (Fingerprints)"]
        TagRules["BookmarkTagRules (Normalization)"]
        TextNorm["TextNormalizer (Unicode NFKD Search Cleaner)"]
        Identity["PlaybackIdentity (SHA-256 Digest Engine)"]
        Geometry["VideoGeometryEngine (Native Aspect Ratio Budget)"]
        VaultPersistence["DurableStorageManager + VaultCrypto (MVX1 AES-128-CTR)"]
    end

    subgraph Data["💾 Storage & Persistence Layer"]
        RoomDB[("Segregated Room DBs (.store/library.dat & .vault/v_idx.dat)")]
        WatchStats[("Watch Stats JSON (Public & AES-Encrypted Vault Files)")]
        DurableVault["Durable Root Documents/mpvRx/ (.nomedia, .store/, .vault/, thumbnails/, music/, lyrics/)"]
    end

    PlayerView --> PlayerVM
    PortraitDrawer --> PlayerVM
    LandscapeSidebar --> PlayerVM
    GalleryModal --> GalleryVM
    ProfileSec --> SecDetector
    AudioUI --> AudioVM
    MusicCustomUI --> AudioVM

    PlayerVM --> Registry
    PlayerVM --> Identity
    PlayerVM --> Matcher
    PlayerVM --> Geometry
    AudioVM --> TextNorm
    GalleryVM --> Registry
    SecDetector --> WatchStats

    Registry --> RoomDB
    Registry --> DurableVault
    VaultPersistence --> DurableVault
    PlayerVM --> RoomDB
    PlayerVM --> WatchStats
    AudioVM --> RoomDB
```

---

## 2. Tech Stack & Why We Chose It

| Layer | Tool / Library | Plain-English Reason Why |
| --- | --- | --- |
| Playback Core | `libmpv` (C/C++ via JNI) | Unmatched format compatibility, hardware decoding, subtitle formatting, custom shader pipelines, and audio-video track switching. |
| UI Framework | Android Jetpack Compose + Material 3 | Declarative, reactive UI that effortlessly animates split panels, adaptive grids, and dual-orientation layouts. |
| Dependency Injection | Koin | Lightweight Kotlin-native DI with zero reflection overhead and immediate startup speeds. |
| Persistence | AndroidX Room (SQLite) + AtomicFile | Robust schema migrations for bookmarks/tags/music and atomic file writes for JSON statistics snapshots. |
| Media ID Hash | Java `MessageDigest` (SHA-256) | Generates collision-resistant stable identifiers for media across URI schemes and filesystem paths. |
| Durable Storage | `Documents/mpvRx/` (`.store/` + `.vault/` + `.nomedia`) | Hosts the segregated public (`.store/library.dat`) and `MVX1`-encrypted Secure Vault (`.vault/v_idx.dat`, `.vault/media/`) databases plus bookmark thumbnails outside app-private storage, so uninstalling or clearing the app cannot delete them. |

---

## 3. Core Subsystems & Engineering Tricks

### [SUBSYSTEM: Dynamic-Portrait-Video-And-Bookmark-Drawer] | Aliases: portrait native ratio, dynamic rows, portrait tag filter
> **1-Line Summary:** Computes portrait video height dynamically based on native media aspect ratio, dedicates remaining vertical space to 16:9 bookmark cards (1, 2, or 3+ rows), caps columns at 2 or 3, and embeds a tag filter button in the portrait toolbar.
* **How It Works (Plain English):**
  * **Native Video Aspect Ratio:** In `PlayerControls.kt`, the video height is no longer hardcoded to 16:9 or a fixed percentage. It is calculated as `containerWidth / mediaAspectRatio`. A standard 16:9 video uses `width * 9 / 16`; an ultra-wide 21:9 video uses `width * 9 / 21`, immediately freeing extra vertical space.
  * **Dynamic Row Capacity:** The remaining height (`containerHeight - videoHeight - toolbarHeight`) is evaluated. Depending on the user's row preference (`1 Row`, `2 Rows`, `Auto-Fit`), the drawer displays 1, 2, or 3+ visible rows of cards without awkward clipping.
  * **Columns Selector:** Strictly restricted to 2 or 3 columns (`2 Cols` ⇄ `3 Cols`).
  * **Tag Filter & 2-Tier Portrait Toolbar:** Tier 1 holds title + `FilterList` (opens tag overlay), `Bookmarks`, `Add`, `ZoomOutMap`, `Close`; Tier 2 holds a horizontally scrollable chip strip (`Sync to video`, `2 Cols`/`3 Cols`, `Video`/`Global`, optional `JAVID`) so 360dp phones never clip.
* **Data Flow Diagram:**
```mermaid
flowchart TD
    A["Video Metadata (width x height)"] --> B["Compute mediaAspectRatio = w / h"]
    B --> C["videoHeight = screenWidth / mediaAspectRatio"]
    C --> D["remainingHeight = screenHeight - videoHeight - controlsHeight"]
    D --> E{"User Row Preference"}
    E -->|1-Row| F["1 Row Visible (~110dp card height) + scroll"]
    E -->|2-Rows| G["2 Rows Visible (~220dp) + scroll"]
    E -->|Auto/3-Rows| H["3+ Rows Visible on wide videos / tall screens"]
```
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/PlayerControls.kt` :: `BookmarkSplitPanel`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` :: `BookmarkSplitPanel`, `BookmarkGridCard`, `PanelToggleChip`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkPositionSync.kt` :: `resolveBookmarkSyncTarget`, `resolveBookmarkOrChapterScrollIndex`

---

### [SUBSYSTEM: Landscape-Sidebar-With-Global-Scope-Toggle] | Aliases: landscape sidebar, 20-35 width, non-overflow toolbar, global scope toggle
> **1-Line Summary:** Landscape bookmark sidebar maintaining 20% to 35% width steps with a non-overflow 2-tier toolbar, floating tag overlay, and a `Video ⇄ Global` scope toggle shared with the Chapters sheet and the full gallery.
* **How It Works (Plain English):**
  * **Width Menu:** Cycles across 20%, 25%, 30%, and 35%.
  * **Non-Overflow 2-Tier Toolbar:**
    * *Tier 1 (Action Icons):* Compact icon buttons with tooltips (`FilterList`, `Bookmarks`, `Add`, `ZoomOutMap`, `Close`).
    * *Tier 2 (Scrollable Toggle & Sync Chips):* Compact chips inside a horizontally scrollable row (`Sync`, `Global`/`Video`, `25%`, card count, optional `JAVID`) so narrow `20%` width never clips or wraps.
  * **Chapter-Style Playhead Sync:** `resolveBookmarkSyncTarget` highlights the playing video's latest bookmark `<= currentPositionMs` without stealing scroll, and **Sync** / **Sync to video** animates Portrait, Landscape, or Overlay `ChaptersSheet` back to it.
  * **In-Player Scope Toggle:** Tapping `🎬 This Video ⇄ 🌐 All Bookmarks` triggers `PlayerViewModel.toggleInPlayerBookmarkScope()`. The state lives in `PlayerViewModel`, so the portrait drawer, Chapters sheet and full-window gallery all show the same slice instead of keeping private copies that reset when a panel leaves composition.
  * **Floating Tag Filter Popup:** Opens a centered translucent overlay over the video without stopping playback.
* **Data Flow Diagram:**
```mermaid
sequenceDiagram
    participant User
    participant Controls as PlayerControls
    participant Drawer as BookmarkSplitPanel
    participant VM as PlayerViewModel
    participant mpv as Native mpv Engine

    User->>Controls: Taps Scope Toggle (🎬 This Video ⇄ 🌐 All Bookmarks)
    Controls->>VM: toggleInPlayerBookmarkScope()
    VM-->>Drawer: Emits updated bookmark list (Local vs Global)
    User->>Drawer: Taps 16:9 Bookmark Card
    Drawer->>VM: openBookmark(bookmark)
    alt Bookmark belongs to the current media
        VM->>mpv: command("seek", positionSeconds, "absolute")
    else Bookmark points at another media
        VM->>VM: resolve bookmark_media_refs replay URI
        VM->>mpv: open that file, then seek to positionSeconds
    end
```
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` :: `BookmarkSplitPanel`, `BookmarkGridCard`, `PanelToggleChip`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkPositionSync.kt` :: `resolveBookmarkSyncTarget`, `resolveBookmarkOrChapterScrollIndex`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/sheets/ChaptersSheet.kt` :: `ChaptersSheet`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/PlayerSheets.kt` :: `ChaptersSheetWithBookmarks`

---

### [SUBSYSTEM: Natural-Scroll-Gallery] | Aliases: bookmark gallery, vertical scroll grid, tag filter
> **1-Line Summary:** Fullscreen modal browsing library bookmarks with natural vertical scrolling (`LazyVerticalGrid`), hard-segregated public (`All videos`) vs vault (`Secure videos`) slices, dual video-and-bookmark card labels, autocomplete tag search, and orientation column caps (max 3 portrait, max 4 landscape).
* **How It Works (Plain English):** Smooth vertical `LazyVerticalGrid`. Top area provides a search bar with clickable autocomplete tag pills directly underneath. Columns are capped at 3 in portrait and 4 in landscape. Cards are 16:9 with gradient labels showing `"<VideoName> · <BookmarkTitle>"` in Global scope.
  * **Hard Segregation:** The public gallery (`secureOnly = false`) offers `This video` and `All videos` only — the `Secure videos` pill is completely removed from the normal browser and normal player so vault bookmarks never appear outside the unlocked Secure Folder. When opened from `SecureFolderScreen` or a Secure Folder video (`secureOnly = true`), the gallery is locked to `Secure videos` (and `This video`) and auto-closes if `SecureFolderSession.unlockedState` transitions to `false`.
  * **Physical Database Separation:** `BookmarkGalleryRepository` reads `BookmarkGalleryScope.AllVideos` strictly from `MpvRxDatabase` (`.store/library.dat`) and `BookmarkGalleryScope.SecureVideos` strictly from `SecureBookmarkStore` (`SecureVaultDatabase` at `.vault/v_idx.dat`, gated on `SecureFolderSession.unlockedState`).
* **Data Flow Diagram:**
```mermaid
sequenceDiagram
    participant User
    participant Gallery as BookmarkGalleryScreen
    participant Repo as BookmarkGalleryRepository
    participant PubDB as MpvRxDatabase (.store/library.dat)
    participant SecStore as SecureBookmarkStore (.vault/v_idx.dat)

    User->>Gallery: Opens Gallery Modal (secureOnly = false or true)
    Gallery->>Repo: observeItems(scope) + observeFilterTags(scope)
    alt BookmarkGalleryScope.AllVideos
        Repo->>PubDB: observeGallery() + observeAllPairs() + observeMediaRefs()
        PubDB-->>Repo: Public bookmark rows + tags + replay URIs
    else BookmarkGalleryScope.SecureVideos (requires SecureFolderSession.unlockedState == true)
        Repo->>SecStore: observeGalleryItems() + observeFilterTags()
        SecStore-->>Repo: Decrypted MVX1 vault bookmarks + tags + replay URIs
    end
    Repo-->>Gallery: Segregated BookmarkGalleryItem list
    Gallery->>User: Displays 16:9 cards with dual Video · Bookmark titles
```
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/bookmarks/BookmarkGalleryScreen.kt` -> `BookmarkGalleryScreen`, `BookmarkGalleryOverlay`, `BookmarkGalleryContent`, `onlyCurrentVideo`, `initialOnlyCurrentVideo`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkGalleryRepository.kt` -> `observeItems`, `observeFilterTags`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/SecureBookmarkStore.kt` -> `observeGalleryItems`, `migrateMediaToVault`, `migrateMediaToPublic`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` -> `formatGlobalBookmarkTitle`, `BookmarkSplitPanel`

---

### [SUBSYSTEM: Bounded-UI-Thread-Native-Reads] | Aliases: playback ANR, mpv_get_property freeze, surface handoff lock, 150ms lock timeout
> **1-Line Summary:** Composition may ask libmpv for properties at any moment, so every UI-thread native read gives up after 150 ms and returns its caller's default instead of blocking on the session-wide lock during a Surface handoff.
* **How It Works (Plain English):**
  * `PlaybackSession` guards native mpv calls with a single `ReentrantLock`. A Surface attach/detach can hold that lock inside libmpv for longer than a frame, while Compose re-reads a property during recomposition.
  * All property reads flow through one helper: on the main looper it uses `nativeLock.tryLock(150 ms)`, and on failure returns the supplied default (0/false/empty) rather than waiting. Off the main thread it still takes a blocking lock, because worker correctness matters more than latency there.
  * The default is a *stale-but-safe* value, not an error: the observed-property flows emit the real value as soon as the event pump resumes, so the next recomposition corrects the UI.
  * Registering an observed property never waits at all — a zero-timeout `tryLock` failure is deferred to the pump, which registers it off the UI thread.
  * Surface attachment flags are `@Volatile`, so the UI thread can check them without taking the native lock.
  * Relative double-tap/remote seeks (`PlayerViewModel.seekBy`) and UI-initiated audiobook seeks (`AudiobookPlayback.seek` / `seekBy` / `seekInBook`) run off the Main thread on single-threaded sequential IO dispatchers (`RelativeSeekBatcher` coalesces rapid bursts that arrive while libmpv is mid-seek and skips redundant EOF `100%` exact seeks), keeping native `commandForGeneration` locks off Compose gesture handlers.
* **Data Flow Diagram:**
```mermaid
sequenceDiagram
    participant UI as Compose (main thread)
    participant PS as PlaybackSession
    participant Lock as nativeLock (held by Surface handoff)
    participant Pump as mpv event pump

    UI->>PS: readProperty(default)
    PS->>Lock: tryLock(150 ms)
    Lock-->>PS: timeout
    PS-->>UI: default value (no ANR)
    Pump->>PS: property-change event after handoff
    PS-->>UI: observed flow emits the real value
```
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/PlaybackSession.kt` :: `UI_NATIVE_READ_TIMEOUT_MS`, `withReadyCoreRead`, `registerObservedLocked`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/PlaybackSession.kt` :: `attachedSurfaceOwner`, `attachedSurfaceWidth` (`@Volatile`)
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/RelativeSeekBatcher.kt` :: `RelativeSeekBatcher`, `enqueue`, `takeNext`, `cancelPending`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/PlayerViewModel.kt` :: `seekBy`, `executeRelativeSeekBatch`, `cancelPendingRelativeSeek`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/AudiobookPlayback.kt` :: `seek`, `seekBy`, `seekInBook`, `seekInBookResolved`, `seekImmediate`

---

### [SUBSYSTEM: Music-Player-Lyrics-And-Split-Landscape] | Aliases: 2-line lyrics, 3-line lyrics, landscape 50-50 split, landscape 40-60 split
> **1-Line Summary:** Multi-line synchronized lyrics in portrait (2-line default, 3-line option) with dynamic artwork scaling, and configurable artwork vs lyrics split in landscape (`50/50`, `40/60`, `60/40`).
* **How It Works (Plain English):**
  * **Portrait Mode:** 2-line mode scales album artwork to 144dp; 3-line mode scales artwork to 112dp. Ensures transport controls and seekbars are never clipped or pushed off screen.
  * **Landscape Mode:** A split selector allows the user to allocate screen width between album artwork (or music video) and scrolling lyrics (`50/50` equal balance, `40/60` lyrics priority, `60/40` artwork priority).
  * **Customization Suite:** Details in [`docs/MUSIC_PLAYER_CUSTOMIZATION.md`](file:///c:/Users/kazir/Documents/Agentic Harness/mpvRx/docs/MUSIC_PLAYER_CUSTOMIZATION.md).
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/AudioPlayerControls.kt` -> `AudioPlayerControls`

---

### [SUBSYSTEM: Zero-Leak-Secure-Sandbox-And-Vault-Persistence] | Aliases: persistent vault, survive uninstall, secure media registry, mvx1 encryption, vault streaming proxy
> **1-Line Summary:** Hard-segregates Secure Folder media, bookmarks, thumbnails, playback states, metadata cache, watch stats, and PIN config into an AES-128-CTR (`MVX1`) encrypted vault under `Documents/mpvRx/.vault/` with loopback range streaming (`VaultStreamingProxy`) and a 30-second background grace lock.
* **How It Works (Plain English):**
  * **Hardware-Accelerated `MVX1` Encryption (`VaultCrypto`):** Every vault media file (`.vault/media/<uuid>.dat`), thumbnail (`.vault/thumbs/*.dat`, `.vault/thumb_cache/*.dat`, `.store/thumbs/*.dat`, `.store/thumb_cache/*.dat`), watch-stats snapshot (`watch_stats.mvx`, `v_stats.mvx`), durable settings mirror (`settings.mvx`, `v_cfg.mvx`), and sensitive SQLite text column in `SecureVaultDatabase` (`v_idx.dat`) is encrypted with AES-128-CTR (`MVX1` 32-byte header carrying a 16-byte random IV and 64-bit plaintext length). A 128-bit master key is wrapped with PBKDF2-HMAC-SHA256 + HMAC integrity tag inside `.vault/.vkey` (avoiding `AndroidKeyStore`, which wipes on Clear Data or app uninstall).
  * **Zero-Copy Seekable Playback & Probing:** `libmpv` streams encrypted vault videos via `VaultStreamingProxy` (`127.0.0.1` NanoHTTPD server computing `baseIv + (byteOffset / 16)` in $O(1)$ per HTTP `Range` request, gated on `SecureFolderSession.isUnlocked`). Android `MediaMetadataRetriever` and `MediaExtractor` read encrypted files in-memory via `VaultMediaDataSource` (`MediaDataSource`).
  * **Hard Database Segregation:** Normal media uses `MpvRxDatabase` (`.store/library.dat`); Secure Folder media uses `SecureVaultDatabase` (`.vault/v_idx.dat`) and `SecureBookmarkStore`. Moving files into or out of the Secure Folder (`SecureFolderRepository.moveIn` / `restore`) atomically migrates bookmarks, tags, groups, thumbnails, and playback resume states across the two databases.
  * **Session Grace Lock & Single PIN Prompt:** `SecureFolderSession` maintains `unlockedState: StateFlow<Boolean>` with a 30-second background grace timer (`BACKGROUND_LOCK_GRACE_MS = 30_000L`) and an explicit Lock button in `SecureFolderScreen`. `MediaUtils.startGuarded` skips `SecureUnlockActivity` whenever `SecureFolderSession.isUnlocked` is `true`, eliminating double PIN prompts. When locked, `VaultStreamingProxy.revokeAllActiveStreams()` and memory cache eviction run immediately, and `SecureFolderScreen`, `SecureFolderAddFilesScreen`, `BookmarkGalleryScreen(secureOnly = true)`, and `PlayerActivity` auto-lock.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/secure/VaultCrypto.kt` -> `encryptBytes`, `decryptBytes`, `readDecryptedAt`, `openDecryptedRangeStream`, `hmacKey`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/secure/VaultStreamingProxy.kt` -> `registerVaultFile`, `revokeAllActiveStreams`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/secure/VaultMediaDataSource.kt` -> `VaultMediaDataSource`, `readAt`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/secure/SecureFolderSession.kt` -> `unlockedState`, `onAppBackgrounded`, `onAppForegrounded`, `lockNow`
  * `app/src/main/java/app/gyrolet/mpvrx/database/SecureVaultDatabase.kt` -> `SecureVaultDatabase`
  * `app/src/main/java/app/gyrolet/mpvrx/database/repository/SecureFolderRepository.kt` -> `moveIn`, `restore`, `destroyAll`

---

### [SUBSYSTEM: Zero-Bloat-Single-Folder-Direct-Hosting-Engine] | Aliases: single folder storage, zero bloat, live room db external, no duplicate backups, survive clear data
> **1-Line Summary:** Hosts segregated live Room databases (`.store/library.dat` and `.vault/v_idx.dat`), encrypted thumbnails, watch stats, and preferences directly under `Documents/mpvRx/`, pruning internal storage copies so App Data stays tiny and 100% of user state survives Clear Data and Uninstall/Reinstall.
* **How It Works (Plain English):**
  * All durable state is stored under `/storage/emulated/0/Documents/mpvRx/` split into `.store/` (normal library database `library.dat`, encrypted bookmark thumbnails `thumbs/`, encrypted video thumbnail cache `thumb_cache/`, encrypted `watch_stats.mvx`, and non-personal UI/player settings `settings.mvx`) and `.vault/` (wrapped master key `.vkey`, `SecureVaultDatabase` `v_idx.dat`, encrypted vault videos `media/<uuid>.dat`, encrypted thumbnails `thumbs/` & `thumb_cache/`, encrypted `v_stats.mvx`, and Secure Folder PIN/recovery config `v_cfg.mvx`).
  * `DurableVaultMigration.run` performs an idempotent, verify-before-delete migration from v3.2.2 mixed directories and internal `filesDir/secure_media`, `bookmark-thumbnails`, `thumbnails`, and `databases/mpvrx.db*`, deleting internal copies once verified so Android's "App Data" footprint does not bloat with multi-GB vault media.
  * Every mpvRx media scanner (`MediaScanOptions`, `VideoScanUtils`, `HiddenFolderDiscovery`, `FileSystemBrowserViewModel`) excludes `Documents/mpvRx` (`DurableStorageManager.isAppDurablePath`), and `.nomedia` markers are placed in every subdirectory.
  * When launched after Clear Data or Uninstall/Reinstall, granting storage permission in `PermissionDeniedState` detects the existing durable library (`hasPreexistingDurableLibrary`) and rebinds the process to `Documents/mpvRx/`, where `DurablePreferencesSync.restoreIfNeeded` restores all settings and the Secure Folder PIN before Koin and Room open.
* **Data Flow Diagram:**
```mermaid
flowchart TD
    Root["Durable Root (/sdcard/Documents/mpvRx/)"] --> Store[".store/ (Normal Library Slice)"]
    Root --> Vault[".vault/ (Isolated Secure Vault Slice)"]
    Store --> LibDB["library.dat (MpvRxDatabase)"]
    Store --> PubThumbs["thumbs/ & thumb_cache/ (MVX1 Encrypted)"]
    Store --> PubMeta["watch_stats.mvx & settings.mvx (MVX1 Encrypted)"]
    Vault --> VKey[".vkey (PBKDF2-Wrapped AES-128 Key)"]
    Vault --> VaultDB["v_idx.dat (SecureVaultDatabase)"]
    Vault --> VaultMedia["media/<uuid>.dat (MVX1 AES-128-CTR Videos)"]
    Vault --> VaultThumbs["thumbs/ & thumb_cache/ (MVX1 Encrypted)"]
    Vault --> VaultMeta["v_stats.mvx & v_cfg.mvx (PIN + Vault Stats)"]
```
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/storage/DurableStorageManager.kt` -> `prepare`, `normalDatabaseFile`, `secureDatabaseFile`, `isAppDurablePath`, `isVaultPath`, `needsProcessRebindToDurableRoot`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/storage/DurableVaultMigration.kt` -> `run`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/storage/DurablePreferencesSync.kt` -> `restoreIfNeeded`, `startObserving`, `flushNow`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/browser/states/PermissionDeniedState.kt` -> `rebindProcessToDurableRoot`

---

### [SUBSYSTEM: Musicolet-Style-Metadata-And-Lyrics-Tag-Injector] | Aliases: musicolet style, embed lyrics into audio, id3 uslt tag, vorbis comment lyrics
> **1-Line Summary:** Directly injects synchronized LRC and unsynced plain text lyrics into audio container tags (`USLT`, `LYRICS`, `©lyr`) and updates metadata without re-encoding audio bytes.
* **How It Works (Plain English):**
  * Reads the physical audio container structure (MP3 ID3v2 header, FLAC/Vorbis comment block, MP4 atom box).
  * Writes the LRC-formatted text block directly into the audio tag:
    * **MP3:** Injects `USLT` (Unsynchronised Lyric/Text) and `SYLT` (Synchronised Lyric/Text) frames.
    * **FLAC / OGG / Opus:** Injects `LYRICS` and `UNSYNCEDLYRICS` Vorbis comment fields.
    * **M4A / MP4:** Injects `©lyr` atom.
  * Execution takes less than 30ms and touches only header bytes, leaving audio streams unaltered and bit-perfect.
* **Data Flow Diagram:**
```mermaid
sequenceDiagram
    participant User
    participant Editor as TagEditorUI
    participant Engine as AudioTaggingEngine
    participant AudioFile as Physical Audio File (.opus / .mp3)
    participant LiveDB as Live Room DB

    User->>Editor: Edits Lyrics / Anchors Timestamps
    Editor->>Engine: embedLyrics(filePath, lrcString)
    Engine->>AudioFile: Injects USLT / Vorbis LYRICS Tag (No Re-encoding)
    Engine->>LiveDB: Updates hasEmbeddedLyrics flag
    AudioFile-->>User: File now carries lyrics inside itself anywhere it is shared
```
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/metadata/AudioTaggingEngine.kt` -> `embedLyricsIntoAudioFile`, `extractEmbeddedLyrics`

---

### [SUBSYSTEM: Audio-less-Companion-Video-And-libmpv-Lockstep-Engine] | Aliases: audioless video, collision free sync, libmpv lockstep, video in artwork
> **1-Line Summary:** Synchronizes pure video streams with local high-fidelity audio, attaching/detaching the video surface without audio restart, collision, or timestamp drift.
* **How It Works (Plain English):**
  * When local audio already exists, yt-dlp extracts the video-only stream (`bestvideo[ext=mp4]`), saving ~60% bandwidth.
  * `libmpv` is instructed to mount the audio file as the primary master clock and slaves the companion video stream to the exact audio presentation timestamp (PTS).
  * Toggling between **Art** and **Video** attaches the Android `SurfaceView` to the existing mpv instance without stopping or reloading the audio stream.
  * When downloading a full video with audio, a smart prompt offers to delete redundant old audio files and unify playback.
* **Data Flow Diagram:**
```mermaid
flowchart LR
    LocalAudio["Local Audio File (Opus/MP3) [Master Clock]"] --> mpvCore["libmpv Core Engine"]
    CompanionVideo["Audio-less Companion Video (MP4) [Slaved PTS]"] --> mpvCore
    mpvCore --> AudioOut["Audio Output Track (Collision-Free)"]
    mpvCore --> SurfaceToggle{"Art vs Video Viewport"}
    SurfaceToggle -->|Art Mode| AlbumArtView["Render Album Artwork + Lyrics"]
    SurfaceToggle -->|Video Mode| SurfaceView["Attach SurfaceView Frame"]
```
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/PlayerViewModel.kt` -> `attachCompanionVideo`, `toggleArtworkVideoSurface`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/download/CompanionVideoDownloader.kt` -> `downloadAudiolessStream`

---

### [SUBSYSTEM: Single-Point-Anchor-Lyric-Offset-Calibrator] | Aliases: 1-point lyric sync, anchor line to playhead, uniform offset shift
> **1-Line Summary:** Offsets an entire song's synchronized lyrics in a single tap by anchoring any selected lyric line to the active playhead, persisting changes to both disk and embedded tags.
* **How It Works (Plain English):**
  * Lyric files often have intro delays or slight differences across album/MV versions.
  * User selects a lyric line (e.g. line at timestamp $T_{\text{lyric}}$) and listens to the track.
  * At the exact second the line is vocalized, user taps "Anchor to Current Time" ($T_{\text{current}}$).
  * Calculates $\Delta t = T_{\text{current}} - T_{\text{lyric}}$.
  * Iterates through all lines in the LRC dataset and applies: $T'_{\text{line}} = \max(0, T_{\text{line}} + \Delta t)$.
  * Immediately writes updated lyrics to `<UserRoot>/lyrics/[Title].lrc` and embeds into audio container metadata.
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/lyrics/LyricsOffsetCalibrator.kt` -> `anchorLineToPlayhead`, `shiftAllTimestamps`

---

### [SUBSYSTEM: Three-Way-Unified-Bookmark-Sync-And-Oshash-Matcher] | Aliases: oshash matching, preset zip v2, lan bookmark sync, orphan point to video, javid extraction, usb adb sync, thumbnail chooser
> **1-Line Summary:** Bridges `MpvRxDatabase` v37 (`oshash`, canonical JAVID `presetCode`, `sourceUrl`, `syncUuid`, `primaryTag`, `endPositionMs`, tombstones) with `AV Manager` / `Stash` via Wi-Fi discovery (`/api/sync/ping`), USB ADB mode, bi-directional LWW REST sync (`X-Sync-Token`), universal `.zip`/`.json` import, Echo-2 automatic local JAVID matching (Gallery open, Activity resume, `MediaLibraryEvents.changes`), and multi-source thumbnail switching (`BookmarkThumbnailPickerDialog`) while isolating `SecureVaultDatabase` v2.
* **How It Works (Plain English):**
  * Canonical JAVID extraction (`BookmarkMediaMatcher.extractJvidCode`, e.g. `[ThZu.Cc]_mimk232c.mp4` -> `MIMK-232`) works alongside 64-bit `oshash` so `resolveGroupKey`, `syncWithPcHub`, and `autoMatchOrphanGroupsIfNeeded` automatically unify bookmarks even when filenames differ or videos are copied onto the phone later (triggered cleanly on Gallery open, `MainActivity`/`PlayerActivity` resume, and `MediaLibraryEvents.changes` when unlinked groups exist).
  * `PcBookmarkSyncRepository` exchanges Last-Write-Wins deltas (`POST /api/sync/merge` including `sourceUrl`) and bi-directional JPEG thumbnails (`/api/sync/thumbnail/{uuid}`, `/api/bookmarks/{uuid}/thumbnails*`) exclusively with public `MpvRxDatabase` (`R-11` isolation), respecting `updatedAt` timestamps bumped on Room `rename`, `updateThumbnail`, and tag edits.
  * Unlinked remote groups land in `bookmark_groups` and auto-match via `MediaFileRepository` after sync / Echo-2 / 1-tap `Auto-match videos`, while `BookmarkGalleryScreen` exposes **Link / JAVID** (`ManageBookmarkGroupLinkDialog`) on every public card so any bookmark group can be manually linked, re-linked, or assigned a JAVID / source URL even for unformatted filenames.
* **Data Flow Diagram:**
```mermaid
flowchart LR
    classDef publicDb fill:#064e3b,stroke:#10b981,stroke-width:2px,color:#ecfdf5
    classDef secureDb fill:#881337,stroke:#f43f5e,stroke-width:2px,color:#fff1f2
    classDef syncNode fill:#312e81,stroke:#818cf8,stroke-width:2px,color:#ffffff
    classDef pcHub fill:#78350f,stroke:#f59e0b,stroke-width:2px,color:#fffbeb

    PublicDB[("MpvRxDatabase v37\noshash, JAVID, sourceUrl, tombstones")]:::publicDb
    SecureDB[("SecureVaultDatabase v2\nMVX1 Encrypted (Isolated R-11)")]:::secureDb
    PresetV2["Preset .zip v2 & JSON\nPreset / Vault / Stash JSON"]:::syncNode
    SyncRepo["PcBookmarkSyncRepository\nLAN Ping + USB ADB + X-Sync-Token"]:::syncNode
    AvHub[("AV Manager vault.db\n<-> Stash localhost:9999")]:::pcHub
    OrphanUI["BookmarkGallery & Picker UI\nAuto-Match + Thumbnail Chooser"]:::publicDb

    PublicDB <--> SyncRepo <-->|LWW Merge + Thumbs| AvHub
    PublicDB <--> PresetV2
    SecureDB <-.->|"Local ZIP/JSON Only\n(Never LAN Synced)"| PresetV2
    OrphanUI -->|JVID Link + Thumb Select| PublicDB
```

> **Legend box:** Emerald = Public DB, Auto-Match & Thumbnail Picker | Rose = Isolated Secure Vault | Indigo = Sync Repository & Universal Preset Reader | Amber = PC Hub (`AV Manager` + `Stash`)

* **Links:** `[FEATURE: Unified-Bookmark-Preset-V2-Oshash-LAN-Sync-And-Orphan-Linker]`, `[TRAP: Stale-Rename-Update-Timestamps-And-Loopback-USB-Conflict]`
* **Code Anchors (Grep Targets):**
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkMediaMatcher.kt` :: `extractJvidCode`, `normalizePresetCode`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkGroupConsolidator.kt` :: `extractCanonicalJavid`, `planDuplicateJvidConsolidation`, `planRollingWindowTimestampMerges`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/BookmarkPresetArchive.kt` :: `readArchive`, `parseJsonImport`
  * `app/src/main/java/app/gyrolet/mpvrx/domain/bookmark/OshashCalculator.kt` :: `computeOshash`
  * `app/src/main/java/app/gyrolet/mpvrx/repository/bookmark/PcBookmarkSyncRepository.kt` :: `discoverPcHubOnLan`, `syncWithPcHub`, `consolidateDuplicateJvidGroupsInDb`, `mergeOverlappingBookmarksForGroup`, `autoMatchOrphanGroupsWithLocalLibrary`, `autoMatchOrphanGroupsIfNeeded`, `fetchThumbnailCandidates`, `selectRemoteThumbnailCandidate`, `linkOrphanGroupToUri`, `assignGroupPresetCodeAndAutoMatch`, `getGroupForMedia`
  * `app/src/main/java/app/gyrolet/mpvrx/database/dao/PlaybackBookmarkDao.kt` :: `reassignBookmarks`, `reassignLinks`, `updatePositionAndTitle`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/bookmarks/BookmarkGalleryScreen.kt` :: `BookmarkGalleryContent`, `BookmarkGalleryCard`, `ManageBookmarkGroupLinkDialog`
  * `app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/BookmarkListContent.kt` :: `BookmarkActionMenuDialog`, `BookmarkThumbnailPickerDialog`
  * `app/src/main/java/app/gyrolet/mpvrx/database/MpvRxDatabase.kt` & `di/DatabaseModule.kt` :: `MIGRATION_35_36`, `MIGRATION_36_37`, `SECURE_MIGRATION_1_2`

---

## 4. Verification & Health Checks

### [VERIFY: Cloud-And-Unit-TestSuite] | Aliases: test commands, gradle check, unit tests
> **1-Line Summary:** GitHub Actions is the only verification path; local Gradle builds and unit tests are not part of this project's workflow.
* **CI Build:** `.github/workflows/build.yml` runs the JVM unit tests and builds the standard release APK on every push to `master`.
* **Tag-Triggered Release Pipeline:** Push annotated git tag `vX.Y.Z` to trigger `.github/workflows/release.yml`, which re-validates the tag against `versionName` and `CHANGELOG.md`, builds and signs the APK, and publishes the GitHub Release asset.
* **Reading results:** `gh` in this checkout defaults to the `upstream` remote (`Riteshp2001/mpvRx`), not `origin`, so always pass `--repo Mostakim2005/mpvRx` to `gh run list` / `gh run view` / `gh release view`.
* **Caveat:** `build.yml` cancels the in-progress run for the same ref, so wait for a run to finish before pushing again; a cancelled run proves nothing.

---

## 5. Recipes: How to Add New Things Safely

* **Dual-Orientation Preview Rule:**
  * When generating UI previews or mocks, ALWAYS generate separate Portrait and Landscape versions with their dedicated proportions and controls.
