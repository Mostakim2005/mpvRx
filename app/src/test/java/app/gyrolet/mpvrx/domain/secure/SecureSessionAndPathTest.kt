/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.secure

import app.gyrolet.mpvrx.domain.storage.DurablePreferencesSync
import app.gyrolet.mpvrx.domain.storage.DurableStorageManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureSessionAndPathTest {
  private var fakeNowMs: Long = 1_000_000L

  @BeforeTest
  fun setUp() {
    fakeNowMs = 1_000_000L
    SecureFolderSession.clockMs = { fakeNowMs }
    SecureFolderSession.lockNow()
  }

  @AfterTest
  fun tearDown() {
    SecureFolderSession.lockNow()
    SecureFolderSession.clockMs = System::currentTimeMillis
  }

  @Test
  fun sessionStaysUnlockedDuringBriefBackgroundAndLocksAfterGracePeriod() {
    var lockCallbacks = 0
    val listener: () -> Unit = { lockCallbacks++ }
    SecureFolderSession.registerOnLockListener(listener)

    assertFalse(SecureFolderSession.isUnlocked)
    SecureFolderSession.markUnlocked()
    assertTrue(SecureFolderSession.isUnlocked)
    assertTrue(SecureFolderSession.unlockedState.value)

    // Brief background transition (< 30s) must NOT lock or re-prompt when returning to foreground
    SecureFolderSession.onAppBackgrounded(graceMs = 30_000L)
    fakeNowMs += 5_000L
    SecureFolderSession.onAppForegrounded(graceMs = 30_000L)
    assertTrue(SecureFolderSession.isUnlocked)

    // Background transition exceeding 30s grace period locks immediately on foreground check
    val countBefore = lockCallbacks
    SecureFolderSession.onAppBackgrounded(graceMs = 30_000L)
    fakeNowMs += 31_000L
    SecureFolderSession.onAppForegrounded(graceMs = 30_000L)
    assertFalse(SecureFolderSession.isUnlocked)
    assertFalse(SecureFolderSession.unlockedState.value)
    assertTrue(lockCallbacks > countBefore)
  }

  @Test
  fun explicitLockNowImmediatelyRevokesSession() {
    SecureFolderSession.markUnlocked()
    assertTrue(SecureFolderSession.isUnlocked)

    SecureFolderSession.lockNow()
    assertFalse(SecureFolderSession.isUnlocked)
    assertFalse(SecureFolderSession.unlockedState.value)
  }

  @Test
  fun durableAndVaultPathDetectionMatchesAllSupportedLayoutsAndExcludesNormalVideos() {
    val vaultPaths = listOf(
      "/storage/emulated/0/Documents/mpvRx/.vault/media/8c9d2e.dat",
      "file:///storage/emulated/0/Documents/mpvRx/.vault/thumbs/bm_1.dat",
      "/storage/emulated/0/Documents/mpvRx/vault/legacy_video.mp4",
      "/data/user/0/app.gyrolet.mpvrx/files/secure_media/old_secret.mkv",
      "/data/user/0/app.gyrolet.mpvrx/files/.vault/media/fallback.dat",
    )
    vaultPaths.forEach { path ->
      assertTrue(DurableStorageManager.isVaultPath(path), "Expected vault path: $path")
      assertTrue(DurableStorageManager.isAppDurablePath(path), "Expected app durable path: $path")
      assertTrue(SecureMediaRegistry.isProtectedReference(path), "Expected protected reference: $path")
    }

    val publicStorePaths = listOf(
      "/storage/emulated/0/Documents/mpvRx/.store/library.dat",
      "/storage/emulated/0/Documents/mpvRx/.store/thumbs/bm_public.dat",
      "/storage/emulated/0/Documents/mpvRx/thumbnails/bm_old.jpg",
    )
    publicStorePaths.forEach { path ->
      assertFalse(DurableStorageManager.isVaultPath(path), "Public store path must not be flagged as vault: $path")
      assertTrue(DurableStorageManager.isAppDurablePath(path), "Expected app durable path: $path")
    }

    val normalUserVideos = listOf(
      "/storage/emulated/0/Movies/Sample.mkv",
      "/storage/emulated/0/Documents/MyLecture.mp4",
      "https://example.com/stream.m3u8",
    )
    normalUserVideos.forEach { path ->
      assertFalse(DurableStorageManager.isVaultPath(path), "Normal video must not match vault: $path")
      assertFalse(DurableStorageManager.isAppDurablePath(path), "Normal video must not match durable dir: $path")
    }
  }

  @Test
  fun preferencesSyncPartitionsVaultAndSharedKeysCleanly() {
    assertTrue(DurablePreferencesSync.isVaultKey("secure_folder_pin_hash"))
    assertTrue(DurablePreferencesSync.isVaultKey("secure_folder_security_answer"))
    assertFalse(DurablePreferencesSync.shouldMirrorPublicKey("secure_folder_pin_hash"))

    // Ephemeral / sensitive non-vault keys must be excluded from public settings mirror
    assertFalse(DurablePreferencesSync.shouldMirrorPublicKey("onboarding_completed"))
    assertFalse(DurablePreferencesSync.shouldMirrorPublicKey("network_saved_credentials"))
    assertFalse(DurablePreferencesSync.shouldMirrorPublicKey("last_played_uri"))

    // Non-personal UI and player layout preferences are mirrored to .store/settings.mvx
    assertTrue(DurablePreferencesSync.shouldMirrorPublicKey("dark_mode"))
    assertTrue(DurablePreferencesSync.shouldMirrorPublicKey("video_grid_columns_portrait"))
    assertTrue(DurablePreferencesSync.shouldMirrorPublicKey("default_playback_speed"))
    assertEquals(30_000L, SecureFolderSession.BACKGROUND_LOCK_GRACE_MS)
  }
}
