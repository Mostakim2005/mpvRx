/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.securefolder

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Default number of taps the stealth entry points require. */
const val SECRET_TAP_COUNT = 5

/** Default sliding window in which [SECRET_TAP_COUNT] taps must land. */
const val SECRET_TAP_WINDOW_MS = 3_000L

/**
 * Universal stealth gesture: [required] taps inside a [windowMs] sliding window run [onTriggered].
 *
 * This is the single activation gesture for every hidden entry point in the app. It replaces the
 * legacy double-tap / long-press shortcuts so there is one shared, harder-to-hit-by-accident
 * protocol instead of several different ones.
 *
 * Taps that fall outside the window are discarded, so a user who taps once every few seconds never
 * accumulates a stray unlock.
 */
fun Modifier.secretTaps(
  required: Int = SECRET_TAP_COUNT,
  windowMs: Long = SECRET_TAP_WINDOW_MS,
  enabled: Boolean = true,
  onTriggered: () -> Unit,
): Modifier =
  if (!enabled) this
  else
    this.pointerInput(required, windowMs) {
      val taps = ArrayDeque<Long>()
      detectTapGestures(
        onTap = {
          val now = System.currentTimeMillis()
          while (taps.isNotEmpty() && now - taps.first() > windowMs) taps.removeFirst()
          taps.addLast(now)
          if (taps.size >= required) {
            taps.clear()
            onTriggered()
          }
        },
      )
    }
