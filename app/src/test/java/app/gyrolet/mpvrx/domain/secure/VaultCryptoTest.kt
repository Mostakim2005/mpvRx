/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.secure

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VaultCryptoTest {
  private val testKey = ByteArray(16) { index -> (index * 17 + 3).toByte() }

  @BeforeTest
  fun setUp() {
    VaultCrypto.initWithRawKeyForTests(testKey)
  }

  @AfterTest
  fun tearDown() {
    VaultCrypto.resetForTests()
  }

  @Test
  fun encryptAndDecryptBytesRoundTripsAndUsesUniqueIvs() {
    val payload = "mpvRx Secure Vault AES-128-CTR payload \uD83D\uDD12".toByteArray(Charsets.UTF_8)
    val encA = VaultCrypto.encryptBytes(payload)
    val encB = VaultCrypto.encryptBytes(payload)

    assertTrue(VaultCrypto.isEncryptedBytes(encA))
    assertTrue(VaultCrypto.isEncryptedBytes(encB))
    assertEquals(VaultCrypto.HEADER_SIZE + payload.size, encA.size)
    // Different random 16-byte IVs must yield distinct ciphertexts
    assertFalse(encA.contentEquals(encB))

    assertContentEquals(payload, VaultCrypto.decryptBytes(encA))
    assertContentEquals(payload, VaultCrypto.decryptBytes(encB))
    // Legacy plaintext passes through decryptBytes unchanged
    assertContentEquals(payload, VaultCrypto.decryptBytes(payload))
  }

  @Test
  fun encryptAndDecryptStringRoundTripsAndPassesLegacyPlaintext() {
    val secret = "/storage/emulated/0/DCIM/Camera/PrivateVideo_2026.mp4"
    val encrypted = VaultCrypto.encryptString(secret)
    assertTrue(VaultCrypto.isEncryptedString(encrypted))
    assertNotEquals(secret, encrypted)
    assertEquals(secret, VaultCrypto.decryptString(encrypted))
    assertEquals("legacy_plain_title.mkv", VaultCrypto.decryptString("legacy_plain_title.mkv"))
  }

  @Test
  fun hmacKeyIsDeterministicAndKeyDependent() {
    val idA1 = VaultCrypto.hmacKey("file:///storage/emulated/0/Documents/mpvRx/.vault/media/a.dat")
    val idA2 = VaultCrypto.hmacKey("file:///storage/emulated/0/Documents/mpvRx/.vault/media/a.dat")
    val idB = VaultCrypto.hmacKey("file:///storage/emulated/0/Documents/mpvRx/.vault/media/b.dat")

    assertEquals(64, idA1.length)
    assertEquals(idA1, idA2)
    assertNotEquals(idA1, idB)
  }

  @Test
  fun keyFileCreationAndReloadPersistsSameMasterKeyAcrossProcessRestarts() {
    val keyDir = Files.createTempDirectory("vault_key_test").toFile()
    try {
      VaultCrypto.resetForTests()
      VaultCrypto.init(keyDir)
      val keyFile = VaultCrypto.keyFile(keyDir)
      assertTrue(keyFile.isFile)

      val ciphertext = VaultCrypto.encryptString("survives-clear-data-and-uninstall")
      val hmacBefore = VaultCrypto.hmacKey("video_key_1")

      // Simulate process death / app reinstall pointing at the same Documents/mpvRx/.vault/.vkey
      VaultCrypto.resetForTests()
      VaultCrypto.init(keyDir)

      assertEquals("survives-clear-data-and-uninstall", VaultCrypto.decryptString(ciphertext))
      assertEquals(hmacBefore, VaultCrypto.hmacKey("video_key_1"))
    } finally {
      keyDir.deleteRecursively()
    }
  }

  @Test
  fun randomAccessAndRangeStreamDecryptArbitraryUnalignedOffsets() {
    val tempDir = Files.createTempDirectory("vault_stream_test").toFile()
    try {
      // 150 KB patterned payload spanning multiple 16-byte AES blocks and 64 KB stream pages
      val plain = ByteArray(150_123) { i -> ((i * 31 + (i ushr 7)) and 0xFF).toByte() }
      val encFile = File(tempDir, "sample.dat")

      VaultCrypto.encryptStream(
        input = ByteArrayInputStream(plain),
        plaintextLength = plain.size.toLong(),
        destFile = encFile,
      )

      assertTrue(VaultCrypto.isEncryptedFile(encFile))
      assertEquals(plain.size.toLong(), VaultCrypto.plaintextLength(encFile))
      val header = VaultCrypto.readHeader(encFile)
      assertNotNull(header)
      assertEquals(plain.size.toLong(), header.plaintextLength)

      // Test unaligned random-access reads via RandomAccessFile (used by VaultMediaDataSource)
      RandomAccessFile(encFile, "r").use { raf ->
        for ((offset, len) in listOf(0 to 64, 1 to 15, 7 to 33, 16 to 48, 1021 to 4099, 65535 to 2048, 149_900 to 500)) {
          val buf = ByteArray(len)
          val read = VaultCrypto.readDecryptedAt(raf, header, offset.toLong(), buf, 0, len)
          val expectedLen = minOf(len, plain.size - offset)
          assertEquals(expectedLen, read, "Mismatch read length at offset=$offset")
          assertContentEquals(
            plain.copyOfRange(offset, offset + expectedLen),
            buf.copyOfRange(0, read),
            "Mismatch decrypted bytes at offset=$offset",
          )
        }
      }

      // Test unaligned range stream (used by VaultStreamingProxy for HTTP 206 Range requests)
      for ((start, length) in listOf(0L to plain.size.toLong(), 13L to 90_000L, 65_530L to 70_000L, 140_000L to 20_000L)) {
        val expectedEnd = minOf(plain.size.toLong(), start + length).toInt()
        val expectedSlice = plain.copyOfRange(start.toInt(), expectedEnd)
        val out = ByteArrayOutputStream()
        VaultCrypto.openDecryptedRangeStream(encFile, start, length).use { input ->
          val chunk = ByteArray(1337) // intentionally odd buffer size
          while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            out.write(chunk, 0, n)
          }
        }
        assertContentEquals(expectedSlice, out.toByteArray(), "Range stream mismatch for start=$start length=$length")
      }
    } finally {
      tempDir.deleteRecursively()
    }
  }
}
