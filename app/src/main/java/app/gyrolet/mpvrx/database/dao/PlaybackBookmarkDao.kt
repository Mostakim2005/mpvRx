package app.gyrolet.mpvrx.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity
import kotlinx.coroutines.flow.Flow

import androidx.room.OnConflictStrategy
import app.gyrolet.mpvrx.database.entities.BookmarkGroupEntity
import app.gyrolet.mpvrx.database.entities.BookmarkMediaLinkEntity

data class BookmarkGroupSummary(
  val groupKey: String,
  val presetCode: String?,
  val fingerprint: String?,
  val stemKey: String?,
  val durationSec: Int,
  val width: Int,
  val height: Int,
  val fileSizeBytes: Long,
  val sourceLabel: String,
  val updatedAt: Long,
  val bookmarkCount: Int,
)

@Dao
interface PlaybackBookmarkDao {
  @Query("SELECT * FROM playback_bookmarks WHERE mediaId = :mediaId ORDER BY positionMs, createdAt")
  fun observe(mediaId: String): Flow<List<PlaybackBookmarkEntity>>

  @Query("SELECT * FROM playback_bookmarks WHERE mediaId = :mediaId ORDER BY positionMs, createdAt")
  suspend fun getInGroup(mediaId: String): List<PlaybackBookmarkEntity>

  @Query("SELECT COUNT(*) FROM playback_bookmarks WHERE mediaId = :mediaId")
  suspend fun countInGroup(mediaId: String): Int

  @Query("SELECT COUNT(*) FROM playback_bookmarks WHERE thumbnailPath = :thumbnailPath")
  suspend fun countByThumbnailPath(thumbnailPath: String): Int

  @Insert
  suspend fun add(bookmark: PlaybackBookmarkEntity): Long

  @Insert
  suspend fun addAll(bookmarks: List<PlaybackBookmarkEntity>): List<Long>

  @Query("UPDATE playback_bookmarks SET title = :title WHERE id = :id")
  suspend fun rename(id: Long, title: String)

  @Query("UPDATE playback_bookmarks SET thumbnailPath = :thumbnailPath WHERE id = :id")
  suspend fun updateThumbnail(id: Long, thumbnailPath: String?): Int

  @Query("DELETE FROM playback_bookmarks WHERE id = :id")
  suspend fun delete(id: Long)

  @Query("SELECT groupKey FROM bookmark_media_links WHERE mediaId = :mediaId LIMIT 1")
  suspend fun findLinkedGroupKey(mediaId: String): String?

  @Query("SELECT groupKey FROM bookmark_media_links WHERE mediaId = :mediaId LIMIT 1")
  fun observeLinkedGroupKey(mediaId: String): Flow<String?>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertMediaLink(link: BookmarkMediaLinkEntity)

  @Query("UPDATE bookmark_media_links SET groupKey = :newGroupKey, updatedAt = :updatedAt WHERE groupKey = :oldGroupKey")
  suspend fun reassignLinks(
    oldGroupKey: String,
    newGroupKey: String,
    updatedAt: Long = System.currentTimeMillis(),
  )

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertGroup(group: BookmarkGroupEntity)

  @Query("SELECT * FROM bookmark_groups WHERE groupKey = :groupKey LIMIT 1")
  suspend fun getGroup(groupKey: String): BookmarkGroupEntity?

  @Query("SELECT * FROM bookmark_groups WHERE groupKey = :groupKey LIMIT 1")
  fun observeGroup(groupKey: String): Flow<BookmarkGroupEntity?>

  @Query(
    """
    SELECT g.* FROM bookmark_groups g
    WHERE g.presetCode = :presetCode COLLATE NOCASE
      AND g.presetCode IS NOT NULL
    ORDER BY g.updatedAt DESC
    LIMIT 1
    """,
  )
  suspend fun findGroupByPresetCode(presetCode: String): BookmarkGroupEntity?

  @Query(
    """
    SELECT g.* FROM bookmark_groups g
    WHERE g.fingerprint = :fingerprint
      AND g.fingerprint IS NOT NULL
      AND EXISTS (SELECT 1 FROM playback_bookmarks b WHERE b.mediaId = g.groupKey)
    ORDER BY g.updatedAt DESC
    LIMIT 1
    """,
  )
  suspend fun findGroupByFingerprint(fingerprint: String): BookmarkGroupEntity?

  @Query(
    """
    SELECT g.* FROM bookmark_groups g
    WHERE g.stemKey = :stemKey
      AND g.stemKey IS NOT NULL
      AND g.durationSec > 0
      AND ABS(g.durationSec - :durationSec) <= 2
      AND EXISTS (SELECT 1 FROM playback_bookmarks b WHERE b.mediaId = g.groupKey)
    ORDER BY g.updatedAt DESC
    LIMIT 1
    """,
  )
  suspend fun findGroupByStemAndDuration(
    stemKey: String,
    durationSec: Int,
  ): BookmarkGroupEntity?

  @Query(
    """
    SELECT g.groupKey, g.presetCode, g.fingerprint, g.stemKey, g.durationSec, g.width, g.height,
           g.fileSizeBytes, g.sourceLabel, g.updatedAt,
           COUNT(b.id) AS bookmarkCount
    FROM bookmark_groups g
    INNER JOIN playback_bookmarks b ON b.mediaId = g.groupKey
    GROUP BY g.groupKey
    ORDER BY g.updatedAt DESC
    """,
  )
  fun observePresetSummaries(): Flow<List<BookmarkGroupSummary>>

  @Query("UPDATE bookmark_groups SET presetCode = :presetCode, updatedAt = :updatedAt WHERE groupKey = :groupKey")
  suspend fun updatePresetCode(
    groupKey: String,
    presetCode: String?,
    updatedAt: Long = System.currentTimeMillis(),
  )
}