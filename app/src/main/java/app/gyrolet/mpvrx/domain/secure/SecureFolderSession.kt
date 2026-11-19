/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.secure

import app.gyrolet.mpvrx.domain.bookmark.BookmarkThumbnailStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Shared Secure Folder session lock state across all surfaces (folder UI, Secure Gallery, player,
 * and loopback proxy).
 *
 * One unlock covers folder browsing, gallery access, and playback without prompting twice; leaving
 * the app locks cleanly after [BACKGROUND_LOCK_GRACE_MS] or immediately when triggered explicitly,
 * revoking active [VaultStreamingProxy] tokens and evicting in-memory decrypted caches.
 */
object SecureFolderSession {
  const val BACKGROUND_LOCK_GRACE_MS = 30_000L

  private val _unlockedState = MutableStateFlow(false)
  val unlockedState: StateFlow<Boolean> = _unlockedState.asStateFlow()

  val isUnlocked: Boolean
    get() = _unlockedState.value

  internal var clockMs: () -> Long = System::currentTimeMillis

  private val lock = Any()
  private val listeners = CopyOnWriteArrayList<() -> Unit>()
  private val timerExecutor =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "SecureSessionLockTimer").apply { isDaemon = true }
    }

  private var pendingTimer: ScheduledFuture<*>? = null
  private var backgroundedAtMs: Long = 0L

  fun registerOnLockListener(listener: () -> Unit) {
    listeners.addIfAbsent(listener)
  }

  fun markUnlocked() {
    synchronized(lock) {
      pendingTimer?.cancel(false)
      pendingTimer = null
      backgroundedAtMs = 0L
      _unlockedState.value = true
    }
  }

  fun onAppBackgrounded(graceMs: Long = BACKGROUND_LOCK_GRACE_MS) {
    synchronized(lock) {
      if (!_unlockedState.value) return
      pendingTimer?.cancel(false)
      pendingTimer = null
      if (graceMs <= 0L) {
        lockNow()
        return
      }
      val now = clockMs()
      backgroundedAtMs = now
      pendingTimer =
        timerExecutor.schedule(
          {
            val shouldLock =
              synchronized(lock) {
                val bgAt = backgroundedAtMs
                bgAt > 0L && (clockMs() - bgAt) >= graceMs
              }
            if (shouldLock) {
              lockNow()
            }
          },
          graceMs,
          TimeUnit.MILLISECONDS,
        )
    }
  }

  fun onAppForegrounded(graceMs: Long = BACKGROUND_LOCK_GRACE_MS) {
    val shouldLock =
      synchronized(lock) {
        pendingTimer?.cancel(false)
        pendingTimer = null
        val bgAt = backgroundedAtMs
        backgroundedAtMs = 0L
        _unlockedState.value && bgAt > 0L && (clockMs() - bgAt) >= graceMs
      }
    if (shouldLock) {
      lockNow()
    }
  }

  fun lockNow() {
    synchronized(lock) {
      pendingTimer?.cancel(false)
      pendingTimer = null
      backgroundedAtMs = 0L
      _unlockedState.value = false
    }
    runCatching { VaultStreamingProxy.revokeAllActiveStreams() }
    runCatching { BookmarkThumbnailStore.evictSecureMemoryCache() }
    listeners.forEach { listener ->
      runCatching { listener() }
    }
  }

  fun clear() {
    lockNow()
  }
}
