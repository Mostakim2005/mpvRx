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
import androidx.room.PrimaryKey

/**
 * Remembers which playable URI a bookmark's `mediaId` refers to.
 *
 * `playback_bookmarks.mediaId` is an opaque hash of the media identity, so on its own it cannot be
 * replayed: the cross-video gallery needs the actual URI to reopen a video at a timestamp. This row
 * is written whenever a bookmark is created, keyed by that same hash.
 */
@Entity(tableName = "bookmark_media_refs")
data class BookmarkMediaRefEntity(
  @PrimaryKey val mediaId: String,
  val playableUri: String,
  val title: String? = null,
  val artworkUri: String? = null,
  val updatedAt: Long = System.currentTimeMillis(),
)
