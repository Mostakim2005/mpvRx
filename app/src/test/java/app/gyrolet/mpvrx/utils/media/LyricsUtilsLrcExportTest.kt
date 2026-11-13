/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsUtilsLrcExportTest {
  @Test
  fun formatLrcTimestampFormatsAndClampsCleanly() {
    assertEquals("00:00.00", LyricsUtils.formatLrcTimestamp(-500L))
    assertEquals("00:00.00", LyricsUtils.formatLrcTimestamp(0L))
    assertEquals("01:05.43", LyricsUtils.formatLrcTimestamp(65_434L))
    assertEquals("01:05.44", LyricsUtils.formatLrcTimestamp(65_439L))
    assertEquals("61:01.06", LyricsUtils.formatLrcTimestamp(3_661_059L))
  }

  @Test
  fun formatToLrcBakesPositiveAndNegativeOffsetsWithRoundTripParity() {
    val lyrics =
      Lyrics(
        synced =
          listOf(
            SyncedLine(time = 10_000, line = "First line"),
            SyncedLine(time = 20_000, line = "Second line"),
          ),
      )

    // With +2000ms offset, active line at 8000ms matches 10000ms -> exported timestamps shift -2000ms.
    val shiftedEarly = LyricsUtils.formatToLrc(lyrics, title = "Song\nTitle", artist = "Artist", offsetMs = 2_000)
    assertEquals(
      "[ti:Song Title]\n[ar:Artist]\n\n[00:08.00]First line\n[00:18.00]Second line",
      shiftedEarly,
    )

    val parsedEarly = LyricsUtils.parseLyrics(shiftedEarly)
    assertEquals(-1, LyricsUtils.getActiveLineIndex(parsedEarly.synced, positionMs = 7_990L, offsetMs = 0))
    assertEquals(0, LyricsUtils.getActiveLineIndex(parsedEarly.synced, positionMs = 8_000L, offsetMs = 0))
    assertEquals(1, LyricsUtils.getActiveLineIndex(parsedEarly.synced, positionMs = 18_000L, offsetMs = 0))

    // With -1500ms offset, exported timestamps shift +1500ms.
    val shiftedLate = LyricsUtils.formatToLrc(lyrics, offsetMs = -1_500)
    assertEquals("[00:11.50]First line\n[00:21.50]Second line", shiftedLate)
  }

  @Test
  fun formatToLrcClampsNegativeShiftedTimesAndHandlesPlainFallback() {
    val lyrics =
      Lyrics(
        synced =
          listOf(
            SyncedLine(time = 1_000, line = "Intro\r\nline"),
            SyncedLine(time = 6_000, line = "Verse"),
          ),
      )
    val clamped = LyricsUtils.formatToLrc(lyrics, offsetMs = 3_500)
    assertEquals("[00:00.00]Intro line\n[00:02.50]Verse", clamped)

    val plainLyrics = Lyrics(plain = listOf("Line A", "Line B"))
    assertEquals("Line A\nLine B", LyricsUtils.formatToLrc(plainLyrics, offsetMs = 2_000))

    val emptyLyrics = Lyrics(plain = null, synced = null)
    assertEquals("", LyricsUtils.formatToLrc(emptyLyrics))
  }
}
