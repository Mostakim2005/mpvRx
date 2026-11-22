/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import app.gyrolet.mpvrx.database.dao.SecureVideoMetadataDao
import app.gyrolet.mpvrx.database.dao.VideoMetadataDao
import app.gyrolet.mpvrx.database.entities.SecureVideoMetadataEntity
import app.gyrolet.mpvrx.database.entities.VideoMetadataEntity
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.secure.SecureMediaRegistry
import app.gyrolet.mpvrx.domain.secure.VaultCrypto
import app.gyrolet.mpvrx.domain.storage.DurableStorageManager
import app.gyrolet.mpvrx.utils.media.MediaInfoOps
import app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow

/**
 * Repository for caching video metadata with parallel processing support
 * Provides progressive loading and batch processing capabilities
 */
class VideoMetadataCacheRepository(
  private val context: Context,
  private val dao: VideoMetadataDao,
  private val secureDao: SecureVideoMetadataDao? = null,
) {
  companion object {
    private const val TAG = "VideoMetadataCache"
    private const val PARALLEL_PROCESSING_LIMIT = 2
    private const val CACHE_VALIDITY_DAYS = 30L
    private const val MAINTENANCE_PREFS = "video_metadata_cache"
    private const val LAST_MAINTENANCE_MS = "last_maintenance_ms"
    private const val MAINTENANCE_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L
    private const val CONTENT_KEY_PREFIX = "content-metadata:"
  }

  // Folder totals, Library and individual folders can request the same document concurrently.
  private val contentLocks = Array(64) { Mutex() }

  private fun contentKey(path: String, uri: Uri): String =
    CONTENT_KEY_PREFIX + (ScopedHiddenFolderScanner.localPathOf(uri)
      ?: path.takeIf { it.startsWith("/") }
      ?: ScopedHiddenFolderScanner.documentKey(uri))

  private fun isVaultTarget(path: String, file: File? = null): Boolean =
    DurableStorageManager.isVaultPath(path) ||
      SecureMediaRegistry.isProtectedReference(path) ||
      (file != null && VaultCrypto.isEncryptedFile(file))

  private fun encryptCodec(codec: String): String {
    if (codec.isEmpty()) return ""
    VaultCrypto.ensureInitialized(context)
    return runCatching { VaultCrypto.encryptString(codec) }.getOrDefault(codec)
  }

  private fun decryptCodec(codec: String): String {
    if (codec.isEmpty()) return ""
    VaultCrypto.ensureInitialized(context)
    return runCatching { VaultCrypto.decryptString(codec) }.getOrDefault(codec)
  }

  private fun SecureVideoMetadataEntity.toMetadata() = MediaInfoOps.VideoMetadata(
    sizeBytes = size,
    durationMs = duration,
    width = width,
    height = height,
    fps = fps,
    hasEmbeddedSubtitles = hasEmbeddedSubtitles,
    subtitleCodec = decryptCodec(subtitleCodec),
  )

  private fun VideoMetadataEntity.toMetadata() = MediaInfoOps.VideoMetadata(
    sizeBytes = size,
    durationMs = duration,
    width = width,
    height = height,
    fps = fps,
    hasEmbeddedSubtitles = hasEmbeddedSubtitles,
    subtitleCodec = subtitleCodec,
  )

  /** Read known durations without opening any document or consulting the filesystem. */
  suspend fun getCachedContentMetadata(videos: List<Video>): Map<String, MediaInfoOps.VideoMetadata> =
    withContext(Dispatchers.IO) {
      val sources = videos.filter { it.uri.scheme == "content" }
      val keys = sources.associateWith { contentKey(it.path, it.uri) }
      val cached = keys.values.distinct().chunked(900)
        .flatMap { dao.getMetadataBatch(it) }.associateBy { it.path }
      buildMap {
        for ((video, key) in keys) {
          val entry = cached[key] ?: continue
          if (entry.size == video.size && entry.dateModified == video.dateModified && entry.duration > 0L) {
            put(video.path, entry.toMetadata())
          }
        }
      }
    }

  /**
   * SAF and MediaStore content URIs use the provider's size/date, never File(content://...).
   * The same external-storage document shares a key across grants and browser modes.
   */
  suspend fun getOrExtractContentMetadata(
    video: Video,
    includeVideoCodec: Boolean = false,
  ): MediaInfoOps.VideoMetadata? = withContext(Dispatchers.IO) {
    val key = contentKey(video.path, video.uri)
    val versionKey = "$key|${video.size}|${video.dateModified}"
    contentLocks[(key.hashCode() and Int.MAX_VALUE) % contentLocks.size].withLock {
      val cached = dao.getMetadata(key, video.dateModified, video.size)
      if (cached != null && cached.duration > 0L) {
        val metadata = cached.toMetadata()
        if (!includeVideoCodec) return@withLock metadata
        val codec = MediaInfoOps.extractVideoCodec(context, video.uri, video.displayName, versionKey)
        return@withLock metadata.copy(videoCodec = codec.label, videoCodecMimeType = codec.mimeType)
      }

      val metadata = MediaInfoOps.extractBasicMetadata(context, video.uri, video.displayName, versionKey)
        .getOrNull() ?: return@withLock null
      // A failed/incomplete probe must not become a permanently cached zero duration.
      if (metadata.durationMs > 0L) {
        dao.insertMetadata(
          VideoMetadataEntity(
            path = key,
            size = video.size,
            dateModified = video.dateModified,
            duration = metadata.durationMs,
            width = metadata.width,
            height = metadata.height,
            fps = metadata.fps,
            hasEmbeddedSubtitles = metadata.hasEmbeddedSubtitles,
            subtitleCodec = metadata.subtitleCodec,
            lastScanned = System.currentTimeMillis(),
          ),
        )
      }
      metadata
    }
  }

  private fun shouldRefreshCachedMetadata(
    file: File,
    cached: VideoMetadataEntity,
  ): Boolean {
    val extension = file.extension.lowercase()
    val transportStream = extension == "ts" || extension == "mts" || extension == "m2ts"
    return transportStream && cached.duration <= 0L
  }

  /**
   * Get metadata from cache or extract using MediaInfo
   * Returns immediately with cached data, or suspends to extract if not cached
   */
  suspend fun getOrExtractMetadata(
    file: File,
    uri: Uri,
    displayName: String,
    includeVideoCodec: Boolean = false,
  ): MediaInfoOps.VideoMetadata? =
    withContext(Dispatchers.IO) {
      val path = file.absolutePath
      val isVault = isVaultTarget(path, file)
      val size = if (isVault) VaultCrypto.plaintextLength(file) else file.length()
      val dateModified = file.lastModified() / 1000

      if (isVault) {
        VaultCrypto.ensureInitialized(context)
        val pathHash = VaultCrypto.hmacKey(path)
        val secCached = secureDao?.getMetadata(pathHash)
          ?.takeIf { it.dateModified == dateModified && it.size == size && it.duration > 0L }
        if (secCached != null) {
          val metadata = secCached.toMetadata()
          if (!includeVideoCodec) return@withContext metadata
          val codec = MediaInfoOps.extractVideoCodec(context, uri, displayName)
          return@withContext metadata.copy(
            videoCodec = codec.label,
            videoCodecMimeType = codec.mimeType,
          )
        }
        val metadata = MediaInfoOps.extractBasicMetadata(context, uri, displayName)
          .getOrElse { error ->
            Log.e(TAG, "Failed to extract vault metadata for $displayName", error)
            return@withContext null
          }
        if (metadata.durationMs > 0L) {
          secureDao?.insertMetadata(
            SecureVideoMetadataEntity(
              pathHash = pathHash,
              size = size,
              dateModified = dateModified,
              duration = metadata.durationMs,
              width = metadata.width,
              height = metadata.height,
              fps = metadata.fps,
              hasEmbeddedSubtitles = metadata.hasEmbeddedSubtitles,
              subtitleCodec = encryptCodec(metadata.subtitleCodec),
              lastScanned = System.currentTimeMillis(),
            ),
          )
        }
        return@withContext metadata
      }

      // Try cache first
      val cached = dao.getMetadata(path, dateModified, size)
      if (cached != null && !shouldRefreshCachedMetadata(file, cached)) {
        Log.d(TAG, "Cache hit for $displayName")
        val metadata = MediaInfoOps.VideoMetadata(
          sizeBytes = cached.size,
          durationMs = cached.duration,
          width = cached.width,
          height = cached.height,
          fps = cached.fps,
          hasEmbeddedSubtitles = cached.hasEmbeddedSubtitles,
          subtitleCodec = cached.subtitleCodec,
        )
        if (!includeVideoCodec) return@withContext metadata

        val codec = MediaInfoOps.extractVideoCodec(context, uri, displayName)
        return@withContext metadata.copy(
          videoCodec = codec.label,
          videoCodecMimeType = codec.mimeType,
        )
      }

      // Cache miss - extract metadata
      Log.d(TAG, "Cache miss for $displayName, extracting metadata")
      val result = MediaInfoOps.extractBasicMetadata(context, uri, displayName)

      result
        .onSuccess { metadata ->
          // Do not persist failed/incomplete probes as permanent cache hits.
          if (metadata.durationMs > 0L) dao.insertMetadata(
            VideoMetadataEntity(
              path = path,
              size = size,
              dateModified = dateModified,
              duration = metadata.durationMs,
              width = metadata.width,
              height = metadata.height,
              fps = metadata.fps,
              hasEmbeddedSubtitles = metadata.hasEmbeddedSubtitles,
              subtitleCodec = metadata.subtitleCodec,
              lastScanned = System.currentTimeMillis(),
            ),
          )
        }.onFailure { error ->
          Log.w(TAG, "Failed to extract metadata for $displayName: ${error.message}")
        }

      result.getOrNull()
    }

  /**
   * OPTIMIZED: Batch get metadata from cache or extract using MediaInfo
   * Processes multiple files with batch cache lookup and parallel extraction
   * Returns map of paths to metadata (much faster than individual calls)
   */
  suspend fun getOrExtractMetadataBatch(
    files: List<Triple<File, Uri, String>>,
    videoCodecPaths: Set<String> = emptySet(),
  ): Map<String, MediaInfoOps.VideoMetadata> =
    withContext(Dispatchers.IO) {
      if (files.isEmpty()) return@withContext emptyMap()

      val results = mutableMapOf<String, MediaInfoOps.VideoMetadata>()

      // Batch lookup from segregated caches
      VaultCrypto.ensureInitialized(context)
      val vaultFlags = files.associate { (file, _, _) ->
        val p = file.absolutePath
        p to isVaultTarget(p, file)
      }
      val publicPaths = files.filter { vaultFlags[it.first.absolutePath] != true }.map { it.first.absolutePath }
      val cachedMap =
        if (publicPaths.isEmpty()) emptyMap()
        else publicPaths.distinct().chunked(500).flatMap { chunk -> dao.getMetadataBatch(chunk) }.associateBy { it.path }

      val vaultPathToHash =
        files.filter { vaultFlags[it.first.absolutePath] == true }
          .associate { (file, _, _) -> file.absolutePath to VaultCrypto.hmacKey(file.absolutePath) }
      val secCachedByHash =
        if (vaultPathToHash.isEmpty() || secureDao == null) emptyMap()
        else vaultPathToHash.values.distinct().chunked(500).flatMap { chunk -> secureDao.getMetadataBatch(chunk) }.associateBy { it.pathHash }

      // Separate cached and uncached files
      val cachedFiles = mutableListOf<Triple<String, File, MediaInfoOps.VideoMetadata>>()
      val uncachedFiles = mutableListOf<Triple<File, Uri, String>>()

      for ((file, uri, displayName) in files) {
        val path = file.absolutePath
        val isVault = vaultFlags[path] == true
        val size = if (isVault) VaultCrypto.plaintextLength(file) else file.length()
        val dateModified = file.lastModified() / 1000

        if (isVault) {
          val secCached = vaultPathToHash[path]?.let { secCachedByHash[it] }
          if (secCached != null &&
            secCached.dateModified == dateModified &&
            secCached.size == size &&
            secCached.duration > 0L
          ) {
            cachedFiles.add(Triple(path, file, secCached.toMetadata()))
          } else {
            uncachedFiles.add(Triple(file, uri, displayName))
          }
          continue
        }

        val cached = cachedMap[path]
        if (cached != null &&
          cached.dateModified == dateModified &&
          cached.size == size &&
          !shouldRefreshCachedMetadata(file, cached)
        ) {
          // Cache hit - valid entry
          cachedFiles.add(Triple(path, file, cached.toMetadata()))
        } else {
          // Cache miss or stale entry
          uncachedFiles.add(Triple(file, uri, displayName))
        }
      }

      Log.d(TAG, "Batch lookup: ${cachedFiles.size} cached, ${uncachedFiles.size} need extraction")

      // Add cached results
      for ((path, _, metadata) in cachedFiles) {
        results[path] = metadata
      }

      // Extract metadata for uncached files in parallel
      if (uncachedFiles.isNotEmpty()) {
        val extractedMetadata = mutableListOf<VideoMetadataEntity>()
        val extractedSecureMetadata = mutableListOf<SecureVideoMetadataEntity>()

        uncachedFiles.chunked(PARALLEL_PROCESSING_LIMIT).forEach { batch ->
          coroutineScope {
            val batchResults =
              batch
                .map { (file, uri, displayName) ->
                  async {
                    val path = file.absolutePath
                    val isVault = vaultFlags[path] == true
                    val size = if (isVault) VaultCrypto.plaintextLength(file) else file.length()
                    val dateModified = file.lastModified() / 1000

                    val result = MediaInfoOps.extractBasicMetadata(context, uri, displayName)
                    result
                      .onSuccess { metadata ->
                        if (metadata.durationMs > 0L) {
                          if (isVault) {
                            synchronized(extractedSecureMetadata) {
                              extractedSecureMetadata.add(
                                SecureVideoMetadataEntity(
                                  pathHash = vaultPathToHash[path] ?: VaultCrypto.hmacKey(path),
                                  size = size,
                                  dateModified = dateModified,
                                  duration = metadata.durationMs,
                                  width = metadata.width,
                                  height = metadata.height,
                                  fps = metadata.fps,
                                  hasEmbeddedSubtitles = metadata.hasEmbeddedSubtitles,
                                  subtitleCodec = encryptCodec(metadata.subtitleCodec),
                                  lastScanned = System.currentTimeMillis(),
                                ),
                              )
                            }
                          } else {
                            synchronized(extractedMetadata) {
                              extractedMetadata.add(
                                VideoMetadataEntity(
                                  path = path,
                                  size = size,
                                  dateModified = dateModified,
                                  duration = metadata.durationMs,
                                  width = metadata.width,
                                  height = metadata.height,
                                  fps = metadata.fps,
                                  hasEmbeddedSubtitles = metadata.hasEmbeddedSubtitles,
                                  subtitleCodec = metadata.subtitleCodec,
                                  lastScanned = System.currentTimeMillis(),
                                ),
                              )
                            }
                          }
                        }
                        path to metadata
                      }.onFailure { error ->
                        Log.w(TAG, "Failed to extract metadata for $displayName: ${error.message}")
                      }
                    result.getOrNull()?.let { path to it }
                  }
                }.awaitAll()
                .filterNotNull()

            results.putAll(batchResults)
          }
        }

        // Batch insert newly extracted metadata into segregated caches
        if (extractedMetadata.isNotEmpty()) {
          dao.insertMetadataBatch(extractedMetadata)
          Log.d(TAG, "Batch inserted ${extractedMetadata.size} metadata entries to cache")
        }
        if (extractedSecureMetadata.isNotEmpty()) {
          secureDao?.insertMetadataBatch(extractedSecureMetadata)
        }
      }

      if (videoCodecPaths.isNotEmpty()) {
        val sourceByPath = files.associateBy { it.first.absolutePath }
        val missingCodec =
          results
            .filter { (path, metadata) -> path in videoCodecPaths && metadata.videoCodec.isBlank() }
            .keys
            .toList()
        missingCodec.chunked(PARALLEL_PROCESSING_LIMIT).forEach { batch ->
          coroutineScope {
            val codecResults =
              batch.mapNotNull { path ->
                val source = sourceByPath[path] ?: return@mapNotNull null
                async {
                  val codec = MediaInfoOps.extractVideoCodec(context, source.second, source.third)
                  path to results.getValue(path).copy(
                    videoCodec = codec.label,
                    videoCodecMimeType = codec.mimeType,
                  )
                }
              }.awaitAll()
            results.putAll(codecResults)
          }
        }
      }

      results
    }

  /**
   * Clear old cache entries (older than CACHE_VALIDITY_DAYS)
   */
  suspend fun clearOldCache() {
    val cutoffTimestamp = System.currentTimeMillis() - (CACHE_VALIDITY_DAYS * 24 * 60 * 60 * 1000)
    dao.clearOldCache(cutoffTimestamp)
    Log.d(TAG, "Cleared cache entries older than $CACHE_VALIDITY_DAYS days")
  }

  /**
   * Invalidate cache for deleted/moved/renamed videos
   * Checks if cached files still exist and removes stale entries
   */
  suspend fun invalidateStaleEntries() {
    withContext(Dispatchers.IO) {
      val cachedPaths = dao.getAllCachedPaths()
      if (cachedPaths.isEmpty()) return@withContext

      Log.d(TAG, "Validating ${cachedPaths.size} cached entries...")

      val stalePaths =
        cachedPaths.filterNot { path ->
          // Provider-backed entries cannot be checked with File.exists(). Their version is
          // validated on use; deleted/orphaned documents expire through clearOldCache().
          path.startsWith(CONTENT_KEY_PREFIX) || File(path).exists()
        }

      if (stalePaths.isNotEmpty()) {
        stalePaths.chunked(999).forEach { chunk ->
          dao.deleteByPaths(chunk)
        }
        Log.d(TAG, "Removed ${stalePaths.size} stale cache entries (deleted/moved/renamed videos)")
      } else {
        Log.d(TAG, "No stale entries found")
      }
    }
  }

  /**
   * Invalidate cache for specific video (when deleted/moved/renamed)
   */
  suspend fun invalidateVideo(path: String) {
    withContext(Dispatchers.IO) {
      dao.deleteByPaths(listOf(path, contentKey(path, Uri.parse(path))))
      VaultCrypto.ensureInitialized(context)
      secureDao?.deleteByHashes(listOf(VaultCrypto.hmacKey(path)))
      Log.d(TAG, "Invalidated cache for: $path")
    }
  }

  /**
   * Invalidate cache for multiple videos (batch operation)
   */
  suspend fun invalidateVideos(paths: List<String>) {
    withContext(Dispatchers.IO) {
      if (paths.isEmpty()) return@withContext
      paths.flatMap { listOf(it, contentKey(it, Uri.parse(it))) }.distinct().chunked(900).forEach {
        dao.deleteByPaths(it)
      }
      VaultCrypto.ensureInitialized(context)
      paths.map { VaultCrypto.hmacKey(it) }.distinct().chunked(900).forEach { hashes ->
        secureDao?.deleteByHashes(hashes)
      }
      Log.d(TAG, "Invalidated cache for ${paths.size} videos")
    }
  }

  suspend fun clearSecureMetadata() {
    withContext(Dispatchers.IO) {
      secureDao?.clearAll()
    }
  }

  /**
   * Get cache statistics
   */
  suspend fun getCacheStats(): CacheStats =
    withContext(Dispatchers.IO) {
      CacheStats(
        totalEntries = dao.getCacheCount(),
        totalSizeBytes = dao.getTotalCacheSize() ?: 0L,
      )
    }

  /**
   * Clear all cached metadata
   */
  suspend fun clearAll() {
    dao.clearAll()
    Log.d(TAG, "Cleared all cached metadata")
  }

  /**
   * Perform comprehensive cache maintenance
   * - Remove stale entries (deleted/moved files)
   * - Clear old entries (30+ days)
   * Call this periodically (e.g., on app start)
   */
  suspend fun performMaintenance(force: Boolean = false) {
    withContext(Dispatchers.IO) {
      val now = System.currentTimeMillis()
      val prefs = context.getSharedPreferences(MAINTENANCE_PREFS, Context.MODE_PRIVATE)
      val lastMaintenance = prefs.getLong(LAST_MAINTENANCE_MS, 0L)
      if (!force && now - lastMaintenance < MAINTENANCE_INTERVAL_MS) {
        Log.d(TAG, "Skipping cache maintenance; last run was recent")
        return@withContext
      }

      Log.d(TAG, "Starting cache maintenance...")
      val startTime = now

      // Step 1: Remove stale entries
      invalidateStaleEntries()

      // Step 2: Clear old entries
      clearOldCache()

      val duration = System.currentTimeMillis() - startTime
      val stats = getCacheStats()
      prefs.edit().putLong(LAST_MAINTENANCE_MS, System.currentTimeMillis()).apply()
      Log.d(
        TAG,
        "Cache maintenance completed in ${duration}ms. " +
          "Entries: ${stats.totalEntries}, Size: ${formatSize(stats.totalSizeBytes)}",
      )
    }
  }

  private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    val digitGroups =
      (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0))
        .toInt()
        .coerceIn(0, units.lastIndex)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return "%.1f %s".format(value, units[digitGroups])
  }

  data class CacheStats(
    val totalEntries: Int,
    val totalSizeBytes: Long,
  )
}
