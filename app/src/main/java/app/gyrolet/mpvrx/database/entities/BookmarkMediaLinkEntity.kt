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
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "bookmark_media_links",
  indices = [Index(value = ["groupKey"])],
)
data class BookmarkMediaLinkEntity(
  @PrimaryKey val mediaId: String,
  val groupKey: String,
  val updatedAt: Long = System.currentTimeMillis(),
)
