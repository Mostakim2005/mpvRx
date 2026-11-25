package app.gyrolet.mpvrx.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmark_sync_tombstones")
data class BookmarkSyncTombstoneEntity(
  @PrimaryKey
  val syncUuid: String,
  val groupKey: String? = null,
  val deletedAt: Long = System.currentTimeMillis(),
)
