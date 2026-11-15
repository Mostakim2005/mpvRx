/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Maps a music track to its artist, song name, local audio file, optional companion video
 * (audio-less or full), synchronized lyrics (.lrc), YouTube URL, and timing offset profile.
 * Delete-proof and uninstall-proof when hosted in external persistent storage.
 */
@Entity(
  tableName = "music_companion",
  indices = [
    Index(value = ["audio_path"], unique = true),
    Index(value = ["artist", "song_name"]),
    Index(value = ["youtube_url"]),
  ],
)
data class MusicCompanionEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,

  @ColumnInfo(name = "artist")
  val artist: String,

  @ColumnInfo(name = "song_name")
  val songName: String,

  @ColumnInfo(name = "audio_path")
  val audioPath: String,

  @ColumnInfo(name = "youtube_url")
  val youtubeUrl: String? = null,

  @ColumnInfo(name = "companion_video_path")
  val companionVideoPath: String? = null,

  @ColumnInfo(name = "lyrics_path")
  val lyricsPath: String? = null,

  @ColumnInfo(name = "is_audioless_video")
  val isAudioLessVideo: Boolean = true,

  @ColumnInfo(name = "has_embedded_lyrics")
  val hasEmbeddedLyrics: Boolean = false,

  @ColumnInfo(name = "lyric_offset_ms")
  val lyricOffsetMs: Long = 0L,

  @ColumnInfo(name = "artwork_path")
  val artworkPath: String? = null,

  @ColumnInfo(name = "album")
  val album: String? = null,

  @ColumnInfo(name = "year")
  val year: String? = null,

  @ColumnInfo(name = "genre")
  val genre: String? = null,

  @ColumnInfo(name = "updated_at")
  val updatedAt: Long = System.currentTimeMillis(),
)
