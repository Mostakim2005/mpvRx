package app.gyrolet.mpvrx.domain.secure

import app.gyrolet.mpvrx.domain.storage.DurablePreferencesSync
import app.gyrolet.mpvrx.domain.storage.DurableStorageManager
import app.gyrolet.mpvrx.ui.player.controls.components.QUICK_SEEK_STEPS
import app.gyrolet.mpvrx.ui.player.controls.components.computePortraitSplitVideoHeightPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultIsolationAndControlsUnitTest {
  @Test
  fun secureSessionTracksLockedPrimaryAndDecoyModesIndependently() {
    SecureFolderSession.lockNow()
    assertEquals(SecureFolderSession.VaultMode.LOCKED, SecureFolderSession.modeState.value)
    assertFalse(SecureFolderSession.isUnlocked)
    assertFalse(SecureFolderSession.isPrimaryUnlocked)
    assertFalse(SecureFolderSession.isDecoyUnlocked)

    SecureFolderSession.markDecoyUnlocked()
    assertEquals(SecureFolderSession.VaultMode.DECOY, SecureFolderSession.modeState.value)
    assertTrue(SecureFolderSession.isUnlocked)
    assertFalse(SecureFolderSession.isPrimaryUnlocked)
    assertTrue(SecureFolderSession.isDecoyUnlocked)

    SecureFolderSession.markUnlocked()
    assertEquals(SecureFolderSession.VaultMode.PRIMARY, SecureFolderSession.modeState.value)
    assertTrue(SecureFolderSession.isUnlocked)
    assertTrue(SecureFolderSession.isPrimaryUnlocked)
    assertFalse(SecureFolderSession.isDecoyUnlocked)

    SecureFolderSession.lockNow()
    assertEquals(SecureFolderSession.VaultMode.LOCKED, SecureFolderSession.modeState.value)
  }

  @Test
  fun durableStorageSeparatesDecoyPrimaryAndPublicPaths() {
    val primaryMedia = "/storage/emulated/0/Documents/mpvRx/.vault/media/abc.enc"
    val decoyMedia = "/storage/emulated/0/Documents/mpvRx/.vault_d/media/def.enc"
    val publicMedia = "/storage/emulated/0/Movies/sample.mp4"

    assertTrue(DurableStorageManager.isPrimaryVaultPath(primaryMedia))
    assertFalse(DurableStorageManager.isDecoyVaultPath(primaryMedia))
    assertTrue(DurableStorageManager.isVaultPath(primaryMedia))

    assertTrue(DurableStorageManager.isDecoyVaultPath(decoyMedia))
    assertFalse(DurableStorageManager.isPrimaryVaultPath(decoyMedia))
    assertTrue(DurableStorageManager.isVaultPath(decoyMedia))

    assertFalse(DurableStorageManager.isVaultPath(publicMedia))
    assertFalse(DurableStorageManager.isAppDurablePath(publicMedia))
  }

  @Test
  fun avSyncAndSecurePreferenceKeysStayExcludedFromPublicBackup() {
    assertTrue(DurablePreferencesSync.isVaultKey("bookmark_sync_hub_url"))
    assertTrue(DurablePreferencesSync.isVaultKey("bookmark_sync_token"))
    assertTrue(DurablePreferencesSync.isVaultKey("secure_folder_decoy_hash"))
    assertFalse(DurablePreferencesSync.shouldMirrorPublicKey("bookmark_sync_hub_url"))
    assertFalse(DurablePreferencesSync.shouldMirrorPublicKey("secure_folder_pin_hash"))
    assertTrue(DurablePreferencesSync.shouldMirrorPublicKey("player_default_speed"))
  }

  @Test
  fun portraitSplitVideoHeightHugsWideAspectAndCapsTallAspect() {
    val w = 1080
    val h = 2400

    // 16:9 landscape video in portrait uses exact undistorted ~608px (25.3% height), not bloated 80%.
    val landscapeHeight = computePortraitSplitVideoHeightPx(w, h, 16.0 / 9.0)
    assertEquals(608, landscapeHeight)

    // Tall 9:16 portrait video caps cleanly at 36% (864px) so control strip + bookmark grid fit below.
    val tallHeight = computePortraitSplitVideoHeightPx(w, h, 9.0 / 16.0)
    assertEquals(864, tallHeight)

    // Ultra-wide 21:9 video respects minimum 22% floor (528px) so top overlay controls remain usable.
    val ultrawideHeight = computePortraitSplitVideoHeightPx(w, h, 3.0)
    assertEquals(528, ultrawideHeight)
  }

  @Test
  fun quickSeekStepsContainRequestedIntervalsInOrder() {
    assertEquals(
      listOf(
        -300 to "-5m",
        -60 to "-1m",
        -10 to "-10s",
        10 to "+10s",
        60 to "+1m",
        300 to "+5m",
      ),
      QUICK_SEEK_STEPS,
    )
  }
}
