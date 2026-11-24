# Handover — mpvRx v3.3.0 release, Namida music UX, and security/storage batch

Last updated: 2026-11-24 (v3.3.0 released; repo metadata/About/docs synced on `master`) · Branch `master` · Tag `v3.3.0`

## 1. How this batch is verified

Verification runs on **GitHub Actions only**. Local Android SDK installs, Gradle builds and unit
tests are not part of this project's workflow; the machine may not have a usable 64-bit JDK or
SDK at all. Push the commits and read the workflow result:

```bash
# what ran, newest first
gh run list --repo Mostakim2005/mpvRx --limit 5 \
  --json databaseId,headSha,status,conclusion,workflowName

# fail details for a run
gh run view <run-id> --repo Mostakim2005/mpvRx --log-failed \
  | grep -E "\.kt:[0-9]+|FAILED|Caused by"

# compiler errors only
gh run view <run-id> --repo Mostakim2005/mpvRx --log-failed \
  | grep "e: file:///"
```

**Caveat — cancelled runs.** `.github/workflows/build.yml` uses a concurrency group with
`cancel-in-progress`, so **every new push cancels the build still running for the previous
commit**. Push one logical change, wait for that run to finish (≈12–18 min: JVM unit tests then
the standard APK build), and only then push the next. A cancelled run proves nothing.

**Verify against `origin`, not the default `gh` repo.** In this checkout `gh` resolves to the
`upstream` remote (`Riteshp2001/mpvRx`), which has unrelated runs. Always pass
`--repo Mostakim2005/mpvRx`.

**Current runs**

| Run | Commit | Workflow | Result |
| --- | --- | --- | --- |
| `45440995888` | `cfb75b18` | CI/CD Build | **success** — JVM unit tests, standard release APK, signing, upload |
| `45440993670` | `cfb75b18` (`v3.3.0`) | Make release | **success** — tag/version/CHANGELOG validation, signed APK, release published |
| `44749087264` | `de3aadb0` (`v3.2.3`) | Make release | **success** — MVX1 AES-CTR vault segregation & Room schema v35 release |
| `44242573783` | `3f3ea718` (`v3.2.2`) | Make release | **success** — tag/version/CHANGELOG validation, signed APK, release published |

GitHub Release `v3.3.0` (published 2026-11-24) carries one asset:
`mpvRx-arm64-v8a-v3.3.0.apk` (`63,410,173` bytes, sha256 `a2e05c917439772f7a432cea9d876951c5487a389f6e061a3fb9926d5946d2e4`).
Query the workflow after every new push and extend this table. A cancelled run proves nothing:
`build.yml` cancels the in-progress run for the same ref, so wait for each run to finish before
pushing again.

Nothing in this batch has been exercised on a device. "Shipped" below means *committed and
pushed*; the only automated check is the GitHub Actions unit-test + APK job. Device verification
is still outstanding.

## 2. Shipped in this batch (Post-v3.2.2 Hard Segregation & MVX1 Vault Encryption)

| Item | Surface | Change | Behaviour |
| --- | --- | --- | --- |
| Hard Segregation | `SecureVaultDatabase.kt`, `SecureVaultEntities.kt`, `SecureVaultDaos.kt`, `SecureBookmarkStore.kt`, `BookmarkGalleryRepository.kt`, `PlayerViewModel.kt`, `BookmarkGalleryScreen.kt` | Isolated `.vault/v_idx.dat` database for vault media, bookmarks, tags, groups, playback states, and metadata cache; removed `Secure` pill from normal gallery | Normal and Secure Folder bookmarks, thumbnails, resume states, and tags never cross paths in any surface |
| `MVX1` AES-128-CTR Vault Encryption | `VaultCrypto.kt`, `VaultMediaDataSource.kt`, `VaultStreamingProxy.kt`, `PlaybackSession.kt`, `MediaInfoOps.kt`, `BookmarkThumbnailStore.kt`, `ThumbnailRepository.kt` | 32-byte `MVX1` header + AES-128-CTR encryption for vault videos (`.vault/media/<uuid>.dat`), thumbnails, watch stats, settings mirrors, and SQLite text columns; loopback HTTP range proxy for `libmpv` | Files in `Documents/mpvRx/` cannot be browsed or identified by other apps; $O(1)$ seeking with hardware AES acceleration on Helio G90T |
| Full Durable Survival & Internal Cleanup | `DurableStorageManager.kt`, `DurableVaultMigration.kt`, `DurablePreferencesSync.kt`, `PermissionDeniedState.kt`, `App.kt`, `WatchStatsRepository.kt`, `AndroidManifest.xml` | `.store/` and `.vault/` layout under `Documents/mpvRx/`; verify-before-delete pruning of internal `filesDir/secure_media`, `bookmark-thumbnails`, `thumbnails`, and `mpvrx.db*`; automatic process rebind after granting storage permission | App Data footprint stays tiny; clearing App Data or uninstalling/reinstalling preserves all library data, bookmarks, thumbnails, watch stats, UI settings, Secure Vault videos, and Secure Folder PIN |
| Global/Video Scope & Foreign Bookmark Launch | `PlayerViewModel.kt`, `BookmarkListContent.kt`, `PlayerSheets.kt`, `ChaptersSheet.kt`, `BookmarkGalleryScreen.kt`, `MediaUtils.kt`, `SecureUnlockActivity.kt` | Routed all player bookmark flows by `isCurrentMediaSecure`; recorded `mediaRef` on quick-add; formatted Global titles as `"<Video> · <Bookmark>"`; passed Activity context and defensive `FLAG_ACTIVITY_NEW_TASK` | `Video ⇄ Global` works in landscape sidebar, portrait drawer, Chapters sheet, and full gallery for both normal and secure videos without crashing on foreign bookmarks |
| Session Lock & Single PIN Prompt | `SecureFolderSession.kt`, `MediaUtils.kt`, `SecureFolderScreen.kt`, `SecureFolderAddFilesScreen.kt`, `PlayerActivity.kt`, `App.kt` | Skipped `SecureUnlockActivity` when `SecureFolderSession.isUnlocked` is true; added 30s background grace period + explicit Lock button + stream revocation and cache eviction on lock | Entering the Secure Folder and playing videos prompts for the PIN only once; locking or backgrounding > 30s locks all vault screens and active playback |

## 2b. Earlier v3.2.1 batch

| Item | Commit | Change | Behaviour |
| --- | --- | --- | --- |
| R-04 (gallery half) | `e106d62b` | `ui/bookmarks/BookmarkGalleryScreen.kt` | `BookmarkGalleryContent` wraps its column in `windowInsetsPadding(WindowInsets.safeDrawing)`, so back, `All videos` / `Secure videos`, `Auto` and the search row clear the status bar in landscape |
| Docs + preview | `0b67687a` | `docs/PRODUCT.md`, `DECISIONS_AND_TRAPS.md`, `MUSIC_PLAYER_CUSTOMIZATION.md`, `v3_2_1_fixes_preview.html` | The backlog, two new traps, and the portrait/landscape mock |
| R-01 | `34ef23be`, `4a1d29ba` | `ui/browser/components/MiniPlayer.kt` | Dismissing the card sends `ACTION_NOTIFICATION_STOP` only when `AudioPreferences.audioBackgroundPlayback` is off. With it on, the card leaves, the session keeps playing in the notification, the toss no longer pauses, and the refused-stop restore is skipped |
| R-03 | `afbc30b0` | `ui/player/controls/components/sheets/ChaptersSheet.kt` | `PlaybackBookmarkEditor` holds `SCREEN_ORIENTATION_PORTRAIT` for as long as it is composed and restores the exact `requestedOrientation` it captured on close |
| Docs status | `8963bee5` | `docs/PRODUCT.md` | Records the above |
| R-04 (player half) | `9c3f01c4` | `ui/player/controls/PlayerControls.kt` | `showSystemStatusBar` now follows the real window: preference OR `WindowInsets.areStatusBarsVisible`, the same pattern the nav bar already used. The toast, unlock button and top-controls rows clear the status bar whenever it is actually visible, including transient immersive reveals |
| R-05 | `a86f06b3` | `domain/secure/SecureFolderSession.kt`, `ui/securefolder/SecureFolderGateScreen.kt`, `ui/securefolder/SecureUnlockActivity.kt`, `App.kt` | One session unlock token: any gate success (folder entry PIN/biometric/setup or playback credential) opens the session, both gates skip prompting while it is live, and `App.onActivityStopped` clears it when the app is backgrounded. Per-action prompts (destroy vault, PIN change) intentionally still ask |
| R-07 | `5ceb475a` | `ui/player/PlayerViewModel.kt`, `ui/player/controls/components/BookmarkListContent.kt` | Scope lives in `PlayerViewModel.toggleInPlayerBookmarkScope()` so it survives drawer close/reopen; the portrait drawer gained the missing scope chip; `currentMediaId` comes from `bookmarkMediaId` (covers bookmark-less videos); a foreign row with no replay ref shows a toast instead of seeking the current file to a foreign timestamp |
| R-02 | `b187233d` | `preferences/MusicActionRowButton.kt`, `preferences/AudioPreferences.kt`, `ui/player/controls/AudioPlayerControls.kt` | Per-orientation action-row visibility (`music_action_row_portrait` / `..._landscape`), a Layout checklist behind the music overflow, contiguously renumbered connected-bar slots, Queue and transport pinned |
| R-06 | `b187233d` | `ui/player/controls/AudioPlayerControls.kt`, `ui/player/controls/components/sheets/MoreSheet.kt`, `ui/player/controls/PlayerSheets.kt` | ⬇ in the action row (participates in R-02 visibility) plus a Download entry in the video overflow; engine missing → install prompt with progress; `ENABLE_YTDLP=false` builds show no download UI |
| R-09 | `b187233d` | `ui/player/controls/AudioPlayerControls.kt` | Landscape dual-pane: `DualPaneSidePanel` moved to the left column, artwork/title/seekbar/transport/action row stay right, each pane clearing the status bar on its own edge; portrait untouched |
| R-08 | `e2859d14` | `ui/player/controls/components/sheets/PlaylistSheet.kt` | Queue list capped at ≈6 rows and scrolls inside its budget; the landscape sheet no longer claims the screen; the header row stays fixed |

### Why R-01 was changed that way

The reported "swipe closes the music" came from one place: `MiniPlayer.dismissPlayer` sent
`MediaPlaybackService.ACTION_NOTIFICATION_STOP` for both the ✕ and the toss, and
`tossAway()` paused mpv before running it. The expanded sheet was never the culprit —
`InAppMusicSheetState.collapse()` only animates to the mini anchor, and `dismiss()` drops the
composed content without touching the session.

The card's `dismissedMediaId` + 3 s restore exists for "playback refused to stop". Hiding while
playing needs that restore suppressed, hence the separate `keepsPlayingAfterToss` flag.
`keepsPlayingAfterToss` must be read inside `MiniPlayerContent` (the toss lives there) **and** in
the outer `MiniPlayer` (where `dismissPlayer` is built) — injecting it in only one of the two was
the compile error CI caught.

## 3. Current state & follow-ups

**v3.3.0 is shipped and released** (tag `v3.3.0`, GitHub Release with `mpvRx-arm64-v8a-v3.3.0.apk`), including all security, concurrency, data durability, accessibility, Namida music UI, fullscreen lyrics tap-to-seek, `TextNormalizer`, and uncropped fast bookmark capture improvements. Subsequent commits on `master` update the in-app `AboutScreen` / `UpdateManager` URLs (`Mostakim2005/mpvRx`), `README.md`, and docs, queued for the next release (`v3.3.1` or later).

## 4. Traps that shaped this batch

- **TRAP 1 (paths):** never normalize with `java.net.URI`; `PlaybackIdentity.normalizeLocalPath`
  must stay pure-string. The v3.1.0 NPE (`a7069359`) was exactly this, via
  `SecureMediaRegistry.isProtectedReference`.
- **TRAP 2 (release):** pushing a tag must use `chore(release): vX.Y.Z`; `release.yml` checks
  `versionName` against `CHANGELOG.md`.
- **TRAP 3 (git):** always `GIT_TERMINAL_PROMPT=0`.
- **TRAP 4 (dual orientation):** portrait and landscape get separate mocks and separate layout
  budgets — never one drawing stretched.
- **TRAP 5 (wrong repo):** `gh` defaults to the `upstream` remote here (`Riteshp2001/mpvRx`), so
  `gh run list` and `gh release view` report that fork's history. Pass `--repo
  Mostakim2005/mpvRx` or a green/red result is meaningless.
- **TRAP 6 (native lock):** never block the UI thread on `nativeLock`; a Surface handoff holds it
  inside libmpv. Use the bounded read helper and let the observed flow correct the value.
- New: landscape status-bar insets must be a window-condition check, not a preference check;
  portrait-only editors must capture and restore the player's orientation.

## 5. Release path when cutting a new release

1. Confirm the final `build.yml` run for `master` is green.
2. Bump `releaseVersionCode` and `versionName` in `app/build.gradle.kts`, and add a matching `## X.Y.Z` section to `CHANGELOG.md`.
3. `git commit -m "chore(release): vX.Y.Z"`.
4. `git tag -a vX.Y.Z -m "Release vX.Y.Z"` then `git push origin master vX.Y.Z`
   (`GIT_TERMINAL_PROMPT=0`), which triggers `release.yml`.

Do not tag until every item above is either shipped and built green or explicitly deferred.
