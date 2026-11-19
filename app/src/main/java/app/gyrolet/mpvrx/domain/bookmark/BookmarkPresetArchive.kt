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
import android.net.Uri
import app.gyrolet.mpvrx.database.entities.BookmarkGroupEntity
import app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BookmarkPresetManifest(
  val schemaVersion: Int = 1,
  val presetCode: String? = null,
  val sourceLabel: String = "",
  val durationSec: Int = 0,
  val width: Int = 0,
  val height: Int = 0,
  val fileSizeBytes: Long = 0L,
  val exportedAt: Long = System.currentTimeMillis(),
  val bookmarks: List<BookmarkPresetEntry> = emptyList(),
)

@Serializable
data class BookmarkPresetEntry(
  val positionMs: Long,
  val title: String,
  val createdAt: Long = 0L,
  val thumbnailEntry: String? = null,
)

data class ImportedBookmarkPreset(
  val manifest: BookmarkPresetManifest,
  val bookmarks: List<PlaybackBookmarkEntity>,
)

/**
 * Serializes and deserializes self-contained bookmark preset ZIP packages containing
 * `preset.json` plus any private JPEG thumbnails referenced by those bookmarks.
 */
object BookmarkPresetArchive {
  private const val MANIFEST_ENTRY = "preset.json"
  private const val THUMBNAILS_DIR = "thumbnails/"
  private const val APP_THUMBNAIL_DIR_NAME = "bookmark-thumbnails"
  private const val NOMEDIA_FILE_NAME = ".nomedia"
  private const val MAX_MANIFEST_BYTES = 1 * 1024 * 1024
  private const val MAX_THUMBNAIL_BYTES = 8 * 1024 * 1024
  private const val MAX_BOOKMARKS = 500
  private const val MAX_ZIP_ENTRIES = 512

  private val json =
    Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      prettyPrint = true
    }

  fun writeArchive(
    output: OutputStream,
    group: BookmarkGroupEntity?,
    bookmarks: List<PlaybackBookmarkEntity>,
  ) {
    val sorted = bookmarks.sortedWith(compareBy<PlaybackBookmarkEntity> { it.positionMs }.thenBy { it.createdAt })
    val seenThumbPaths = LinkedHashMap<String, String>()
    val entries =
      sorted.map { bookmark ->
        val rawPath = bookmark.thumbnailPath?.takeIf { it.isNotBlank() }
        val file = rawPath?.let(::File)?.takeIf { it.isFile && it.canRead() && it.length() in 1L..MAX_THUMBNAIL_BYTES }
        val zipEntryName =
          if (file != null) {
            seenThumbPaths.getOrPut(file.absolutePath) {
              val ext = file.extension.lowercase().takeIf { it == "jpg" || it == "jpeg" || it == "png" || it == "webp" } ?: "jpg"
              "${THUMBNAILS_DIR}thumb_${seenThumbPaths.size}.$ext"
            }
          } else {
            null
          }
        BookmarkPresetEntry(
          positionMs = bookmark.positionMs.coerceAtLeast(0L),
          title = bookmark.title,
          createdAt = bookmark.createdAt,
          thumbnailEntry = zipEntryName,
        )
      }

    val manifest =
      BookmarkPresetManifest(
        presetCode = group?.presetCode,
        sourceLabel = group?.sourceLabel.orEmpty(),
        durationSec = group?.durationSec ?: 0,
        width = group?.width ?: 0,
        height = group?.height ?: 0,
        fileSizeBytes = group?.fileSizeBytes ?: 0L,
        bookmarks = entries,
      )

    ZipOutputStream(output).use { zip ->
      zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
      zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
      zip.closeEntry()

      seenThumbPaths.forEach { (sourcePath, entryName) ->
        val file = File(sourcePath)
        if (!file.isFile || !file.canRead()) return@forEach
        val rawBytes =
          runCatching {
            app.gyrolet.mpvrx.domain.secure.VaultCrypto.decryptBytes(file.readBytes())
          }.getOrNull() ?: return@forEach
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(rawBytes)
        zip.closeEntry()
      }
    }
  }

  fun readArchive(
    input: InputStream,
    targetGroupKey: String,
    thumbnailOutputDir: File,
  ): ImportedBookmarkPreset {
    ensurePrivateThumbnailDir(thumbnailOutputDir)
    var manifestJson: String? = null
    val pendingThumbBytes = LinkedHashMap<String, ByteArray>()
    var entryCount = 0

    ZipInputStream(input).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        entryCount++
        if (entryCount > MAX_ZIP_ENTRIES) {
          throw IllegalArgumentException("Preset archive has too many entries")
        }
        val entryName = entry.name.replace('\\', '/')
        if (entry.isDirectory || entryName.contains("..")) {
          zip.closeEntry()
          continue
        }
        when {
          entryName == MANIFEST_ENTRY -> {
            manifestJson = readBoundedBytes(zip, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
          }
          entryName.startsWith(THUMBNAILS_DIR) -> {
            val bytes = readBoundedBytes(zip, MAX_THUMBNAIL_BYTES)
            if (bytes.isNotEmpty()) {
              pendingThumbBytes[entryName] = bytes
            }
          }
        }
        zip.closeEntry()
      }
    }

    val rawManifest = manifestJson ?: throw IllegalArgumentException("Missing $MANIFEST_ENTRY in bookmark preset")
    val manifest = json.decodeFromString<BookmarkPresetManifest>(rawManifest)
    if (manifest.bookmarks.size > MAX_BOOKMARKS) {
      throw IllegalArgumentException("Preset archive exceeds maximum of $MAX_BOOKMARKS bookmarks")
    }

    val materializedPaths = HashMap<String, String>()
    val importedBookmarks =
      manifest.bookmarks.map { entry ->
        val savedThumbPath =
          entry.thumbnailEntry?.let { entryName ->
            pendingThumbBytes[entryName]?.let { bytes ->
              materializedPaths.getOrPut(entryName) {
                val payload =
                  runCatching {
                    app.gyrolet.mpvrx.domain.secure.VaultCrypto.encryptBytes(bytes)
                  }.getOrNull() ?: bytes
                val ext =
                  if (app.gyrolet.mpvrx.domain.secure.VaultCrypto.isEncryptedBytes(payload)) {
                    "dat"
                  } else {
                    entryName.substringAfterLast('.', "jpg").lowercase()
                      .takeIf { it == "jpg" || it == "jpeg" || it == "png" || it == "webp" } ?: "jpg"
                  }
                val destFile = File(thumbnailOutputDir, "bm_import_${UUID.randomUUID()}.$ext")
                destFile.writeBytes(payload)
                destFile.absolutePath
              }
            }
          }
        PlaybackBookmarkEntity(
          mediaId = targetGroupKey,
          positionMs = entry.positionMs.coerceAtLeast(0L),
          title = entry.title.trim().ifBlank { "Bookmark" },
          createdAt = entry.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
          bookTrackId = null,
          thumbnailPath = savedThumbPath,
        )
      }

    return ImportedBookmarkPreset(manifest = manifest, bookmarks = importedBookmarks)
  }

  suspend fun exportToUri(
    context: Context,
    uri: Uri,
    group: BookmarkGroupEntity?,
    bookmarks: List<PlaybackBookmarkEntity>,
  ): Result<Int> =
    withContext(Dispatchers.IO) {
      runCatching {
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
          writeArchive(output, group, bookmarks)
        } ?: throw IllegalStateException("Could not open preset destination")
        bookmarks.size
      }
    }

  suspend fun importFromUri(
    context: Context,
    uri: Uri,
    targetGroupKey: String,
    isSecure: Boolean = false,
  ): Result<ImportedBookmarkPreset> =
    withContext(Dispatchers.IO) {
      runCatching {
        app.gyrolet.mpvrx.domain.secure.VaultCrypto.ensureInitialized(context)
        val dir = BookmarkThumbnailStore.privateDirectory(context, isSecure = isSecure)
        context.contentResolver.openInputStream(uri)?.use { input ->
          readArchive(input, targetGroupKey, dir)
        } ?: throw IllegalStateException("Could not open preset file")
      }
    }

  fun suggestedFilename(
    group: BookmarkGroupEntity?,
    fallbackTitle: String?,
  ): String {
    val rawName =
      group?.presetCode?.takeIf { it.isNotBlank() }
        ?: group?.sourceLabel?.takeIf { it.isNotBlank() }
        ?: fallbackTitle?.takeIf { it.isNotBlank() }
        ?: "bookmarks"
    val clean = rawName.replace(Regex("""[^\w.\- ]+"""), "_").trim().ifBlank { "bookmarks" }.take(60)
    return "$clean.mpvrx-bookmarks.zip"
  }

  private fun ensurePrivateThumbnailDir(dir: File) {
    if (!dir.exists()) dir.mkdirs()
    val nomedia = File(dir, NOMEDIA_FILE_NAME)
    if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
  }

  private fun readBoundedBytes(
    input: InputStream,
    maxBytes: Int,
  ): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0
    while (true) {
      val read = input.read(chunk)
      if (read <= 0) break
      total += read
      if (total > maxBytes) {
        throw IllegalArgumentException("Preset entry exceeded $maxBytes bytes limit")
      }
      buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
  }
}
