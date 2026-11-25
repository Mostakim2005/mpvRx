package app.gyrolet.mpvrx.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gyrolet.mpvrx.database.entities.BookmarkSyncTombstoneEntity

@Dao
interface BookmarkSyncTombstoneDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(tombstone: BookmarkSyncTombstoneEntity)

  @Query("SELECT * FROM bookmark_sync_tombstones ORDER BY deletedAt DESC")
  suspend fun getAll(): List<BookmarkSyncTombstoneEntity>

  @Query("DELETE FROM bookmark_sync_tombstones WHERE syncUuid = :syncUuid")
  suspend fun deleteBySyncUuid(syncUuid: String)

  @Query("DELETE FROM bookmark_sync_tombstones")
  suspend fun clearAll()
}
