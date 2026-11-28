/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gyrolet.mpvrx.database.entities.SecureBookmarkEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkGroupEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkMediaLinkEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkMediaRefEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkSyncTombstoneEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkTagEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkTagMemoryEntity
import app.gyrolet.mpvrx.database.entities.SecurePlaybackStateEntity
import app.gyrolet.mpvrx.database.entities.SecureVaultMediaEntity
import app.gyrolet.mpvrx.database.entities.SecureVideoMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SecureVaultMediaDao {
  @Query("SELECT * FROM secure_media ORDER BY dateHidden DESC")
  fun observeAll(): Flow<List<SecureVaultMediaEntity>>

  @Query("SELECT * FROM secure_media ORDER BY dateHidden DESC")
  suspend fun getAll(): List<SecureVaultMediaEntity>

  @Query("SELECT * FROM secure_media WHERE id IN (:ids)")
  suspend fun getByIds(ids: List<Long>): List<SecureVaultMediaEntity>

  @Query("SELECT * FROM secure_media WHERE secureFilePath = :secureFilePath LIMIT 1")
  suspend fun findBySecurePath(secureFilePath: String): SecureVaultMediaEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(entity: SecureVaultMediaEntity): Long

  @Query("DELETE FROM secure_media WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM secure_media WHERE id IN (:ids)")
  suspend fun deleteByIds(ids: List<Long>)

  @Query("DELETE FROM secure_media")
  suspend fun deleteAll()

  @Query(
    """
    UPDATE secure_media
    SET secureFilePath = :newPrefix || SUBSTR(secureFilePath, LENGTH(:oldPrefix) + 1)
    WHERE secureFilePath LIKE :oldPrefix || '%'
    """,
  )
  suspend fun rebaseSecurePaths(oldPrefix: String, newPrefix: String)

  @Query("SELECT COUNT(*) FROM secure_media")
  fun observeCount(): Flow<Int>
}

@Dao
interface SecureBookmarkDao {
  @Query("SELECT * FROM secure_bookmarks WHERE mediaId = :mediaId ORDER BY positionMs ASC, id ASC")
  fun observe(mediaId: String): Flow<List<SecureBookmarkEntity>>

  @Query("SELECT * FROM secure_bookmarks WHERE mediaId = :mediaId ORDER BY positionMs ASC, id ASC")
  suspend fun getInGroup(mediaId: String): List<SecureBookmarkEntity>

  @Query("SELECT * FROM secure_bookmarks ORDER BY createdAt DESC, id DESC")
  suspend fun getAll(): List<SecureBookmarkEntity>

  @Query("SELECT * FROM secure_bookmarks ORDER BY createdAt DESC, id DESC")
  fun observeGallery(): Flow<List<SecureBookmarkEntity>>

  @Query("SELECT COUNT(*) FROM secure_bookmarks WHERE mediaId = :mediaId")
  suspend fun countInGroup(mediaId: String): Int

  @Query("SELECT COUNT(*) FROM secure_bookmarks WHERE thumbnailPath = :thumbnailPath")
  suspend fun countByThumbnailPath(thumbnailPath: String): Int

  @Insert
  suspend fun add(bookmark: SecureBookmarkEntity): Long

  @Insert
  suspend fun addAll(bookmarks: List<SecureBookmarkEntity>): List<Long>

  @Query("UPDATE secure_bookmarks SET title = :title, updatedAt = :updatedAt WHERE id = :id")
  suspend fun rename(id: Long, title: String, updatedAt: Long = System.currentTimeMillis())

  @Query("UPDATE secure_bookmarks SET positionMs = :positionMs, title = :title, updatedAt = :updatedAt WHERE id = :id")
  suspend fun updatePositionAndTitle(id: Long, positionMs: Long, title: String, updatedAt: Long = System.currentTimeMillis())

  @Query(
    """
    UPDATE secure_bookmarks
    SET endPositionMs = :endPositionMs,
        primaryTag = :primaryTag,
        syncUuid = :syncUuid,
        updatedAt = :updatedAt
    WHERE id = :id
    """,
  )
  suspend fun updateBookmarkSyncMetadata(
    id: Long,
    endPositionMs: Long?,
    primaryTag: String?,
    syncUuid: String?,
    updatedAt: Long = System.currentTimeMillis(),
  )

  @Query("SELECT * FROM secure_bookmarks WHERE syncUuid = :syncUuid LIMIT 1")
  suspend fun findBookmarkBySyncUuid(syncUuid: String): SecureBookmarkEntity?

  @Query("UPDATE secure_bookmarks SET mediaId = :newMediaId, updatedAt = :updatedAt WHERE mediaId = :oldMediaId")
  suspend fun reassignBookmarks(oldMediaId: String, newMediaId: String, updatedAt: Long = System.currentTimeMillis())

  @Query("UPDATE secure_bookmarks SET thumbnailPath = :thumbnailPath, updatedAt = :updatedAt WHERE id = :id")
  suspend fun updateThumbnail(id: Long, thumbnailPath: String?, updatedAt: Long = System.currentTimeMillis()): Int

  @Query("UPDATE secure_bookmarks SET updatedAt = :updatedAt WHERE id = :id")
  suspend fun touchUpdatedAt(id: Long, updatedAt: Long = System.currentTimeMillis())

  @Query("DELETE FROM secure_bookmarks WHERE id = :id")
  suspend fun delete(id: Long)

  @Query("DELETE FROM secure_bookmarks WHERE id IN (:ids)")
  suspend fun deleteByIds(ids: List<Long>)

  @Query("DELETE FROM secure_bookmarks WHERE mediaId = :mediaId")
  suspend fun deleteByMediaId(mediaId: String)

  @Query("DELETE FROM secure_bookmarks")
  suspend fun deleteAllBookmarks()

  @Query("SELECT groupKey FROM secure_bookmark_media_links WHERE mediaId = :mediaId LIMIT 1")
  suspend fun findLinkedGroupKey(mediaId: String): String?

  @Query("SELECT groupKey FROM secure_bookmark_media_links WHERE mediaId = :mediaId LIMIT 1")
  fun observeLinkedGroupKey(mediaId: String): Flow<String?>

  @Query("SELECT * FROM secure_bookmark_media_links")
  fun observeAllLinks(): Flow<List<SecureBookmarkMediaLinkEntity>>

  @Query("SELECT * FROM secure_bookmark_media_links")
  suspend fun getAllLinks(): List<SecureBookmarkMediaLinkEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertMediaLink(link: SecureBookmarkMediaLinkEntity)

  @Query("DELETE FROM secure_bookmark_media_links WHERE mediaId = :mediaId")
  suspend fun deleteMediaLink(mediaId: String)

  @Query("DELETE FROM secure_bookmark_media_links")
  suspend fun deleteAllLinks()

  @Query("UPDATE secure_bookmark_media_links SET groupKey = :targetGroupKey WHERE groupKey = :sourceGroupKey")
  suspend fun reassignLinks(sourceGroupKey: String, targetGroupKey: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertGroup(group: SecureBookmarkGroupEntity)

  @Query("SELECT * FROM secure_bookmark_groups WHERE groupKey = :groupKey LIMIT 1")
  suspend fun getGroup(groupKey: String): SecureBookmarkGroupEntity?

  @Query("SELECT * FROM secure_bookmark_groups WHERE groupKey = :groupKey LIMIT 1")
  fun observeGroup(groupKey: String): Flow<SecureBookmarkGroupEntity?>

  @Query("SELECT * FROM secure_bookmark_groups")
  suspend fun getAllGroups(): List<SecureBookmarkGroupEntity>

  @Query("SELECT * FROM secure_bookmark_groups ORDER BY updatedAt DESC")
  fun observeAllGroups(): Flow<List<SecureBookmarkGroupEntity>>

  @Query("UPDATE secure_bookmark_groups SET sourceUrl = :sourceUrl, updatedAt = :updatedAt WHERE groupKey = :groupKey")
  suspend fun updateGroupSourceUrl(groupKey: String, sourceUrl: String?, updatedAt: Long = System.currentTimeMillis())

  @Query("DELETE FROM secure_bookmark_groups WHERE groupKey = :groupKey")
  suspend fun deleteGroup(groupKey: String)

  @Query("DELETE FROM secure_bookmark_groups")
  suspend fun deleteAllGroups()

  @Query("SELECT * FROM secure_bookmark_groups WHERE presetCode = :presetCode ORDER BY updatedAt DESC LIMIT 1")
  suspend fun findGroupByPresetCode(presetCode: String): SecureBookmarkGroupEntity?

  @Query(
    "SELECT * FROM secure_bookmark_groups WHERE lower(oshash) = lower(:oshash) AND oshash IS NOT NULL " +
      "ORDER BY updatedAt DESC LIMIT 1",
  )
  suspend fun findGroupByOhash(oshash: String): SecureBookmarkGroupEntity?

  @Query("SELECT * FROM secure_bookmark_groups WHERE fingerprint = :fingerprint ORDER BY updatedAt DESC LIMIT 1")
  suspend fun findGroupByFingerprint(fingerprint: String): SecureBookmarkGroupEntity?

  @Query(
    """
    SELECT * FROM secure_bookmark_groups
    WHERE stemKey = :stemKey
      AND (:durationSec <= 0 OR durationSec <= 0 OR ABS(durationSec - :durationSec) <= 3)
    ORDER BY updatedAt DESC
    LIMIT 1
    """,
  )
  suspend fun findGroupByStemAndDuration(stemKey: String, durationSec: Int): SecureBookmarkGroupEntity?

  @Query(
    """
    SELECT g.groupKey, g.presetCode, g.fingerprint, g.stemKey, g.durationSec, g.width, g.height,
      g.fileSizeBytes, g.sourceLabel, g.updatedAt,
      (
        SELECT COUNT(*) FROM secure_bookmarks b
        WHERE b.mediaId = g.groupKey
          OR b.mediaId IN (
            SELECT l.mediaId FROM secure_bookmark_media_links l WHERE l.groupKey = g.groupKey
          )
      ) AS bookmarkCount
    FROM secure_bookmark_groups g
    WHERE (
      SELECT COUNT(*) FROM secure_bookmarks b
      WHERE b.mediaId = g.groupKey
        OR b.mediaId IN (
          SELECT l.mediaId FROM secure_bookmark_media_links l WHERE l.groupKey = g.groupKey
        )
    ) > 0
    ORDER BY g.updatedAt DESC
    """,
  )
  fun observePresetSummaries(): Flow<List<BookmarkGroupSummary>>

  @Query("UPDATE secure_bookmark_groups SET presetCode = :presetCode, updatedAt = :updatedAt WHERE groupKey = :groupKey")
  suspend fun updatePresetCode(
    groupKey: String,
    presetCode: String?,
    updatedAt: Long = System.currentTimeMillis(),
  )
}

@Dao
interface SecureBookmarkTagDao {
  @Query("SELECT * FROM secure_bookmark_tags WHERE bookmarkId = :bookmarkId ORDER BY id ASC")
  suspend fun tagsFor(bookmarkId: Long): List<SecureBookmarkTagEntity>

  @Query("SELECT * FROM secure_bookmark_tags ORDER BY id ASC")
  fun observeAllTags(): Flow<List<SecureBookmarkTagEntity>>

  @Query("SELECT * FROM secure_bookmark_tags ORDER BY id ASC")
  suspend fun getAllTagsOnce(): List<SecureBookmarkTagEntity>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAll(tags: List<SecureBookmarkTagEntity>)

  @Query("DELETE FROM secure_bookmark_tags WHERE bookmarkId = :bookmarkId")
  suspend fun clearForBookmark(bookmarkId: Long)

  @Query("DELETE FROM secure_bookmark_tags")
  suspend fun deleteAllTags()

  @Query("SELECT * FROM secure_bookmark_tag_memory ORDER BY useCount DESC, lastUsedAt DESC")
  fun observeMemory(): Flow<List<SecureBookmarkTagMemoryEntity>>

  @Query("SELECT * FROM secure_bookmark_tag_memory ORDER BY useCount DESC, lastUsedAt DESC")
  suspend fun getAllMemoryOnce(): List<SecureBookmarkTagMemoryEntity>

  @Query("DELETE FROM secure_bookmark_tag_memory WHERE nameHash IN (:nameHashes)")
  suspend fun deleteMemoryByHashes(nameHashes: List<String>)

  @Query("SELECT * FROM secure_bookmark_tag_memory WHERE nameHash = :nameHash LIMIT 1")
  suspend fun getMemory(nameHash: String): SecureBookmarkTagMemoryEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertMemory(entry: SecureBookmarkTagMemoryEntity)

  @Query("DELETE FROM secure_bookmark_tag_memory WHERE nameHash = :nameHash")
  suspend fun forget(nameHash: String)

  @Query("DELETE FROM secure_bookmark_tag_memory")
  suspend fun deleteAllMemory()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertMediaRef(ref: SecureBookmarkMediaRefEntity)

  @Query("SELECT * FROM secure_bookmark_media_refs WHERE mediaId = :mediaId LIMIT 1")
  suspend fun mediaRef(mediaId: String): SecureBookmarkMediaRefEntity?

  @Query("SELECT * FROM secure_bookmark_media_refs")
  fun observeMediaRefs(): Flow<List<SecureBookmarkMediaRefEntity>>

  @Query("SELECT * FROM secure_bookmark_media_refs")
  suspend fun getAllMediaRefs(): List<SecureBookmarkMediaRefEntity>

  @Query("DELETE FROM secure_bookmark_media_refs WHERE mediaId = :mediaId")
  suspend fun deleteMediaRef(mediaId: String)

  @Query("DELETE FROM secure_bookmark_media_refs")
  suspend fun deleteAllMediaRefs()
}

@Dao
interface SecurePlaybackStateDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: SecurePlaybackStateEntity)

  @Query("SELECT * FROM secure_playback_state WHERE mediaKey = :mediaKey LIMIT 1")
  suspend fun getByMediaKey(mediaKey: String): SecurePlaybackStateEntity?

  @Query("SELECT * FROM secure_playback_state ORDER BY updatedAt DESC")
  suspend fun getAll(): List<SecurePlaybackStateEntity>

  @Query("DELETE FROM secure_playback_state WHERE mediaKey = :mediaKey")
  suspend fun deleteByMediaKey(mediaKey: String)

  @Query("DELETE FROM secure_playback_state WHERE mediaKey IN (:mediaKeys)")
  suspend fun deleteByMediaKeys(mediaKeys: List<String>)

  @Query("DELETE FROM secure_playback_state")
  suspend fun clearAll()
}

@Dao
interface SecureBookmarkSyncTombstoneDao {
  @Query("SELECT * FROM secure_bookmark_sync_tombstones ORDER BY deletedAt DESC")
  suspend fun getAll(): List<SecureBookmarkSyncTombstoneEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: SecureBookmarkSyncTombstoneEntity)

  @Query("DELETE FROM secure_bookmark_sync_tombstones WHERE syncUuid = :syncUuid")
  suspend fun deleteByUuid(syncUuid: String)

  @Query("DELETE FROM secure_bookmark_sync_tombstones")
  suspend fun clearAll()
}

@Dao
interface SecureVideoMetadataDao {
  @Query("SELECT * FROM secure_video_metadata WHERE pathHash = :pathHash LIMIT 1")
  suspend fun getMetadata(pathHash: String): SecureVideoMetadataEntity?

  @Query("SELECT * FROM secure_video_metadata WHERE pathHash IN (:pathHashes)")
  suspend fun getMetadataBatch(pathHashes: List<String>): List<SecureVideoMetadataEntity>

  @Query("SELECT * FROM secure_video_metadata ORDER BY lastScanned DESC")
  suspend fun getAll(): List<SecureVideoMetadataEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMetadata(metadata: SecureVideoMetadataEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMetadataBatch(metadata: List<SecureVideoMetadataEntity>)

  @Query("DELETE FROM secure_video_metadata WHERE pathHash IN (:pathHashes)")
  suspend fun deleteByHashes(pathHashes: List<String>)

  @Query("DELETE FROM secure_video_metadata")
  suspend fun clearAll()
}
