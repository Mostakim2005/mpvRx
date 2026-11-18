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
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.utils.memory.BitmapMemoryCache
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Captures, stores, caches, and deletes bookmark frame thumbnails inside app-private internal
 * storage (`filesDir/bookmark-thumbnails`). Files here are outside shared storage and never
 * registered with MediaStore, keeping them invisible to Android Gallery and other apps.
 */
object BookmarkThumbnailStore {
  private const val CAPTURE_TEMP_DIR_NAME = "bookmark-capture"
  private const val NOMEDIA_FILE_NAME = ".nomedia"
  private const val THUMBNAIL_MAX_PX = 720
  private const val JPEG_QUALITY = 82
  private const val SCREENSHOT_POLL_INTERVAL_MS = 25L
  private const val SCREENSHOT_TIMEOUT_MS = 800L

  private val bitmapCache = BitmapMemoryCache<String>(heapDivisor = 24, maxSizeMiB = 8)

  fun privateDirectory(context: Context): File {
    val dir = app.gyrolet.mpvrx.domain.storage.DurableStorageManager.thumbnailsDirectory(context)
    if (!dir.exists()) dir.mkdirs()
    val nomedia = File(dir, NOMEDIA_FILE_NAME)
    if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
    return dir
  }

  suspend fun captureCurrentVideoFrame(context: Context): String? =
    withContext(Dispatchers.IO) {
      val grabbed = runCatching { PlaybackSession.grabThumbnail(THUMBNAIL_MAX_PX) }.getOrNull()
      if (grabbed != null) {
        try {
          return@withContext saveScaledBitmap(context, grabbed)
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
        val decoded = decodeSampledFile(tempFile.absolutePath, THUMBNAIL_MAX_PX) ?: return@withContext null
        try {
          return@withContext saveScaledBitmap(context, decoded)
        } finally {
          if (!decoded.isRecycled) decoded.recycle()
        }
      } catch (_: Throwable) {
        null
      } finally {
        runCatching { tempFile.delete() }
      }
    }

  suspend fun loadBitmap(context: Context, path: String?): Bitmap? {
    if (path.isNullOrBlank()) return null
    bitmapCache.get(path)?.takeIf { !it.isRecycled }?.let { return it }
    return withContext(Dispatchers.IO) {
      bitmapCache.get(path)?.takeIf { !it.isRecycled }?.let { return@withContext it }
      if (!isInsidePrivateDir(context, path)) return@withContext null
      val decoded = decodeSampledFile(path, THUMBNAIL_MAX_PX) ?: return@withContext null
      bitmapCache.put(path, decoded)
      decoded
    }
  }

  suspend fun deleteThumbnail(context: Context, path: String?) {
    if (path.isNullOrBlank()) return
    bitmapCache.remove(path)
    withContext(Dispatchers.IO) {
      if (isInsidePrivateDir(context, path)) {
        runCatching { File(path).delete() }
      }
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

  private fun saveScaledBitmap(context: Context, source: Bitmap): String? {
    val destDir = privateDirectory(context)
    val destFile = File(destDir, "bm_${UUID.randomUUID()}.jpg")
    val scaled = scaleDownToMaxPx(source, THUMBNAIL_MAX_PX)
    return try {
      FileOutputStream(destFile).use { out ->
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
          return null
        }
        out.flush()
      }
      if (scaled !== source && !scaled.isRecycled) {
        // Cache a clean copy only on demand or decode via loadBitmap; store compact path now.
        scaled.recycle()
      }
      destFile.absolutePath
    } catch (_: Throwable) {
      if (scaled !== source && !scaled.isRecycled) scaled.recycle()
      runCatching { destFile.delete() }
      null
    }
  }

  private fun scaleDownToMaxPx(bitmap: Bitmap, maxPx: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return bitmap
    val largest = maxOf(width, height)
    if (largest <= maxPx) return bitmap
    val ratio = maxPx.toFloat() / largest.toFloat()
    val targetW = (width * ratio).toInt().coerceAtLeast(1)
    val targetH = (height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
  }

  private fun decodeSampledFile(path: String, maxPx: Int): Bitmap? {
    val file = File(path)
    if (!file.exists() || !file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
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
    val decoded = BitmapFactory.decodeFile(path, decodeOpts) ?: return null
    val scaled = scaleDownToMaxPx(decoded, maxPx)
    if (scaled !== decoded && !decoded.isRecycled) {
      decoded.recycle()
    }
    return scaled
  }

  private fun isInsidePrivateDir(context: Context, path: String): Boolean {
    return runCatching {
      val targetCanonical = File(path).canonicalFile.path
      val roots =
        listOf(
          privateDirectory(context),
          app.gyrolet.mpvrx.domain.storage.DurableStorageManager.legacyThumbnailsDirectory(context),
        )
      roots.any { root ->
        val rootCanonical = root.canonicalFile.path
        targetCanonical.startsWith("$rootCanonical${File.separator}")
      }
    }.getOrDefault(false)
  }
}
