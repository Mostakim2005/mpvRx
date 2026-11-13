/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import dev.vivvvek.seeker.Segment
import kotlin.test.Test
import kotlin.test.assertEquals

class SeekbarBookmarkSnapTest {
  private val bookmarks =
    listOf(
      Segment("Opening", 20f),
      Segment("Chorus A", 50f),
      Segment("Chorus B", 51.2f),
    )

  @Test
  fun tapWithinToleranceSnapsToNearestBookmark() {
    // 8px out of 1000px on 100s = 0.8s tolerance.
    val snapped =
      snapPositionToNearbyBookmark(
        positionSeconds = 20.5f,
        durationSeconds = 100f,
        bookmarks = bookmarks,
        trackWidthPx = 1000f,
        tolerancePx = 8f,
        wasDragged = false,
      )
    assertEquals(20f, snapped)

    // When two bookmarks are both within tolerance, pick the closest one.
    val nearestOfTwo =
      snapPositionToNearbyBookmark(
        positionSeconds = 50.9f,
        durationSeconds = 100f,
        bookmarks = bookmarks,
        trackWidthPx = 1000f,
        tolerancePx = 15f,
        wasDragged = false,
      )
    assertEquals(51.2f, nearestOfTwo)
  }

  @Test
  fun tapOutsideTolerancePreservesTappedPosition() {
    val unsnapped =
      snapPositionToNearbyBookmark(
        positionSeconds = 22.0f,
        durationSeconds = 100f,
        bookmarks = bookmarks,
        trackWidthPx = 1000f,
        tolerancePx = 8f,
        wasDragged = false,
      )
    assertEquals(22.0f, unsnapped)
  }

  @Test
  fun dragNeverSnapsEvenDirectlyOverBookmark() {
    val dragged =
      snapPositionToNearbyBookmark(
        positionSeconds = 20.1f,
        durationSeconds = 100f,
        bookmarks = bookmarks,
        trackWidthPx = 1000f,
        tolerancePx = 8f,
        wasDragged = true,
      )
    assertEquals(20.1f, dragged)
  }

  @Test
  fun handlesEmptyBookmarksAndInvalidGeometrySafely() {
    assertEquals(
      33f,
      snapPositionToNearbyBookmark(33f, 100f, emptyList(), 1000f, 8f, wasDragged = false),
    )
    assertEquals(
      10f,
      snapPositionToNearbyBookmark(10f, 0f, bookmarks, 1000f, 8f, wasDragged = false),
    )
    assertEquals(
      20.2f,
      snapPositionToNearbyBookmark(20.2f, 100f, bookmarks, 0f, 8f, wasDragged = false),
    )
  }
}
