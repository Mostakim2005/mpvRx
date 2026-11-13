/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.bookmark

import app.gyrolet.mpvrx.database.entities.BookmarkGroupEntity
import app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookmarkMediaMatcherTest {
  @Test
  fun exactFingerprintRequiresAllPositiveMetricsAndMatchesAcrossPaths() {
    val fpOriginal = BookmarkMediaMatcher.exactFingerprint(fileSizeBytes = 1_450_000_000L, width = 1920, height = 1080, durationSec = 5400)
    val fpRenamedOrMoved = BookmarkMediaMatcher.exactFingerprint(fileSizeBytes = 1_450_000_000L, width = 1920, height = 1080, durationSec = 5400)
    assertNotNull(fpOriginal)
    assertEquals(fpOriginal, fpRenamedOrMoved)

    assertNull(BookmarkMediaMatcher.exactFingerprint(0L, 1920, 1080, 5400))
    assertNull(BookmarkMediaMatcher.exactFingerprint(1_450_000_000L, 0, 1080, 5400))
    assertNull(BookmarkMediaMatcher.exactFingerprint(1_450_000_000L, 1920, 0, 5400))
    assertNull(BookmarkMediaMatcher.exactFingerprint(1_450_000_000L, 1920, 1080, 0))
  }

  @Test
  fun normalizedStemMatchesQualityUpgradesAndCopyNames() {
    val lowRes = BookmarkMediaMatcher.normalizedStem("/storage/emulated/0/Movies/Inception.2010.720p.BluRay.x264-YIFY.mp4")
    val upgraded4k = BookmarkMediaMatcher.normalizedStem("file:///sdcard/4K/Inception (2010) 2160p WEB-DL H.265 10bit HDR AAC.mkv")
    val duplicateCopy = BookmarkMediaMatcher.normalizedStem("Inception 2010 (1).mkv")

    assertEquals("inception 2010", lowRes)
    assertEquals(lowRes, upgraded4k)
    assertEquals(lowRes, duplicateCopy)
  }

  @Test
  fun isUpgradeMatchRequiresMatchingStemAndDurationWithinTwoSeconds() {
    val stemA = BookmarkMediaMatcher.normalizedStem("Show.Name.S01E05.720p.WEB-DL.x264.mkv")
    val stemB = BookmarkMediaMatcher.normalizedStem("Show Name - S01E05 [1080p HEVC 10bit].mkv")
    val otherEp = BookmarkMediaMatcher.normalizedStem("Show.Name.S01E06.1080p.HEVC.mkv")

    assertTrue(BookmarkMediaMatcher.isUpgradeMatch(stemA, 1420, stemB, 1422))
    assertFalse(BookmarkMediaMatcher.isUpgradeMatch(stemA, 1420, stemB, 1425))
    assertFalse(BookmarkMediaMatcher.isUpgradeMatch(stemA, 1420, otherEp, 1420))
    assertFalse(BookmarkMediaMatcher.isUpgradeMatch(stemA, 0, stemB, 0))
  }

  @Test
  fun presetArchiveRoundTripsBookmarksAndPrivateThumbnails() {
    val srcDir = Files.createTempDirectory("bm_src").toFile()
    val dstDir = Files.createTempDirectory("bm_dst").toFile()
    try {
      val thumbFile = File(srcDir, "thumb_a.jpg").apply {
        writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4))
      }
      val group = BookmarkGroupEntity(
        groupKey = "group_1",
        presetCode = "boss_fight",
        sourceLabel = "Boss.Fight.720p.mp4",
        durationSec = 320,
        width = 1280,
        height = 720,
        fileSizeBytes = 123456L,
      )
      val sourceBookmarks = listOf(
        PlaybackBookmarkEntity(id = 10, mediaId = "group_1", positionMs = 45_000L, title = "Phase 1", thumbnailPath = thumbFile.absolutePath),
        PlaybackBookmarkEntity(id = 11, mediaId = "group_1", positionMs = 125_000L, title = "Phase 2", thumbnailPath = null),
      )

      val zipOut = ByteArrayOutputStream()
      BookmarkPresetArchive.writeArchive(zipOut, group, sourceBookmarks)

      val imported = BookmarkPresetArchive.readArchive(
        input = ByteArrayInputStream(zipOut.toByteArray()),
        targetGroupKey = "group_2",
        thumbnailOutputDir = dstDir,
      )

      assertEquals("boss_fight", imported.manifest.presetCode)
      assertEquals(2, imported.bookmarks.size)
      assertEquals("group_2", imported.bookmarks[0].mediaId)
      assertEquals(45_000L, imported.bookmarks[0].positionMs)
      assertEquals("Phase 1", imported.bookmarks[0].title)
      val extractedPath = imported.bookmarks[0].thumbnailPath
      assertNotNull(extractedPath)
      val extractedFile = File(extractedPath)
      assertTrue(extractedFile.isFile)
      assertEquals(thumbFile.readBytes().toList(), extractedFile.readBytes().toList())
      assertTrue(File(dstDir, ".nomedia").exists())
      assertNull(imported.bookmarks[1].thumbnailPath)
    } finally {
      srcDir.deleteRecursively()
      dstDir.deleteRecursively()
    }
  }
}
