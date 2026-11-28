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
import app.gyrolet.mpvrx.database.entities.SecureBookmarkSyncTombstoneEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkTagEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkTagMemoryEntity
import app.gyrolet.mpvrx.domain.secure.SecureFolderSession
import app.gyrolet.mpvrx.domain.secure.SecureMediaRegistry
import app.gyrolet.mpvrx.domain.secure.VaultCrypto
import app.gyrolet.mpvrx.domain.storage.DurableStorageManager
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

data class VaultSavedGroupSummary(
  val groupKey: String,
  val presetCode: String?,
  val sourceLabel: String,
  val sourceUrl: String?,
  val bookmarkCount: Int,
  val mediaAttached: Boolean,
  val mediaTitle: String?,
  val updatedAt: Long,
)

data class VaultSavedBookmarkSummary(
  val id: Long,
  val mediaId: String,
  val groupLabel: String,
  val title: String,
  val positionMs: Long,
  val tags: List<String>,
  val thumbnailPath: String?,
  val mediaAttached: Boolean,
  val syncUuid: String?,
)

data class VaultSavedThumbnailFile(
  val path: String,
  val name: String,
  val sizeBytes: Long,
  val isOrphaned: Boolean,
  val isCache: Boolean,
)

data class VaultSavedTagSummary(
  val nameHash: String,
  val displayName: String,
  val useCount: Int,
  val attachedBookmarkCount: Int,
)

data class VaultSavedBookmarkInventory(
  val groups: List<VaultSavedGroupSummary>,
  val bookmarks: List<VaultSavedBookmarkSummary>,
  val thumbnails: List<VaultSavedThumbnailFile>,
  val tags: List<VaultSavedTagSummary>,
  val tombstoneCount: Int,
)

/**
 * Encrypted, isolated store for Secure Vault bookmarks, tags, groups, presets, and media refs
 * backed by [SecureVaultDatabase] (`Documents/mpvRx/.vault/v_idx.dat`, plus `.vault_d/vd_idx.dat`
 * when unlocked in Decoy mode).
 */
class SecureBookmarkStore(
  private val database: SecureVaultDatabase,
  private val context: Context,
  private val decoyDatabase: SecureVaultDatabase? = null,
) {
  private fun activeDb(forceDecoy: Boolean? = null): SecureVaultDatabase =
    when {
      forceDecoy == true && decoyDatabase != null -> decoyDatabase
      forceDecoy == false -> database
      SecureFolderSession.isDecoyUnlocked && decoyDatabase != null -> decoyDatabase
      else -> database
    }

  private val bookmarkDao get() = activeDb().secureBookmarkDao()
  private val tagDao get() = activeDb().secureBookmarkTagDao()
  private val tombstoneDao get() = activeDb().secureBookmarkSyncTombstoneDao()

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
      endPositionMs = endPositionMs,
      primaryTag = decNullable(primaryTag),
      syncUuid = syncUuid,
      updatedAt = updatedAt,
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
      endPositionMs = endPositionMs,
      primaryTag = encNullable(primaryTag),
      syncUuid = syncUuid,
      updatedAt = updatedAt,
    )

  private fun SecureBookmarkGroupEntity.toDomain(): BookmarkGroupEntity =
    BookmarkGroupEntity(
      groupKey = groupKey,
      presetCode = decNullable(presetCode),
      oshash = oshash,
      fingerprint = fingerprint,
      stemKey = decNullable(stemKey),
      durationSec = durationSec,
      width = width,
      height = height,
      fileSizeBytes = fileSizeBytes,
      sourceLabel = dec(sourceLabel),
      sourceUrl = decNullable(sourceUrl),
      updatedAt = updatedAt,
    )

  private fun BookmarkGroupEntity.toSecure(): SecureBookmarkGroupEntity =
    SecureBookmarkGroupEntity(
      groupKey = groupKey,
      presetCode = encNullable(presetCode),
      oshash = oshash,
      fingerprint = fingerprint,
      stemKey = encNullable(stemKey),
      durationSec = durationSec,
      width = width,
      height = height,
      fileSizeBytes = fileSizeBytes,
      sourceLabel = enc(sourceLabel),
      sourceUrl = encNullable(sourceUrl),
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

  suspend fun rename(
    id: Long,
    title: String,
    updatedAt: Long = System.currentTimeMillis(),
  ) =
    withContext(Dispatchers.IO) {
      bookmarkDao.rename(id, enc(title), updatedAt = updatedAt)
    }

  suspend fun updateThumbnail(
    id: Long,
    thumbnailPath: String?,
    updatedAt: Long = System.currentTimeMillis(),
  ): Int =
    withContext(Dispatchers.IO) {
      bookmarkDao.updateThumbnail(id, encNullable(thumbnailPath), updatedAt = updatedAt)
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

  fun observePresetSummaries(forceDecoy: Boolean? = null): Flow<List<BookmarkGroupSummary>> =
    activeDb(forceDecoy).secureBookmarkDao().observePresetSummaries().map { list -> list.map { it.decrypted() } }

  suspend fun resolveGroupKey(
    rawMediaId: String,
    fingerprint: String?,
    stemKey: String?,
    durationSec: Int,
    oshash: String? = null,
    sourceLabel: String? = null,
  ): String =
    withContext(Dispatchers.IO) {
      bookmarkDao.findLinkedGroupKey(rawMediaId)?.let { return@withContext it }
      if (bookmarkDao.countInGroup(rawMediaId) > 0) return@withContext rawMediaId

      val groups = bookmarkDao.getAllGroups().map { it.toDomain() }
      if (!oshash.isNullOrBlank()) {
        groups
          .filter { !it.oshash.isNullOrBlank() && it.oshash.equals(oshash, ignoreCase = true) }
          .maxByOrNull { it.updatedAt }
          ?.let { matched ->
            bookmarkDao.upsertMediaLink(SecureBookmarkMediaLinkEntity(mediaId = rawMediaId, groupKey = matched.groupKey))
            return@withContext matched.groupKey
          }
      }

      fingerprint?.let { fp ->
        groups
          .filter { it.fingerprint == fp }
          .maxByOrNull { it.updatedAt }
          ?.let { matched ->
            bookmarkDao.upsertMediaLink(SecureBookmarkMediaLinkEntity(mediaId = rawMediaId, groupKey = matched.groupKey))
            return@withContext matched.groupKey
          }
      }

      val jvidCode =
        BookmarkMediaMatcher.extractJvidCode(sourceLabel)
          ?: BookmarkMediaMatcher.extractJvidCode(rawMediaId)
      if (!jvidCode.isNullOrBlank()) {
        groups
          .filter { g ->
            val gCode =
              g.presetCode?.let { BookmarkMediaMatcher.extractJvidCode(it) ?: it.uppercase(java.util.Locale.ROOT) }
                ?: BookmarkMediaMatcher.extractJvidCode(g.sourceLabel)
                ?: BookmarkMediaMatcher.extractJvidCode(g.groupKey)
            gCode != null && gCode.equals(jvidCode, ignoreCase = true)
          }.maxByOrNull { it.updatedAt }
          ?.let { matched ->
            bookmarkDao.upsertMediaLink(
              SecureBookmarkMediaLinkEntity(mediaId = rawMediaId, groupKey = matched.groupKey),
            )
            return@withContext matched.groupKey
          }
      }

      if (!stemKey.isNullOrBlank() && durationSec > 0) {
        groups
          .filter { g ->
            g.stemKey == stemKey && (g.durationSec <= 0 || abs(g.durationSec - durationSec) <= 3)
          }.maxByOrNull { it.updatedAt }
          ?.let { matched ->
            bookmarkDao.upsertMediaLink(
              SecureBookmarkMediaLinkEntity(mediaId = rawMediaId, groupKey = matched.groupKey),
            )
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
        val codeOnly = BookmarkMediaMatcher.extractJvidCode(groupKey)
        val newGroup = BookmarkGroupEntity(groupKey = groupKey, presetCode = codeOnly, updatedAt = now)
        bookmarkDao.upsertGroup(newGroup.toSecure())
      }
      return@withContext
    }

    val inferredCode =
      existing?.presetCode?.takeIf { it.isNotBlank() }
        ?: BookmarkMediaMatcher.extractJvidCode(metadata.sourceLabel)
        ?: BookmarkMediaMatcher.extractJvidCode(metadata.rawMediaId)
        ?: BookmarkMediaMatcher.extractJvidCode(groupKey)

    val updated =
      BookmarkGroupEntity(
        groupKey = groupKey,
        presetCode = inferredCode,
        oshash = metadata.oshash ?: existing?.oshash,
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
      val toInsert = mutableListOf<Pair<PlaybackBookmarkEntity, List<String>>>()
      imported.bookmarks.forEachIndexed { idx, entry ->
        val key = entry.positionMs to entry.title.trim()
        if (existingKeys.add(key)) {
          toInsert.add(entry to imported.importedTagsByIndex.getOrElse(idx) { emptyList() })
        }
      }
      toInsert.forEach { (entry, tags) ->
        val newId = add(entry)
        if (tags.isNotEmpty() && newId > 0L) {
          replaceTags(newId, tags)
        }
      }
      val mergedMeta =
        if (metadata != null) {
          metadata.copy(oshash = metadata.oshash ?: imported.manifest.oshash)
        } else if (!imported.manifest.oshash.isNullOrBlank()) {
          BookmarkMediaMetadata(
            rawMediaId = groupKey,
            fileSizeBytes = imported.manifest.fileSizeBytes,
            width = imported.manifest.width,
            height = imported.manifest.height,
            durationSec = imported.manifest.durationSec,
            sourceLabel = imported.manifest.sourceLabel,
            stemKey = imported.manifest.stemKey,
            oshash = imported.manifest.oshash,
          )
        } else {
          null
        }
      ensureGroupMetadata(groupKey, mergedMeta, forceLinkRawMediaId = metadata != null)
      val existingGroup = getGroup(groupKey)
      val importedCode = imported.manifest.presetCode?.trim()?.takeIf { it.isNotBlank() }
      if (existingGroup?.presetCode.isNullOrBlank() && importedCode != null) {
        bookmarkDao.updatePresetCode(groupKey = groupKey, presetCode = enc(importedCode))
      }
      Result.success(toInsert.size)
    }

  suspend fun deleteBookmarkSafely(bookmark: PlaybackBookmarkEntity) =
    withContext(Dispatchers.IO) {
      if (!SecureFolderSession.isDecoyUnlocked) {
        bookmark.syncUuid?.trim()?.takeIf { it.isNotEmpty() }?.let { sUuid ->
          tombstoneDao.upsert(
            SecureBookmarkSyncTombstoneEntity(
              syncUuid = sUuid,
              groupKey = bookmark.mediaId,
              deletedAt = System.currentTimeMillis(),
            ),
          )
        }
      }
      bookmarkDao.delete(bookmark.id)
      tagDao.clearForBookmark(bookmark.id)
      val thumbPath = bookmark.thumbnailPath
      if (!thumbPath.isNullOrBlank() && countByThumbnailPath(thumbPath) == 0) {
        BookmarkThumbnailStore.deleteThumbnail(context, thumbPath, isSecure = true)
      }
    }

  suspend fun deleteBookmarkById(
    bookmarkId: Long,
    fallbackMediaId: String,
    fallbackThumbnailPath: String? = null,
  ) = withContext(Dispatchers.IO) {
    val existing = bookmarkDao.getInGroup(fallbackMediaId).firstOrNull { it.id == bookmarkId }
    val thumbPath = existing?.let { decNullable(it.thumbnailPath) } ?: fallbackThumbnailPath
    val sUuid = existing?.syncUuid?.trim()?.takeIf { it.isNotEmpty() }
    if (!SecureFolderSession.isDecoyUnlocked && sUuid != null) {
      tombstoneDao.upsert(
        SecureBookmarkSyncTombstoneEntity(
          syncUuid = sUuid,
          groupKey = existing.mediaId,
          deletedAt = System.currentTimeMillis(),
        ),
      )
    }
    bookmarkDao.delete(bookmarkId)
    tagDao.clearForBookmark(bookmarkId)
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

  fun observeAllPairs(forceDecoy: Boolean? = null): Flow<List<BookmarkTagPair>> =
    activeDb(forceDecoy).secureBookmarkTagDao().observeAllTags().map { tags ->
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
    bookmarkDao.touchUpdatedAt(bookmarkId, now)
  }

  fun observeMemory(forceDecoy: Boolean? = null): Flow<List<BookmarkTagMemoryEntity>> =
    activeDb(forceDecoy).secureBookmarkTagDao().observeMemory().map { list ->
      list.map { entry ->
        BookmarkTagMemoryEntity(
          name = dec(entry.name),
          displayName = dec(entry.displayName),
          useCount = entry.useCount,
          lastUsedAt = entry.lastUsedAt,
        )
      }
    }

  fun observeFilterTags(forceDecoy: Boolean? = null): Flow<List<String>> =
    combine(observeAllPairs(forceDecoy), observeMemory(forceDecoy)) { pairs, memory ->
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

  fun observeMediaRefs(forceDecoy: Boolean? = null): Flow<List<BookmarkMediaRefEntity>> =
    activeDb(forceDecoy).secureBookmarkTagDao().observeMediaRefs().map { list -> list.map { it.toDomain() } }

  fun observeGalleryItems(forceDecoy: Boolean? = null): Flow<List<BookmarkGalleryItem>> {
    val db = activeDb(forceDecoy)
    val rowsFlow =
      db.secureBookmarkDao().observeGallery().map { list ->
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
    return combine(
      rowsFlow,
      observeAllPairs(forceDecoy),
      observeMediaRefs(forceDecoy),
      db.secureBookmarkDao().observeAllGroups().map { groups -> groups.associate { it.groupKey to dec(it.sourceLabel) } },
    ) { rows, pairs, refs, groupLabels ->
      val tagsByBookmark = pairs.groupBy({ it.bookmarkId }, { it.name })
      val refsByMedia = refs.associateBy { it.mediaId }
      rows.map { row ->
        val ref = refsByMedia[row.mediaId]
        val livePlayable =
          ref?.playableUri?.takeIf { uri ->
            !DurableStorageManager.isVaultPath(uri) || File(uri).isFile
          }
        BookmarkGalleryItem(
          row = row,
          tags = tagsByBookmark[row.id].orEmpty().sorted(),
          playableUri = livePlayable,
          mediaTitle = ref?.title ?: groupLabels[row.mediaId]?.takeIf { it.isNotBlank() },
        )
      }
    }.distinctUntilChanged()
  }

  /**
   * Moves bookmarks, tags, groups, links, refs, and thumbnails for a media file from public
   * [MpvRxDatabase] into the encrypted [SecureVaultDatabase] (or active Decoy compartment) when
   * hidden into Secret Folder.
   *
   * Expands legacy unhashed paths / raw URI links cleanly (`Charlie`), matches existing retained
   * JAVID/stem vault groups first (`Echo`), and removes all moved public rows from [MpvRxDatabase].
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
    val expandedOldIds = LinkedHashSet<String>()
    oldMediaIds.filter { it.isNotBlank() }.forEach { raw ->
      expandedOldIds += raw
      expandedOldIds += PlaybackIdentity.normalizeLocalPath(raw)
      expandedOldIds += SecureMediaRegistry.identitiesForPath(raw)
      if (raw.startsWith("/")) {
        expandedOldIds += "file://$raw"
      }
    }

    val allPublicRefs = publicTagDao.getAllMediaRefs()
    allPublicRefs.forEach { ref ->
      val refUri = ref.playableUri.trim()
      val refNorm = PlaybackIdentity.normalizeLocalPath(refUri)
      if (
        ref.mediaId in expandedOldIds ||
        refUri in expandedOldIds ||
        refNorm in expandedOldIds ||
        SecureMediaRegistry.identitiesForPath(refUri).any { it in expandedOldIds }
      ) {
        expandedOldIds += ref.mediaId
        expandedOldIds += refUri
        if (refNorm.isNotBlank()) expandedOldIds += refNorm
      }
    }

    val allPublicLinks = publicBookmarkDao.getAllLinks()
    val allPublicBookmarks = publicBookmarkDao.getAllBookmarks()

    val candidateGroupKeys = HashSet<String>()
    allPublicLinks.forEach { link ->
      if (link.mediaId in expandedOldIds || PlaybackIdentity.normalizeLocalPath(link.mediaId) in expandedOldIds) {
        candidateGroupKeys += link.groupKey
      }
    }
    expandedOldIds.forEach { id ->
      if (allPublicBookmarks.any { it.mediaId == id }) candidateGroupKeys += id
    }

    val publicRefsByMedia = allPublicRefs.associateBy { it.mediaId }
    val expandedAliasIds = HashSet<String>(expandedOldIds)
    allPublicLinks.forEach { link ->
      if (link.groupKey in candidateGroupKeys) {
        val linkNorm = PlaybackIdentity.normalizeLocalPath(link.mediaId)
        val refUri = publicRefsByMedia[link.mediaId]?.playableUri
        if (
          link.mediaId in expandedOldIds ||
          linkNorm in expandedOldIds ||
          (refUri != null && (refUri in expandedOldIds || PlaybackIdentity.normalizeLocalPath(refUri) in expandedOldIds)) ||
          link.mediaId.startsWith("content://", ignoreCase = true) ||
          link.mediaId.startsWith("file://", ignoreCase = true) ||
          link.mediaId.startsWith("/")
        ) {
          expandedAliasIds += link.mediaId
        }
      }
    }

    val allTargetMediaIds = expandedAliasIds + candidateGroupKeys
    val exclusiveGroupKeys =
      candidateGroupKeys.filterTo(HashSet()) { gk ->
        allPublicLinks.none { link -> link.groupKey == gk && link.mediaId !in allTargetMediaIds }
      }
    val movingBookmarks = allPublicBookmarks.filter { it.mediaId in allTargetMediaIds }

    val vaultIdentities = SecureMediaRegistry.identitiesForPath(targetVaultPath).ifEmpty { listOf(targetVaultMediaId) }
    val rawCanonicalId = vaultIdentities.firstOrNull() ?: targetVaultMediaId
    val sourceGroup =
      candidateGroupKeys.firstNotNullOfOrNull { publicBookmarkDao.getGroup(it) }
    val stem = BookmarkMediaMatcher.normalizedStem(displayTitle) ?: sourceGroup?.stemKey
    val jvidCode =
      sourceGroup?.presetCode?.takeIf { it.isNotBlank() }
        ?: BookmarkMediaMatcher.extractJvidCode(displayTitle)
        ?: candidateGroupKeys.firstNotNullOfOrNull { BookmarkMediaMatcher.extractJvidCode(it) }

    // Reuse an existing retained vault group if this video (by JAVID, oshash, fingerprint, or stem)
    // already has retained bookmarks in the active vault compartment.
    val existingRetainedKey =
      resolveGroupKey(
        rawMediaId = rawCanonicalId,
        fingerprint = sourceGroup?.fingerprint,
        stemKey = stem,
        durationSec = sourceGroup?.durationSec ?: 0,
        oshash = sourceGroup?.oshash,
        sourceLabel = jvidCode ?: displayTitle,
      ).takeIf { key ->
        key != rawCanonicalId || bookmarkDao.getGroup(rawCanonicalId) != null || bookmarkDao.countInGroup(rawCanonicalId) > 0
      }

    val preferredGroupKey =
      existingRetainedKey
        ?: sourceGroup?.groupKey?.takeIf { !it.startsWith("/") && it in exclusiveGroupKeys }
        ?: rawCanonicalId

    if (movingBookmarks.isNotEmpty() || existingRetainedKey != null || sourceGroup != null) {
      val existingSecure = getInGroup(preferredGroupKey).mapTo(HashSet()) { it.positionMs to it.title.trim() }
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
              mediaId = preferredGroupKey,
              positionMs = oldBm.positionMs,
              title = oldBm.title,
              createdAt = oldBm.createdAt,
              bookTrackId = oldBm.bookTrackId,
              thumbnailPath = newThumbPath,
              endPositionMs = oldBm.endPositionMs,
              primaryTag = oldBm.primaryTag,
              syncUuid = oldBm.syncUuid,
              updatedAt = oldBm.updatedAt.takeIf { it > 0L } ?: oldBm.createdAt,
            )
          val newId = add(newBookmark)
          if (oldTags.isNotEmpty()) {
            replaceTags(newId, oldTags.map { it.displayName.ifBlank { it.name } })
          }
        }
      }

      val existingVaultGroup = getGroup(preferredGroupKey)
      val meta =
        BookmarkMediaMetadata(
          rawMediaId = rawCanonicalId,
          fileSizeBytes = fileSizeBytes.takeIf { it > 0L } ?: sourceGroup?.fileSizeBytes ?: existingVaultGroup?.fileSizeBytes ?: 0L,
          width = existingVaultGroup?.width?.takeIf { it > 0 } ?: sourceGroup?.width ?: 0,
          height = existingVaultGroup?.height?.takeIf { it > 0 } ?: sourceGroup?.height ?: 0,
          durationSec = existingVaultGroup?.durationSec?.takeIf { it > 0 } ?: sourceGroup?.durationSec ?: 0,
          sourceLabel = BookmarkMediaMatcher.displaySourceLabel(displayTitle, targetVaultPath),
          stemKey = stem,
          oshash = existingVaultGroup?.oshash ?: sourceGroup?.oshash,
        )
      ensureGroupMetadata(preferredGroupKey, meta, forceLinkRawMediaId = true)
      val mergedUrl = sourceGroup?.sourceUrl?.takeIf { it.isNotBlank() } ?: existingVaultGroup?.sourceUrl
      if (!mergedUrl.isNullOrBlank()) {
        bookmarkDao.updateGroupSourceUrl(preferredGroupKey, encNullable(mergedUrl))
      }
      val resolvedPreset = jvidCode ?: existingVaultGroup?.presetCode
      if (!resolvedPreset.isNullOrBlank() && getGroup(preferredGroupKey)?.presetCode.isNullOrBlank()) {
        bookmarkDao.updatePresetCode(preferredGroupKey, encNullable(resolvedPreset))
      }

      val now = System.currentTimeMillis()
      (vaultIdentities + targetVaultPath).distinct().forEach { id ->
        upsertMediaLink(BookmarkMediaLinkEntity(mediaId = id, groupKey = preferredGroupKey, updatedAt = now))
      }
      upsertMediaRef(
        BookmarkMediaRefEntity(
          mediaId = preferredGroupKey,
          playableUri = targetVaultPath,
          title = displayTitle,
          updatedAt = now,
        ),
      )

      // Delete migrated public rows strictly where exclusive to the moved media
      val idsToDeleteFromPublic =
        movingBookmarks.filter { it.mediaId in expandedAliasIds || it.mediaId in exclusiveGroupKeys }.map { it.id }
      if (idsToDeleteFromPublic.isNotEmpty()) {
        publicBookmarkDao.deleteByIds(idsToDeleteFromPublic)
        idsToDeleteFromPublic.forEach { publicTagDao.clearForBookmark(it) }
      }
      expandedAliasIds.forEach { publicBookmarkDao.deleteMediaLink(it) }
      exclusiveGroupKeys.forEach { gk ->
        if (publicBookmarkDao.countInGroup(gk) == 0 && publicBookmarkDao.countLinksForGroup(gk) == 0) {
          publicBookmarkDao.deleteGroup(gk)
        }
        publicTagDao.deleteMediaRef(gk)
      }
      expandedAliasIds.forEach { publicTagDao.deleteMediaRef(it) }

      movingBookmarks.forEach { oldBm ->
        val oldThumb = oldBm.thumbnailPath
        if (!oldThumb.isNullOrBlank() && publicBookmarkDao.countByThumbnailPath(oldThumb) == 0) {
          BookmarkThumbnailStore.deleteThumbnail(context, oldThumb, isSecure = false)
        }
      }
    } else {
      // Even without bookmarks, clean up any stale public media refs/links pointing at moved ids
      expandedAliasIds.forEach { id ->
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
        publicBookmarkDao.updatePresetCode(restoredMediaId, secGroup.presetCode)
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

  suspend fun clearAll(decoyOnly: Boolean = false) =
    withContext(Dispatchers.IO) {
      val db = if (decoyOnly) (decoyDatabase ?: return@withContext) else database
      db.secureBookmarkTagDao().deleteAllTags()
      db.secureBookmarkTagDao().deleteAllMemory()
      db.secureBookmarkTagDao().deleteAllMediaRefs()
      db.secureBookmarkDao().deleteAllLinks()
      db.secureBookmarkDao().deleteAllGroups()
      db.secureBookmarkDao().deleteAllBookmarks()
      db.secureBookmarkSyncTombstoneDao().clearAll()
      val thumbsDir =
        if (decoyOnly) {
          DurableStorageManager.decoyBookmarkThumbnailsDir(context)
        } else {
          DurableStorageManager.secureBookmarkThumbnailsDir(context)
        }
      thumbsDir.listFiles()?.forEach { file ->
        if (file.isFile && !file.name.equals(".nomedia", ignoreCase = true)) {
          runCatching { file.delete() }
        }
      }
      BookmarkThumbnailStore.evictSecureMemoryCache()
    }

  /**
   * Builds a decrypted inventory of all retained Primary Vault bookmarks, JAVID groups,
   * `.vault/thumbs/` & `.vault/thumb_cache/` image files, and tag memory for Vault-Safe Data Manager.
   */
  suspend fun loadPrimaryVaultInventory(): VaultSavedBookmarkInventory =
    withContext(Dispatchers.IO) {
      VaultCrypto.ensureInitialized(context)
      val bmDao = database.secureBookmarkDao()
      val tDao = database.secureBookmarkTagDao()
      val mediaDao = database.secureVaultMediaDao()

      val livePaths = mediaDao.getAll().mapTo(HashSet()) { it.secureFilePath }
      val allGroups = bmDao.getAllGroups().map { it.toDomain() }
      val groupsByKey = allGroups.associateBy { it.groupKey }
      val allBookmarks = bmDao.getAll().map { it.toDomain() }
      val refsByMedia = tDao.getAllMediaRefs().map { it.toDomain() }.associateBy { it.mediaId }
      val tagsByBm =
        tDao.getAllTagsOnce().groupBy({ it.bookmarkId }, { dec(it.displayName).ifBlank { dec(it.name) } })
      val bmCountByGroup = allBookmarks.groupingBy { it.mediaId }.eachCount()

      fun isGroupAttached(groupKey: String): Boolean {
        val uri = refsByMedia[groupKey]?.playableUri ?: return false
        return if (DurableStorageManager.isVaultPath(uri)) uri in livePaths && File(uri).isFile else true
      }

      val groupSummaries =
        allGroups.map { g ->
          val ref = refsByMedia[g.groupKey]
          VaultSavedGroupSummary(
            groupKey = g.groupKey,
            presetCode = g.presetCode,
            sourceLabel = g.sourceLabel.ifBlank { ref?.title ?: g.groupKey },
            sourceUrl = g.sourceUrl,
            bookmarkCount = bmCountByGroup[g.groupKey] ?: 0,
            mediaAttached = isGroupAttached(g.groupKey),
            mediaTitle = ref?.title,
            updatedAt = g.updatedAt,
          )
        }.sortedByDescending { it.updatedAt }

      val bookmarkSummaries =
        allBookmarks.map { bm ->
          val grp = groupsByKey[bm.mediaId]
          val label =
            grp?.presetCode?.takeIf { it.isNotBlank() }
              ?: grp?.sourceLabel?.takeIf { it.isNotBlank() }
              ?: refsByMedia[bm.mediaId]?.title
              ?: bm.mediaId
          VaultSavedBookmarkSummary(
            id = bm.id,
            mediaId = bm.mediaId,
            groupLabel = label,
            title = bm.title,
            positionMs = bm.positionMs,
            tags = tagsByBm[bm.id].orEmpty().distinct().sorted(),
            thumbnailPath = bm.thumbnailPath,
            mediaAttached = isGroupAttached(bm.mediaId),
            syncUuid = bm.syncUuid,
          )
        }.sortedByDescending { it.id }

      val referencedThumbs = allBookmarks.mapNotNull { it.thumbnailPath }.toSet()
      val thumbFiles = ArrayList<VaultSavedThumbnailFile>()
      DurableStorageManager.secureBookmarkThumbnailsDir(context).listFiles()?.forEach { f ->
        if (f.isFile && !f.name.equals(".nomedia", ignoreCase = true)) {
          thumbFiles +=
            VaultSavedThumbnailFile(
              path = f.absolutePath,
              name = f.name,
              sizeBytes = f.length(),
              isOrphaned = f.absolutePath !in referencedThumbs,
              isCache = false,
            )
        }
      }
      DurableStorageManager.secureThumbnailCacheDir(context).listFiles()?.forEach { f ->
        if (f.isFile && !f.name.equals(".nomedia", ignoreCase = true)) {
          thumbFiles +=
            VaultSavedThumbnailFile(
              path = f.absolutePath,
              name = f.name,
              sizeBytes = f.length(),
              isOrphaned = false,
              isCache = true,
            )
        }
      }

      val tagAttachCounts = HashMap<String, Int>()
      tDao.getAllTagsOnce().forEach { tag ->
        tagAttachCounts[tag.nameHash] = (tagAttachCounts[tag.nameHash] ?: 0) + 1
      }
      val tagSummaries =
        tDao.getAllMemoryOnce().map { mem ->
          VaultSavedTagSummary(
            nameHash = mem.nameHash,
            displayName = dec(mem.displayName).ifBlank { dec(mem.name) },
            useCount = mem.useCount,
            attachedBookmarkCount = tagAttachCounts[mem.nameHash] ?: 0,
          )
        }

      VaultSavedBookmarkInventory(
        groups = groupSummaries,
        bookmarks = bookmarkSummaries,
        thumbnails = thumbFiles.sortedWith(compareBy<VaultSavedThumbnailFile> { it.isCache }.thenByDescending { it.isOrphaned }.thenBy { it.name }),
        tags = tagSummaries,
        tombstoneCount = database.secureBookmarkSyncTombstoneDao().getAll().size,
      )
    }

  suspend fun deletePrimaryBookmarksByIds(ids: List<Long>) =
    withContext(Dispatchers.IO) {
      if (ids.isEmpty()) return@withContext
      val bmDao = database.secureBookmarkDao()
      val tDao = database.secureBookmarkTagDao()
      val tombDao = database.secureBookmarkSyncTombstoneDao()
      val targets = bmDao.getAll().filter { it.id in ids.toSet() }
      val now = System.currentTimeMillis()
      targets.forEach { entity ->
        entity.syncUuid?.trim()?.takeIf { it.isNotEmpty() }?.let { uuid ->
          tombDao.upsert(SecureBookmarkSyncTombstoneEntity(syncUuid = uuid, groupKey = entity.mediaId, deletedAt = now))
        }
        tDao.clearForBookmark(entity.id)
      }
      ids.chunked(900).forEach { bmDao.deleteByIds(it) }
      val remainingThumbs = bmDao.getAll().mapNotNull { decNullable(it.thumbnailPath) }.toSet()
      targets.forEach { entity ->
        val thumbPath = decNullable(entity.thumbnailPath)
        if (!thumbPath.isNullOrBlank() && thumbPath !in remainingThumbs) {
          BookmarkThumbnailStore.deleteThumbnail(context, thumbPath, isSecure = true)
        }
      }
    }

  suspend fun deletePrimaryGroupsByKeys(groupKeys: List<String>) =
    withContext(Dispatchers.IO) {
      if (groupKeys.isEmpty()) return@withContext
      val keySet = groupKeys.toSet()
      val bmDao = database.secureBookmarkDao()
      val tDao = database.secureBookmarkTagDao()
      val linksToDelete = bmDao.getAllLinks().filter { it.groupKey in keySet }.map { it.mediaId }
      val allTargetIds = keySet + linksToDelete
      val bmIds = bmDao.getAll().filter { it.mediaId in allTargetIds }.map { it.id }
      deletePrimaryBookmarksByIds(bmIds)
      allTargetIds.forEach { id ->
        bmDao.deleteMediaLink(id)
        tDao.deleteMediaRef(id)
      }
      keySet.forEach { gk -> bmDao.deleteGroup(gk) }
    }

  suspend fun deletePrimaryThumbnailFiles(paths: List<String>) =
    withContext(Dispatchers.IO) {
      if (paths.isEmpty()) return@withContext
      val pathSet = paths.toSet()
      val bmDao = database.secureBookmarkDao()
      bmDao.getAll().forEach { entity ->
        val decryptedThumb = decNullable(entity.thumbnailPath)
        if (decryptedThumb != null && decryptedThumb in pathSet) {
          bmDao.updateThumbnail(entity.id, null)
        }
      }
      pathSet.forEach { path ->
        BookmarkThumbnailStore.evictSecureMemoryCache()
        runCatching { File(path).delete() }
      }
    }

  suspend fun deletePrimaryTagMemory(nameHashes: List<String>) =
    withContext(Dispatchers.IO) {
      if (nameHashes.isEmpty()) return@withContext
      nameHashes.chunked(900).forEach { chunk ->
        database.secureBookmarkTagDao().deleteMemoryByHashes(chunk)
      }
    }

  suspend fun clearPrimaryTombstones() =
    withContext(Dispatchers.IO) {
      database.secureBookmarkSyncTombstoneDao().clearAll()
    }
}
