/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.gyrolet.mpvrx.database.entities.MusicCompanionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicCompanionDao {
  @Query("SELECT * FROM music_companion WHERE audio_path = :audioPath LIMIT 1")
  suspend fun getByAudioPath(audioPath: String): MusicCompanionEntity?

  @Query("SELECT * FROM music_companion WHERE audio_path = :audioPath LIMIT 1")
  fun observeByAudioPath(audioPath: String): Flow<MusicCompanionEntity?>

  @Query("SELECT * FROM music_companion WHERE artist = :artist AND song_name = :songName LIMIT 1")
  suspend fun getByArtistAndTitle(artist: String, songName: String): MusicCompanionEntity?

  @Query("SELECT * FROM music_companion WHERE youtube_url = :youtubeUrl LIMIT 1")
  suspend fun getByYoutubeUrl(youtubeUrl: String): MusicCompanionEntity?

  @Query("SELECT * FROM music_companion ORDER BY updated_at DESC")
  fun observeAll(): Flow<List<MusicCompanionEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrReplace(entity: MusicCompanionEntity): Long

  @Update
  suspend fun update(entity: MusicCompanionEntity)

  @Query("DELETE FROM music_companion WHERE audio_path = :audioPath")
  suspend fun deleteByAudioPath(audioPath: String)

  @Query("UPDATE music_companion SET audio_path = :newPath WHERE audio_path = :oldPath")
  suspend fun updateAudioPath(oldPath: String, newPath: String)

  @Query("UPDATE music_companion SET lyric_offset_ms = :offsetMs, has_embedded_lyrics = :embedded, updated_at = :timestamp WHERE id = :id")
  suspend fun updateLyricSync(id: Long, offsetMs: Long, embedded: Boolean, timestamp: Long = System.currentTimeMillis())
}
