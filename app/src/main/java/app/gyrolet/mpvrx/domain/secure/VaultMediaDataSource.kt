/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.secure

import android.media.MediaDataSource
import java.io.File
import java.io.RandomAccessFile

/**
 * Random-access [MediaDataSource] that decrypts an `MVX1`-encrypted vault file on the fly for
 * Android's `MediaMetadataRetriever` and `MediaExtractor` without staging a decrypted file on disk.
 */
class VaultMediaDataSource(
  file: File,
) : MediaDataSource() {
  private val raf = RandomAccessFile(file, "r")
  private val header = VaultCrypto.readHeader(file)
  private val totalSize = header?.plaintextLength ?: raf.length()
  private val lock = Any()

  override fun readAt(
    position: Long,
    buffer: ByteArray,
    offset: Int,
    size: Int,
  ): Int =
    synchronized(lock) {
      if (position < 0L || position >= totalSize) return -1
      if (size == 0) return 0
      val currentHeader = header
      if (currentHeader != null) {
        return VaultCrypto.readDecryptedAt(raf, currentHeader, position, buffer, offset, size)
      }
      val toRead = minOf(size.toLong(), totalSize - position).toInt()
      raf.seek(position)
      var total = 0
      while (total < toRead) {
        val read = raf.read(buffer, offset + total, toRead - total)
        if (read < 0) break
        total += read
      }
      if (total == 0) -1 else total
    }

  override fun getSize(): Long = totalSize

  override fun close() {
    synchronized(lock) {
      raf.close()
    }
  }
}
