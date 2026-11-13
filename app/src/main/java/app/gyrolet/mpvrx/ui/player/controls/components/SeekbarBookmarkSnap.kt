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
import kotlin.math.abs

internal fun snapPositionToNearbyBookmark(
  positionSeconds: Float,
  durationSeconds: Float,
  bookmarks: List<Segment>,
  trackWidthPx: Float,
  tolerancePx: Float,
  wasDragged: Boolean,
): Float {
  if (wasDragged) return positionSeconds
  if (!positionSeconds.isFinite() || !durationSeconds.isFinite() || durationSeconds <= 0f) return positionSeconds
  if (!trackWidthPx.isFinite() || trackWidthPx <= 0f || !tolerancePx.isFinite() || tolerancePx <= 0f) return positionSeconds
  if (bookmarks.isEmpty()) return positionSeconds

  val toleranceSeconds = (tolerancePx / trackWidthPx) * durationSeconds
  val clampedTarget = positionSeconds.coerceIn(0f, durationSeconds)
  var nearestBookmark: Float? = null
  var nearestDistance = Float.MAX_VALUE

  for (bookmark in bookmarks) {
    val start = bookmark.start
    if (!start.isFinite() || start < 0f) continue
    val bounded = start.coerceIn(0f, durationSeconds)
    val distance = abs(bounded - clampedTarget)
    if (distance <= toleranceSeconds && distance < nearestDistance) {
      nearestDistance = distance
      nearestBookmark = bounded
    }
  }
  return nearestBookmark ?: clampedTarget
}
