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
  tableName = "bookmark_groups",
  indices = [
    Index(value = ["presetCode"]),
    Index(value = ["oshash"]),
    Index(value = ["fingerprint"]),
    Index(value = ["stemKey"]),
  ],
)
data class BookmarkGroupEntity(
  @PrimaryKey val groupKey: String,
  val presetCode: String? = null,
  val oshash: String? = null,
  val fingerprint: String? = null,
  val stemKey: String? = null,
  val durationSec: Int = 0,
  val width: Int = 0,
  val height: Int = 0,
  val fileSizeBytes: Long = 0L,
  val sourceLabel: String = "",
  val updatedAt: Long = System.currentTimeMillis(),
)
