# Handover — mpvRx v3.2.2 bookmark, secure-folder and storage batch

Last updated: 2026-11-18 (v3.2.2: bookmark scope, secure segregation, PIN gate, durable storage) · Branch `master` · Tag `v3.2.2`

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

**Current runs**

Query the workflow after every push and record the run IDs here. A cancelled run proves nothing:
`build.yml` cancels the in-progress run for the same ref, so wait for each run to finish before
pushing again.

Nothing in this batch has been exercised on a device. "Shipped" below means *committed and
pushed*; the only automated check is the GitHub Actions unit-test + APK job. Device verification
is still outstanding.

## 2. Shipped in this batch (v3.2.2)

| Item | Surface | Change | Behaviour |
| --- | --- | --- | --- |
| R-10 | `PlayerViewModel.kt`, `BookmarkListContent.kt`, `PlayerSheets.kt`, `ChaptersSheet.kt`, `BookmarkGalleryScreen.kt` | Scope is shared by the drawer, Chapters sheet and full gallery; `openBookmark()` launches foreign media; global cards keep tag chips | `Video ⇄ Global` works in every surface, and a global card opens its own video at the saved timestamp |
| R-11 | `BookmarkGalleryRepository.kt`, `PlaybackBookmarkDao.kt` | Secure membership follows `bookmark_media_links` group keys, not only the bookmark's stored `mediaId` | A file moved into the vault leaves the public/global list and appears in the secure list; restore reverses it |
| R-12 | `SecureUnlockActivity.kt`, `SecureFolderGateScreen.kt` | Playback gate asks for the app PIN; biometric entry is strong-biometric only | No phone screen-lock prompt on the way from the vault to the player |
| R-13 | `DurableStorageManager.kt`, `App.kt`, `DatabaseModule.kt`, `SecureFolderRepository.kt`, `BookmarkThumbnailStore.kt`, backup XML | Live DB, vault and thumbnails under `Documents/mpvRx/`; one-time copy + Room path rebase; Auto Backup fallback | Uninstall no longer deletes the library, vault or thumbnails |
| ANR | `PlaybackSession.kt` | UI-thread property reads use a 150 ms bounded lock attempt | Composition no longer ANRs behind `mpv_get_property` during a Surface handoff |

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

## 3. Remaining work

**None — the repair backlog (R-01 … R-09) is complete.** Statuses mirror `PRODUCT.md` § 4; the
per-item fixes are in § 2 and `DECISIONS_AND_TRAPS.md`. One deliberate scope boundary: R-06
shipped the download *affordances* only (its item spec said "no downloader logic changes"); the
companion-video downloader sheet, `MusicCompanionDao` attach-binding and audio-replacement prompt
from `PRODUCT.md`'s music-side spec remain their own feature
([FEATURE: Granular-Quality-Downloader-And-Configurable-Presets]).

## 4. Traps that shaped this batch

- **TRAP 1 (paths):** never normalize with `java.net.URI`; `PlaybackIdentity.normalizeLocalPath`
  must stay pure-string. The v3.1.0 NPE (`a7069359`) was exactly this, via
  `SecureMediaRegistry.isProtectedReference`.
- **TRAP 2 (release):** pushing a tag must use `chore(release): vX.Y.Z`; `release.yml` checks
  `versionName` against `CHANGELOG.md`.
- **TRAP 3 (git):** always `GIT_TERMINAL_PROMPT=0`.
- **TRAP 4 (dual orientation):** portrait and landscape get separate mocks and separate layout
  budgets — never one drawing stretched.
- New: landscape status-bar insets must be a window-condition check, not a preference check;
  portrait-only editors must capture and restore the player's orientation.

## 5. Release path when the batch is done

1. Confirm the final `build.yml` run for `master` is green.
2. Bump `versionName`/`versionCode` and `CHANGELOG.md` (v3.2.2).
3. `git commit -m "chore(release): v3.2.2"`.
4. `git tag -a v3.2.2 -m "Release v3.2.2"` then `git push origin master --tags`
   (`GIT_TERMINAL_PROMPT=0`), which triggers `release.yml`.

Do not tag until every item above is either shipped and built green or explicitly deferred.
