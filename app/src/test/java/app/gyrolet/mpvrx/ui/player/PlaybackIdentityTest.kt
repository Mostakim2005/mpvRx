/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import app.gyrolet.mpvrx.database.entities.SecureMediaEntity
import app.gyrolet.mpvrx.domain.secure.SecureMediaRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaybackIdentityTest {

  @AfterTest
  fun tearDown() {
    SecureMediaRegistry.clear()
  }

  @Test
  fun forLocalPathNormalizesFileUriAndRedundantSegments() {
    val rawPath = "/storage/emulated/0/Download/video.mp4"
    val fileUri = "file:///storage/emulated/0/Download/video.mp4"
    val redundantPath = "/storage/emulated/0/../0/Download//video.mp4"
    val withDot = "/storage/emulated/0/./Download/video.mp4"

    val idRaw = PlaybackIdentity.forLocalPath(rawPath)
    val idFileUri = PlaybackIdentity.forLocalPath(fileUri)
    val idRedundant = PlaybackIdentity.forLocalPath(redundantPath)
    val idWithDot = PlaybackIdentity.forLocalPath(withDot)

    assertTrue(idRaw.startsWith("media:v2:"))
    assertEquals(idRaw, idFileUri)
    assertEquals(idRaw, idRedundant)
    assertEquals(idRaw, idWithDot)
  }

  @Test
  fun forLocalPathHandlesUnicodeBengaliFilenamesWithoutNpe() {
    val rawBengali = "/storage/emulated/0/Download/Gmail কাজ করার নিয়ম.mp4"
    val uriBengali = "file:///storage/emulated/0/Download/Gmail কাজ করার নিয়ম.mp4"

    val idRaw = PlaybackIdentity.forLocalPath(rawBengali)
    val idUri = PlaybackIdentity.forLocalPath(uriBengali)

    assertTrue(idRaw.startsWith("media:v2:"))
    assertEquals(idRaw, idUri)
  }

  @Test
  fun forLocalPathHandlesOpaqueIdsAndContentUrisSafely() {
    val opaqueId = "media:v2:e718ede2ab06cff4c995"
    val contentUri = "content://media/external/video/media/12345"
    val blank = "   "

    val idOpaque = PlaybackIdentity.forLocalPath(opaqueId)
    val idContent = PlaybackIdentity.forLocalPath(contentUri)
    val idBlank = PlaybackIdentity.forLocalPath(blank)

    assertTrue(idOpaque.startsWith("media:v2:"))
    assertTrue(idContent.startsWith("media:v2:"))
    assertTrue(idBlank.startsWith("media:v2:"))
    assertNotEquals(idOpaque, idContent)
  }

  @Test
  fun forLocalPathNormalizesWindowsPathsConsistently() {
    val winBackslash = "C:\\Users\\Media\\video.mp4"
    val winSlash = "C:/Users/Media/video.mp4"
    val winFileUri = "file:///C:/Users/Media/video.mp4"

    val idBackslash = PlaybackIdentity.forLocalPath(winBackslash)
    val idSlash = PlaybackIdentity.forLocalPath(winSlash)
    val idFileUri = PlaybackIdentity.forLocalPath(winFileUri)

    assertTrue(idBackslash.startsWith("media:v2:"))
    assertEquals(idBackslash, idSlash)
    assertEquals(idBackslash, idFileUri)
  }

  @Test
  fun normalizeLocalPathHandlesEdgeCases() {
    assertEquals("", PlaybackIdentity.normalizeLocalPath(""))
    assertEquals("", PlaybackIdentity.normalizeLocalPath("   "))
    assertEquals("/", PlaybackIdentity.normalizeLocalPath("/"))
    assertEquals("/storage/emulated/0/test.mp4", PlaybackIdentity.normalizeLocalPath("/storage/emulated/0/test.mp4"))
    assertEquals("/storage/emulated/0/test.mp4", PlaybackIdentity.normalizeLocalPath("file:///storage/emulated/0/test.mp4"))
    assertEquals("content://media/external/123", PlaybackIdentity.normalizeLocalPath("content://media/external/123"))
    assertEquals("media:v2:abc", PlaybackIdentity.normalizeLocalPath("media:v2:abc"))
  }

  @Test
  fun canonicalizeUriNormalizesHttpPortsAndHandlesQueryAndPathSafely() {
    val urlWithPort = "http://example.com:80/stream/video.mp4"
    val urlWithoutPort = "http://example.com/stream/video.mp4"
    val httpsWithPort = "https://example.com:443/video.mp4?token=abc"
    val httpsWithoutPort = "https://example.com/video.mp4?token=abc"

    assertEquals(PlaybackIdentity.forUri(urlWithPort), PlaybackIdentity.forUri(urlWithoutPort))
    assertEquals(PlaybackIdentity.forUri(httpsWithPort), PlaybackIdentity.forUri(httpsWithoutPort))
  }

  @Test
  fun secureMediaRegistryProtectsExactPathsAndIdentitiesWithoutNpe() {
    val securePath = "/data/user/0/app.gyrolet.mpvrx/files/secure_media/secret.mp4"
    val originalPath = "/storage/emulated/0/Download/secret.mp4"
    val entity = SecureMediaEntity(
      id = 1,
      secureFilePath = securePath,
      originalPath = originalPath,
      fileName = "secret.mp4",
      fileSize = 1024L,
      duration = 60L,
      width = 1920,
      height = 1080,
      mimeType = "video/mp4",
      addedTimestamp = 1000L,
    )

    SecureMediaRegistry.update(listOf(entity))

    assertTrue(SecureMediaRegistry.isProtectedReference(securePath))
    assertTrue(SecureMediaRegistry.isProtectedReference("file://$securePath"))
    assertTrue(SecureMediaRegistry.isProtectedReference(originalPath))
    assertTrue(SecureMediaRegistry.isProtectedReference("file://$originalPath"))
    assertTrue(SecureMediaRegistry.isProtectedReference(PlaybackIdentity.forLocalPath(securePath)))

    assertFalse(SecureMediaRegistry.isProtectedReference("/storage/emulated/0/Download/public.mp4"))
    assertFalse(SecureMediaRegistry.isProtectedReference("/storage/emulated/0/Download/Gmail কাজ করার নিয়ম.mp4"))
    assertFalse(SecureMediaRegistry.isProtectedReference("content://media/external/video/media/999"))
    assertFalse(SecureMediaRegistry.isProtectedReference("media:v2:randomhash12345"))
    assertFalse(SecureMediaRegistry.isProtectedReference(null))
    assertFalse(SecureMediaRegistry.isProtectedReference(""))
    assertFalse(SecureMediaRegistry.isProtectedReference("   "))
  }
}
