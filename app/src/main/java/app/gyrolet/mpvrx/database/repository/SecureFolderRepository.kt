/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.dao.BookmarkTagDao
import app.gyrolet.mpvrx.database.dao.PlaybackBookmarkDao
import app.gyrolet.mpvrx.database.dao.SecureVaultMediaDao
import app.gyrolet.mpvrx.database.dao.SecureVideoMetadataDao
import app.gyrolet.mpvrx.database.entities.SecureMediaEntity
import app.gyrolet.mpvrx.database.entities.SecureVaultMediaEntity
import app.gyrolet.mpvrx.domain.bookmark.SecureBookmarkStore
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.domain.secure.SecureFolderSession
import app.gyrolet.mpvrx.domain.secure.SecureMediaRegistry
import app.gyrolet.mpvrx.domain.secure.VaultCrypto
import app.gyrolet.mpvrx.domain.storage.DurablePreferencesSync
import app.gyrolet.mpvrx.domain.storage.DurableStorageManager
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.repository.WatchStatsRepository
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import app.gyrolet.mpvrx.utils.coroutines.LoggingCoroutineExceptionHandler
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner
import app.gyrolet.mpvrx.utils.permission.PermissionUtils
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages encrypted vault media in `Documents/mpvRx/.vault/media/` and its isolated metadata index
 * (`SecureVaultDatabase`), including cross-DB migration of bookmarks, thumbnails, and playback
 * resume states whenever media enters or leaves the Secure Folder.
 */
class SecureFolderRepository(
  private val context: Context,
  private val dao: SecureVaultMediaDao,
  private val secureBookmarkStore: SecureBookmarkStore,
  private val publicBookmarkDao: PlaybackBookmarkDao,
  private val publicTagDao: BookmarkTagDao,
  private val playbackStateRepository: PlaybackStateRepository,
  private val secureVideoMetadataDao: SecureVideoMetadataDao,
  private val thumbnailRepository: ThumbnailRepository,
  private val watchStatsRepository: WatchStatsRepository,
) {
  companion object {
    private const val TAG = "SecureFolderRepository"
    private const val BUFFER_SIZE = 8 * 1024
    private const val MAX_FILENAME_ATTEMPTS = 1000
  }

  data class SecureOperationProgress(
    val currentFile: String = "",
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    val currentFileProgress: Float = 0f,
    val overallProgress: Float = 0f,
    val isComplete: Boolean = false,
    val isCancelled: Boolean = false,
    val error: String? = null,
  )

  data class BatchResult(
    val succeededIds: List<Long>,
    val failedIds: List<Long>,
  )

  private data class PendingSecureMove(
    val video: Video,
    val entityId: Long,
    val entity: SecureMediaEntity,
    val file: File,
  )

  private val _progress = MutableStateFlow(SecureOperationProgress())
  val progress: StateFlow<SecureOperationProgress> = _progress.asStateFlow()

  private val isCancelled = AtomicBoolean(false)

  init {
    CoroutineScope(SupervisorJob() + Dispatchers.IO + LoggingCoroutineExceptionHandler).launch {
      VaultCrypto.ensureInitialized(context)
      observeAll().collect { entities -> SecureMediaRegistry.update(entities) }
    }
  }

  private fun enc(value: String): String {
    VaultCrypto.ensureInitialized(context)
    return if (value.isEmpty()) "" else VaultCrypto.encryptString(value)
  }

  private fun dec(value: String): String {
    VaultCrypto.ensureInitialized(context)
    return if (value.isEmpty()) "" else runCatching { VaultCrypto.decryptString(value) }.getOrDefault(value)
  }

  private fun SecureVaultMediaEntity.toDomain(): SecureMediaEntity =
    SecureMediaEntity(
      id = id,
      originalPath = dec(originalPath),
      secureFilePath = secureFilePath,
      fileName = dec(fileName),
      fileSize = fileSize,
      mimeType = dec(mimeType),
      dateHidden = dateHidden,
    )

  suspend fun syncRegistry() {
    withContext(Dispatchers.IO) {
      SecureMediaRegistry.update(getAll())
    }
  }

  fun cancelOperation() {
    isCancelled.set(true)
  }

  private fun resetOperation() {
    isCancelled.set(false)
    _progress.value = SecureOperationProgress()
  }

  private fun secureDir(context: Context): File = DurableStorageManager.vaultMediaDir(context)

  private fun playbackIds(vararg paths: String): List<String> =
    paths
      .filter { it.isNotBlank() }
      .flatMap { SecureMediaRegistry.identitiesForPath(it) }
      .distinct()

  suspend fun moveIn(
    context: Context,
    videos: List<Video>,
  ): Result<BatchResult> =
    withContext(Dispatchers.IO) {
      if (videos.isEmpty()) return@withContext Result.failure(IllegalArgumentException("No files to hide"))
      VaultCrypto.ensureInitialized(context)
      if (BuildConfig.SCOPED_STORAGE_ONLY) {
        return@withContext moveInScoped(context, videos.distinctBy { it.uri })
      }

      resetOperation()
      val destDir = secureDir(context)
      val succeeded = mutableListOf<Long>()
      val failed = mutableListOf<Long>()
      var firstError: Exception? = null
      val totalBytes = videos.sumOf { it.size }.coerceAtLeast(1)
      var bytesDone = 0L

      videos.forEachIndexed { index, video ->
        try {
          checkCancellation()
          val sourceFile = File(video.path)
          if (!sourceFile.exists() || !sourceFile.canRead()) {
            throw IOException(context.getString(R.string.secure_folder_source_unreadable, video.displayName))
          }

          val expectedPlainSize = sourceFile.length()
          val destFile = File(destDir, "${UUID.randomUUID()}.dat")
          val writtenPlain =
            VaultCrypto.encryptFile(
              source = sourceFile,
              target = destFile,
              onProgress = { fileProgress ->
                checkCancellation()
                updateProgress(
                  currentFile = sourceFile.name,
                  currentFileIndex = index + 1,
                  totalFiles = videos.size,
                  currentFileProgress = fileProgress,
                  bytesProcessed = bytesDone + (expectedPlainSize * fileProgress).toLong(),
                  totalBytes = totalBytes,
                )
              },
            )

          if (!destFile.isFile ||
            writtenPlain != expectedPlainSize ||
            VaultCrypto.plaintextLength(destFile) != expectedPlainSize
          ) {
            destFile.delete()
            throw IOException(context.getString(R.string.secure_folder_verify_failed, sourceFile.name))
          }

          val mime = resolveMimeType(video.mimeType, sourceFile.name)
          val vaultEntity =
            SecureVaultMediaEntity(
              originalPath = enc(sourceFile.absolutePath),
              secureFilePath = destFile.absolutePath,
              fileName = enc(sourceFile.name),
              fileSize = expectedPlainSize,
              mimeType = enc(mime),
              dateHidden = System.currentTimeMillis(),
            )
          val entityId = dao.insert(vaultEntity)

          val sourceDeleted = sourceFile.delete()
          if (!sourceDeleted) {
            Log.w(TAG, "Failed to delete original after encrypt, rolling back: ${video.path}")
            dao.deleteById(entityId)
            destFile.delete()
            throw IOException(context.getString(R.string.storage_source_delete_failed))
          }

          removeFromMediaStore(context, video)
          RecentlyPlayedOps.onVideoDeleted(video.path)

          val oldStateKeys =
            listOf(video.path, sourceFile.absolutePath, video.uri.toString(), sourceFile.name)
              .filter { it.isNotBlank() }
              .distinct()
          playbackStateRepository.migrateState(
            oldMediaTitles = oldStateKeys,
            newMediaTitle = destFile.absolutePath,
            toSecure = true,
          )
          secureBookmarkStore.migrateMediaToVault(
            publicBookmarkDao = publicBookmarkDao,
            publicTagDao = publicTagDao,
            oldMediaIds = playbackIds(video.path, sourceFile.absolutePath, video.uri.toString()),
            targetVaultMediaId = PlaybackIdentity.forLocalPath(destFile.absolutePath),
            targetVaultPath = destFile.absolutePath,
            displayTitle = sourceFile.name,
            fileSizeBytes = expectedPlainSize,
          )
          syncRegistry()

          succeeded += video.id
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to hide ${video.path}: ${e.message}", e)
          if (firstError == null) firstError = e
          _progress.value = _progress.value.copy(error = e.localizedMessage)
          failed += video.id
        } finally {
          bytesDone += video.size
        }
      }

      MediaLibraryEvents.notifyChanged()
      _progress.value = _progress.value.copy(isComplete = true, overallProgress = 1f)

      if (succeeded.isEmpty() && failed.isNotEmpty()) {
        Result.failure(firstError ?: IOException("Failed to hide ${failed.size} file(s)"))
      } else {
        Result.success(BatchResult(succeeded, failed))
      }
    }

  private suspend fun moveInScoped(
    context: Context,
    videos: List<Video>,
  ): Result<BatchResult> {
    resetOperation()
    val pending = mutableListOf<PendingSecureMove>()
    val deletedUris = mutableSetOf<Uri>()
    val totalBytes = videos.sumOf { it.size.coerceAtLeast(0L) }.coerceAtLeast(1L)
    var bytesDone = 0L
    var operationError: Exception? = null

    fun recordFailure(video: Video, error: Exception) {
      val failure =
        IOException(
          "${video.displayName}: ${error.localizedMessage ?: error.javaClass.simpleName}",
          error,
        )
      if (operationError == null) operationError = failure
      _progress.value = _progress.value.copy(error = failure.message)
    }

    try {
      val destDir = secureDir(context)
      videos.forEachIndexed { index, video ->
        currentCoroutineContext().ensureActive()
        checkCancellation()
        var destination: File? = null
        var recorded = false
        try {
          val fileName = video.displayName.ifBlank { File(video.path).name }
          val destFile = File(destDir, "${UUID.randomUUID()}.dat")
          destination = destFile
          updateProgress(fileName, index + 1, videos.size, 0f, bytesDone, totalBytes)
          val copiedBytes =
            copyScopedSourceEncrypted(context, video, destFile) { progress ->
              updateProgress(
                fileName, index + 1, videos.size, progress,
                bytesDone + (video.size.coerceAtLeast(0L) * progress).toLong(), totalBytes,
              )
            }
          val originalPath = ScopedHiddenFolderScanner.localPathOf(video.uri) ?: video.path
          val mime = resolveMimeType(video.mimeType, fileName)
          val entity =
            SecureVaultMediaEntity(
              originalPath = enc(originalPath),
              secureFilePath = destFile.absolutePath,
              fileName = enc(fileName),
              fileSize = copiedBytes,
              mimeType = enc(mime),
              dateHidden = System.currentTimeMillis(),
            )
          withContext(NonCancellable) {
            val entityId = dao.insert(entity)
            pending +=
              PendingSecureMove(
                video = video,
                entityId = entityId,
                entity =
                  SecureMediaEntity(
                    id = entityId,
                    originalPath = originalPath,
                    secureFilePath = destFile.absolutePath,
                    fileName = fileName,
                    fileSize = copiedBytes,
                    mimeType = mime,
                    dateHidden = entity.dateHidden,
                  ),
                file = destFile,
              )
            recorded = true
          }
        } catch (error: CancellationException) {
          throw error
        } catch (error: Exception) {
          Log.e(TAG, "Failed to prepare secure copy of ${video.displayName}", error)
          recordFailure(video, error)
        } finally {
          if (!recorded && destination != null) {
            runCatching { discardPrivateCopy(destination) }
          }
          bytesDone += video.size.coerceAtLeast(0L)
        }
      }

      withContext(NonCancellable) {
        for (batch in pending.chunked(2_000)) {
          checkCancellation()
          PermissionUtils.StorageOps.deleteScopedVideosForMove(
            context,
            batch.map { it.video },
            onDeleteFailed = ::recordFailure,
          ) { video ->
            deletedUris += video.uri
          }
        }
      }
    } catch (error: CancellationException) {
      _progress.value = _progress.value.copy(isCancelled = true, error = "Operation cancelled")
      throw error
    } catch (error: Exception) {
      if (operationError == null) operationError = error
      Log.e(TAG, "Scoped secure move failed", error)
      _progress.value = _progress.value.copy(error = error.message)
    } finally {
      withContext(NonCancellable) {
        pending.forEach { move ->
          try {
            if (move.video.uri in deletedUris) {
              RecentlyPlayedOps.onVideoDeleted(move.video.path)
              playbackStateRepository.migrateState(
                oldMediaTitles =
                  listOf(move.video.path, move.entity.originalPath, move.video.uri.toString(), move.entity.fileName)
                    .filter { it.isNotBlank() }
                    .distinct(),
                newMediaTitle = move.file.absolutePath,
                toSecure = true,
              )
              secureBookmarkStore.migrateMediaToVault(
                publicBookmarkDao = publicBookmarkDao,
                publicTagDao = publicTagDao,
                oldMediaIds = playbackIds(move.video.path, move.entity.originalPath, move.video.uri.toString()),
                targetVaultMediaId = PlaybackIdentity.forLocalPath(move.file.absolutePath),
                targetVaultPath = move.file.absolutePath,
                displayTitle = move.entity.fileName,
                fileSizeBytes = move.entity.fileSize,
              )
            } else {
              dao.deleteById(move.entityId)
              discardPrivateCopy(move.file)
            }
          } catch (error: Exception) {
            Log.e(TAG, "Could not finalize secure move for ${move.video.displayName}", error)
            _progress.value = _progress.value.copy(error = error.message)
          }
        }
        syncRegistry()
      }
      MediaLibraryEvents.notifyChanged()
      _progress.value =
        _progress.value.copy(
          isComplete = true,
          overallProgress = if (_progress.value.isCancelled) _progress.value.overallProgress else 1f,
        )
    }

    val succeeded = videos.filter { it.uri in deletedUris }.map { it.id }
    val failed = videos.filterNot { it.uri in deletedUris }.map { it.id }
    return if (succeeded.isEmpty()) {
      Result.failure(operationError ?: IOException("Failed to hide ${failed.size} file(s)"))
    } else {
      Result.success(BatchResult(succeeded, failed))
    }
  }

  private suspend fun copyScopedSourceEncrypted(
    context: Context,
    video: Video,
    destination: File,
    onProgress: (Float) -> Unit,
  ): Long {
    val descriptor =
      context.contentResolver.openAssetFileDescriptor(video.uri, "r")
        ?: throw IOException("Could not open ${video.displayName}")
    return descriptor.use { source ->
      val expectedSize = source.length.takeIf { it >= 0L }
      val copiedPlain =
        source.createInputStream().use { input ->
          VaultCrypto.encryptStream(
            input = input,
            expectedSize = expectedSize ?: -1L,
            target = destination,
            onProgress = { p ->
              checkCancellation()
              onProgress(p)
            },
          )
        }
      if (!destination.isFile ||
        VaultCrypto.plaintextLength(destination) != copiedPlain ||
        (expectedSize != null && copiedPlain != expectedSize)
      ) {
        discardPrivateCopy(destination)
        throw IOException(context.getString(R.string.secure_folder_verify_failed, video.displayName))
      }
      copiedPlain
    }
  }

  suspend fun restore(
    context: Context,
    entities: List<SecureMediaEntity>,
  ): Result<BatchResult> =
    withContext(Dispatchers.IO) {
      if (entities.isEmpty()) return@withContext Result.failure(IllegalArgumentException("No files to restore"))
      VaultCrypto.ensureInitialized(context)

      resetOperation()
      val succeeded = mutableListOf<Long>()
      val failed = mutableListOf<Long>()
      var firstRestoreError: Exception? = null
      val totalBytes = entities.sumOf { it.fileSize }.coerceAtLeast(1)
      var bytesDone = 0L

      entities.forEachIndexed { index, entity ->
        try {
          checkCancellation()
          val secureFile = File(entity.secureFilePath)
          if (!secureFile.exists()) {
            Log.w(TAG, "Secure file missing, dropping DB row: ${entity.secureFilePath}")
            dao.deleteById(entity.id)
            failed += entity.id
            return@forEachIndexed
          }

          val expectedPlainSize = VaultCrypto.plaintextLength(secureFile)
          val onProgress: (Float) -> Unit = { fileProgress ->
            checkCancellation()
            updateProgress(
              currentFile = entity.fileName,
              currentFileIndex = index + 1,
              totalFiles = entities.size,
              currentFileProgress = fileProgress,
              bytesProcessed = bytesDone + (expectedPlainSize * fileProgress).toLong(),
              totalBytes = totalBytes,
            )
          }

          if (BuildConfig.SCOPED_STORAGE_ONLY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            restoreScopedFile(context, entity, secureFile, onProgress)
            succeeded += entity.id
            return@forEachIndexed
          }

          val restoreTarget = resolveRestoreTarget(entity)
          restoreTarget.parentFile?.let { if (!it.exists()) it.mkdirs() }
          val writtenPlain = VaultCrypto.decryptFile(secureFile, restoreTarget, onProgress = onProgress)

          if (!restoreTarget.exists() ||
            writtenPlain != expectedPlainSize ||
            restoreTarget.length() != expectedPlainSize
          ) {
            restoreTarget.delete()
            throw IOException(context.getString(R.string.secure_folder_restore_verify_failed, entity.fileName))
          }

          val restoredId = PlaybackIdentity.forLocalPath(restoreTarget.absolutePath)
          secureBookmarkStore.migrateMediaToPublic(
            publicBookmarkDao = publicBookmarkDao,
            publicTagDao = publicTagDao,
            vaultMediaIds = playbackIds(entity.secureFilePath, entity.originalPath),
            restoredMediaId = restoredId,
            restoredPath = restoreTarget.absolutePath,
            displayTitle = entity.fileName,
            fileSizeBytes = expectedPlainSize,
          )
          playbackStateRepository.migrateState(
            oldMediaTitles =
              (playbackIds(entity.secureFilePath) + listOf(entity.secureFilePath, entity.fileName)).distinct(),
            newMediaTitle = restoreTarget.absolutePath,
            toSecure = false,
          )
          secureVideoMetadataDao.deleteByHashes(listOf(VaultCrypto.hmacKey(entity.secureFilePath)))
          thumbnailRepository.evictSecureMemoryCache()

          if (!secureFile.delete()) {
            Log.w(TAG, "Restored but couldn't clean up secure copy: ${entity.secureFilePath}")
          }
          dao.deleteById(entity.id)
          syncRegistry()

          triggerMediaScan(context, restoreTarget.absolutePath)
          succeeded += entity.id
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to restore ${entity.fileName}: ${e.message}", e)
          if (firstRestoreError == null) firstRestoreError = e
          _progress.value = _progress.value.copy(error = e.localizedMessage)
          failed += entity.id
        } finally {
          bytesDone += entity.fileSize
        }
      }

      MediaLibraryEvents.notifyChanged()
      _progress.value = _progress.value.copy(isComplete = true, overallProgress = 1f)

      if (succeeded.isEmpty() && failed.isNotEmpty()) {
        Result.failure(firstRestoreError ?: IOException("Failed to restore ${failed.size} file(s)"))
      } else {
        Result.success(BatchResult(succeeded, failed))
      }
    }

  suspend fun deleteForever(entities: List<SecureMediaEntity>): Result<BatchResult> =
    withContext(Dispatchers.IO) {
      if (entities.isEmpty()) return@withContext Result.failure(IllegalArgumentException("No files to delete"))
      VaultCrypto.ensureInitialized(context)

      resetOperation()
      val succeeded = mutableListOf<Long>()
      val failed = mutableListOf<Long>()

      entities.forEachIndexed { index, entity ->
        try {
          checkCancellation()
          updateProgress(
            currentFile = entity.fileName,
            currentFileIndex = index + 1,
            totalFiles = entities.size,
            currentFileProgress = 0.5f,
            bytesProcessed = index.toLong(),
            totalBytes = entities.size.toLong(),
          )

          val secureFile = File(entity.secureFilePath)
          val deleted = !secureFile.exists() || secureFile.delete()

          if (deleted) {
            dao.deleteById(entity.id)
            secureBookmarkStore.deleteAllForMedia(playbackIds(entity.secureFilePath, entity.originalPath))
            playbackStateRepository.deleteByTitle(entity.secureFilePath)
            playbackStateRepository.deleteByTitle(entity.fileName)
            secureVideoMetadataDao.deleteByHashes(listOf(VaultCrypto.hmacKey(entity.secureFilePath)))
            succeeded += entity.id
          } else {
            Log.w(TAG, "Failed to delete secure file, keeping DB row: ${entity.secureFilePath}")
            failed += entity.id
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to delete ${entity.fileName}: ${e.message}", e)
          failed += entity.id
        }
      }

      thumbnailRepository.evictSecureMemoryCache()
      syncRegistry()
      _progress.value = _progress.value.copy(isComplete = true, overallProgress = 1f)

      if (succeeded.isEmpty() && failed.isNotEmpty()) {
        Result.failure(IOException("Failed to delete ${failed.size} file(s)"))
      } else {
        Result.success(BatchResult(succeeded, failed))
      }
    }

  suspend fun destroyAll(context: Context): Result<Int> =
    withContext(Dispatchers.IO) {
      VaultCrypto.ensureInitialized(context)
      val entities = getAll()
      if (entities.isNotEmpty()) {
        val batch = deleteForever(entities).getOrElse { return@withContext Result.failure(it) }
        if (batch.failedIds.isNotEmpty()) {
          return@withContext Result.failure(IOException("Failed to delete ${batch.failedIds.size} file(s)"))
        }
      }

      val dirsToClean =
        listOfNotNull(
          DurableStorageManager.vaultMediaDir(context),
          DurableStorageManager.legacyVaultDirectory(context),
          DurableStorageManager.legacyV322VaultDirectory(context),
        ).distinctBy { it.absolutePath }
      val leftoverFiles =
        dirsToClean
          .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
          .filter { it.isFile && !it.name.equals(".nomedia", ignoreCase = true) }
      val failedDeletes = leftoverFiles.count { !it.delete() }
      if (failedDeletes > 0) {
        return@withContext Result.failure(IOException("Failed to delete $failedDeletes leftover file(s)"))
      }

      dao.deleteAll()
      secureBookmarkStore.clearAll()
      playbackStateRepository.clearSecurePlaybackStates()
      secureVideoMetadataDao.clearAll()
      thumbnailRepository.clearSecureThumbnailCache()
      watchStatsRepository.clearSecure()
      DurablePreferencesSync.deleteSecureConfig(context)
      syncRegistry()
      SecureFolderSession.lockNow()
      Result.success(entities.size)
    }

  fun observeAll(): Flow<List<SecureMediaEntity>> =
    dao.observeAll().map { list -> list.map { it.toDomain() } }

  fun observeCount(): Flow<Int> = dao.observeCount()

  suspend fun getAll(): List<SecureMediaEntity> =
    withContext(Dispatchers.IO) { dao.getAll().map { it.toDomain() } }

  suspend fun getByIds(ids: List<Long>): List<SecureMediaEntity> =
    withContext(Dispatchers.IO) { dao.getByIds(ids).map { it.toDomain() } }

  private data class ScopedRestoreLocation(
    val collection: Uri,
    val relativePath: String,
  )

  @RequiresApi(Build.VERSION_CODES.Q)
  private suspend fun restoreScopedFile(
    context: Context,
    entity: SecureMediaEntity,
    secureFile: File,
    onProgress: (Float) -> Unit,
  ) {
    val resolver = context.contentResolver
    val mimeType = resolveMimeType(entity.mimeType, entity.fileName)
    val location = resolveScopedRestoreLocation(context, entity.originalPath, mimeType)
    val values =
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, entity.fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
    fun insertPending(target: ScopedRestoreLocation): Uri {
      val targetValues =
        ContentValues(values).apply {
          put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
        }
      return resolver.insert(target.collection, targetValues)
        ?: throw IOException("Could not create restored media for ${entity.fileName}")
    }

    val restoredUri =
      try {
        insertPending(location)
      } catch (error: Exception) {
        if (error !is SecurityException && error !is IllegalArgumentException && error !is IOException) throw error
        val fallback = resolveScopedRestoreLocation(context, "", mimeType)
        if (location == fallback) throw error
        Log.w(TAG, "Original restore location rejected; retrying in ${fallback.relativePath}", error)
        insertPending(fallback)
      }

    var published = false
    try {
      val expectedPlainSize = VaultCrypto.plaintextLength(secureFile)
      val copied =
        resolver.openOutputStream(restoredUri, "w")?.use { output ->
          VaultCrypto.decryptToStream(secureFile, output, onProgress = onProgress)
        } ?: throw IOException("Could not open restored media for ${entity.fileName}")
      if (copied != expectedPlainSize || scopedContentLength(context, restoredUri) != expectedPlainSize) {
        throw IOException("Restore verification failed for ${entity.fileName}")
      }
      val publishedRows =
        resolver.update(
          restoredUri,
          ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
          null,
          null,
        )
      if (publishedRows <= 0) {
        throw IOException("Could not publish restored media for ${entity.fileName}")
      }
      published = true
      withContext(NonCancellable) {
        val restoredLocalPath = ScopedHiddenFolderScanner.localPathOf(restoredUri)
        val targetPathOrUri = restoredLocalPath ?: restoredUri.toString()
        val restoredId = PlaybackIdentity.forLocalPath(targetPathOrUri)
        secureBookmarkStore.migrateMediaToPublic(
          publicBookmarkDao = publicBookmarkDao,
          publicTagDao = publicTagDao,
          vaultMediaIds = playbackIds(entity.secureFilePath, entity.originalPath),
          restoredMediaId = restoredId,
          restoredPath = targetPathOrUri,
          displayTitle = entity.fileName,
          fileSizeBytes = expectedPlainSize,
        )
        playbackStateRepository.migrateState(
          oldMediaTitles =
            (playbackIds(entity.secureFilePath) + listOf(entity.secureFilePath, entity.fileName)).distinct(),
          newMediaTitle = targetPathOrUri,
          toSecure = false,
        )
        secureVideoMetadataDao.deleteByHashes(listOf(VaultCrypto.hmacKey(entity.secureFilePath)))
        dao.deleteById(entity.id)
        syncRegistry()
        if (!secureFile.delete()) {
          Log.w(TAG, "Restored but couldn't clean up secure copy: ${entity.secureFilePath}")
        }
      }
    } catch (error: Exception) {
      if (!published) {
        withContext(NonCancellable) {
          runCatching { resolver.delete(restoredUri, null, null) }
            .onFailure { error.addSuppressed(it) }
        }
      }
      throw error
    }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun resolveScopedRestoreLocation(
    context: Context,
    originalPath: String,
    mimeType: String,
  ): ScopedRestoreLocation {
    val audio = mimeType.startsWith("audio/")
    val video = mimeType.startsWith("video/")
    val defaultDirectory =
      when {
        audio -> Environment.DIRECTORY_MUSIC
        video -> Environment.DIRECTORY_MOVIES
        else -> Environment.DIRECTORY_DOWNLOADS
      }
    fun collection(volume: String, downloads: Boolean): Uri =
      when {
        downloads -> MediaStore.Downloads.getContentUri(volume)
        audio -> MediaStore.Audio.Media.getContentUri(volume)
        else -> MediaStore.Video.Media.getContentUri(volume)
      }

    val localPath =
      if (originalPath.startsWith("content://")) {
        ScopedHiddenFolderScanner.localPathOf(Uri.parse(originalPath))
      } else {
        originalPath
      }
    val originalParent = localPath?.let { File(it).parent }
    val audioDirectories =
      setOf(
        Environment.DIRECTORY_MUSIC,
        Environment.DIRECTORY_PODCASTS,
        Environment.DIRECTORY_RINGTONES,
        Environment.DIRECTORY_ALARMS,
        Environment.DIRECTORY_NOTIFICATIONS,
        Environment.DIRECTORY_AUDIOBOOKS,
      ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setOf(Environment.DIRECTORY_RECORDINGS) else emptySet()

    for (volume in MediaStore.getExternalVolumeNames(context)) {
      val root =
        if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
          Environment.getExternalStorageDirectory().absolutePath
        } else {
          "/storage/$volume"
        }
      if (originalParent == null || !originalParent.startsWith("$root/", ignoreCase = true)) continue
      val relativePath = originalParent.substring(root.length + 1).trimEnd('/')
      val segments = relativePath.split('/')
      if (segments.any { it.isBlank() || it == ".." || it.startsWith(".") }) continue
      val topDirectory = segments.first()
      val downloads = topDirectory == Environment.DIRECTORY_DOWNLOADS
      val supported =
        downloads ||
          (audio && topDirectory in audioDirectories) ||
          (video &&
            topDirectory in
            setOf(
              Environment.DIRECTORY_MOVIES,
              Environment.DIRECTORY_DCIM,
              Environment.DIRECTORY_PICTURES,
            ))
      if (supported) return ScopedRestoreLocation(collection(volume, downloads), "$relativePath/")
    }

    val fallbackPath = "$defaultDirectory/Restored/"
    Log.i(TAG, "Original restore directory is unavailable or unsupported; using $fallbackPath")
    return ScopedRestoreLocation(
      collection(MediaStore.VOLUME_EXTERNAL_PRIMARY, defaultDirectory == Environment.DIRECTORY_DOWNLOADS),
      fallbackPath,
    )
  }

  private fun resolveMimeType(mimeType: String, fileName: String): String =
    if (mimeType.isBlank() || mimeType.endsWith("/*") || mimeType == "application/octet-stream") {
      FileTypeUtils.getMimeTypeFromExtension(File(fileName).extension)
    } else {
      mimeType
    }

  private suspend fun scopedContentLength(context: Context, uri: Uri): Long {
    val descriptor =
      context.contentResolver.openAssetFileDescriptor(uri, "r")
        ?: throw IOException("Could not verify restored media: $uri")
    return descriptor.use { source ->
      if (source.length >= 0L) {
        source.length
      } else {
        source.createInputStream().use { input ->
          val buffer = ByteArray(BUFFER_SIZE)
          var length = 0L
          var read: Int
          while (input.read(buffer).also { read = it } != -1) {
            currentCoroutineContext().ensureActive()
            checkCancellation()
            length += read
          }
          length
        }
      }
    }
  }

  private fun discardPrivateCopy(file: File) {
    if (file.exists() && !file.delete()) throw IOException("Could not remove unfinished secure copy: ${file.name}")
  }

  private fun resolveRestoreTarget(entity: SecureMediaEntity): File {
    val originalUri = Uri.parse(entity.originalPath)
    val originalLocalPath =
      when (originalUri.scheme) {
        "content" -> ScopedHiddenFolderScanner.localPathOf(originalUri)
        "file" -> originalUri.path
        null -> entity.originalPath
        else -> null
      }
    val originalParent =
      originalLocalPath
        ?.let { File(it) }
        ?.takeIf { it.isAbsolute }
        ?.parentFile
    if (originalParent != null &&
      (originalParent.isDirectory || originalParent.mkdirs()) &&
      originalParent.canWrite()
    ) {
      return uniqueFileIn(originalParent, entity.fileName)
    }

    val mimeType = resolveMimeType(entity.mimeType, entity.fileName)
    val directory =
      when {
        mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
        mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
        else -> Environment.DIRECTORY_DOWNLOADS
      }
    val fallback = File(Environment.getExternalStoragePublicDirectory(directory), "Restored")
    Log.i(TAG, "Original restore folder is unavailable or not writable; using ${fallback.absolutePath}")
    return uniqueFileIn(fallback, entity.fileName)
  }

  private fun uniqueFileIn(
    dir: File,
    fileName: String,
  ): File {
    require(
      fileName.isNotBlank() &&
        File(fileName).name == fileName &&
        fileName != "." &&
        fileName != ".." &&
        '\u0000' !in fileName,
    ) {
      "Invalid filename: $fileName"
    }
    dir.mkdirs()
    var candidate = File(dir, fileName)
    if (!candidate.exists()) return candidate

    val nameWithoutExt = candidate.nameWithoutExtension
    val ext = candidate.extension
    for (counter in 1..MAX_FILENAME_ATTEMPTS) {
      val newName = if (ext.isNotEmpty()) "${nameWithoutExt}_$counter.$ext" else "${nameWithoutExt}_$counter"
      candidate = File(dir, newName)
      if (!candidate.exists()) return candidate
    }
    throw IOException("Could not generate unique filename for $fileName")
  }

  private fun removeFromMediaStore(
    context: Context,
    video: Video,
  ) {
    runCatching {
      context.contentResolver.delete(video.uri, null, null)
    }.onFailure {
      Log.d(TAG, "MediaStore entry cleanup skipped for ${video.displayName}: ${it.message}")
    }
  }

  private fun triggerMediaScan(
    context: Context,
    path: String,
  ) {
    runCatching {
      android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }
  }

  private fun updateProgress(
    currentFile: String,
    currentFileIndex: Int,
    totalFiles: Int,
    currentFileProgress: Float,
    bytesProcessed: Long,
    totalBytes: Long,
  ) {
    val overall = if (totalBytes > 0) bytesProcessed.toFloat() / totalBytes else 0f
    _progress.value =
      _progress.value.copy(
        currentFile = currentFile,
        currentFileIndex = currentFileIndex,
        totalFiles = totalFiles,
        currentFileProgress = currentFileProgress,
        overallProgress = overall.coerceIn(0f, 1f),
      )
  }

  private fun checkCancellation() {
    if (isCancelled.get()) {
      _progress.value = _progress.value.copy(isCancelled = true, error = "Operation cancelled")
      throw IOException("Operation cancelled by user")
    }
  }
}
