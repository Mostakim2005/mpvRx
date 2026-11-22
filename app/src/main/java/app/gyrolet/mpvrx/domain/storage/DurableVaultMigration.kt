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
import android.util.Log
import app.gyrolet.mpvrx.database.MpvRxDatabase
import app.gyrolet.mpvrx.database.SecureVaultDatabase
import app.gyrolet.mpvrx.database.entities.SecureVaultMediaEntity
import app.gyrolet.mpvrx.database.repository.SecureFolderRepository
import app.gyrolet.mpvrx.domain.bookmark.BookmarkThumbnailStore
import app.gyrolet.mpvrx.domain.bookmark.SecureBookmarkStore
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.domain.secure.SecureMediaRegistry
import app.gyrolet.mpvrx.domain.secure.VaultCrypto
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Idempotent, verify-before-delete startup migration from v3.2.2 mixed storage and internal files
 * into segregated encrypted durable directories under `Documents/mpvRx/` (`.store/` and `.vault/`).
 */
object DurableVaultMigration {
  private const val TAG = "DurableVaultMigration"
  private val migrationMutex = Mutex()

  private data class MigratedVaultRecord(
    val oldPaths: Set<String>,
    val vaultPath: String,
    val displayTitle: String,
    val plainSize: Long,
  )

  suspend fun run(
    context: Context,
    publicDb: MpvRxDatabase,
    secureDb: SecureVaultDatabase,
    secureBookmarkStore: SecureBookmarkStore,
    playbackStateRepository: PlaybackStateRepository,
    secureFolderRepository: SecureFolderRepository,
  ) = migrationMutex.withLock {
    withContext(Dispatchers.IO) {
      VaultCrypto.ensureInitialized(context)
      val appContext = context.applicationContext
      val durableRoot = DurableStorageManager.durableRoot(appContext)
      val vaultMediaDir = DurableStorageManager.vaultMediaDir(appContext)
      val legacyInternalVault = DurableStorageManager.legacyVaultDirectory(appContext)
      val legacyV322Vault = DurableStorageManager.legacyV322VaultDirectory(appContext)

      val legacySecureDao = publicDb.secureMediaDao()
      val secureMediaDao = secureDb.secureVaultMediaDao()
      val publicBookmarkDao = publicDb.playbackBookmarkDao()
      val publicTagDao = publicDb.bookmarkTagDao()

      val migratedRecords = mutableListOf<MigratedVaultRecord>()
      val handledLegacyFilePaths = HashSet<String>()

      // 1. Migrate legacy secure_media rows out of MpvRxDatabase into encrypted SecureVaultDatabase + .vault/media/
      val legacyRows = runCatching { legacySecureDao.getAll() }.getOrDefault(emptyList())
      for (row in legacyRows) {
        val leafName = File(row.secureFilePath).name.ifBlank { row.fileName }
        val sourceCandidate =
          listOfNotNull(
            File(row.secureFilePath),
            File(vaultMediaDir, leafName),
            File(legacyInternalVault, leafName),
            File(legacyInternalVault, row.fileName),
            legacyV322Vault?.let { File(it, leafName) },
            legacyV322Vault?.let { File(it, row.fileName) },
          ).firstOrNull { it.isFile && it.canRead() }

        if (sourceCandidate == null) {
          Log.w(TAG, "Dropping orphaned legacy secure_media row with missing file: ${row.secureFilePath}")
          legacySecureDao.deleteById(row.id)
          continue
        }

        val plainSize =
          if (VaultCrypto.isEncryptedFile(sourceCandidate)) {
            VaultCrypto.plaintextLength(sourceCandidate)
          } else {
            sourceCandidate.length()
          }
        val alreadyVaultEncrypted =
          isInsideDir(sourceCandidate, vaultMediaDir) && VaultCrypto.isEncryptedFile(sourceCandidate)
        val targetFile =
          if (alreadyVaultEncrypted) {
            sourceCandidate
          } else {
            File(vaultMediaDir, "${UUID.randomUUID()}.dat").also { dest ->
              if (VaultCrypto.isEncryptedFile(sourceCandidate)) {
                DurableStorageManager.copyFile(sourceCandidate, dest)
              } else {
                VaultCrypto.encryptFile(sourceCandidate, dest)
              }
            }
          }

        if (!targetFile.isFile || VaultCrypto.plaintextLength(targetFile) != plainSize) {
          if (!alreadyVaultEncrypted) runCatching { targetFile.delete() }
          Log.w(TAG, "Verification failed encrypting legacy vault item: ${sourceCandidate.absolutePath}")
          continue
        }

        val existingByTarget = secureMediaDao.findBySecurePath(targetFile.absolutePath)
        if (existingByTarget == null) {
          secureMediaDao.insert(
            SecureVaultMediaEntity(
              originalPath = encryptIfPlain(row.originalPath),
              secureFilePath = targetFile.absolutePath,
              fileName = encryptIfPlain(row.fileName),
              fileSize = plainSize,
              mimeType = encryptIfPlain(row.mimeType),
              dateHidden = row.dateHidden,
            ),
          )
        }
        legacySecureDao.deleteById(row.id)
        if (!alreadyVaultEncrypted && sourceCandidate.absolutePath != targetFile.absolutePath) {
          handledLegacyFilePaths += sourceCandidate.canonicalPathOrAbsolute()
          runCatching { sourceCandidate.delete() }
        }

        migratedRecords +=
          MigratedVaultRecord(
            oldPaths =
              setOf(
                row.secureFilePath,
                row.originalPath,
                sourceCandidate.absolutePath,
                targetFile.absolutePath,
              ),
            vaultPath = targetFile.absolutePath,
            displayTitle = row.fileName,
            plainSize = plainSize,
          )
      }

      // 2. Ensure any existing SecureVaultDatabase entries also point to encrypted files inside vaultMediaDir
      val currentSecureRows = secureMediaDao.getAll()
      for (secRow in currentSecureRows) {
        val decryptedName = runCatching { VaultCrypto.decryptString(secRow.fileName) }.getOrDefault(secRow.fileName)
        val decryptedOrig = runCatching { VaultCrypto.decryptString(secRow.originalPath) }.getOrDefault(secRow.originalPath)
        val currentFile = File(secRow.secureFilePath)
        var finalPath = secRow.secureFilePath
        var finalSize = secRow.fileSize

        if (currentFile.isFile && (!isInsideDir(currentFile, vaultMediaDir) || !VaultCrypto.isEncryptedFile(currentFile))) {
          val plainSize =
            if (VaultCrypto.isEncryptedFile(currentFile)) {
              VaultCrypto.plaintextLength(currentFile)
            } else {
              currentFile.length()
            }
          val destFile = File(vaultMediaDir, "${UUID.randomUUID()}.dat")
          val copiedOk =
            runCatching {
              if (VaultCrypto.isEncryptedFile(currentFile)) {
                DurableStorageManager.copyFile(currentFile, destFile)
              } else {
                VaultCrypto.encryptFile(currentFile, destFile)
                true
              }
            }.getOrDefault(false)
          if (copiedOk && destFile.isFile && VaultCrypto.plaintextLength(destFile) == plainSize) {
            secureMediaDao.insert(
              secRow.copy(
                originalPath = encryptIfPlain(decryptedOrig),
                secureFilePath = destFile.absolutePath,
                fileName = encryptIfPlain(decryptedName),
                fileSize = plainSize,
                mimeType = encryptIfPlain(runCatching { VaultCrypto.decryptString(secRow.mimeType) }.getOrDefault(secRow.mimeType)),
              ),
            )
            handledLegacyFilePaths += currentFile.canonicalPathOrAbsolute()
            runCatching { currentFile.delete() }
            finalPath = destFile.absolutePath
            finalSize = plainSize
          } else {
            runCatching { destFile.delete() }
          }
        }

        migratedRecords +=
          MigratedVaultRecord(
            oldPaths = setOf(secRow.secureFilePath, decryptedOrig, finalPath),
            vaultPath = finalPath,
            displayTitle = decryptedName,
            plainSize = finalSize,
          )
      }

      // 3. Recover any untracked physical files left in legacy vault dirs so no file is stranded
      listOfNotNull(legacyInternalVault, legacyV322Vault).forEach { dir ->
        if (!dir.isDirectory) return@forEach
        dir.listFiles()?.forEach { file ->
          if (!file.isFile || file.name.equals(".nomedia", ignoreCase = true) || file.name.endsWith(".tmp")) {
            return@forEach
          }
          if (file.canonicalPathOrAbsolute() in handledLegacyFilePaths) return@forEach
          val plainSize =
            if (VaultCrypto.isEncryptedFile(file)) VaultCrypto.plaintextLength(file) else file.length()
          val destFile = File(vaultMediaDir, "${UUID.randomUUID()}.dat")
          val ok =
            runCatching {
              if (VaultCrypto.isEncryptedFile(file)) {
                DurableStorageManager.copyFile(file, destFile)
              } else {
                VaultCrypto.encryptFile(file, destFile)
                true
              }
            }.getOrDefault(false)
          if (ok && destFile.isFile && VaultCrypto.plaintextLength(destFile) == plainSize) {
            val origName = file.name
            val mime = FileTypeUtils.getMimeTypeFromExtension(file.extension)
            secureMediaDao.insert(
              SecureVaultMediaEntity(
                originalPath = encryptIfPlain(file.absolutePath),
                secureFilePath = destFile.absolutePath,
                fileName = encryptIfPlain(origName),
                fileSize = plainSize,
                mimeType = encryptIfPlain(mime),
                dateHidden = System.currentTimeMillis(),
              ),
            )
            runCatching { file.delete() }
            migratedRecords +=
              MigratedVaultRecord(
                oldPaths = setOf(file.absolutePath, destFile.absolutePath),
                vaultPath = destFile.absolutePath,
                displayTitle = origName,
                plainSize = plainSize,
              )
          } else {
            runCatching { destFile.delete() }
          }
        }
      }

      // Refresh SecureMediaRegistry now so all subsequent identity queries classify vault media accurately
      secureFolderRepository.syncRegistry()

      // 4. Segregate bookmarks, tags, groups, links, refs, thumbnails, and playback states out of public DB
      val allKnownSecurePaths = HashSet<String>()
      migratedRecords.forEach { rec ->
        allKnownSecurePaths += rec.oldPaths
        allKnownSecurePaths += rec.vaultPath
        val oldIds = rec.oldPaths.filter { it.isNotBlank() }.flatMap { SecureMediaRegistry.identitiesForPath(it) }
        val targetId = PlaybackIdentity.forLocalPath(rec.vaultPath)
        secureBookmarkStore.migrateMediaToVault(
          publicBookmarkDao = publicBookmarkDao,
          publicTagDao = publicTagDao,
          oldMediaIds = oldIds,
          targetVaultMediaId = targetId,
          targetVaultPath = rec.vaultPath,
          displayTitle = rec.displayTitle,
          fileSizeBytes = rec.plainSize,
        )
        playbackStateRepository.migrateState(
          oldMediaTitles = (rec.oldPaths + oldIds + rec.displayTitle).filter { it.isNotBlank() }.distinct(),
          newMediaTitle = rec.vaultPath,
          toSecure = true,
        )
      }

      // Catch any remaining public bookmark_media_refs whose URI already pointed to a vault path
      publicTagDao.getAllMediaRefs().forEach { ref ->
        if (DurableStorageManager.isVaultPath(ref.playableUri) || ref.playableUri in allKnownSecurePaths) {
          val matchingRecord =
            migratedRecords.firstOrNull { rec ->
              ref.playableUri in rec.oldPaths || ref.playableUri == rec.vaultPath
            }
          val targetPath = matchingRecord?.vaultPath ?: ref.playableUri
          val targetTitle = matchingRecord?.displayTitle ?: ref.title ?: File(targetPath).name
          val targetId = PlaybackIdentity.forLocalPath(targetPath)
          secureBookmarkStore.migrateMediaToVault(
            publicBookmarkDao = publicBookmarkDao,
            publicTagDao = publicTagDao,
            oldMediaIds = (listOf(ref.mediaId) + SecureMediaRegistry.identitiesForPath(ref.playableUri)).distinct(),
            targetVaultMediaId = targetId,
            targetVaultPath = targetPath,
            displayTitle = targetTitle,
            fileSizeBytes = matchingRecord?.plainSize ?: 0L,
          )
        }
      }

      // Purge any vault entries lingering in MpvRxDatabase.videoMetadataDao()
      val publicMetaDao = publicDb.videoMetadataDao()
      val vaultMetaPaths =
        publicMetaDao.getAllCachedPaths().filter { path ->
          DurableStorageManager.isVaultPath(path) || path in allKnownSecurePaths
        }
      if (vaultMetaPaths.isNotEmpty()) {
        vaultMetaPaths.chunked(900).forEach { chunk -> publicMetaDao.deleteByPaths(chunk) }
      }

      // 5. Encrypt and relocate remaining normal bookmark thumbnails into .store/thumbs/
      publicBookmarkDao.getAllBookmarks().forEach { bm ->
        val thumbPath = bm.thumbnailPath?.takeIf { it.isNotBlank() } ?: return@forEach
        val migratedThumb = BookmarkThumbnailStore.migrateThumbnailFile(appContext, thumbPath, toSecure = false)
        if (migratedThumb != null && migratedThumb != thumbPath) {
          publicBookmarkDao.updateThumbnail(bm.id, migratedThumb)
        }
      }

      // 6. Encrypt & move internal video thumbnail cache -> .store/thumb_cache/ when durable root is writable
      if (durableRoot != null) {
        migrateLegacyVideoThumbDir(
          sourceDir = File(appContext.filesDir, "thumbnails/local"),
          targetDir = DurableStorageManager.normalThumbnailCacheLocalDir(appContext),
        )
        migrateLegacyVideoThumbDir(
          sourceDir = File(appContext.filesDir, "thumbnails/network"),
          targetDir = DurableStorageManager.normalThumbnailCacheNetworkDir(appContext),
        )

        // Prune legacy empty dirs & verified redundant internal database/key files
        pruneDirectoryIfOnlyEmptyOrNomedia(legacyInternalVault)
        pruneDirectoryIfOnlyEmptyOrNomedia(DurableStorageManager.legacyThumbnailsDirectory(appContext))
        pruneDirectoryIfOnlyEmptyOrNomedia(File(appContext.filesDir, "thumbnails/local"))
        pruneDirectoryIfOnlyEmptyOrNomedia(File(appContext.filesDir, "thumbnails/network"))
        pruneDirectoryIfOnlyEmptyOrNomedia(File(appContext.filesDir, "thumbnails"))
        legacyV322Vault?.let(::pruneDirectoryIfOnlyEmptyOrNomedia)
        DurableStorageManager.legacyV322ThumbnailsDirectory(appContext)?.let(::pruneDirectoryIfOnlyEmptyOrNomedia)

        val liveNormalDb = DurableStorageManager.normalDatabaseFile(appContext)
        val internalDb = appContext.getDatabasePath("mpvrx.db")
        if (liveNormalDb.isFile && liveNormalDb.length() > 0L && internalDb.absolutePath != liveNormalDb.absolutePath) {
          deleteDatabaseWithAuxFiles(internalDb)
        }
        val legacyV322DbDir = DurableStorageManager.legacyV322DatabaseDirectory(appContext)
        if (legacyV322DbDir != null && liveNormalDb.isFile && liveNormalDb.length() > 0L) {
          deleteDatabaseWithAuxFiles(File(legacyV322DbDir, "mpvrx.db"))
          pruneDirectoryIfOnlyEmptyOrNomedia(legacyV322DbDir)
        }

        val durableKeyFile = VaultCrypto.keyFile(DurableStorageManager.vaultKeyDirectory(appContext))
        val internalVaultDir = File(appContext.filesDir, ".vault")
        val internalKeyFile = VaultCrypto.keyFile(internalVaultDir)
        if (durableKeyFile.isFile && internalKeyFile.isFile && internalKeyFile.absolutePath != durableKeyFile.absolutePath) {
          runCatching { internalKeyFile.delete() }
          pruneDirectoryIfOnlyEmptyOrNomedia(internalVaultDir)
        }
      }

      secureFolderRepository.syncRegistry()
      DurablePreferencesSync.flushNow(appContext)
    }
  }

  private fun encryptIfPlain(value: String): String {
    if (value.isEmpty() || value.startsWith("mvx1:")) return value
    return VaultCrypto.encryptString(value)
  }

  private fun isInsideDir(file: File, dir: File): Boolean =
    runCatching {
      file.canonicalFile.path.startsWith("${dir.canonicalFile.path}${File.separator}")
    }.getOrDefault(false)

  private fun File.canonicalPathOrAbsolute(): String =
    runCatching { canonicalPath }.getOrDefault(absolutePath)

  private fun migrateLegacyVideoThumbDir(
    sourceDir: File,
    targetDir: File,
  ) {
    if (!sourceDir.isDirectory || sourceDir.canonicalPathOrAbsolute() == targetDir.canonicalPathOrAbsolute()) return
    sourceDir.listFiles()?.forEach { file ->
      if (!file.isFile || file.name.equals(".nomedia", ignoreCase = true) || file.name.endsWith(".tmp")) {
        return@forEach
      }
      val stem = file.nameWithoutExtension
      val dest = File(targetDir, "$stem.dat")
      if (dest.isFile && dest.length() >= VaultCrypto.HEADER_SIZE) {
        runCatching { file.delete() }
        return@forEach
      }
      val raw = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
      val plain = runCatching { VaultCrypto.decryptBytes(raw) }.getOrNull() ?: return@forEach
      val encrypted = VaultCrypto.encryptBytes(plain)
      val temp = File(dest.path + ".${java.util.UUID.randomUUID()}.tmp")
      val wrote =
        runCatching {
          java.io.FileOutputStream(temp).use { out ->
            out.write(encrypted)
            out.fd.sync()
          }
          temp.renameTo(dest) && VaultCrypto.plaintextLength(dest) == plain.size.toLong()
        }.getOrDefault(false)
      if (wrote) {
        runCatching { file.delete() }
      } else {
        runCatching { temp.delete() }
      }
    }
  }

  private fun pruneDirectoryIfOnlyEmptyOrNomedia(dir: File) {
    if (!dir.isDirectory) return
    val children = dir.listFiles().orEmpty()
    val hasLiveContent =
      children.any { !it.name.equals(".nomedia", ignoreCase = true) }
    if (!hasLiveContent) {
      children.forEach { runCatching { it.delete() } }
      runCatching { dir.delete() }
    }
  }

  private fun deleteDatabaseWithAuxFiles(dbFile: File) {
    listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
      val f = File(dbFile.path + suffix)
      if (f.exists()) runCatching { f.delete() }
    }
  }
}
