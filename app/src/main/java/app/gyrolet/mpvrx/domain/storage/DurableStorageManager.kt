/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.storage

import android.content.Context
import android.os.Environment
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.domain.secure.VaultCrypto
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Unified Durable Storage Manager (`Documents/mpvRx/`).
 *
 * Standard builds store all persistent data under `/sdcard/Documents/mpvRx/` inside two segregated,
 * `.nomedia`-guarded subdirectories:
 * - `.store/`: normal library database (`library.dat`), encrypted normal bookmark thumbnails
 *   (`thumbs/`), encrypted normal video thumbnail cache (`thumb_cache/`), encrypted settings mirror
 *   (`settings.mvx`), and encrypted normal watch stats (`watch_stats.mvx`).
 * - `.vault/`: isolated Secure Folder database (`v_idx.dat`), AES-128-CTR encrypted vault media
 *   (`media/<uuid>.dat`), encrypted secure bookmark thumbnails (`thumbs/<uuid>.dat`), encrypted secure video
 *   thumbnail cache (`thumb_cache/`), encrypted vault settings (`v_cfg.mvx`), encrypted secure
 *   watch stats (`v_stats.mvx`), and wrapped master key (`.vkey`).
 *
 * Nothing durable remains in internal app data (`filesDir` / `databases`), so clearing App Data or
 * uninstalling the app preserves the library, settings, stats, bookmarks, thumbnails, and vault.
 */
object DurableStorageManager {
  private const val DEFAULT_ROOT_NAME = "mpvRx"
  private const val STORE_DIR_NAME = ".store"
  private const val VAULT_DIR_NAME = ".vault"
  private const val NORMAL_DATABASE_NAME = "library.dat"
  private const val SECURE_DATABASE_NAME = "v_idx.dat"
  private const val LEGACY_DATABASE_NAME = "mpvrx.db"
  private const val LEGACY_VAULT_NAME = "secure_media"
  private const val LEGACY_THUMBNAILS_NAME = "bookmark-thumbnails"
  private const val LEGACY_VIDEO_THUMBS_NAME = "thumbnails"
  private const val SETTINGS_MIRROR_NAME = "settings.mvx"
  private const val SECURE_CONFIG_NAME = "v_cfg.mvx"
  private const val NORMAL_WATCH_STATS_NAME = "watch_stats.mvx"
  private const val SECURE_WATCH_STATS_NAME = "v_stats.mvx"
  private const val NOMEDIA_NAME = ".nomedia"
  private const val COPY_BUFFER_SIZE = 64 * 1024
  private val REPEATED_SLASHES = Regex("/+")

  /** Retained for startup migration orchestration in `App.onCreate()`. */
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

  @Suppress("DEPRECATION")
  private fun defaultRootCandidate(): File {
    val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    return File(documents, DEFAULT_ROOT_NAME)
  }

  /**
   * Resolves and writability-tests the durable root once per process. A null result means internal
   * storage fallback is active for this process so an open Room database is never moved mid-flight.
   */
  fun durableRoot(context: Context): File? {
    if (rootResolved) return resolvedRoot
    synchronized(this) {
      if (rootResolved) return resolvedRoot
      val root =
        if (BuildConfig.SCOPED_STORAGE_ONLY) {
          null
        } else {
          runCatching {
            defaultRootCandidate().takeIf { ensureWritableRoot(it) != null }
          }.getOrNull()
        }
      resolvedRoot = root
      rootResolved = true
      return root
    }
  }

  /**
   * True when the process started without durable storage permission (so Room opened internal files)
   * and all-files access has since been granted, making `Documents/mpvRx/` writable.
   */
  fun needsProcessRebindToDurableRoot(context: Context): Boolean {
    if (BuildConfig.SCOPED_STORAGE_ONLY) return false
    if (resolvedRoot != null) return false
    return runCatching { ensureWritableRoot(defaultRootCandidate()) != null }.getOrDefault(false)
  }

  /** True if a pre-existing durable library/vault already sits under `Documents/mpvRx/`. */
  fun hasPreexistingDurableLibrary(): Boolean {
    if (BuildConfig.SCOPED_STORAGE_ONLY) return false
    val root = defaultRootCandidate()
    return File(File(root, STORE_DIR_NAME), NORMAL_DATABASE_NAME).isFile ||
      File(File(root, STORE_DIR_NAME), SETTINGS_MIRROR_NAME).isFile ||
      File(File(root, VAULT_DIR_NAME), SECURE_DATABASE_NAME).isFile ||
      File(File(root, VAULT_DIR_NAME), SECURE_CONFIG_NAME).isFile ||
      File(File(root, "database"), LEGACY_DATABASE_NAME).isFile
  }

  /**
   * Prepares the durable root, transfers any internal `.vkey` and legacy `mpvrx.db` into
   * `.store/library.dat` BEFORE Room opens, and initializes [VaultCrypto].
   */
  fun prepare(context: Context): MigrationReport {
    val root = durableRoot(context)
    if (root == null) {
      ensureDirectoryWithNomedia(File(context.filesDir, VAULT_DIR_NAME))
      ensureDirectoryWithNomedia(File(context.filesDir, STORE_DIR_NAME))
      VaultCrypto.ensureInitialized(context)
      mediaReady = true
      return MigrationReport()
    }

    val storeDir = ensureDirectoryWithNomedia(File(root, STORE_DIR_NAME))
    val vaultRoot = ensureDirectoryWithNomedia(File(root, VAULT_DIR_NAME))

    // Carry forward a key created during a pre-permission first launch only if no durable key exists.
    val internalVaultRoot = File(context.filesDir, VAULT_DIR_NAME)
    val durableKey = VaultCrypto.keyFile(vaultRoot)
    val internalKey = VaultCrypto.keyFile(internalVaultRoot)
    if (!durableKey.isFile && internalKey.isFile) {
      copyFile(internalKey, durableKey)
    }

    VaultCrypto.ensureInitialized(context)
    migrateNormalDatabaseBeforeOpen(context, root, storeDir)

    val legacyVault = legacyVaultDirectory(context)
    val vaultMedia = vaultMediaDir(context)
    val legacyThumbs = legacyThumbnailsDirectory(context)
    val normalThumbs = normalBookmarkThumbnailsDir(context)
    mediaReady = true
    return MigrationReport(root, legacyVault, vaultMedia, legacyThumbs, normalThumbs)
  }

  fun markMediaReady() {
    mediaReady = true
  }

  fun isMediaReady(): Boolean = mediaReady

  private fun storeRoot(context: Context): File {
    val root = durableRoot(context)
    val dir = if (root != null) File(root, STORE_DIR_NAME) else File(context.filesDir, STORE_DIR_NAME)
    return ensureDirectoryWithNomedia(dir)
  }

  fun vaultRootDir(context: Context): File {
    val root = durableRoot(context)
    val dir = if (root != null) File(root, VAULT_DIR_NAME) else File(context.filesDir, VAULT_DIR_NAME)
    return ensureDirectoryWithNomedia(dir)
  }

  fun vaultKeyDirectory(context: Context): File = vaultRootDir(context)

  fun normalDatabaseFile(context: Context): File {
    val root = durableRoot(context) ?: return context.getDatabasePath(LEGACY_DATABASE_NAME)
    return File(ensureDirectoryWithNomedia(File(root, STORE_DIR_NAME)), NORMAL_DATABASE_NAME)
  }

  fun secureDatabaseFile(context: Context): File = File(vaultRootDir(context), SECURE_DATABASE_NAME)

  fun vaultMediaDir(context: Context): File =
    ensureDirectoryWithNomedia(File(vaultRootDir(context), "media"))

  fun normalBookmarkThumbnailsDir(context: Context): File =
    ensureDirectoryWithNomedia(File(storeRoot(context), "thumbs"))

  fun secureBookmarkThumbnailsDir(context: Context): File =
    ensureDirectoryWithNomedia(File(vaultRootDir(context), "thumbs"))

  fun normalThumbnailCacheLocalDir(context: Context): File {
    val root = durableRoot(context)
    val base =
      if (root != null) File(ensureDirectoryWithNomedia(File(root, STORE_DIR_NAME)), "thumb_cache")
      else File(context.filesDir, LEGACY_VIDEO_THUMBS_NAME)
    return ensureDirectoryWithNomedia(File(base, "local"))
  }

  fun normalThumbnailCacheNetworkDir(context: Context): File {
    val root = durableRoot(context)
    val base =
      if (root != null) File(ensureDirectoryWithNomedia(File(root, STORE_DIR_NAME)), "thumb_cache")
      else File(context.filesDir, LEGACY_VIDEO_THUMBS_NAME)
    return ensureDirectoryWithNomedia(File(base, "network"))
  }

  fun secureThumbnailCacheDir(context: Context): File =
    ensureDirectoryWithNomedia(File(vaultRootDir(context), "thumb_cache"))

  fun settingsMirrorFile(context: Context): File =
    File(storeRoot(context), SETTINGS_MIRROR_NAME)

  fun secureConfigFile(context: Context): File =
    File(vaultRootDir(context), SECURE_CONFIG_NAME)

  fun normalWatchStatsFile(context: Context): File =
    File(storeRoot(context), NORMAL_WATCH_STATS_NAME)

  fun secureWatchStatsFile(context: Context): File =
    File(vaultRootDir(context), SECURE_WATCH_STATS_NAME)

  // Legacy path accessors for migration and compatibility cleanup
  fun legacyVaultDirectory(context: Context): File = File(context.filesDir, LEGACY_VAULT_NAME)

  fun legacyThumbnailsDirectory(context: Context): File = File(context.filesDir, LEGACY_THUMBNAILS_NAME)

  fun legacyV322VaultDirectory(context: Context): File? =
    durableRoot(context)?.let { File(it, "vault") }

  fun legacyV322ThumbnailsDirectory(context: Context): File? =
    durableRoot(context)?.let { File(it, "thumbnails") }

  fun legacyV322DatabaseDirectory(context: Context): File? =
    durableRoot(context)?.let { File(it, "database") }

  /**
   * True when [rawPath] points inside mpvRx's durable root (`Documents/mpvRx`) or internal vault.
   * Used by every library scanner and the built-in file browser to keep app stores invisible.
   */
  fun isAppDurablePath(rawPath: String?): Boolean {
    val norm = normalizeForMatching(rawPath) ?: return false
    if (norm.endsWith("/documents/mpvrx") || norm.contains("/documents/mpvrx/")) return true
    return isVaultPath(norm)
  }

  /** True when [rawPath] points inside any Secure Folder storage root (current or legacy). */
  fun isVaultPath(rawPath: String?): Boolean {
    val norm = normalizeForMatching(rawPath) ?: return false
    return norm.endsWith("/documents/mpvrx/.vault") ||
      norm.contains("/documents/mpvrx/.vault/") ||
      norm.endsWith("/documents/mpvrx/vault") ||
      norm.contains("/documents/mpvrx/vault/") ||
      norm.endsWith("/files/.vault") ||
      norm.contains("/files/.vault/") ||
      norm.endsWith("/files/secure_media") ||
      norm.contains("/files/secure_media/")
  }

  private fun normalizeForMatching(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val withoutScheme = when {
      trimmed.startsWith("file://", ignoreCase = true) -> trimmed.substring(7)
      trimmed.startsWith("file:", ignoreCase = true) -> trimmed.substring(5)
      else -> trimmed
    }
    if (withoutScheme.contains("://")) return null
    val collapsed = withoutScheme.replace('\\', '/').replace(REPEATED_SLASHES, "/")
    val noTrailing = if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
    return noTrailing.lowercase(Locale.ROOT)
  }

  private fun migrateNormalDatabaseBeforeOpen(
    context: Context,
    root: File,
    storeDir: File,
  ) {
    val target = File(storeDir, NORMAL_DATABASE_NAME)
    if (target.isFile && target.length() > 0L) return

    val v322Db = File(File(root, "database"), LEGACY_DATABASE_NAME)
    val internalDb = context.getDatabasePath(LEGACY_DATABASE_NAME)
    val hasV322 = v322Db.isFile && v322Db.length() > 0L
    val hasInternal = internalDb.isFile && internalDb.length() > 0L
    val source =
      when {
        hasV322 && hasInternal -> {
          // A fresh pre-permission launch after Clear Data/reinstall creates a tiny empty internal
          // DB with timestamp NOW; only prefer internalDb if it is genuinely larger AND newer.
          if (
            internalDb.length() > v322Db.length() &&
            effectiveDbLastModified(internalDb) > effectiveDbLastModified(v322Db) + 60_000L
          ) {
            internalDb
          } else {
            v322Db
          }
        }
        hasV322 -> v322Db
        hasInternal -> internalDb
        else -> return
      }

    copyFile(source, target)
    listOf("-wal", "-shm", "-journal").forEach { suffix ->
      val aux = File(source.path + suffix)
      if (aux.isFile) copyFile(aux, File(target.path + suffix))
    }
  }

  private fun effectiveDbLastModified(dbFile: File): Long {
    val wal = File(dbFile.path + "-wal")
    return if (wal.isFile) maxOf(dbFile.lastModified(), wal.lastModified()) else dbFile.lastModified()
  }

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
      File(root, NOMEDIA_NAME).takeIf { !it.exists() }?.createNewFile()
    }
    return root
  }

  private fun ensureDirectoryWithNomedia(dir: File): File {
    if (!dir.exists()) dir.mkdirs()
    runCatching {
      val nomedia = File(dir, NOMEDIA_NAME)
      if (!nomedia.exists()) nomedia.createNewFile()
    }
    return dir
  }

  fun copyFile(source: File, target: File): Boolean {
    val parent = target.parentFile
    if (parent != null && !parent.exists() && !parent.mkdirs()) return false
    val temp = File(target.path + ".${java.util.UUID.randomUUID()}.tmp")
    return try {
      source.inputStream().use { input ->
        FileOutputStream(temp).use { output ->
          input.copyTo(output, COPY_BUFFER_SIZE)
          output.fd.sync()
        }
      }
      if (temp.length() != source.length()) {
        temp.delete()
        false
      } else if (temp.renameTo(target)) {
        true
      } else {
        // Fallback for filesystems where renameTo does not overwrite existing targets
        val backup = File(target.path + ".bak")
        if (target.exists() && !target.renameTo(backup)) {
          temp.delete()
          return false
        }
        val moved = temp.renameTo(target)
        if (moved) {
          backup.delete()
        } else {
          if (backup.exists()) backup.renameTo(target)
          temp.delete()
        }
        moved
      }
    } catch (_: Exception) {
      temp.delete()
      false
    }
  }
}
