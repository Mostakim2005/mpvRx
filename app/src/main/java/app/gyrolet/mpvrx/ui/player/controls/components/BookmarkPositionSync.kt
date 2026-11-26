/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity

internal data class BookmarkSyncTarget(
  val activeBookmarkId: Long?,
  val scrollIndex: Int,
  val isBeforeFirstBookmark: Boolean,
)

/**
 * Finds the YouTube-chapter-style bookmark matching [currentPositionMs] for the playing video:
 * selects the latest bookmark at or before the current playback time, or points [scrollIndex] to the
 * earliest bookmark when playback is still before the first bookmark.
 */
internal fun resolveBookmarkSyncTarget(
  bookmarks: List<PlaybackBookmarkEntity>,
  positionMsOf: (PlaybackBookmarkEntity) -> Long,
  currentPositionMs: Long,
  currentMediaId: String?,
  isGlobalScope: Boolean,
): BookmarkSyncTarget {
  if (bookmarks.isEmpty()) {
    return BookmarkSyncTarget(activeBookmarkId = null, scrollIndex = -1, isBeforeFirstBookmark = false)
  }

  val safePositionMs = currentPositionMs.coerceAtLeast(0L)
  var latestIndex = -1
  var latestPosMs = Long.MIN_VALUE
  var earliestIndex = -1
  var earliestPosMs = Long.MAX_VALUE

  for (idx in bookmarks.indices) {
    val bookmark = bookmarks[idx]
    if (isGlobalScope && (currentMediaId == null || bookmark.mediaId != currentMediaId)) {
      continue
    }
    val posMs = positionMsOf(bookmark).coerceAtLeast(0L)
    if (posMs < earliestPosMs) {
      earliestPosMs = posMs
      earliestIndex = idx
    }
    if (posMs <= safePositionMs && posMs >= latestPosMs) {
      latestPosMs = posMs
      latestIndex = idx
    }
  }

  return when {
    latestIndex >= 0 ->
      BookmarkSyncTarget(
        activeBookmarkId = bookmarks[latestIndex].id,
        scrollIndex = latestIndex,
        isBeforeFirstBookmark = false,
      )
    earliestIndex >= 0 ->
      BookmarkSyncTarget(
        activeBookmarkId = null,
        scrollIndex = earliestIndex,
        isBeforeFirstBookmark = true,
      )
    else ->
      BookmarkSyncTarget(
        activeBookmarkId = null,
        scrollIndex = -1,
        isBeforeFirstBookmark = false,
      )
  }
}

/**
 * Resolves the LazyList / LazyGrid item index to scroll to when syncing to playback position:
 * prefers the resolved bookmark target and falls back to the active chapter item index.
 */
internal fun resolveBookmarkOrChapterScrollIndex(
  syncTarget: BookmarkSyncTarget,
  bookmarkCount: Int,
  activeChapterIndex: Int,
  chapterCount: Int,
  hasChaptersHeader: Boolean = bookmarkCount > 0,
): Int {
  if (syncTarget.scrollIndex >= 0) return syncTarget.scrollIndex
  if (activeChapterIndex in 0 until chapterCount) {
    val headerOffset = if (hasChaptersHeader) 1 else 0
    return bookmarkCount + headerOffset + activeChapterIndex
  }
  return -1
}
