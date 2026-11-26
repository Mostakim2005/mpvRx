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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookmarkPositionSyncTest {
  private fun bookmark(
    id: Long,
    positionMs: Long,
    mediaId: String = "video-10",
  ) = PlaybackBookmarkEntity(
    id = id,
    mediaId = mediaId,
    positionMs = positionMs,
    title = "BM $id",
    createdAt = id * 1000L,
  )

  @Test
  fun selectsLatestBookmarkAtOrBeforeCurrentPosition() {
    val bookmarks =
      listOf(
        bookmark(id = 1L, positionMs = 10_000L),
        bookmark(id = 2L, positionMs = 30_000L),
        bookmark(id = 3L, positionMs = 60_000L),
      )

    val between =
      resolveBookmarkSyncTarget(
        bookmarks = bookmarks,
        positionMsOf = { it.positionMs },
        currentPositionMs = 45_000L,
        currentMediaId = "video-10",
        isGlobalScope = false,
      )
    assertEquals(2L, between.activeBookmarkId)
    assertEquals(1, between.scrollIndex)
    assertFalse(between.isBeforeFirstBookmark)

    val exact =
      resolveBookmarkSyncTarget(
        bookmarks = bookmarks,
        positionMsOf = { it.positionMs },
        currentPositionMs = 60_000L,
        currentMediaId = "video-10",
        isGlobalScope = false,
      )
    assertEquals(3L, exact.activeBookmarkId)
    assertEquals(2, exact.scrollIndex)
  }

  @Test
  fun beforeFirstBookmarkLeavesActiveNullButTargetsEarliestForScroll() {
    val bookmarks =
      listOf(
        bookmark(id = 11L, positionMs = 25_000L),
        bookmark(id = 12L, positionMs = 90_000L),
      )

    val target =
      resolveBookmarkSyncTarget(
        bookmarks = bookmarks,
        positionMsOf = { it.positionMs },
        currentPositionMs = 4_000L,
        currentMediaId = "video-10",
        isGlobalScope = false,
      )
    assertNull(target.activeBookmarkId)
    assertEquals(0, target.scrollIndex)
    assertTrue(target.isBeforeFirstBookmark)
  }

  @Test
  fun handlesUnsortedAndGlobalScopeFilteringByCurrentMediaId() {
    val bookmarks =
      listOf(
        bookmark(id = 101L, positionMs = 40_000L, mediaId = "video-99"),
        bookmark(id = 102L, positionMs = 55_000L, mediaId = "video-10"),
        bookmark(id = 103L, positionMs = 20_000L, mediaId = "video-10"),
        bookmark(id = 104L, positionMs = 44_000L, mediaId = "video-99"),
      )

    val target =
      resolveBookmarkSyncTarget(
        bookmarks = bookmarks,
        positionMsOf = { it.positionMs },
        currentPositionMs = 45_000L,
        currentMediaId = "video-10",
        isGlobalScope = true,
      )
    assertEquals(103L, target.activeBookmarkId)
    assertEquals(2, target.scrollIndex)
    assertFalse(target.isBeforeFirstBookmark)
  }

  @Test
  fun fallsBackToActiveChapterWhenCurrentMediaHasNoMatchingBookmarks() {
    val globalOtherMedia =
      listOf(
        bookmark(id = 201L, positionMs = 15_000L, mediaId = "video-99"),
        bookmark(id = 202L, positionMs = 30_000L, mediaId = "video-99"),
      )
    val target =
      resolveBookmarkSyncTarget(
        bookmarks = globalOtherMedia,
        positionMsOf = { it.positionMs },
        currentPositionMs = 20_000L,
        currentMediaId = "video-10",
        isGlobalScope = true,
      )
    assertNull(target.activeBookmarkId)
    assertEquals(-1, target.scrollIndex)

    val splitScrollIndex =
      resolveBookmarkOrChapterScrollIndex(
        syncTarget = target,
        bookmarkCount = globalOtherMedia.size,
        activeChapterIndex = 1,
        chapterCount = 3,
        hasChaptersHeader = true,
      )
    assertEquals(4, splitScrollIndex)

    val emptyListScrollIndex =
      resolveBookmarkOrChapterScrollIndex(
        syncTarget = BookmarkSyncTarget(activeBookmarkId = null, scrollIndex = -1, isBeforeFirstBookmark = false),
        bookmarkCount = 0,
        activeChapterIndex = 1,
        chapterCount = 3,
        hasChaptersHeader = false,
      )
    assertEquals(1, emptyListScrollIndex)
  }
}
