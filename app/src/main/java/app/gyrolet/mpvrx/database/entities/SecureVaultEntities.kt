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

@Entity(
  tableName = "secure_media",
  indices = [Index(value = ["secureFilePath"], unique = true)],
)
data class SecureVaultMediaEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val originalPath: String,
  val secureFilePath: String,
  val fileName: String,
  val fileSize: Long,
  val mimeType: String,
  val dateHidden: Long = System.currentTimeMillis(),
)

@Entity(
  tableName = "secure_bookmarks",
  indices = [Index(value = ["mediaId"])],
)
data class SecureBookmarkEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val mediaId: String,
  val positionMs: Long,
  val title: String,
  val createdAt: Long = System.currentTimeMillis(),
  val bookTrackId: Long? = null,
  val thumbnailPath: String? = null,
)

@Entity(
  tableName = "secure_bookmark_groups",
  indices = [
    Index(value = ["presetCode"]),
    Index(value = ["fingerprint"]),
    Index(value = ["stemKey"]),
  ],
)
data class SecureBookmarkGroupEntity(
  @PrimaryKey
  val groupKey: String,
  val presetCode: String? = null,
  val fingerprint: String? = null,
  val stemKey: String? = null,
  val durationSec: Int = 0,
  val width: Int = 0,
  val height: Int = 0,
  val fileSizeBytes: Long = 0L,
  val sourceLabel: String = "",
  val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
  tableName = "secure_bookmark_media_links",
  indices = [Index(value = ["groupKey"])],
)
data class SecureBookmarkMediaLinkEntity(
  @PrimaryKey
  val mediaId: String,
  val groupKey: String,
  val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "secure_bookmark_media_refs")
data class SecureBookmarkMediaRefEntity(
  @PrimaryKey
  val mediaId: String,
  val playableUri: String,
  val title: String? = null,
  val artworkUri: String? = null,
  val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
  tableName = "secure_bookmark_tags",
  foreignKeys = [
    ForeignKey(
      entity = SecureBookmarkEntity::class,
      parentColumns = ["id"],
      childColumns = ["bookmarkId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["bookmarkId"]),
    Index(value = ["nameHash"]),
    Index(value = ["nameHash", "bookmarkId"], unique = true),
  ],
)
data class SecureBookmarkTagEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val bookmarkId: Long,
  val nameHash: String,
  val name: String,
  val displayName: String,
  val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "secure_bookmark_tag_memory")
data class SecureBookmarkTagMemoryEntity(
  @PrimaryKey
  val nameHash: String,
  val name: String,
  val displayName: String,
  val useCount: Int = 1,
  val lastUsedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "secure_playback_state")
data class SecurePlaybackStateEntity(
  @PrimaryKey
  val mediaKey: String,
  val lastPosition: Int = 0,
  val playbackSpeed: Float = 1f,
  val videoZoom: Float = 0f,
  val sid: Int = -1,
  val secondarySid: Int = -1,
  val subDelay: Int = 0,
  val subSpeed: Float = 1f,
  val aid: Int = -1,
  val audioDelay: Int = 0,
  val timeRemaining: Int = 0,
  val externalSubtitles: String = "",
  val hasBeenWatched: Boolean = false,
  val newLabelOverride: Boolean? = null,
)

@Entity(tableName = "secure_video_metadata")
data class SecureVideoMetadataEntity(
  @PrimaryKey
  val pathHash: String,
  val size: Long,
  val dateModified: Long,
  val duration: Long,
  val width: Int,
  val height: Int,
  val fps: Float,
  val hasEmbeddedSubtitles: Boolean = false,
  val subtitleCodec: String = "",
  val lastScanned: Long,
)
