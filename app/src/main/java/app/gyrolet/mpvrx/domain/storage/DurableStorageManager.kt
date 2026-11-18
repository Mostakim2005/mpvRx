/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.storage

import android.content.Context
import android.os.Environment
import app.gyrolet.mpvrx.BuildConfig
import java.io.File
import java.io.FileOutputStream

/**
 * Zero-Bloat Single Root Folder Storage Manager.
 *
 * The live Room SQLite database, Secure Vault, bookmark thumbnails, configuration and generated
 * media live under one user-visible root (`Documents/mpvRx/` on standard builds), so uninstalling
 * the app, updating it, or clearing its private data cannot delete them. Internal storage remains
 * the fallback for Play Store scoped-storage builds and for launches before all-files access is
 * granted. Existing internal files are copied once and their database paths are rebased.
 */
object DurableStorageManager {
  private const val DEFAULT_ROOT_NAME = "mpvRx"
  private const val DATABASE_NAME = "mpvrx.db"
  private const val LEGACY_VAULT_NAME = "secure_media"
  private const val LEGACY_THUMBNAILS_NAME = "bookmark-thumbnails"
  private const val COPY_BUFFER_SIZE = 64 * 1024

  /** What [prepare] found, so startup can copy large media off the main thread and rebase paths. */
  data class MigrationReport(
    val durableRoot: File? = null,
    val legacyVault: File? = null,
    val vault: File? = null,
    val legacyThumbnails: File? = null,
    val thumbnails: File? = null,
  ) {
    val hasDurableRoot: Boolean get() = durableRoot != null
  }

  @Volatile private var rootResolved = false
  @Volatile private var resolvedRoot: File? = null
  @Volatile private var mediaReady = false

  /**
   * Resolves and writability-tests the durable root once per process. A null result means the
   * caller must keep using internal storage; it is never retried mid-process because a permission
   * grant should not move an open database underneath Room.
   */
  @Suppress("DEPRECATION")
  fun durableRoot(context: Context): File? {
    if (rootResolved) return resolvedRoot
    synchronized(this) {
      if (rootResolved) return resolvedRoot
      val root =
        if (BuildConfig.SCOPED_STORAGE_ONLY) {
          null
        } else {
          runCatching {
            val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            File(documents, DEFAULT_ROOT_NAME).takeIf { ensureWritableRoot(it) != null }
          }.getOrNull()
        }
      resolvedRoot = root
      rootResolved = true
      return root
    }
  }

  /**
   * Copies the existing internal database (and its journal) to the durable root before Room opens,
   * then reports the legacy media directories so [copyMediaDirectories] can move them in the
   * background. Safe to call twice; the second call sees the copied database and does nothing.
   */
  fun prepare(context: Context): MigrationReport {
    val root = durableRoot(context)
    if (root == null) {
      mediaReady = true
      return MigrationReport()
    }
    copyDatabase(context, root)

    val legacyVault = File(context.filesDir, LEGACY_VAULT_NAME)
    val vault = getVaultDirectory(root)
    val legacyThumbnails = File(context.filesDir, LEGACY_THUMBNAILS_NAME)
    val thumbnails = getThumbnailsDirectory(root)
    val hasLegacyMedia =
      legacyVault.isDirectory && legacyVault.listFiles()?.any { it.isFile } == true ||
        legacyThumbnails.isDirectory && legacyThumbnails.listFiles()?.any { it.isFile } == true
    if (!hasLegacyMedia) mediaReady = true
    return MigrationReport(root, legacyVault, vault, legacyThumbnails, thumbnails)
  }

  /** Copies vault files and bookmark thumbnails once; returns true when both roots are complete. */
  fun copyMediaDirectories(report: MigrationReport): Boolean {
    if (report.durableRoot == null) {
      mediaReady = true
      return true
    }
    val vaultOk = report.legacyVault?.let { copyDirectoryContents(it, report.vault) } ?: true
    val thumbsOk =
      report.legacyThumbnails?.let { copyDirectoryContents(it, report.thumbnails) } ?: true
    return vaultOk && thumbsOk
  }

  /** Called after the database rows point at the copied files; from then on new files use them. */
  fun markMediaReady() {
    mediaReady = true
  }

  fun isMediaReady(): Boolean = mediaReady

  fun databaseFile(context: Context): File {
    val root = durableRoot(context) ?: return context.getDatabasePath(DATABASE_NAME)
    return File(getDatabaseDirectory(root), DATABASE_NAME)
  }

  /**
   * Vault location for new moves and reads. Until the one-time copy finishes this stays on the
   * legacy internal folder, which is also where the database rows still point, so a restore cannot
   * race the migration.
   */
  fun vaultDirectory(context: Context): File {
    val root = durableRoot(context)
    return if (mediaReady && root != null) getVaultDirectory(root) else legacyVaultDirectory(context)
  }

  fun thumbnailsDirectory(context: Context): File {
    val root = durableRoot(context)
    return if (mediaReady && root != null) getThumbnailsDirectory(root) else legacyThumbnailsDirectory(context)
  }

  fun legacyVaultDirectory(context: Context): File = File(context.filesDir, LEGACY_VAULT_NAME)

  fun legacyThumbnailsDirectory(context: Context): File = File(context.filesDir, LEGACY_THUMBNAILS_NAME)

  fun getDatabaseDirectory(root: File = getRootDirectory()): File = ensureDirectory(File(root, "database"))

  /**
   * Resolves the primary root directory (defaults to /sdcard/Documents/mpvRx/).
   * Kept for callers that already hold a resolved root.
   */
  fun getRootDirectory(customPath: String? = null): File {
    if (!customPath.isNullOrBlank()) {
      return ensureWritableRoot(File(customPath)) ?: File(customPath)
    }
    @Suppress("DEPRECATION")
    val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    return ensureWritableRoot(File(documents, DEFAULT_ROOT_NAME)) ?: File(documents, DEFAULT_ROOT_NAME)
  }

  fun getVaultDirectory(root: File = getRootDirectory()): File = ensureDirectory(File(root, "vault"))

  fun getThumbnailsDirectory(root: File = getRootDirectory()): File = ensureDirectory(File(root, "thumbnails"))

  fun getConfigDirectory(root: File = getRootDirectory()): File = ensureDirectory(File(root, "config"))

  fun getMusicDirectory(root: File = getRootDirectory()): File = ensureDirectory(File(root, "music"))

  fun getCompanionVideosDirectory(root: File = getRootDirectory()): File = ensureDirectory(File(root, "companion_videos"))

  fun getLyricsDirectory(root: File = getRootDirectory()): File = ensureDirectory(File(root, "lyrics"))

  private fun ensureWritableRoot(root: File): File? {
    if (!root.exists() && !root.mkdirs()) return null
    if (!root.isDirectory || !root.canWrite()) return null
    val probe = File(root, ".mpvrx-write-test")
    val writable =
      try {
        FileOutputStream(probe).use { it.write(0) }
        true
      } catch (_: Exception) {
        false
      } finally {
        probe.delete()
      }
    if (!writable) return null
    runCatching {
      File(root, ".nomedia").takeIf { !it.exists() }?.createNewFile()
    }
    return root
  }

  private fun ensureDirectory(dir: File): File {
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  private fun copyDatabase(context: Context, root: File) {
    val legacy = context.getDatabasePath(DATABASE_NAME)
    val target = File(getDatabaseDirectory(root), DATABASE_NAME)
    if (!legacy.isFile) return
    val targetIsStale =
      target.isFile && target.length() > 0 && target.lastModified() < legacy.lastModified()
    if (target.isFile && target.length() > 0 && !targetIsStale) return

    copyFile(legacy, target)
    listOf("-wal", "-shm", "-journal").forEach { suffix ->
      val source = File(legacy.path + suffix)
      if (source.isFile) copyFile(source, File(target.path + suffix))
    }
  }

  private fun copyDirectoryContents(source: File?, destination: File?): Boolean {
    if (source == null || destination == null) return true
    if (!source.isDirectory) return true
    if (!destination.exists() && !destination.mkdirs()) return false
    var complete = true
    source.listFiles().orEmpty().forEach { file ->
      if (!file.isFile) return@forEach
      val target = File(destination, file.name)
      if (target.isFile && target.length() == file.length()) return@forEach
      if (!copyFile(file, target)) complete = false
    }
    return complete
  }

  private fun copyFile(source: File, target: File): Boolean {
    val parent = target.parentFile
    if (parent != null && !parent.exists() && !parent.mkdirs()) return false
    val temp = File(target.path + ".tmp")
    return try {
      source.inputStream().use { input ->
        FileOutputStream(temp).use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
      }
      if (temp.length() != source.length()) {
        temp.delete()
        false
      } else {
        if (target.exists()) target.delete()
        temp.renameTo(target)
      }
    } catch (_: Exception) {
      temp.delete()
      false
    }
  }
}
