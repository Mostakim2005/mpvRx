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
import android.util.Log
import app.gyrolet.mpvrx.database.dao.PlaybackBookmarkDao
import app.gyrolet.mpvrx.database.entities.BookmarkGroupEntity
import app.gyrolet.mpvrx.database.entities.BookmarkMediaLinkEntity
import app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity
import app.gyrolet.mpvrx.domain.media.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

private const val TAG = "PlaybackBookmarkOps"

/** Every stable-id form a path can be launched under, so a move cannot lose its group. */
private fun playbackIds(vararg paths: String): List<String> =
  paths
    .filter { it.isNotBlank() }
    .flatMap { app.gyrolet.mpvrx.domain.secure.SecureMediaRegistry.identitiesForPath(it) }
    .distinct()

data class BookmarkMediaMetadata(
  val rawMediaId: String,
  val fileSizeBytes: Long = 0L,
  val width: Int = 0,
  val height: Int = 0,
  val durationSec: Int = 0,
  val sourceLabel: String = "",
  val stemKey: String? = null,
) {
  val fingerprint: String?
    get() = BookmarkMediaMatcher.exactFingerprint(fileSizeBytes, width, height, durationSec)
}

sealed interface BookmarkGroupLoadResult {
  data class Linked(val groupKey: String, val count: Int) : BookmarkGroupLoadResult
  data class Merged(val groupKey: String, val addedCount: Int) : BookmarkGroupLoadResult
  data object AlreadyActive : BookmarkGroupLoadResult
}

object PlaybackBookmarkOps {
  private fun daoOrNull(): PlaybackBookmarkDao? =
    runCatching { GlobalContext.getOrNull()?.get<PlaybackBookmarkDao>() }.getOrNull()

  suspend fun resolveGroupKey(
    dao: PlaybackBookmarkDao,
    rawMediaId: String,
    fingerprint: String?,
    stemKey: String?,
    durationSec: Int,
  ): String =
    withContext(Dispatchers.IO) {
      dao.findLinkedGroupKey(rawMediaId)?.let { return@withContext it }
      if (dao.countInGroup(rawMediaId) > 0) return@withContext rawMediaId

      fingerprint?.let { fp ->
        dao.findGroupByFingerprint(fp)?.let { matched ->
          dao.upsertMediaLink(BookmarkMediaLinkEntity(mediaId = rawMediaId, groupKey = matched.groupKey))
          return@withContext matched.groupKey
        }
      }

      if (!stemKey.isNullOrBlank() && durationSec > 0) {
        dao.findGroupByStemAndDuration(stemKey, durationSec)?.let { matched ->
          dao.upsertMediaLink(BookmarkMediaLinkEntity(mediaId = rawMediaId, groupKey = matched.groupKey))
          return@withContext matched.groupKey
        }
      }

      rawMediaId
    }

  suspend fun ensureGroupMetadata(
    dao: PlaybackBookmarkDao,
    groupKey: String,
    metadata: BookmarkMediaMetadata?,
    forceLinkRawMediaId: Boolean = false,
  ) = withContext(Dispatchers.IO) {
    val existing = dao.getGroup(groupKey)
    val now = System.currentTimeMillis()
    if (metadata == null) {
      if (existing == null) {
        dao.upsertGroup(BookmarkGroupEntity(groupKey = groupKey, updatedAt = now))
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
    dao.upsertGroup(updated)

    if (forceLinkRawMediaId || dao.findLinkedGroupKey(metadata.rawMediaId) == null) {
      dao.upsertMediaLink(BookmarkMediaLinkEntity(mediaId = metadata.rawMediaId, groupKey = groupKey, updatedAt = now))
    }
  }

  suspend fun assignPresetCode(
    dao: PlaybackBookmarkDao,
    groupKey: String,
    rawCode: String,
    metadata: BookmarkMediaMetadata?,
  ): String? =
    withContext(Dispatchers.IO) {
      val codeToSave = BookmarkMediaMatcher.normalizePresetCode(rawCode).takeIf { it.isNotBlank() }
      ensureGroupMetadata(dao, groupKey, metadata, forceLinkRawMediaId = true)
      dao.updatePresetCode(groupKey = groupKey, presetCode = codeToSave)
      codeToSave
    }

  suspend fun loadGroupIntoCurrent(
    dao: PlaybackBookmarkDao,
    rawMediaId: String,
    currentGroupKey: String,
    sourceGroupKey: String,
    metadata: BookmarkMediaMetadata?,
  ): BookmarkGroupLoadResult =
    withContext(Dispatchers.IO) {
      if (sourceGroupKey == currentGroupKey) return@withContext BookmarkGroupLoadResult.AlreadyActive
      val currentCount = dao.countInGroup(currentGroupKey)
      if (currentCount == 0) {
        dao.upsertMediaLink(BookmarkMediaLinkEntity(mediaId = rawMediaId, groupKey = sourceGroupKey))
        if (metadata != null) {
          ensureGroupMetadata(dao, sourceGroupKey, metadata, forceLinkRawMediaId = true)
        }
        return@withContext BookmarkGroupLoadResult.Linked(
          groupKey = sourceGroupKey,
          count = dao.countInGroup(sourceGroupKey),
        )
      }

      val existingKeys =
        dao.getInGroup(currentGroupKey)
          .mapTo(HashSet()) { it.positionMs to it.title.trim() }
      val incoming = dao.getInGroup(sourceGroupKey)
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
        dao.addAll(toInsert)
      }
      ensureGroupMetadata(dao, currentGroupKey, metadata, forceLinkRawMediaId = true)
      BookmarkGroupLoadResult.Merged(groupKey = currentGroupKey, addedCount = toInsert.size)
    }

  suspend fun importArchiveIntoCurrent(
    dao: PlaybackBookmarkDao,
    context: Context,
    uri: Uri,
    groupKey: String,
    metadata: BookmarkMediaMetadata?,
  ): Result<Int> =
    withContext(Dispatchers.IO) {
      val imported =
        BookmarkPresetArchive.importFromUri(context, uri, groupKey).getOrElse { error ->
          return@withContext Result.failure(error)
        }
      val existingKeys =
        dao.getInGroup(groupKey)
          .mapTo(HashSet()) { it.positionMs to it.title.trim() }
      val skippedThumbnails = mutableListOf<String>()
      val toInsert =
        imported.bookmarks.filter { entry ->
          val key = entry.positionMs to entry.title.trim()
          if (key in existingKeys) {
            entry.thumbnailPath?.takeIf { it.isNotBlank() }?.let(skippedThumbnails::add)
            false
          } else {
            existingKeys.add(key)
          }
        }
      skippedThumbnails.forEach { path -> runCatching { java.io.File(path).delete() } }
      if (toInsert.isNotEmpty()) {
        dao.addAll(toInsert)
      }
      ensureGroupMetadata(dao, groupKey, metadata, forceLinkRawMediaId = true)
      val existingGroup = dao.getGroup(groupKey)
      val importedCode = imported.manifest.presetCode?.trim()?.takeIf { it.isNotBlank() }
      if (existingGroup?.presetCode.isNullOrBlank() && importedCode != null) {
        dao.updatePresetCode(groupKey = groupKey, presetCode = importedCode)
      }
      Result.success(toInsert.size)
    }

  suspend fun deleteBookmarkSafely(
    dao: PlaybackBookmarkDao,
    context: Context,
    bookmark: PlaybackBookmarkEntity,
  ) = withContext(Dispatchers.IO) {
    dao.delete(bookmark.id)
    val thumbPath = bookmark.thumbnailPath
    if (!thumbPath.isNullOrBlank() && dao.countByThumbnailPath(thumbPath) == 0) {
      BookmarkThumbnailStore.deleteThumbnail(context, thumbPath)
    }
  }

  suspend fun onMediaMoved(
    oldMediaIds: List<String>,
    newMediaIds: List<String>,
    fileSizeBytes: Long,
    width: Int,
    height: Int,
    durationSec: Int,
    sourceLabel: String,
    /** New on-disk source after a vault move/restore, so the replay row stops pointing at the old path. */
    newPlayableUri: String? = null,
  ) {
    val dao = daoOrNull() ?: return
    val cleanOld = oldMediaIds.filter { it.isNotBlank() }.distinct()
    val cleanNew = newMediaIds.filter { it.isNotBlank() }.distinct()
    if (cleanOld.isEmpty() && cleanNew.isEmpty()) return

    withContext(Dispatchers.IO) {
      runCatching {
        val fingerprint = BookmarkMediaMatcher.exactFingerprint(fileSizeBytes, width, height, durationSec)
        val stemKey = BookmarkMediaMatcher.normalizedStem(sourceLabel)

        var resolvedGroupKey: String? = null
        for (oldId in cleanOld) {
          val linked = dao.findLinkedGroupKey(oldId)
          if (linked != null) {
            resolvedGroupKey = linked
            break
          }
          if (dao.countInGroup(oldId) > 0) {
            resolvedGroupKey = oldId
            break
          }
        }
        if (resolvedGroupKey == null && fingerprint != null) {
          resolvedGroupKey = dao.findGroupByFingerprint(fingerprint)?.groupKey
        }
        if (resolvedGroupKey == null && stemKey != null && durationSec > 0) {
          resolvedGroupKey = dao.findGroupByStemAndDuration(stemKey, durationSec)?.groupKey
        }

        val groupKey = resolvedGroupKey ?: return@runCatching
        if (dao.countInGroup(groupKey) == 0 && dao.getGroup(groupKey) == null) return@runCatching

        val now = System.currentTimeMillis()
        (cleanOld + cleanNew).distinct().forEach { mediaId ->
          dao.upsertMediaLink(BookmarkMediaLinkEntity(mediaId = mediaId, groupKey = groupKey, updatedAt = now))
        }
        ensureGroupMetadata(
          dao = dao,
          groupKey = groupKey,
          metadata =
            BookmarkMediaMetadata(
              rawMediaId = cleanNew.firstOrNull() ?: groupKey,
              fileSizeBytes = fileSizeBytes,
              width = width,
              height = height,
              durationSec = durationSec,
              sourceLabel = BookmarkMediaMatcher.displaySourceLabel(null, sourceLabel),
              stemKey = stemKey,
            ),
        )
        if (!newPlayableUri.isNullOrBlank()) {
          dao.updateMediaRefUri(groupKey, newPlayableUri)
        }
      }.onFailure { error ->
        Log.w(TAG, "Failed to migrate bookmark links during media move", error)
      }
    }
  }

  suspend fun onPathRenamed(
    oldPath: String,
    newPath: String,
    sourceLabel: String = BookmarkMediaMatcher.extractFileName(newPath),
  ) {
    val oldIds = playbackIds(oldPath)
    val newIds = playbackIds(newPath)
    if (oldIds.isEmpty() || newIds.isEmpty()) return
    onMediaMoved(
      oldMediaIds = oldIds,
      newMediaIds = newIds,
      fileSizeBytes = 0L,
      width = 0,
      height = 0,
      durationSec = 0,
      sourceLabel = sourceLabel,
      newPlayableUri = newPath,
    )
  }

  suspend fun onScopedVideoRenamed(
    video: Video,
    newPath: String,
    newDisplayName: String,
  ) {
    val oldIds = playbackIds(video.path, video.uri.toString())
    val newIds = playbackIds(newPath)
    onMediaMoved(
      oldMediaIds = oldIds,
      newMediaIds = newIds,
      fileSizeBytes = video.size,
      width = video.width,
      height = video.height,
      durationSec = (video.duration / 1000L).toInt().coerceAtLeast(0),
      sourceLabel = newDisplayName,
      newPlayableUri = newPath,
    )
  }
}
