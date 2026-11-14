/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One tag attached to one bookmark.
 *
 * Tags are stored per bookmark rather than as a delimited string on the bookmark row so that a
 * gallery filter can be a plain indexed SQL match instead of scanning and splitting every title.
 * [name] is always stored normalized (see `BookmarkTags.normalize`) so "Lecture" and "lecture"
 * cannot both exist.
 */
@Entity(
  tableName = "bookmark_tags",
  foreignKeys = [
    ForeignKey(
      entity = PlaybackBookmarkEntity::class,
      parentColumns = ["id"],
      childColumns = ["bookmarkId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index(value = ["bookmarkId"]), Index(value = ["name"]), Index(value = ["name", "bookmarkId"], unique = true)],
)
data class BookmarkTagEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val bookmarkId: Long,
  /** Normalized, lower-case tag text. This is what filtering and suggestions match on. */
  val name: String,
  /** The casing the user last typed, so chips display pleasingly while still matching case-insensitively. */
  val displayName: String = name,
  val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Usage memory for tag autocomplete, independent of any single bookmark.
 *
 * A tag survives deleting every bookmark that used it: [useCount] is what makes the most-used
 * suggestions surface first, and keeping the row means "recently used" stays meaningful.
 */
@Entity(tableName = "bookmark_tag_memory", indices = [Index(value = ["name"], unique = true)])
data class BookmarkTagMemoryEntity(
  @PrimaryKey val name: String,
  val displayName: String = name,
  val useCount: Int = 1,
  val lastUsedAt: Long = System.currentTimeMillis(),
)
