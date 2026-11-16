# Handover — mpvRx v3.2.1 repair batch

Last updated: 2026-11-16 · Branch `master` · Base `2244fbf1`

## 1. How this batch is verified

There is **no usable local Android toolchain on this machine**: `JAVA_HOME` points at a 32-bit
JRE (`C:\Program Files (x86)\Eclipse Adoptium\jre-19.0.2.7-hotspot`) with no `javac`, and no
Android SDK is installed (`ANDROID_HOME` unset, no `local.properties`). Gradle dies with
`Could not reserve enough space for 2097152KB object heap` followed by
`GeneratedClassCompilationException: No Java compiler found`.

Verification therefore runs on GitHub:

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

| Run | Commit | Result |
| --- | --- | --- |
| `43804923099` | `afbc30b0` | **failure** — my `Unresolved reference 'audioPreferences'` in `MiniPlayer.kt:455`, fixed in `4a1d29ba` |
| `43805359408` | `4a1d29ba` | **cancelled** by the docs push that followed |
| `43806394165` | `8963bee5` | first complete check of the shipped code |

Nothing in this batch has been exercised on a device. "Shipped" below means *committed and
pushed*, not *seen working*.

## 2. Shipped in this batch

| Item | Commit | Change | Behaviour |
| --- | --- | --- | --- |
| R-04 (gallery half) | `e106d62b` | `ui/bookmarks/BookmarkGalleryScreen.kt` | `BookmarkGalleryContent` wraps its column in `windowInsetsPadding(WindowInsets.safeDrawing)`, so back, `All videos` / `Secure videos`, `Auto` and the search row clear the status bar in landscape |
| Docs + preview | `0b67687a` | `docs/PRODUCT.md`, `DECISIONS_AND_TRAPS.md`, `MUSIC_PLAYER_CUSTOMIZATION.md`, `v3_2_1_fixes_preview.html` | The backlog, two new traps, and the portrait/landscape mock |
| R-01 | `34ef23be`, `4a1d29ba` | `ui/browser/components/MiniPlayer.kt` | Dismissing the card sends `ACTION_NOTIFICATION_STOP` only when `AudioPreferences.audioBackgroundPlayback` is off. With it on, the card leaves, the session keeps playing in the notification, the toss no longer pauses, and the refused-stop restore is skipped |
| R-03 | `afbc30b0` | `ui/player/controls/components/sheets/ChaptersSheet.kt` | `PlaybackBookmarkEditor` holds `SCREEN_ORIENTATION_PORTRAIT` for as long as it is composed and restores the exact `requestedOrientation` it captured on close |
| Docs status | `8963bee5` | `docs/PRODUCT.md` | Records the above |

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

Statuses mirror `PRODUCT.md` § 4.

### R-04 (player half) — status-bar inset on the player's top rows · P1

`ui/player/controls/PlayerControls.kt` already insets three blocks with
`windowInsetsPadding(WindowInsets.statusBars)` guarded by `showSystemStatusBar`
(the player-update toast ≈1539, the unlock button ≈1848, the top-controls box ≈2324).
The remaining top rows use the same guard, so the fix is a **condition** fix, not new padding:
find where `showSystemStatusBar` is derived and make it true whenever the bars are actually
visible (non-immersive, controls shown, not locked), instead of deriving it from a preference
that can disagree with the window. Do not add a second inset pass on top of an active one.

### R-02 — music action-row visibility menu · P2

Targets the eight real buttons in `ui/player/controls/AudioPlayerControls.kt` (≈2560–2760):
Equalizer, Lyrics, Shuffle, Repeat, artwork/visualizer toggle, Edit tags, Background playback,
Queue. Pattern to mirror: `ui/preferences/ControlLayoutEditorScreen.kt` (`ControlRegion` model).

1. Add `AudioPreferences.musicActionVisibility` as a string set per orientation
   (`music_action_row_portrait` / `..._landscape`), default = all visible.
2. Render a "Layout" entry in the music overflow that opens a checklist (not a reorder list —
   these buttons live in fixed slots).
3. Wrap each button in `if (region in visible)`; `Queue` stays pinned so the sheet is always
   reachable, and play/pause + seek are never hideable.
4. Portrait and landscape read their own set (`TRAP: Dual-Orientation-UI-Consistency-Rule`).

### R-06 — ⬇ download button in the music action row · P2

`AudioPlayerControls` action row (same block as R-02): add a download action that resolves
`currentItem` to a video source and opens the existing downloader sheet
(`docs/MUSIC_PLAYER_CUSTOMIZATION.md` § Integrated-Media-Downloader-And-Databasing). The button
participates in R-02 visibility. No downloader logic changes; this is discoverability.

### R-05 — Secure Folder prompts twice · P1

Reproduce first: the reported flow asks for the credential, shows the folder, then asks again on
entry. Suspects are a second `SecureFolderRepository.unlock(...)` call from a lifecycle/`LaunchedEffect`
re-entry, or `requireUnlock` being consulted per screen instead of per session. Fix by holding a
single session-scoped unlock token (cleared on `onStop` when the app is backgrounded, per the
existing security contract) and making every gate read it.

### R-07 — `Video` ⇄ `Global` scope · P1

Reviewed `ui/player/controls/components/BookmarkListContent.kt` (580–620) and
`domain/bookmark/BookmarkGalleryRepository.kt`:

- `baseBookmarks` is scope-derived and the drawer renders `displayedBookmarks` in **both** the
  grid and list branches, so the toggle is wired.
- `viewModel.bookmarkTags` is global (`observeAllPairs()`), so tag filtering is not limited to the
  current video — my earlier hypothesis was wrong.
- `currentMediaId` comes from `bookmarks.firstOrNull()?.mediaId`, so a video with **no** bookmarks
  of its own yields `null` and Global taps relaunch the file instead of seeking.

Remaining candidates, in order: (a) `BookmarkGalleryScope.AllVideos` drops every curated media
row (`bookmarkDao.observeGallery()` is joined against replay refs), so Global looks identical to
Video when nothing else was replayed; (b) `PlaybackIdentity.forLocalPath` mismatch when the secure
folder holds a renamed copy, leaking or hiding rows; (c) the panel state `isGlobalScope` being
reset by recomposition when the drawer is recreated in landscape. Reproduce with two videos'
bookmarks before changing anything.

### R-08 — Now Playing queue compactness · P3

`ui/player/controls/components/sheets/PlaylistSheet.kt`: the sheet sizes its height by content
rather than the queue, leaving dead space under the last row. Cap visible rows (≈6), make the list
scroll inside, and keep the header + close row fixed. No change to queue ordering or actions.

### R-09 — landscape music layout · P2

One side only: browse list left, current track + action row right. `AudioPlayerControls` has the
landscape branch (`isCompactLandscape`); move the browse/list panel into the left column and keep
artwork, title, action row and transport in the right. Portrait untouched. Reuse the existing
panels — the ask is placement, not new surfaces.

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
2. Bump `versionName`/`versionCode` and `CHANGELOG.md` (v3.2.1).
3. `git commit -m "chore(release): v3.2.1"`.
4. `git tag -a v3.2.1 -m "Release v3.2.1"` then `git push origin master --tags`
   (`GIT_TERMINAL_PROMPT=0`), which triggers `release.yml`.

Do not tag until every item above is either shipped and built green or explicitly deferred.
