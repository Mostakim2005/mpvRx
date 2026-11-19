/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.secure

import android.content.Context
import android.util.Base64
import app.gyrolet.mpvrx.domain.storage.DurableStorageManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware-accelerated AES-128-CTR encryption for the Secure Vault, thumbnails, and durable
 * metadata/stats/preferences.
 *
 * Binary file envelope (`MVX1`, 32-byte header):
 * - `0..3`: ASCII `"MVX1"`
 * - `4..5`: version `1` (big-endian short)
 * - `6..7`: flags `0` (big-endian short)
 * - `8..23`: 16-byte random IV
 * - `24..31`: 64-bit big-endian plaintext length
 * - `32..EOF`: AES-128-CTR ciphertext (exact length == plaintext length)
 *
 * The 128-bit master key is stored wrapped inside `.vkey` in the durable vault directory rather than
 * AndroidKeyStore so the entire vault survives App Data clear and app uninstall/reinstall.
 */
object VaultCrypto {
  const val HEADER_SIZE = 32
  private const val FILE_MAGIC_0: Byte = 'M'.code.toByte()
  private const val FILE_MAGIC_1: Byte = 'V'.code.toByte()
  private const val FILE_MAGIC_2: Byte = 'X'.code.toByte()
  private const val FILE_MAGIC_3: Byte = '1'.code.toByte()
  private const val FORMAT_VERSION: Short = 1
  private const val IV_BYTES = 16
  private const val KEY_BYTES = 16
  private const val KEY_FILE_NAME = ".vkey"
  private const val KEY_FILE_MAGIC_0: Byte = 'M'.code.toByte()
  private const val KEY_FILE_MAGIC_1: Byte = 'V'.code.toByte()
  private const val KEY_FILE_MAGIC_2: Byte = 'K'.code.toByte()
  private const val KEY_FILE_MAGIC_3: Byte = '1'.code.toByte()
  private const val KEY_SALT_BYTES = 16
  private const val KEY_HMAC_BYTES = 32
  private const val KEY_FILE_TOTAL_BYTES = 4 + KEY_SALT_BYTES + IV_BYTES + KEY_BYTES + KEY_HMAC_BYTES
  private const val PBKDF2_ITERATIONS = 10_000
  private const val STRING_PREFIX = "mvx1:"
  private const val STREAM_BUFFER_BYTES = 64 * 1024
  private val WRAP_PASSPHRASE = "mpvRx.VaultKey.Wrap.v1\u0000app.gyrolet.mpvrx".toCharArray()
  private val HMAC_SUBKEY_CONTEXT = "mpvRx-vault-hmac-subkey-v1".toByteArray(Charsets.UTF_8)

  private val random = SecureRandom()
  private val initLock = Any()

  @Volatile private var masterKey: ByteArray? = null

  @Volatile private var hmacSubkey: ByteArray? = null

  @Volatile private var initializedKeyDir: String? = null

  data class VaultFileHeader(
    val iv: ByteArray,
    val plaintextLength: Long,
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is VaultFileHeader) return false
      return plaintextLength == other.plaintextLength && iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int = 31 * iv.contentHashCode() + plaintextLength.hashCode()
  }

  fun keyFile(keyDir: File): File = File(keyDir, KEY_FILE_NAME)

  fun ensureInitialized(context: Context) {
    val keyDir = DurableStorageManager.vaultKeyDirectory(context)
    val dirPath = keyDir.absolutePath
    if (masterKey != null && initializedKeyDir == dirPath) return
    init(keyDir)
  }

  /**
   * Loads or creates the `.vkey` keyfile inside [keyDir]. Safe to call repeatedly across startups
   * or after migrating into durable storage.
   */
  fun init(keyDir: File) {
    synchronized(initLock) {
      if (!keyDir.exists()) keyDir.mkdirs()
      val file = keyFile(keyDir)
      val dirPath = keyDir.absolutePath
      if (masterKey != null && initializedKeyDir == dirPath && file.isFile) return
      val rawKey = loadOrCreateKey(file)
      setMasterKeyLocked(rawKey, dirPath)
    }
  }

  /** Reads an existing `.vkey` file in [keyDir] without creating a new one. */
  fun readKeyFromDir(keyDir: File): ByteArray? {
    val file = keyFile(keyDir)
    if (!file.isFile || file.length() != KEY_FILE_TOTAL_BYTES.toLong()) return null
    return runCatching { unwrapKeyFile(file.readBytes()) }.getOrNull()
  }

  internal fun initWithRawKeyForTests(rawKey: ByteArray) {
    require(rawKey.size == KEY_BYTES) { "Expected $KEY_BYTES-byte AES key" }
    synchronized(initLock) {
      setMasterKeyLocked(rawKey.copyOf(), null)
    }
  }

  internal fun resetForTests() {
    synchronized(initLock) {
      masterKey = null
      hmacSubkey = null
      initializedKeyDir = null
    }
  }

  private fun setMasterKeyLocked(rawKey: ByteArray, dirPath: String?) {
    masterKey = rawKey
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(rawKey, "HmacSHA256"))
    hmacSubkey = mac.doFinal(HMAC_SUBKEY_CONTEXT)
    initializedKeyDir = dirPath
  }

  private fun requireKeySpec(overrideKey: ByteArray? = null): SecretKeySpec {
    val key = overrideKey ?: masterKey ?: error("VaultCrypto is not initialized")
    return SecretKeySpec(key, "AES")
  }

  private fun requireHmacSubkey(): ByteArray =
    hmacSubkey ?: error("VaultCrypto is not initialized")

  private fun loadOrCreateKey(file: File): ByteArray {
    if (file.isFile && file.length() == KEY_FILE_TOTAL_BYTES.toLong()) {
      return unwrapKeyFile(file.readBytes())
    }
    val generated = ByteArray(KEY_BYTES).also(random::nextBytes)
    val salt = ByteArray(KEY_SALT_BYTES).also(random::nextBytes)
    val iv = ByteArray(IV_BYTES).also(random::nextBytes)
    val wrapKey = deriveWrappingKey(salt)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"), IvParameterSpec(iv))
    val wrapped = cipher.doFinal(generated)
    val payloadSize = 4 + KEY_SALT_BYTES + IV_BYTES + KEY_BYTES
    val output = ByteArray(KEY_FILE_TOTAL_BYTES)
    output[0] = KEY_FILE_MAGIC_0
    output[1] = KEY_FILE_MAGIC_1
    output[2] = KEY_FILE_MAGIC_2
    output[3] = KEY_FILE_MAGIC_3
    System.arraycopy(salt, 0, output, 4, KEY_SALT_BYTES)
    System.arraycopy(iv, 0, output, 4 + KEY_SALT_BYTES, IV_BYTES)
    System.arraycopy(wrapped, 0, output, 4 + KEY_SALT_BYTES + IV_BYTES, KEY_BYTES)

    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(wrapKey, "HmacSHA256"))
    mac.update(output, 0, payloadSize)
    val tag = mac.doFinal()
    System.arraycopy(tag, 0, output, payloadSize, KEY_HMAC_BYTES)

    val parent = file.parentFile
    if (parent != null && !parent.exists()) parent.mkdirs()
    val temp = File(file.path + ".tmp")
    FileOutputStream(temp).use {
      it.write(output)
      it.fd.sync()
    }
    if (file.exists()) file.delete()
    if (!temp.renameTo(file)) {
      file.writeBytes(output)
      temp.delete()
    }
    return generated
  }

  private fun unwrapKeyFile(bytes: ByteArray): ByteArray {
    if (bytes.size != KEY_FILE_TOTAL_BYTES ||
      bytes[0] != KEY_FILE_MAGIC_0 ||
      bytes[1] != KEY_FILE_MAGIC_1 ||
      bytes[2] != KEY_FILE_MAGIC_2 ||
      bytes[3] != KEY_FILE_MAGIC_3
    ) {
      throw IOException("Corrupt vault key header")
    }
    val salt = bytes.copyOfRange(4, 4 + KEY_SALT_BYTES)
    val iv = bytes.copyOfRange(4 + KEY_SALT_BYTES, 4 + KEY_SALT_BYTES + IV_BYTES)
    val payloadSize = 4 + KEY_SALT_BYTES + IV_BYTES + KEY_BYTES
    val wrapped = bytes.copyOfRange(4 + KEY_SALT_BYTES + IV_BYTES, payloadSize)
    val expectedTag = bytes.copyOfRange(payloadSize, KEY_FILE_TOTAL_BYTES)

    val wrapKey = deriveWrappingKey(salt)
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(wrapKey, "HmacSHA256"))
    mac.update(bytes, 0, payloadSize)
    val actualTag = mac.doFinal()
    if (!MessageDigest.isEqual(expectedTag, actualTag)) {
      throw IOException("Vault key integrity check failed")
    }
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrapKey, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(wrapped)
  }

  private fun deriveWrappingKey(salt: ByteArray): ByteArray {
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(WRAP_PASSPHRASE, salt, PBKDF2_ITERATIONS, KEY_BYTES * 8)
    return factory.generateSecret(spec).encoded
  }

  fun isEncryptedBytes(bytes: ByteArray): Boolean =
    bytes.size >= HEADER_SIZE &&
      bytes[0] == FILE_MAGIC_0 &&
      bytes[1] == FILE_MAGIC_1 &&
      bytes[2] == FILE_MAGIC_2 &&
      bytes[3] == FILE_MAGIC_3

  fun isEncryptedFile(file: File): Boolean {
    if (!file.isFile || file.length() < HEADER_SIZE) return false
    return runCatching {
      FileInputStream(file).use { input ->
        val header = ByteArray(4)
        input.read(header) == 4 &&
          header[0] == FILE_MAGIC_0 &&
          header[1] == FILE_MAGIC_1 &&
          header[2] == FILE_MAGIC_2 &&
          header[3] == FILE_MAGIC_3
      }
    }.getOrDefault(false)
  }

  fun readHeader(file: File): VaultFileHeader? {
    if (!file.isFile || file.length() < HEADER_SIZE) return null
    val header = ByteArray(HEADER_SIZE)
    val read = FileInputStream(file).use { it.read(header) }
    if (read < HEADER_SIZE || !isEncryptedBytes(header)) return null
    return parseHeader(header)
  }

  private fun parseHeader(header: ByteArray): VaultFileHeader? {
    if (!isEncryptedBytes(header)) return null
    val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
    buf.position(4)
    val version = buf.short
    if (version != FORMAT_VERSION) return null
    buf.short // flags
    val iv = ByteArray(IV_BYTES)
    buf.get(iv)
    val plaintextLength = buf.long
    if (plaintextLength < 0L) return null
    return VaultFileHeader(iv = iv, plaintextLength = plaintextLength)
  }

  private fun buildHeader(iv: ByteArray, plaintextLength: Long): ByteArray {
    val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
    buf.put(FILE_MAGIC_0)
    buf.put(FILE_MAGIC_1)
    buf.put(FILE_MAGIC_2)
    buf.put(FILE_MAGIC_3)
    buf.putShort(FORMAT_VERSION)
    buf.putShort(0)
    buf.put(iv)
    buf.putLong(plaintextLength)
    return buf.array()
  }

  fun plaintextLength(file: File): Long =
    if (!file.isFile) 0L else readHeader(file)?.plaintextLength ?: file.length()

  /**
   * Computes `baseIv + blockIndex` as an unsigned 128-bit big-endian integer, returning a new
   * 16-byte IV array for direct seeking in AES-128-CTR.
   */
  fun addBlockOffset(baseIv: ByteArray, blockIndex: Long): ByteArray {
    require(baseIv.size == IV_BYTES) { "IV must be $IV_BYTES bytes" }
    val result = baseIv.copyOf()
    if (blockIndex == 0L) return result
    var carry = blockIndex
    var i = IV_BYTES - 1
    while (i >= 0 && carry != 0L) {
      val sum = (result[i].toLong() and 0xFFL) + (carry and 0xFFL)
      result[i] = (sum and 0xFFL).toByte()
      carry = (carry ushr 8) + (sum ushr 8)
      i--
    }
    return result
  }

  fun encryptBytes(plain: ByteArray, overrideKey: ByteArray? = null): ByteArray {
    val iv = ByteArray(IV_BYTES).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, requireKeySpec(overrideKey), IvParameterSpec(iv))
    val ciphertext = cipher.doFinal(plain)
    val out = ByteArray(HEADER_SIZE + ciphertext.size)
    val header = buildHeader(iv, plain.size.toLong())
    System.arraycopy(header, 0, out, 0, HEADER_SIZE)
    System.arraycopy(ciphertext, 0, out, HEADER_SIZE, ciphertext.size)
    return out
  }

  /** Decrypts `MVX1`-wrapped payloads, or returns [data] unchanged if legacy plaintext. */
  fun decryptBytes(data: ByteArray, overrideKey: ByteArray? = null): ByteArray {
    if (!isEncryptedBytes(data)) return data
    val header = parseHeader(data) ?: throw IOException("Invalid MVX1 header")
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, requireKeySpec(overrideKey), IvParameterSpec(header.iv))
    val decrypted = cipher.doFinal(data, HEADER_SIZE, data.size - HEADER_SIZE)
    val expected = header.plaintextLength.coerceAtMost(decrypted.size.toLong()).toInt()
    return if (expected == decrypted.size) decrypted else decrypted.copyOf(expected)
  }

  fun encryptString(plain: String): String {
    if (plain.isEmpty()) return ""
    val iv = ByteArray(IV_BYTES).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, requireKeySpec(), IvParameterSpec(iv))
    val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    val combined = ByteArray(IV_BYTES + ciphertext.size)
    System.arraycopy(iv, 0, combined, 0, IV_BYTES)
    System.arraycopy(ciphertext, 0, combined, IV_BYTES, ciphertext.size)
    return STRING_PREFIX + encodeBase64NoWrap(combined)
  }

  /** Decrypts an `mvx1:` field string, returning unprefixed legacy values unchanged. */
  fun decryptString(value: String): String {
    if (!value.startsWith(STRING_PREFIX)) return value
    val raw = decodeBase64(value.substring(STRING_PREFIX.length))
    if (raw.size < IV_BYTES) return ""
    val iv = raw.copyOfRange(0, IV_BYTES)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, requireKeySpec(), IvParameterSpec(iv))
    return String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
  }

  /** Deterministic keyed MAC for indexed lookup on encrypted Room columns (tags, stems, codes). */
  fun hmacKey(normalizedValue: String): String {
    if (normalizedValue.isBlank()) return ""
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(requireHmacSubkey(), "HmacSHA256"))
    val digest = mac.doFinal(normalizedValue.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
  }

  fun encryptFile(
    source: File,
    target: File,
    onProgress: ((Float) -> Unit)? = null,
    overrideKey: ByteArray? = null,
  ): Long {
    val size = source.length()
    return FileInputStream(source).use { input ->
      encryptStream(input, size, target, onProgress, overrideKey)
    }
  }

  fun encryptStream(
    input: InputStream,
    expectedSize: Long,
    target: File,
    onProgress: ((Float) -> Unit)? = null,
    overrideKey: ByteArray? = null,
  ): Long {
    val parent = target.parentFile
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw IOException("Could not create encrypted directory: ${parent.absolutePath}")
    }
    val temp = File(target.path + ".tmp")
    val iv = ByteArray(IV_BYTES).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, requireKeySpec(overrideKey), IvParameterSpec(iv))
    var totalPlain = 0L
    try {
      FileOutputStream(temp).use { out ->
        out.write(buildHeader(iv, expectedSize.coerceAtLeast(0L)))
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
          if (read <= 0) continue
          val encrypted = cipher.update(buffer, 0, read)
          if (encrypted != null && encrypted.isNotEmpty()) out.write(encrypted)
          totalPlain += read
          if (onProgress != null) {
            val progress = if (expectedSize > 0L) totalPlain.toFloat() / expectedSize else 1f
            onProgress(progress.coerceIn(0f, 1f))
          }
        }
        val tail = cipher.doFinal()
        if (tail.isNotEmpty()) out.write(tail)
        out.flush()
        out.fd.sync()
      }
      if (expectedSize < 0L || totalPlain != expectedSize) {
        // Patch the header's plaintext length if the stream length was unknown upfront.
        RandomAccessFile(temp, "rw").use { raf ->
          raf.seek(24L)
          val lenBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(totalPlain).array()
          raf.write(lenBytes)
          raf.fd.sync()
        }
      }
      if (temp.length() != HEADER_SIZE + totalPlain) {
        throw IOException("Encrypted size mismatch for ${target.name}")
      }
      if (target.exists() && !target.delete()) {
        throw IOException("Could not overwrite ${target.name}")
      }
      if (!temp.renameTo(target)) {
        throw IOException("Could not finalize ${target.name}")
      }
      return totalPlain
    } catch (error: Exception) {
      runCatching { temp.delete() }
      throw error
    }
  }

  fun decryptFile(
    source: File,
    target: File,
    onProgress: ((Float) -> Unit)? = null,
    overrideKey: ByteArray? = null,
  ): Long {
    val parent = target.parentFile
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw IOException("Could not create destination directory: ${parent.absolutePath}")
    }
    val temp = File(target.path + ".tmp")
    return try {
      val written =
        FileOutputStream(temp).use { out ->
          decryptToStream(source, out, onProgress, overrideKey).also { out.fd.sync() }
        }
      if (target.exists() && !target.delete()) {
        throw IOException("Could not replace ${target.name}")
      }
      if (!temp.renameTo(target)) {
        throw IOException("Could not rename decrypted file ${target.name}")
      }
      written
    } catch (error: Exception) {
      runCatching { temp.delete() }
      throw error
    }
  }

  fun decryptToStream(
    source: File,
    output: OutputStream,
    onProgress: ((Float) -> Unit)? = null,
    overrideKey: ByteArray? = null,
  ): Long {
    val header = readHeader(source)
    if (header == null) {
      val total = source.length()
      var copied = 0L
      FileInputStream(source).use { input ->
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
          output.write(buffer, 0, read)
          copied += read
          onProgress?.invoke(if (total > 0L) (copied.toFloat() / total).coerceIn(0f, 1f) else 1f)
        }
      }
      output.flush()
      return copied
    }
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, requireKeySpec(overrideKey), IvParameterSpec(header.iv))
    val expected = header.plaintextLength
    var decryptedTotal = 0L
    FileInputStream(source).use { input ->
      var skipped = 0L
      while (skipped < HEADER_SIZE) {
        val step = input.skip(HEADER_SIZE - skipped)
        if (step <= 0L) {
          if (input.read() < 0) throw IOException("Truncated MVX1 header")
          skipped++
        } else {
          skipped += step
        }
      }
      val buffer = ByteArray(STREAM_BUFFER_BYTES)
      while (decryptedTotal < expected) {
        val maxRead = minOf(buffer.size.toLong(), expected - decryptedTotal).toInt()
        val read = input.read(buffer, 0, maxRead)
        if (read < 0) break
        val plainChunk = cipher.update(buffer, 0, read)
        if (plainChunk != null && plainChunk.isNotEmpty()) {
          output.write(plainChunk)
        }
        decryptedTotal += read
        onProgress?.invoke(if (expected > 0L) (decryptedTotal.toFloat() / expected).coerceIn(0f, 1f) else 1f)
      }
      val tail = cipher.doFinal()
      if (tail.isNotEmpty()) output.write(tail)
    }
    output.flush()
    return decryptedTotal
  }

  /**
   * Random-access O(1) decryption at [position] for `MediaDataSource` and byte-range reads.
   */
  fun readDecryptedAt(
    raf: RandomAccessFile,
    header: VaultFileHeader,
    position: Long,
    buffer: ByteArray,
    offset: Int,
    size: Int,
    overrideKey: ByteArray? = null,
  ): Int {
    if (size == 0) return 0
    if (position < 0L || position >= header.plaintextLength) return -1
    val remaining = header.plaintextLength - position
    val wanted = minOf(size.toLong(), remaining).toInt()
    val blockIndex = position ushr 4
    val skipInBlock = (position and 15L).toInt()
    val alignedFileOffset = HEADER_SIZE.toLong() + (blockIndex shl 4)
    val rawBuffer = ByteArray(skipInBlock + wanted)
    raf.seek(alignedFileOffset)
    var filled = 0
    while (filled < rawBuffer.size) {
      val read = raf.read(rawBuffer, filled, rawBuffer.size - filled)
      if (read < 0) break
      filled += read
    }
    if (filled <= skipInBlock) return -1
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(
      Cipher.DECRYPT_MODE,
      requireKeySpec(overrideKey),
      IvParameterSpec(addBlockOffset(header.iv, blockIndex)),
    )
    val decrypted = cipher.doFinal(rawBuffer, 0, filled)
    val copied = decrypted.size - skipInBlock
    if (copied <= 0) return -1
    System.arraycopy(decrypted, skipInBlock, buffer, offset, copied)
    return copied
  }

  /**
   * Opens a seekable streaming [InputStream] covering `[startOffset, startOffset + length)` of
   * plaintext bytes, supporting both `MVX1`-encrypted files and unencrypted legacy files.
   */
  fun openDecryptedRangeStream(
    file: File,
    startOffset: Long,
    length: Long,
    overrideKey: ByteArray? = null,
  ): InputStream {
    val header = readHeader(file)
    return if (header == null) {
      PlainRangeInputStream(file, startOffset, length)
    } else {
      EncryptedRangeInputStream(file, header, startOffset, length, requireKeySpec(overrideKey))
    }
  }

  private class PlainRangeInputStream(
    file: File,
    startOffset: Long,
    length: Long,
  ) : InputStream() {
    private val raf = RandomAccessFile(file, "r")
    private var remaining = length.coerceAtLeast(0L)
    private val single = ByteArray(1)

    init {
      raf.seek(startOffset.coerceAtLeast(0L))
    }

    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
      if (len == 0) return 0
      if (remaining <= 0L) return -1
      val maxRead = minOf(len.toLong(), remaining).toInt()
      val read = raf.read(b, off, maxRead)
      if (read <= 0) return -1
      remaining -= read
      return read
    }

    override fun close() {
      raf.close()
    }
  }

  /**
   * Buffered 64 KB block-aligned CTR range reader.
   *
   * Every `Cipher.doFinal` operates on a 16-byte block boundary so output sizing is exact across all
   * Android JCE providers (Conscrypt) and JVM unit tests with no partial-block buffering quirks.
   */
  private class EncryptedRangeInputStream(
    file: File,
    private val header: VaultFileHeader,
    startOffset: Long,
    length: Long,
    private val keySpec: SecretKeySpec,
  ) : InputStream() {
    private val raf = RandomAccessFile(file, "r")
    private var position = startOffset.coerceIn(0L, header.plaintextLength)
    private val endExclusive = (position + length.coerceAtLeast(0L)).coerceAtMost(header.plaintextLength)
    private val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    private val encryptedPage = ByteArray(STREAM_BUFFER_BYTES)
    private val decryptedPage = ByteArray(STREAM_BUFFER_BYTES + 32)
    private var pageStart = -1L
    private var pageLength = 0
    private val single = ByteArray(1)

    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
      if (len == 0) return 0
      if (position >= endExclusive) return -1

      if (pageStart < 0L || position < pageStart || position >= pageStart + pageLength) {
        if (!fillAlignedPage(position)) return -1
      }
      val offsetInPage = (position - pageStart).toInt()
      val availableInPage = pageLength - offsetInPage
      val remainingInRange = (endExclusive - position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      val count = minOf(len, availableInPage, remainingInRange)
      if (count <= 0) return -1
      System.arraycopy(decryptedPage, offsetInPage, b, off, count)
      position += count
      return count
    }

    private fun fillAlignedPage(targetPos: Long): Boolean {
      val blockIndex = targetPos ushr 4
      val alignedPos = blockIndex shl 4
      val remainingFilePlain = header.plaintextLength - alignedPos
      if (remainingFilePlain <= 0L) return false
      val toRead = minOf(encryptedPage.size.toLong(), remainingFilePlain).toInt()
      raf.seek(HEADER_SIZE.toLong() + alignedPos)
      var filled = 0
      while (filled < toRead) {
        val n = raf.read(encryptedPage, filled, toRead - filled)
        if (n < 0) break
        filled += n
      }
      if (filled <= 0) return false
      cipher.init(
        Cipher.DECRYPT_MODE,
        keySpec,
        IvParameterSpec(addBlockOffset(header.iv, blockIndex)),
      )
      pageLength = cipher.doFinal(encryptedPage, 0, filled, decryptedPage, 0)
      pageStart = alignedPos
      return pageLength > 0
    }

    override fun close() {
      raf.close()
    }
  }

  private fun encodeBase64NoWrap(bytes: ByteArray): String =
    runCatching {
      Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrElse {
      java.util.Base64.getEncoder().encodeToString(bytes)
    }

  private fun decodeBase64(text: String): ByteArray =
    runCatching {
      Base64.decode(text, Base64.DEFAULT)
    }.getOrElse {
      java.util.Base64.getDecoder().decode(text)
    }
}
