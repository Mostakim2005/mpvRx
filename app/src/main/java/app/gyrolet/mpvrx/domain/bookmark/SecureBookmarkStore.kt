/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.bookmark

import android.content.Context
import android.net.Uri
import app.gyrolet.mpvrx.database.SecureVaultDatabase
import app.gyrolet.mpvrx.database.dao.BookmarkGalleryRow
import app.gyrolet.mpvrx.database.dao.BookmarkGroupSummary
import app.gyrolet.mpvrx.database.dao.BookmarkTagDao
import app.gyrolet.mpvrx.database.dao.BookmarkTagPair
import app.gyrolet.mpvrx.database.dao.PlaybackBookmarkDao
import app.gyrolet.mpvrx.database.entities.BookmarkGroupEntity
import app.gyrolet.mpvrx.database.entities.BookmarkMediaLinkEntity
import app.gyrolet.mpvrx.database.entities.BookmarkMediaRefEntity
import app.gyrolet.mpvrx.database.entities.BookmarkTagEntity
import app.gyrolet.mpvrx.database.entities.BookmarkTagMemoryEntity
import app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkGroupEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkMediaLinkEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkMediaRefEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkTagEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkTagMemoryEntity
import app.gyrolet.mpvrx.domain.secure.SecureMediaRegistry
import app.gyrolet.mpvrx.domain.secure.VaultCrypto
import app.gyrolet.mpvrx.domain.storage.DurableStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Encrypted, isolated store for Secure Vault bookmarks, tags, groups, presets, and media refs
 * backed by [SecureVaultDatabase] (`Documents/mpvRx/.vault/v_idx.dat`).
 */
class SecureBookmarkStore(
  private val database: SecureVaultDatabase,
  private val context: Context,
) {
  private val bookmarkDao get() = database.secureBookmarkDao()
  private val tagDao get() = database.secureBookmarkTagDao()

  private fun enc(value: String): String {
    VaultCrypto.ensureInitialized(context)
    return if (value.isEmpty()) "" else VaultCrypto.encryptString(value)
  }

  private fun dec(value: String): String {
    VaultCrypto.ensureInitialized(context)
    return if (value.isEmpty()) "" else VaultCrypto.decryptString(value)
  }

  private fun encNullable(value: String?): String? =
    value?.takeIf { it.isNotEmpty() }?.let(::enc)

  private fun decNullable(value: String?): String? =
    value?.takeIf { it.isNotEmpty() }?.let(::dec)

  private fun SecureBookmarkEntity.toDomain(): PlaybackBookmarkEntity =
    PlaybackBookmarkEntity(
      id = id,
      mediaId = mediaId,
      positionMs = positionMs,
      title = dec(title),
      createdAt = createdAt,
      bookTrackId = bookTrackId,
      thumbnailPath = decNullable(thumbnailPath),
    )

  private fun PlaybackBookmarkEntity.toSecure(): SecureBookmarkEntity =
    SecureBookmarkEntity(
      id = id,
      mediaId = mediaId,
      positionMs = positionMs,
      title = enc(title),
      createdAt = createdAt,
      bookTrackId = bookTrackId,
      thumbnailPath = encNullable(thumbnailPath),
    )

  private fun SecureBookmarkGroupEntity.toDomain(): BookmarkGroupEntity =
    BookmarkGroupEntity(
      groupKey = groupKey,
      presetCode = decNullable(presetCode),
      fingerprint = fingerprint,
      stemKey = decNullable(stemKey),
      durationSec = durationSec,
      width = width,
      height = height,
      fileSizeBytes = fileSizeBytes,
      sourceLabel = dec(sourceLabel),
      updatedAt = updatedAt,
    )

  private fun BookmarkGroupEntity.toSecure(): SecureBookmarkGroupEntity =
    SecureBookmarkGroupEntity(
      groupKey = groupKey,
      presetCode = encNullable(presetCode),
      fingerprint = fingerprint,
      stemKey = encNullable(stemKey),
      durationSec = durationSec,
      width = width,
      height = height,
      fileSizeBytes = fileSizeBytes,
      sourceLabel = enc(sourceLabel),
      updatedAt = updatedAt,
    )

  private fun SecureBookmarkMediaRefEntity.toDomain(): BookmarkMediaRefEntity =
    BookmarkMediaRefEntity(
      mediaId = mediaId,
      playableUri = dec(playableUri),
      title = decNullable(title),
      artworkUri = decNullable(artworkUri),
      updatedAt = updatedAt,
    )

  private fun BookmarkMediaRefEntity.toSecure(): SecureBookmarkMediaRefEntity =
    SecureBookmarkMediaRefEntity(
      mediaId = mediaId,
      playableUri = enc(playableUri),
      title = encNullable(title),
      artworkUri = encNullable(artworkUri),
      updatedAt = updatedAt,
    )

  private fun SecureBookmarkMediaLinkEntity.toDomain(): BookmarkMediaLinkEntity =
    BookmarkMediaLinkEntity(
      mediaId = mediaId,
      groupKey = groupKey,
      updatedAt = updatedAt,
    )

  private fun BookmarkGroupSummary.decrypted(): BookmarkGroupSummary =
    copy(
      presetCode = decNullable(presetCode),
      stemKey = decNullable(stemKey),
      sourceLabel = dec(sourceLabel),
    )

  fun observe(mediaId: String): Flow<List<PlaybackBookmarkEntity>> =
    bookmarkDao.observe(mediaId).map { list -> list.map { it.toDomain() } }

  suspend fun getInGroup(mediaId: String): List<PlaybackBookmarkEntity> =
    withContext(Dispatchers.IO) {
      bookmarkDao.getInGroup(mediaId).map { it.toDomain() }
    }

  suspend fun countInGroup(mediaId: String): Int =
    withContext(Dispatchers.IO) {
      bookmarkDao.countInGroup(mediaId)
    }

  suspend fun countByThumbnailPath(thumbnailPath: String): Int =
    withContext(Dispatchers.IO) {
      // Encrypted thumbnailPath strings use a random IV per row, so compare decrypted values.
      bookmarkDao.getAll().count { decNullable(it.thumbnailPath) == thumbnailPath }
    }

  suspend fun add(bookmark: PlaybackBookmarkEntity): Long =
    withContext(Dispatchers.IO) {
      bookmarkDao.add(bookmark.toSecure())
    }

  suspend fun addAll(bookmarks: List<PlaybackBookmarkEntity>): List<Long> =
    withContext(Dispatchers.IO) {
      if (bookmarks.isEmpty()) emptyList() else bookmarkDao.addAll(bookmarks.map { it.toSecure() })
    }

  suspend fun rename(id: Long, title: String) =
    withContext(Dispatchers.IO) {
      bookmarkDao.rename(id, enc(title))
    }

  suspend fun updateThumbnail(id: Long, thumbnailPath: String?): Int =
    withContext(Dispatchers.IO) {
      bookmarkDao.updateThumbnail(id, encNullable(thumbnailPath))
    }

  suspend fun findLinkedGroupKey(mediaId: String): String? =
    withContext(Dispatchers.IO) {
      bookmarkDao.findLinkedGroupKey(mediaId)
    }

  fun observeLinkedGroupKey(mediaId: String): Flow<String?> =
    bookmarkDao.observeLinkedGroupKey(mediaId)

  fun observeAllLinks(): Flow<List<BookmarkMediaLinkEntity>> =
    bookmarkDao.observeAllLinks().map { list -> list.map { it.toDomain() } }

  suspend fun upsertMediaLink(link: BookmarkMediaLinkEntity) =
    withContext(Dispatchers.IO) {
      bookmarkDao.upsertMediaLink(
        SecureBookmarkMediaLinkEntity(
          mediaId = link.mediaId,
          groupKey = link.groupKey,
          updatedAt = link.updatedAt,
        ),
      )
    }

  suspend fun getGroup(groupKey: String): BookmarkGroupEntity? =
    withContext(Dispatchers.IO) {
      bookmarkDao.getGroup(groupKey)?.toDomain()
    }

  fun observeGroup(groupKey: String): Flow<BookmarkGroupEntity?> =
    bookmarkDao.observeGroup(groupKey).map { it?.toDomain() }

  fun observePresetSummaries(): Flow<List<BookmarkGroupSummary>> =
    bookmarkDao.observePresetSummaries().map { list -> list.map { it.decrypted() } }

  suspend fun resolveGroupKey(
    rawMediaId: String,
    fingerprint: String?,
    stemKey: String?,
    durationSec: Int,
  ): String =
    withContext(Dispatchers.IO) {
      bookmarkDao.findLinkedGroupKey(rawMediaId)?.let { return@withContext it }
      if (bookmarkDao.countInGroup(rawMediaId) > 0) return@withContext rawMediaId

      val groups = bookmarkDao.getAllGroups().map { it.toDomain() }
      fingerprint?.let { fp ->
        groups
          .filter { it.fingerprint == fp }
          .maxByOrNull { it.updatedAt }
          ?.let { matched ->
            bookmarkDao.upsertMediaLink(SecureBookmarkMediaLinkEntity(mediaId = rawMediaId, groupKey = matched.groupKey))
            return@withContext matched.groupKey
          }
      }

      if (!stemKey.isNullOrBlank() && durationSec > 0) {
        groups
          .filter { g ->
            g.stemKey == stemKey && (g.durationSec <= 0 || abs(g.durationSec - durationSec) <= 3)
          }.maxByOrNull { it.updatedAt }
          ?.let { matched ->
            bookmarkDao.upsertMediaLink(SecureBookmarkMediaLinkEntity(mediaId = rawMediaId, groupKey = matched.groupKey))
            return@withContext matched.groupKey
          }
      }

      rawMediaId
    }

  suspend fun ensureGroupMetadata(
    groupKey: String,
    metadata: BookmarkMediaMetadata?,
    forceLinkRawMediaId: Boolean = false,
  ) = withContext(Dispatchers.IO) {
    val existing = getGroup(groupKey)
    val now = System.currentTimeMillis()
    if (metadata == null) {
      if (existing == null) {
        bookmarkDao.upsertGroup(BookmarkGroupEntity(groupKey = groupKey, updatedAt = now).toSecure())
      }
      return@withContext
    }

    val updated =
      BookmarkGroupEntity(
        groupKey = groupKey,
        presetCode = existing?.presetCode,
        fingerprint = metadata.fingerprint ?: existing?.fingerprint,
        stemKey = metadata.stemKey ?: existing?.stemKey,
        durationSec = metadata.durationSec.takeIf { it > 0 } ?: existing?.durationSec ?: 0,
        width = metadata.width.takeIf { it > 0 } ?: existing?.width ?: 0,
        height = metadata.height.takeIf { it > 0 } ?: existing?.height ?: 0,
        fileSizeBytes = metadata.fileSizeBytes.takeIf { it > 0L } ?: existing?.fileSizeBytes ?: 0L,
        sourceLabel = metadata.sourceLabel.takeIf { it.isNotBlank() } ?: existing?.sourceLabel.orEmpty(),
        updatedAt = now,
      )
    bookmarkDao.upsertGroup(updated.toSecure())

    if (forceLinkRawMediaId || bookmarkDao.findLinkedGroupKey(metadata.rawMediaId) == null) {
      bookmarkDao.upsertMediaLink(
        SecureBookmarkMediaLinkEntity(
          mediaId = metadata.rawMediaId,
          groupKey = groupKey,
          updatedAt = now,
        ),
      )
    }
  }

  suspend fun findGroupSummaryByPresetCode(
    rawCode: String,
    excludeGroupKey: String?,
  ): BookmarkGroupSummary? =
    withContext(Dispatchers.IO) {
      val normalized = BookmarkMediaMatcher.normalizePresetCode(rawCode)
      if (normalized.isBlank()) return@withContext null
      val found =
        bookmarkDao.getAllGroups()
          .map { it.toDomain() }
          .filter { g ->
            g.groupKey != excludeGroupKey &&
              !g.presetCode.isNullOrBlank() &&
              g.presetCode.equals(normalized, ignoreCase = true)
          }.maxByOrNull { it.updatedAt }
          ?: return@withContext null
      val count = bookmarkDao.countInGroup(found.groupKey)
      if (count <= 0) return@withContext null
      BookmarkGroupSummary(
        groupKey = found.groupKey,
        presetCode = found.presetCode,
        fingerprint = found.fingerprint,
        stemKey = found.stemKey,
        durationSec = found.durationSec,
        width = found.width,
        height = found.height,
        fileSizeBytes = found.fileSizeBytes,
        sourceLabel = found.sourceLabel,
        updatedAt = found.updatedAt,
        bookmarkCount = count,
      )
    }

  suspend fun assignPresetCode(
    groupKey: String,
    rawCode: String,
    metadata: BookmarkMediaMetadata?,
  ): String? =
    withContext(Dispatchers.IO) {
      val codeToSave = BookmarkMediaMatcher.normalizePresetCode(rawCode).takeIf { it.isNotBlank() }
      ensureGroupMetadata(groupKey, metadata, forceLinkRawMediaId = true)
      bookmarkDao.updatePresetCode(groupKey = groupKey, presetCode = encNullable(codeToSave))
      codeToSave
    }

  suspend fun loadGroupIntoCurrent(
    rawMediaId: String,
    currentGroupKey: String,
    sourceGroupKey: String,
    metadata: BookmarkMediaMetadata?,
  ): BookmarkGroupLoadResult =
    withContext(Dispatchers.IO) {
      if (sourceGroupKey == currentGroupKey) return@withContext BookmarkGroupLoadResult.AlreadyActive
      val currentCount = bookmarkDao.countInGroup(currentGroupKey)
      if (currentCount == 0) {
        bookmarkDao.upsertMediaLink(
          SecureBookmarkMediaLinkEntity(mediaId = rawMediaId, groupKey = sourceGroupKey),
        )
        if (metadata != null) {
          ensureGroupMetadata(sourceGroupKey, metadata, forceLinkRawMediaId = true)
        }
        return@withContext BookmarkGroupLoadResult.Linked(
          groupKey = sourceGroupKey,
          count = bookmarkDao.countInGroup(sourceGroupKey),
        )
      }

      val existingKeys =
        getInGroup(currentGroupKey)
          .mapTo(HashSet()) { it.positionMs to it.title.trim() }
      val incoming = getInGroup(sourceGroupKey)
      val toInsert =
        incoming.mapNotNull { entry ->
          val key = entry.positionMs to entry.title.trim()
          if (key in existingKeys) {
            null
          } else {
            existingKeys += key
            entry.copy(id = 0L, mediaId = currentGroupKey, bookTrackId = null)
          }
        }
      if (toInsert.isNotEmpty()) {
        addAll(toInsert)
      }
      ensureGroupMetadata(currentGroupKey, metadata, forceLinkRawMediaId = true)
      BookmarkGroupLoadResult.Merged(groupKey = currentGroupKey, addedCount = toInsert.size)
    }

  suspend fun importArchiveIntoCurrent(
    uri: Uri,
    groupKey: String,
    metadata: BookmarkMediaMetadata?,
  ): Result<Int> =
    withContext(Dispatchers.IO) {
      val imported =
        BookmarkPresetArchive.importFromUri(context, uri, groupKey, isSecure = true).getOrElse { error ->
          return@withContext Result.failure(error)
        }
      val existingKeys =
        getInGroup(groupKey)
          .mapTo(HashSet()) { it.positionMs to it.title.trim() }
      val toInsert =
        imported.bookmarks.filter { entry ->
          val key = entry.positionMs to entry.title.trim()
          if (key in existingKeys) false else existingKeys.add(key)
        }
      if (toInsert.isNotEmpty()) {
        addAll(toInsert)
      }
      ensureGroupMetadata(groupKey, metadata, forceLinkRawMediaId = true)
      val existingGroup = getGroup(groupKey)
      val importedCode = imported.manifest.presetCode?.trim()?.takeIf { it.isNotBlank() }
      if (existingGroup?.presetCode.isNullOrBlank() && importedCode != null) {
        bookmarkDao.updatePresetCode(groupKey = groupKey, presetCode = enc(importedCode))
      }
      Result.success(toInsert.size)
    }

  suspend fun deleteBookmarkSafely(bookmark: PlaybackBookmarkEntity) =
    withContext(Dispatchers.IO) {
      bookmarkDao.delete(bookmark.id)
      tagDao.clearForBookmark(bookmark.id)
      val thumbPath = bookmark.thumbnailPath
      if (!thumbPath.isNullOrBlank() && countByThumbnailPath(thumbPath) == 0) {
        BookmarkThumbnailStore.deleteThumbnail(context, thumbPath, isSecure = true)
      }
    }

  suspend fun tagsFor(bookmarkId: Long): List<BookmarkTagEntity> =
    withContext(Dispatchers.IO) {
      tagDao.tagsFor(bookmarkId).map { entity ->
        BookmarkTagEntity(
          id = entity.id,
          bookmarkId = entity.bookmarkId,
          name = dec(entity.name),
          displayName = dec(entity.displayName),
          createdAt = entity.createdAt,
        )
      }
    }

  fun observeAllPairs(): Flow<List<BookmarkTagPair>> =
    tagDao.observeAllTags().map { tags ->
      tags.map { tag -> BookmarkTagPair(bookmarkId = tag.bookmarkId, name = dec(tag.name)) }
    }

  suspend fun replaceTags(
    bookmarkId: Long,
    normalizedTags: List<String>,
  ) = withContext(Dispatchers.IO) {
    VaultCrypto.ensureInitialized(context)
    val existingNames = tagsFor(bookmarkId).mapTo(HashSet()) { it.name }
    tagDao.clearForBookmark(bookmarkId)
    val now = System.currentTimeMillis()
    if (normalizedTags.isNotEmpty()) {
      tagDao.insertAll(
        normalizedTags.mapNotNull { raw ->
          val norm = BookmarkTagRules.normalize(raw)
          val hash = VaultCrypto.hmacKey(norm)
          if (norm.isBlank() || hash.isBlank()) null
          else {
            SecureBookmarkTagEntity(
              bookmarkId = bookmarkId,
              nameHash = hash,
              name = enc(norm),
              displayName = enc(norm),
              createdAt = now,
            )
          }
        },
      )
    }
    normalizedTags.forEach { raw ->
      val norm = BookmarkTagRules.normalize(raw)
      val hash = VaultCrypto.hmacKey(norm)
      if (norm.isBlank() || hash.isBlank()) return@forEach
      val currentMem = tagDao.getMemory(hash)
      if (currentMem == null) {
        tagDao.upsertMemory(
          SecureBookmarkTagMemoryEntity(
            nameHash = hash,
            name = enc(norm),
            displayName = enc(norm),
            useCount = 1,
            lastUsedAt = now,
          ),
        )
      } else {
        val nextCount = if (norm in existingNames) currentMem.useCount + 1 else currentMem.useCount + 1
        tagDao.upsertMemory(
          currentMem.copy(
            displayName = enc(norm),
            useCount = nextCount,
            lastUsedAt = now,
          ),
        )
      }
    }
  }

  fun observeMemory(): Flow<List<BookmarkTagMemoryEntity>> =
    tagDao.observeMemory().map { list ->
      list.map { entry ->
        BookmarkTagMemoryEntity(
          name = dec(entry.name),
          displayName = dec(entry.displayName),
          useCount = entry.useCount,
          lastUsedAt = entry.lastUsedAt,
        )
      }
    }

  fun observeFilterTags(): Flow<List<String>> =
    combine(observeAllPairs(), observeMemory()) { pairs, memory ->
      val live = pairs.map { it.name }
      (live + memory.map { it.displayName.ifBlank { it.name } }).distinct().sorted()
    }

  suspend fun upsertMediaRef(ref: BookmarkMediaRefEntity) =
    withContext(Dispatchers.IO) {
      tagDao.upsertMediaRef(ref.toSecure())
    }

  suspend fun mediaRef(mediaId: String): BookmarkMediaRefEntity? =
    withContext(Dispatchers.IO) {
      tagDao.mediaRef(mediaId)?.toDomain()
    }

  fun observeMediaRefs(): Flow<List<BookmarkMediaRefEntity>> =
    tagDao.observeMediaRefs().map { list -> list.map { it.toDomain() } }

  fun observeGalleryItems(): Flow<List<BookmarkGalleryItem>> {
    val rowsFlow =
      bookmarkDao.observeGallery().map { list ->
        list.map { row ->
          val domain = row.toDomain()
          BookmarkGalleryRow(
            id = domain.id,
            mediaId = domain.mediaId,
            positionMs = domain.positionMs,
            title = domain.title,
            createdAt = domain.createdAt,
            thumbnailPath = domain.thumbnailPath,
          )
        }
      }
    return combine(rowsFlow, observeAllPairs(), observeMediaRefs()) { rows, pairs, refs ->
      val tagsByBookmark = pairs.groupBy({ it.bookmarkId }, { it.name })
      val refsByMedia = refs.associateBy { it.mediaId }
      rows.map { row ->
        val ref = refsByMedia[row.mediaId]
        BookmarkGalleryItem(
          row = row,
          tags = tagsByBookmark[row.id].orEmpty().sorted(),
          playableUri = ref?.playableUri,
          mediaTitle = ref?.title,
        )
      }
    }.distinctUntilChanged()
  }

  /**
   * Moves bookmarks, tags, groups, links, refs, and thumbnails for a media file from public
   * [MpvRxDatabase] into the encrypted [SecureVaultDatabase] when hidden into the Secure Folder
   * or during migration.
   */
  suspend fun migrateMediaToVault(
    publicBookmarkDao: PlaybackBookmarkDao,
    publicTagDao: BookmarkTagDao,
    oldMediaIds: List<String>,
    targetVaultMediaId: String,
    targetVaultPath: String,
    displayTitle: String,
    fileSizeBytes: Long,
  ) = withContext(Dispatchers.IO) {
    VaultCrypto.ensureInitialized(context)
    val cleanOldIds = oldMediaIds.filter { it.isNotBlank() }.toSet()
    val allPublicLinks = publicBookmarkDao.getAllLinks()
    val allPublicBookmarks = publicBookmarkDao.getAllBookmarks()

    val candidateGroupKeys = HashSet<String>()
    allPublicLinks.forEach { link ->
      if (link.mediaId in cleanOldIds) candidateGroupKeys += link.groupKey
    }
    cleanOldIds.forEach { id ->
      if (allPublicBookmarks.any { it.mediaId == id }) candidateGroupKeys += id
    }
    val allTargetMediaIds = cleanOldIds + candidateGroupKeys

    val exclusiveGroupKeys =
      candidateGroupKeys.filterTo(HashSet()) { gk ->
        allPublicLinks.none { link -> link.groupKey == gk && link.mediaId !in allTargetMediaIds }
      }
    val movingBookmarks = allPublicBookmarks.filter { it.mediaId in allTargetMediaIds }

    val vaultIdentities = SecureMediaRegistry.identitiesForPath(targetVaultPath).ifEmpty { listOf(targetVaultMediaId) }
    val canonicalId = vaultIdentities.firstOrNull() ?: targetVaultMediaId

    if (movingBookmarks.isNotEmpty()) {
      val existingSecure = getInGroup(canonicalId).mapTo(HashSet()) { it.positionMs to it.title.trim() }
      movingBookmarks.forEach { oldBm ->
        val key = oldBm.positionMs to oldBm.title.trim()
        val oldTags = publicTagDao.tagsFor(oldBm.id)
        if (key !in existingSecure) {
          existingSecure += key
          val newThumbPath =
            BookmarkThumbnailStore.migrateThumbnailFile(
              context = context,
              existingPath = oldBm.thumbnailPath,
              toSecure = true,
            )
          val newBookmark =
            PlaybackBookmarkEntity(
              id = 0L,
              mediaId = canonicalId,
              positionMs = oldBm.positionMs,
              title = oldBm.title,
              createdAt = oldBm.createdAt,
              bookTrackId = oldBm.bookTrackId,
              thumbnailPath = newThumbPath,
            )
          val newId = add(newBookmark)
          if (oldTags.isNotEmpty()) {
            replaceTags(newId, oldTags.map { it.displayName.ifBlank { it.name } })
          }
        }
      }

      // Copy group metadata into vault
      val sourceGroup =
        candidateGroupKeys.firstNotNullOfOrNull { publicBookmarkDao.getGroup(it) }
      val meta =
        BookmarkMediaMetadata(
          rawMediaId = canonicalId,
          fileSizeBytes = fileSizeBytes,
          width = sourceGroup?.width ?: 0,
          height = sourceGroup?.height ?: 0,
          durationSec = sourceGroup?.durationSec ?: 0,
          sourceLabel = BookmarkMediaMatcher.displaySourceLabel(displayTitle, targetVaultPath),
          stemKey = BookmarkMediaMatcher.normalizedStem(displayTitle) ?: sourceGroup?.stemKey,
        )
      ensureGroupMetadata(canonicalId, meta, forceLinkRawMediaId = true)
      if (!sourceGroup?.presetCode.isNullOrBlank() && getGroup(canonicalId)?.presetCode.isNullOrBlank()) {
        bookmarkDao.updatePresetCode(canonicalId, encNullable(sourceGroup?.presetCode))
      }

      val now = System.currentTimeMillis()
      vaultIdentities.forEach { id ->
        upsertMediaLink(BookmarkMediaLinkEntity(mediaId = id, groupKey = canonicalId, updatedAt = now))
      }
      upsertMediaRef(
        BookmarkMediaRefEntity(
          mediaId = canonicalId,
          playableUri = targetVaultPath,
          title = displayTitle,
          updatedAt = now,
        ),
      )

      // Delete migrated public rows only where exclusive to moved media
      val idsToDeleteFromPublic =
        movingBookmarks.filter { it.mediaId in cleanOldIds || it.mediaId in exclusiveGroupKeys }.map { it.id }
      if (idsToDeleteFromPublic.isNotEmpty()) {
        publicBookmarkDao.deleteByIds(idsToDeleteFromPublic)
        idsToDeleteFromPublic.forEach { publicTagDao.clearForBookmark(it) }
      }
      cleanOldIds.forEach { publicBookmarkDao.deleteMediaLink(it) }
      exclusiveGroupKeys.forEach { gk ->
        if (publicBookmarkDao.countInGroup(gk) == 0 && publicBookmarkDao.countLinksForGroup(gk) == 0) {
          publicBookmarkDao.deleteGroup(gk)
        }
        publicTagDao.deleteMediaRef(gk)
      }
      cleanOldIds.forEach { publicTagDao.deleteMediaRef(it) }

      movingBookmarks.forEach { oldBm ->
        val oldThumb = oldBm.thumbnailPath
        if (!oldThumb.isNullOrBlank() && publicBookmarkDao.countByThumbnailPath(oldThumb) == 0) {
          BookmarkThumbnailStore.deleteThumbnail(context, oldThumb, isSecure = false)
        }
      }
    } else {
      // Even without bookmarks, clean up any stale public media refs/links pointing at moved ids
      cleanOldIds.forEach { id ->
        publicBookmarkDao.deleteMediaLink(id)
        publicTagDao.deleteMediaRef(id)
      }
    }
  }

  /**
   * Moves bookmarks, tags, groups, links, refs, and thumbnails back out of [SecureVaultDatabase]
   * into [MpvRxDatabase] when restoring a video from the Secure Folder.
   */
  suspend fun migrateMediaToPublic(
    publicBookmarkDao: PlaybackBookmarkDao,
    publicTagDao: BookmarkTagDao,
    vaultMediaIds: List<String>,
    restoredMediaId: String,
    restoredPath: String,
    displayTitle: String,
    fileSizeBytes: Long,
  ) = withContext(Dispatchers.IO) {
    VaultCrypto.ensureInitialized(context)
    val cleanVaultIds = vaultMediaIds.filter { it.isNotBlank() }.toSet()
    val allSecureLinks = bookmarkDao.getAllLinks()
    val allSecureBookmarks = bookmarkDao.getAll().map { it.toDomain() }

    val candidateGroupKeys = HashSet<String>()
    allSecureLinks.forEach { link ->
      if (link.mediaId in cleanVaultIds) candidateGroupKeys += link.groupKey
    }
    cleanVaultIds.forEach { id ->
      if (allSecureBookmarks.any { it.mediaId == id }) candidateGroupKeys += id
    }
    val allVaultKeys = cleanVaultIds + candidateGroupKeys
    val movingBookmarks = allSecureBookmarks.filter { it.mediaId in allVaultKeys }

    if (movingBookmarks.isNotEmpty()) {
      val existingPublic = publicBookmarkDao.getInGroup(restoredMediaId).mapTo(HashSet()) { it.positionMs to it.title.trim() }
      movingBookmarks.forEach { secBm ->
        val key = secBm.positionMs to secBm.title.trim()
        val secTags = tagsFor(secBm.id)
        if (key !in existingPublic) {
          existingPublic += key
          val newThumbPath =
            BookmarkThumbnailStore.migrateThumbnailFile(
              context = context,
              existingPath = secBm.thumbnailPath,
              toSecure = false,
            )
          val restoredBookmark =
            PlaybackBookmarkEntity(
              id = 0L,
              mediaId = restoredMediaId,
              positionMs = secBm.positionMs,
              title = secBm.title,
              createdAt = secBm.createdAt,
              bookTrackId = secBm.bookTrackId,
              thumbnailPath = newThumbPath,
            )
          val newId = publicBookmarkDao.add(restoredBookmark)
          if (secTags.isNotEmpty()) {
            publicTagDao.insertAll(
              secTags.map { t ->
                BookmarkTagEntity(
                  bookmarkId = newId,
                  name = t.name,
                  displayName = t.displayName,
                  createdAt = t.createdAt,
                )
              },
            )
            secTags.forEach { t ->
              publicTagDao.insertMemory(
                BookmarkTagMemoryEntity(name = t.name, displayName = t.displayName),
              )
              publicTagDao.bumpMemory(name = t.name, displayName = t.displayName)
            }
          }
        }
      }

      val secGroup = candidateGroupKeys.firstNotNullOfOrNull { getGroup(it) }
      val meta =
        BookmarkMediaMetadata(
          rawMediaId = restoredMediaId,
          fileSizeBytes = fileSizeBytes,
          width = secGroup?.width ?: 0,
          height = secGroup?.height ?: 0,
          durationSec = secGroup?.durationSec ?: 0,
          sourceLabel = BookmarkMediaMatcher.displaySourceLabel(displayTitle, restoredPath),
          stemKey = BookmarkMediaMatcher.normalizedStem(displayTitle) ?: secGroup?.stemKey,
        )
      PlaybackBookmarkOps.ensureGroupMetadata(
        dao = publicBookmarkDao,
        groupKey = restoredMediaId,
        metadata = meta,
        forceLinkRawMediaId = true,
      )
      if (!secGroup?.presetCode.isNullOrBlank()) {
        publicBookmarkDao.updatePresetCode(restoredMediaId, secGroup?.presetCode)
      }
      SecureMediaRegistry.identitiesForPath(restoredPath).forEach { id ->
        publicBookmarkDao.upsertMediaLink(
          BookmarkMediaLinkEntity(mediaId = id, groupKey = restoredMediaId),
        )
      }
      publicTagDao.upsertMediaRef(
        BookmarkMediaRefEntity(
          mediaId = restoredMediaId,
          playableUri = restoredPath,
          title = displayTitle,
        ),
      )

      bookmarkDao.deleteByIds(movingBookmarks.map { it.id })
      movingBookmarks.forEach { secBm ->
        val oldThumb = secBm.thumbnailPath
        if (!oldThumb.isNullOrBlank() && countByThumbnailPath(oldThumb) == 0) {
          BookmarkThumbnailStore.deleteThumbnail(context, oldThumb, isSecure = true)
        }
      }
    }

    allVaultKeys.forEach { key ->
      bookmarkDao.deleteMediaLink(key)
      bookmarkDao.deleteGroup(key)
      tagDao.deleteMediaRef(key)
    }
  }

  /** Deletes all secure bookmarks, tags, groups, links, refs, and thumbnails for permanently removed vault files. */
  suspend fun deleteAllForMedia(vaultMediaIds: List<String>) =
    withContext(Dispatchers.IO) {
      val cleanIds = vaultMediaIds.filter { it.isNotBlank() }.toSet()
      if (cleanIds.isEmpty()) return@withContext
      val allLinks = bookmarkDao.getAllLinks()
      val candidateGroups = HashSet<String>(cleanIds)
      allLinks.forEach { if (it.mediaId in cleanIds) candidateGroups += it.groupKey }
      val allKeys = cleanIds + candidateGroups
      val targets = bookmarkDao.getAll().filter { it.mediaId in allKeys }
      if (targets.isNotEmpty()) {
        bookmarkDao.deleteByIds(targets.map { it.id })
        targets.forEach { entity ->
          val thumbPath = decNullable(entity.thumbnailPath)
          if (!thumbPath.isNullOrBlank() && countByThumbnailPath(thumbPath) == 0) {
            BookmarkThumbnailStore.deleteThumbnail(context, thumbPath, isSecure = true)
          }
        }
      }
      allKeys.forEach { key ->
        bookmarkDao.deleteMediaLink(key)
        bookmarkDao.deleteGroup(key)
        tagDao.deleteMediaRef(key)
      }
    }

  suspend fun clearAll() =
    withContext(Dispatchers.IO) {
      tagDao.deleteAllTags()
      tagDao.deleteAllMemory()
      tagDao.deleteAllMediaRefs()
      bookmarkDao.deleteAllLinks()
      bookmarkDao.deleteAllGroups()
      bookmarkDao.deleteAllBookmarks()
      DurableStorageManager.secureBookmarkThumbnailsDir(context).listFiles()?.forEach { file ->
        if (file.isFile && !file.name.equals(".nomedia", ignoreCase = true)) {
          runCatching { file.delete() }
        }
      }
    }
}
