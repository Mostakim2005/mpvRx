/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.storage

import android.os.Environment
import java.io.File

/**
 * Zero-Bloat Single Root Folder Storage Manager.
 * Ensures the live Room SQLite database, Secure Vault, thumbnails, configuration,
 * music, companion videos, and lyrics are hosted directly in a single user-selected
 * directory without duplicate backup bloat. Survives app deletion, updates, and wipes.
 */
object DurableStorageManager {

  private const val DEFAULT_ROOT_NAME = "mpvRx"

  /**
   * Resolves the primary root directory (defaults to /sdcard/Documents/mpvRx/).
   */
  fun getRootDirectory(customPath: String? = null): File {
    val root = if (!customPath.isNullOrBlank()) {
      File(customPath)
    } else {
      val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
      File(documents, DEFAULT_ROOT_NAME)
    }

    if (!root.exists()) {
      root.mkdirs()
    }

    // Ensure .nomedia exists to protect private vault media and thumbnails
    val nomedia = File(root, ".nomedia")
    if (!nomedia.exists()) {
      runCatching { nomedia.createNewFile() }
    }

    return root
  }

  fun getDatabaseDirectory(root: File = getRootDirectory()): File {
    val dir = File(root, "database")
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  fun getLiveDatabaseFile(root: File = getRootDirectory()): File {
    return File(getDatabaseDirectory(root), "mpvrx.db")
  }

  fun getVaultDirectory(root: File = getRootDirectory()): File {
    val dir = File(root, "vault")
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  fun getThumbnailsDirectory(root: File = getRootDirectory()): File {
    val dir = File(root, "thumbnails")
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  fun getConfigDirectory(root: File = getRootDirectory()): File {
    val dir = File(root, "config")
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  fun getMusicDirectory(root: File = getRootDirectory()): File {
    val dir = File(root, "music")
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  fun getCompanionVideosDirectory(root: File = getRootDirectory()): File {
    val dir = File(root, "companion_videos")
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  fun getLyricsDirectory(root: File = getRootDirectory()): File {
    val dir = File(root, "lyrics")
    if (!dir.exists()) dir.mkdirs()
    return dir
  }
}
