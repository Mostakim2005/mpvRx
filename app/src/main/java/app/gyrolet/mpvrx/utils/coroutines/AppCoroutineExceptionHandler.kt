/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.coroutines

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Handler for app-owned background scopes.
 *
 * A `.launch {}` inside `CoroutineScope(SupervisorJob() + Dispatchers.Default/IO)` with no handler
 * reports every failure to the thread's uncaught-exception handler, which is what turns one bad
 * background update into a full app crash. Long-lived housekeeping loops (playback property events,
 * the secure-media index, startup work) should log and keep the rest of the app alive instead.
 *
 * Deliberately scoped to background scopes only: failures on a screen's or a ViewModel's scope still
 * go through the normal crash report, because those usually indicate a real, user-visible bug.
 */
val LoggingCoroutineExceptionHandler: CoroutineExceptionHandler =
  CoroutineExceptionHandler { context, error ->
    if (error is CancellationException) return@CoroutineExceptionHandler
    Log.e("BackgroundWork", "Uncaught coroutine failure in $context", error)
  }
