/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.bookmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.gyrolet.mpvrx.domain.secure.SecureFolderSession
import app.gyrolet.mpvrx.domain.secure.VaultCrypto
import app.gyrolet.mpvrx.domain.storage.DurableStorageManager
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.utils.memory.BitmapMemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Captures, encrypts, caches, migrates, and deletes bookmark thumbnails with hard separation
 * between normal `.store/thumbs/` and Secure Vault `.vault/thumbs/`.
 */
object BookmarkThumbnailStore {
  private const val CAPTURE_TEMP_DIR_NAME = "bookmark-capture"
  private const val NOMEDIA_FILE_NAME = ".nomedia"
  private const val THUMBNAIL_MAX_PX = 720
  private const val JPEG_QUALITY = 82
  private const val SCREENSHOT_POLL_INTERVAL_MS = 25L
  private const val SCREENSHOT_TIMEOUT_MS = 800L

  private val publicBitmapCache = BitmapMemoryCache<String>(heapDivisor = 24, maxSizeMiB = 8)
  private val secureBitmapCache = BitmapMemoryCache<String>(heapDivisor = 24, maxSizeMiB = 8)

  fun evictSecureMemoryCache() {
    secureBitmapCache.evictAll()
  }

  fun privateDirectory(
    context: Context,
    isSecure: Boolean = false,
  ): File {
    val dir =
      if (isSecure) {
        DurableStorageManager.secureBookmarkThumbnailsDir(context)
      } else {
        DurableStorageManager.normalBookmarkThumbnailsDir(context)
      }
    if (!dir.exists()) dir.mkdirs()
    val nomedia = File(dir, NOMEDIA_FILE_NAME)
    if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
    return dir
  }

  suspend fun captureCurrentVideoFrame(
    context: Context,
    isSecure: Boolean = false,
  ): String? =
    withContext(Dispatchers.IO) {
      VaultCrypto.ensureInitialized(context)
      val grabbed = runCatching { PlaybackSession.grabThumbnail(THUMBNAIL_MAX_PX) }.getOrNull()
      if (grabbed != null) {
        try {
          return@withContext saveScaledEncryptedBitmap(context, grabbed, isSecure)
        } finally {
          if (!grabbed.isRecycled) grabbed.recycle()
        }
      }

      val captureDir = File(context.applicationContext.cacheDir, CAPTURE_TEMP_DIR_NAME).apply {
        if (!exists()) mkdirs()
      }
      val tempFile = File(captureDir, "bm_tmp_${UUID.randomUUID()}.jpg")
      try {
        PlaybackSession.command("screenshot-to-file", tempFile.absolutePath, "video")
        if (!awaitWrittenFile(tempFile)) return@withContext null
        val tempBytes = runCatching { tempFile.readBytes() }.getOrNull() ?: return@withContext null
        val decoded = decodeSampledBytes(tempBytes, THUMBNAIL_MAX_PX) ?: return@withContext null
        try {
          return@withContext saveScaledEncryptedBitmap(context, decoded, isSecure)
        } finally {
          if (!decoded.isRecycled) decoded.recycle()
        }
      } catch (_: Throwable) {
        null
      } finally {
        runCatching { tempFile.delete() }
      }
    }

  suspend fun loadBitmap(
    context: Context,
    path: String?,
    isSecure: Boolean = false,
  ): Bitmap? {
    if (path.isNullOrBlank()) return null
    if (isSecure && !SecureFolderSession.isUnlocked) return null
    val cache = if (isSecure) secureBitmapCache else publicBitmapCache
    cache.get(path)?.takeIf { !it.isRecycled }?.let { return it }
    return withContext(Dispatchers.IO) {
      if (isSecure && !SecureFolderSession.isUnlocked) return@withContext null
      cache.get(path)?.takeIf { !it.isRecycled }?.let { return@withContext it }
      if (!isInsidePrivateDir(context, path, isSecure)) return@withContext null
      val file = File(path)
      if (!file.isFile) return@withContext null
      VaultCrypto.ensureInitialized(context)
      val decrypted =
        runCatching {
          VaultCrypto.decryptBytes(file.readBytes())
        }.getOrNull() ?: return@withContext null
      val decoded = decodeSampledBytes(decrypted, THUMBNAIL_MAX_PX) ?: return@withContext null
      if (isSecure && !SecureFolderSession.isUnlocked) {
        decoded.recycle()
        return@withContext null
      }
      cache.put(path, decoded)
      decoded
    }
  }

  suspend fun deleteThumbnail(
    context: Context,
    path: String?,
    isSecure: Boolean = false,
  ) {
    if (path.isNullOrBlank()) return
    if (isSecure) secureBitmapCache.remove(path) else publicBitmapCache.remove(path)
    withContext(Dispatchers.IO) {
      if (isInsidePrivateDir(context, path, isSecure)) {
        runCatching { File(path).delete() }
      }
    }
  }

  /**
   * Moves or encrypts an existing bookmark thumbnail between normal and secure encrypted stores
   * (or from legacy plaintext storage into `.store/thumbs/` / `.vault/thumbs/`).
   */
  fun migrateThumbnailFile(
    context: Context,
    existingPath: String?,
    toSecure: Boolean,
  ): String? {
    if (existingPath.isNullOrBlank()) return null
    VaultCrypto.ensureInitialized(context)
    val source = File(existingPath)
    if (!source.isFile) return null
    val targetDir = privateDirectory(context, isSecure = toSecure)
    val alreadyInTargetDir =
      runCatching {
        val targetRoot = targetDir.canonicalFile.path
        source.canonicalFile.path.startsWith("$targetRoot${File.separator}")
      }.getOrDefault(false)
    val rawBytes = runCatching { source.readBytes() }.getOrNull() ?: return null
    if (alreadyInTargetDir && VaultCrypto.isEncryptedBytes(rawBytes)) {
      return source.absolutePath
    }
    val plainBytes = runCatching { VaultCrypto.decryptBytes(rawBytes) }.getOrNull() ?: return null
    val encryptedPayload = VaultCrypto.encryptBytes(plainBytes)
    val destFile = File(targetDir, "bm_${UUID.randomUUID()}.dat")
    val tempFile = File(destFile.path + ".tmp")
    return try {
      FileOutputStream(tempFile).use { out ->
        out.write(encryptedPayload)
        out.flush()
        out.fd.sync()
      }
      if (tempFile.length() != encryptedPayload.size.toLong()) {
        tempFile.delete()
        return null
      }
      if (!tempFile.renameTo(destFile)) {
        tempFile.delete()
        return null
      }
      if (VaultCrypto.plaintextLength(destFile) != plainBytes.size.toLong()) {
        return null
      }
      publicBitmapCache.remove(existingPath)
      secureBitmapCache.remove(existingPath)
      if (isInsideAnyAppThumbnailDir(context, existingPath) && destFile.absolutePath != source.absolutePath) {
        runCatching { source.delete() }
      }
      destFile.absolutePath
    } catch (_: Throwable) {
      runCatching { tempFile.delete() }
      runCatching { destFile.delete() }
      null
    }
  }

  private suspend fun awaitWrittenFile(file: File): Boolean {
    var previousSize = -1L
    var stableChecks = 0
    val maxAttempts = (SCREENSHOT_TIMEOUT_MS / SCREENSHOT_POLL_INTERVAL_MS).toInt().coerceAtLeast(1)
    repeat(maxAttempts) {
      val len = if (file.exists()) file.length() else 0L
      if (len > 0L) {
        if (len == previousSize) {
          stableChecks++
          if (stableChecks >= 2) return true
        } else {
          stableChecks = 0
          previousSize = len
        }
      }
      delay(SCREENSHOT_POLL_INTERVAL_MS)
    }
    return file.exists() && file.length() > 0L
  }

  private fun saveScaledEncryptedBitmap(
    context: Context,
    source: Bitmap,
    isSecure: Boolean,
  ): String? {
    val destDir = privateDirectory(context, isSecure = isSecure)
    val destFile = File(destDir, "bm_${UUID.randomUUID()}.dat")
    val tempFile = File(destFile.path + ".tmp")
    val scaled = scaleDownToMaxPx(source, THUMBNAIL_MAX_PX)
    return try {
      val jpegBytes =
        ByteArrayOutputStream().use { buffer ->
          if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)) {
            return null
          }
          buffer.toByteArray()
        }
      val encrypted = VaultCrypto.encryptBytes(jpegBytes)
      FileOutputStream(tempFile).use { out ->
        out.write(encrypted)
        out.flush()
      }
      if (!tempFile.renameTo(destFile)) {
        tempFile.delete()
        return null
      }
      // Pre-populate the in-memory LRU cache so the UI renders the newly captured thumbnail
      // immediately without re-reading, decrypting, and JPEG-decoding it back from disk.
      val path = destFile.absolutePath
      val cachedCopy =
        runCatching {
          if (scaled === source) scaled.copy(Bitmap.Config.RGB_565, false) else scaled
        }.getOrNull()
      if (cachedCopy != null && !cachedCopy.isRecycled) {
        val targetCache = if (isSecure) secureBitmapCache else publicBitmapCache
        targetCache.put(path, cachedCopy)
      }
      path
    } catch (_: Throwable) {
      runCatching { tempFile.delete() }
      runCatching { destFile.delete() }
      null
    } finally {
      val cached =
        if (isSecure) secureBitmapCache.get(destFile.absolutePath) else publicBitmapCache.get(destFile.absolutePath)
      if (scaled !== source && scaled !== cached && !scaled.isRecycled) {
        scaled.recycle()
      }
    }
  }

  private fun scaleDownToMaxPx(bitmap: Bitmap, maxPx: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return bitmap
    val longest = maxOf(width, height)
    if (longest <= maxPx) return bitmap
    val ratio = maxPx.toFloat() / longest.toFloat()
    val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
    val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
  }

  private fun decodeSampledBytes(bytes: ByteArray, maxPx: Int): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null
    var sampleSize = 1
    while (width / (sampleSize * 2) >= maxPx || height / (sampleSize * 2) >= maxPx) {
      sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.RGB_565
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
    val scaled = scaleDownToMaxPx(decoded, maxPx)
    if (scaled !== decoded && !decoded.isRecycled) {
      decoded.recycle()
    }
    return scaled
  }

  private fun isInsidePrivateDir(
    context: Context,
    path: String,
    isSecure: Boolean,
  ): Boolean {
    return runCatching {
      val targetCanonical = File(path).canonicalFile.path
      val roots =
        if (isSecure) {
          listOfNotNull(
            DurableStorageManager.secureBookmarkThumbnailsDir(context),
            DurableStorageManager.legacyV322VaultDirectory(context)?.let { File(it, "thumbs") },
          )
        } else {
          listOfNotNull(
            DurableStorageManager.normalBookmarkThumbnailsDir(context),
            DurableStorageManager.legacyThumbnailsDirectory(context),
            DurableStorageManager.legacyV322ThumbnailsDirectory(context),
          )
        }
      roots.any { root ->
        val rootCanonical = root.canonicalFile.path
        targetCanonical.startsWith("$rootCanonical${File.separator}")
      }
    }.getOrDefault(false)
  }

  private fun isInsideAnyAppThumbnailDir(
    context: Context,
    path: String,
  ): Boolean =
    isInsidePrivateDir(context, path, isSecure = true) ||
      isInsidePrivateDir(context, path, isSecure = false)
}
