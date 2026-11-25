package app.gyrolet.mpvrx.domain.bookmark

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Computes Stash / OpenSubtitles 64-bit `oshash` (MPC/OSDb movie hash):
 * unsigned 64-bit sum of file size + first 64 KiB + last 64 KiB as 8-byte LE words.
 */
object OshashCalculator {
  private const val CHUNK_BYTES = 65_536

  fun computeOshash(file: File): String? {
    if (!file.isFile) return null
    val size = file.length()
    if (size < CHUNK_BYTES) return null
    return runCatching {
      RandomAccessFile(file, "r").use { raf ->
        computeFromChannel(raf.channel, size)
      }
    }.getOrNull()
  }

  fun computeOshash(context: Context, uri: Uri): String? {
    if (uri.scheme.equals("file", ignoreCase = true)) {
      val path = uri.path ?: return null
      return computeOshash(File(path))
    }
    if (!uri.scheme.equals("content", ignoreCase = true)) return null
    return runCatching {
      val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
      pfd.use {
        FileInputStream(it.fileDescriptor).use { fis ->
          val channel = fis.channel
          val size = channel.size()
          if (size < CHUNK_BYTES) return null
          computeFromChannel(channel, size)
        }
      }
    }.getOrNull()
  }

  fun computeFromBytes(firstChunk: ByteArray, lastChunk: ByteArray, fileSize: Long): String? {
    if (fileSize < CHUNK_BYTES || firstChunk.size < CHUNK_BYTES || lastChunk.size < CHUNK_BYTES) return null
    var hash = fileSize
    hash += sumLittleEndian64Words(firstChunk, CHUNK_BYTES)
    hash += sumLittleEndian64Words(lastChunk, CHUNK_BYTES)
    return "%016x".format(hash)
  }

  private fun computeFromChannel(channel: FileChannel, fileSize: Long): String? {
    if (fileSize < CHUNK_BYTES) return null
    val head = ByteArray(CHUNK_BYTES)
    val tail = ByteArray(CHUNK_BYTES)
    if (!readFullyAt(channel, 0L, head)) return null
    if (!readFullyAt(channel, fileSize - CHUNK_BYTES, tail)) return null
    return computeFromBytes(head, tail, fileSize)
  }

  private fun readFullyAt(channel: FileChannel, offset: Long, dst: ByteArray): Boolean {
    val buf = ByteBuffer.wrap(dst)
    var pos = offset
    while (buf.hasRemaining()) {
      val read = channel.read(buf, pos)
      if (read <= 0) return false
      pos += read
    }
    return true
  }

  private fun sumLittleEndian64Words(bytes: ByteArray, length: Int): Long {
    val buf = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
    var sum = 0L
    val words = length / 8
    for (i in 0 until words) {
      sum += buf.long
    }
    return sum
  }
}
