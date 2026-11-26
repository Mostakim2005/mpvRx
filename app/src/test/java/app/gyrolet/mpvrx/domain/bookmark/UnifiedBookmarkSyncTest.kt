package app.gyrolet.mpvrx.domain.bookmark

import app.gyrolet.mpvrx.database.entities.BookmarkGroupEntity
import app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity
import app.gyrolet.mpvrx.preferences.BookmarkSyncPreferences
import app.gyrolet.mpvrx.repository.bookmark.PcBookmarkSyncRepository
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UnifiedBookmarkSyncTest {
  @Test
  fun oshashMatchesKnownVectorAndRejectsTinyInputs() {
    val head = ByteArray(65_536) { 0 }
    val tail = ByteArray(65_536) { 0 }
    // First LE 64-bit word = 1, last LE 64-bit word = 2, size = 131072 (0x20000) -> 0x20003
    head[0] = 1
    tail[0] = 2
    assertEquals("0000000000020003", OshashCalculator.computeFromBytes(head, tail, 131_072L))
    assertNull(OshashCalculator.computeFromBytes(ByteArray(100), ByteArray(100), 100L))
  }

  @Test
  fun bookmarkPresetArchiveRoundTripsV2MetadataAndReadsLegacyV1() {
    val group =
      BookmarkGroupEntity(
        groupKey = "MIMK-266",
        presetCode = "MIMK-266",
        oshash = "deadbeefcafebabe",
        stemKey = "mimk-266",
        durationSec = 3600,
        width = 1920,
        height = 1080,
        fileSizeBytes = 1_500_000_000L,
        sourceLabel = "MIMK-266.mp4",
      )
    val bm =
      PlaybackBookmarkEntity(
        id = 11L,
        mediaId = "MIMK-266",
        positionMs = 95_000L,
        endPositionMs = 125_000L,
        primaryTag = "cowgirl",
        syncUuid = "uuid-sync-1",
        title = "Climax",
        createdAt = 1_700_000_000_000L,
      )
    val out = ByteArrayOutputStream()
    BookmarkPresetArchive.writeArchive(
      output = out,
      group = group,
      bookmarks = listOf(bm),
      tagsByBookmarkId = mapOf(11L to listOf("cowgirl", "paizuri")),
    )

    val tempDir = Files.createTempDirectory("mpvrx_preset_v2").toFile()
    try {
      val imported =
        BookmarkPresetArchive.readArchive(
          input = ByteArrayInputStream(out.toByteArray()),
          targetGroupKey = "MIMK-266",
          thumbnailOutputDir = tempDir,
        )
      assertEquals(2, imported.manifest.schemaVersion)
      assertEquals("deadbeefcafebabe", imported.manifest.oshash)
      assertEquals(1, imported.bookmarks.size)
      assertEquals(125_000L, imported.bookmarks[0].endPositionMs)
      assertEquals("cowgirl", imported.bookmarks[0].primaryTag)
      assertEquals("uuid-sync-1", imported.bookmarks[0].syncUuid)
      assertEquals(listOf("cowgirl", "paizuri"), imported.importedTagsByIndex[0])

      // Verify legacy v1 archive without v2 fields decodes safely
      val legacyJson =
        Json.encodeToString(
          BookmarkPresetManifest.serializer(),
          BookmarkPresetManifest(
            schemaVersion = 1,
            presetCode = "OLD-100",
            bookmarks = listOf(BookmarkPresetEntry(positionMs = 10_000L, title = "Intro")),
          ),
        )
      val legacyOut = ByteArrayOutputStream()
      ZipOutputStream(legacyOut).use { zip ->
        zip.putNextEntry(ZipEntry("preset.json"))
        zip.write(legacyJson.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }
      val legacyImported =
        BookmarkPresetArchive.readArchive(
          input = ByteArrayInputStream(legacyOut.toByteArray()),
          targetGroupKey = "OLD-100",
          thumbnailOutputDir = tempDir,
        )
      assertEquals(1, legacyImported.bookmarks.size)
      assertNull(legacyImported.bookmarks[0].endPositionMs)
      assertTrue(legacyImported.importedTagsByIndex[0].isEmpty())
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun syncHelpersNormalizeUrlsAndIsoTimestamps() {
    assertEquals("http://192.168.1.50:8000", BookmarkSyncPreferences.normalizeHubUrl(" 192.168.1.50:8000/ "))
    assertEquals("https://hub.local", BookmarkSyncPreferences.normalizeHubUrl("https://hub.local///"))
    val ms = PcBookmarkSyncRepository.parseIsoMillis("2026-11-25T12:00:00Z")
    assertTrue(ms > 0L)
    assertNotNull(PcBookmarkSyncRepository.formatIsoUtc(ms))
    assertEquals(0L, PcBookmarkSyncRepository.parseIsoMillis("invalid"))
  }

  @Test
  fun syncDiagnosticsExplainLoopbackAndTimeouts() {
    val loopbackErr =
      PcBookmarkSyncRepository.friendlyNetworkError(
        "http://127.0.0.1:8000",
        ConnectException("Failed to connect"),
      )
    assertTrue(loopbackErr.message.orEmpty().contains("phone", ignoreCase = true))

    val lanErr =
      PcBookmarkSyncRepository.friendlyNetworkError(
        "http://192.168.0.100:8000",
        ConnectException("Connection refused"),
      )
    assertTrue(lanErr.message.orEmpty().contains("192.168.0.100:8000"))

    val timeoutErr =
      PcBookmarkSyncRepository.friendlyNetworkError(
        "http://192.168.0.100:8000",
        SocketTimeoutException("timeout"),
      )
    assertTrue(timeoutErr.message.orEmpty().contains("Timed out", ignoreCase = true))

    val usbErr =
      PcBookmarkSyncRepository.friendlyNetworkError(
        "http://127.0.0.1:8000",
        ConnectException("Connection refused"),
        usbMode = true,
      )
    assertTrue(usbErr.message.orEmpty().contains("USB", ignoreCase = true))
  }

  @Test
  fun jvidExtractionHandlesExtraCharactersAndFc2() {
    assertEquals("MIMK-232", BookmarkMediaMatcher.extractJvidCode("[ThZu.Cc]_MIMK-232_Uncut_1080p.mp4"))
    assertEquals("MIMK-232", BookmarkMediaMatcher.extractJvidCode("mimk232c.mp4"))
    assertEquals("OKSN-191", BookmarkMediaMatcher.extractJvidCode("[4K] oksn191 - Title [HardSub].mkv"))
    assertEquals("FC2-PPV-1234567", BookmarkMediaMatcher.extractJvidCode("FC2_PPV_1234567_raw.mp4"))
    assertNull(BookmarkMediaMatcher.extractJvidCode("Inception.2010.1080p.x264.mp4"))
    assertEquals("ABC-123", BookmarkMediaMatcher.normalizePresetCode("  [4K] abc123 final cut  "))
    assertEquals("Unformatted Clip", BookmarkMediaMatcher.normalizePresetCode("  Unformatted   Clip  "))
  }

  @Test
  fun presetArchiveReadsStandalonePresetAndStashMarkersJson() {
    val tempDir = Files.createTempDirectory("mpvrx_json_import").toFile()
    try {
      val presetJson =
        """
        {
          "schemaVersion": 2,
          "presetCode": "_json-900_",
          "bookmarks": [
            {
              "positionMs": 45000,
              "endPositionMs": 60000,
              "title": "Peak",
              "primaryTag": "cowgirl",
              "tags": ["cowgirl", "pov"]
            }
          ]
        }
        """.trimIndent()
      val presetRes =
        BookmarkPresetArchive.readArchive(
          input = ByteArrayInputStream(presetJson.toByteArray(Charsets.UTF_8)),
          targetGroupKey = "JSON-900",
          thumbnailOutputDir = tempDir,
        )
      assertEquals("JSON-900", presetRes.manifest.presetCode)
      assertEquals(1, presetRes.bookmarks.size)
      assertEquals(45_000L, presetRes.bookmarks[0].positionMs)
      assertEquals(listOf("cowgirl", "pov"), presetRes.importedTagsByIndex[0])

      val stashJson =
        """
        {
          "scene_markers": [
            {
              "title": "Finish",
              "seconds": 600.0,
              "end_seconds": 630.0,
              "primary_tag": { "name": "cumshot" },
              "tags": [{ "name": "pov" }],
              "scene": { "code": "_CONV-042_", "title": "CONV-042 Sample" }
            }
          ]
        }
        """.trimIndent()
      val stashRes =
        BookmarkPresetArchive.readArchive(
          input = ByteArrayInputStream(stashJson.toByteArray(Charsets.UTF_8)),
          targetGroupKey = "CONV-042",
          thumbnailOutputDir = tempDir,
        )
      assertEquals("CONV-042", stashRes.manifest.presetCode)
      assertEquals(1, stashRes.bookmarks.size)
      assertEquals(600_000L, stashRes.bookmarks[0].positionMs)
      assertEquals(630_000L, stashRes.bookmarks[0].endPositionMs)
      assertEquals(listOf("cumshot", "pov"), stashRes.importedTagsByIndex[0])
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun syncGroupDtoPreservesSourceUrlField() {
    val dto =
      app.gyrolet.mpvrx.repository.bookmark.SyncGroupDto(
        groupKey = "URL-999",
        code = "URL-999",
        sourceUrl = "https://portal.example.invalid/watch/url-999",
      )
    val encoded = Json.encodeToString(app.gyrolet.mpvrx.repository.bookmark.SyncGroupDto.serializer(), dto)
    assertTrue(encoded.contains("\"source_url\":\"https://portal.example.invalid/watch/url-999\""))
    val decoded = Json.decodeFromString(app.gyrolet.mpvrx.repository.bookmark.SyncGroupDto.serializer(), encoded)
    assertEquals("https://portal.example.invalid/watch/url-999", decoded.sourceUrl)
  }
}
