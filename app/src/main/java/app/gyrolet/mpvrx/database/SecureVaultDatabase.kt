/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.gyrolet.mpvrx.database.dao.SecureBookmarkDao
import app.gyrolet.mpvrx.database.dao.SecureBookmarkTagDao
import app.gyrolet.mpvrx.database.dao.SecurePlaybackStateDao
import app.gyrolet.mpvrx.database.dao.SecureVaultMediaDao
import app.gyrolet.mpvrx.database.dao.SecureVideoMetadataDao
import app.gyrolet.mpvrx.database.entities.SecureBookmarkEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkGroupEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkMediaLinkEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkMediaRefEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkTagEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkTagMemoryEntity
import app.gyrolet.mpvrx.database.entities.SecurePlaybackStateEntity
import app.gyrolet.mpvrx.database.entities.SecureVaultMediaEntity
import app.gyrolet.mpvrx.database.entities.SecureVideoMetadataEntity

@Database(
  entities = [
    SecureVaultMediaEntity::class,
    SecureBookmarkEntity::class,
    SecureBookmarkGroupEntity::class,
    SecureBookmarkMediaLinkEntity::class,
    SecureBookmarkMediaRefEntity::class,
    SecureBookmarkTagEntity::class,
    SecureBookmarkTagMemoryEntity::class,
    SecurePlaybackStateEntity::class,
    SecureVideoMetadataEntity::class,
  ],
  version = 2,
  exportSchema = true,
)
abstract class SecureVaultDatabase : RoomDatabase() {
  abstract fun secureVaultMediaDao(): SecureVaultMediaDao

  abstract fun secureBookmarkDao(): SecureBookmarkDao

  abstract fun secureBookmarkTagDao(): SecureBookmarkTagDao

  abstract fun securePlaybackStateDao(): SecurePlaybackStateDao

  abstract fun secureVideoMetadataDao(): SecureVideoMetadataDao
}
