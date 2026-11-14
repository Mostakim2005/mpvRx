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
import app.gyrolet.mpvrx.database.entities.BookmarkMediaRefEntity
import app.gyrolet.mpvrx.database.entities.BookmarkTagEntity
import app.gyrolet.mpvrx.database.entities.BookmarkTagMemoryEntity
import kotlinx.coroutines.flow.Flow

/** A bookmark flattened with the media it belongs to, for the cross-video gallery. */
data class BookmarkGalleryRow(
  val id: Long,
  val mediaId: String,
  val positionMs: Long,
  val title: String,
  val createdAt: Long,
  val thumbnailPath: String?,
)

@Dao
interface BookmarkTagDao {
  @Query("SELECT * FROM bookmark_tags WHERE bookmarkId = :bookmarkId ORDER BY name")
  suspend fun tagsFor(bookmarkId: Long): List<BookmarkTagEntity>

  @Query("SELECT bookmarkId, name FROM bookmark_tags")
  fun observeAllPairs(): Flow<List<BookmarkTagPair>>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAll(tags: List<BookmarkTagEntity>)

  @Query("DELETE FROM bookmark_tags WHERE bookmarkId = :bookmarkId")
  suspend fun clearForBookmark(bookmarkId: Long)

  /** Suggestions: prefix matches first, then the most-used tags, so typing "lec" surfaces "lecture". */
  @Query(
    """
    SELECT * FROM bookmark_tag_memory
    WHERE :query = '' OR name LIKE :query || '%'
    ORDER BY useCount DESC, lastUsedAt DESC, name
    LIMIT :limit
    """,
  )
  fun observeSuggestions(query: String, limit: Int = 24): Flow<List<BookmarkTagMemoryEntity>>

  @Query("SELECT * FROM bookmark_tag_memory ORDER BY useCount DESC, lastUsedAt DESC, name")
  fun observeMemory(): Flow<List<BookmarkTagMemoryEntity>>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertMemory(entry: BookmarkTagMemoryEntity)

  @Query("UPDATE bookmark_tag_memory SET useCount = useCount + 1, lastUsedAt = :now, displayName = :displayName WHERE name = :name")
  suspend fun bumpMemory(name: String, displayName: String, now: Long = System.currentTimeMillis())

  @Query("DELETE FROM bookmark_tag_memory WHERE name = :name")
  suspend fun forget(name: String)

  /** Records how to replay a bookmarked video, so the gallery can reopen it at a timestamp. */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertMediaRef(ref: BookmarkMediaRefEntity)

  @Query("SELECT * FROM bookmark_media_refs WHERE mediaId = :mediaId LIMIT 1")
  suspend fun mediaRef(mediaId: String): BookmarkMediaRefEntity?

  @Query("SELECT * FROM bookmark_media_refs")
  fun observeMediaRefs(): Flow<List<BookmarkMediaRefEntity>>
}

/** Projection for the (bookmarkId -> tagName) join the gallery uses. */
data class BookmarkTagPair(
  val bookmarkId: Long,
  val name: String,
)
