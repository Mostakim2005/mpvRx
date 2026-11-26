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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BookmarkPresetManifest(
  val schemaVersion: Int = 2,
  val presetCode: String? = null,
  val oshash: String? = null,
  val fingerprint: String? = null,
  val stemKey: String? = null,
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
  val syncUuid: String? = null,
  val positionMs: Long,
  val endPositionMs: Long? = null,
  val title: String,
  val primaryTag: String? = null,
  val tags: List<String> = emptyList(),
  val createdAt: Long = 0L,
  val updatedAt: Long = 0L,
  val thumbnailEntry: String? = null,
)

data class ImportedBookmarkPreset(
  val manifest: BookmarkPresetManifest,
  val bookmarks: List<PlaybackBookmarkEntity>,
  val importedTagsByIndex: List<List<String>> = emptyList(),
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
    tagsByBookmarkId: Map<Long, List<String>> = emptyMap(),
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
        val tagList = tagsByBookmarkId[bookmark.id].orEmpty()
        val pTag = bookmark.primaryTag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: tagList.firstOrNull()
        BookmarkPresetEntry(
          syncUuid = bookmark.syncUuid,
          positionMs = bookmark.positionMs.coerceAtLeast(0L),
          endPositionMs = bookmark.endPositionMs,
          title = bookmark.title,
          primaryTag = pTag,
          tags = tagList,
          createdAt = bookmark.createdAt,
          updatedAt = bookmark.updatedAt,
          thumbnailEntry = zipEntryName,
        )
      }

    val manifest =
      BookmarkPresetManifest(
        schemaVersion = 2,
        presetCode = group?.presetCode,
        oshash = group?.oshash,
        fingerprint = group?.fingerprint,
        stemKey = group?.stemKey,
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
    val pb = PushbackInputStream(input, 64)
    val head = ByteArray(64)
    val headLen = pb.read(head).coerceAtLeast(0)
    if (headLen > 0) {
      pb.unread(head, 0, headLen)
    }
    val headPreview = head.copyOf(headLen).toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    if (headPreview.startsWith("{") || headPreview.startsWith("[")) {
      val jsonBytes = readBoundedBytes(pb, 16 * 1024 * 1024)
      val rawText = jsonBytes.toString(Charsets.UTF_8).trimStart('\uFEFF').trim()
      return parseJsonImport(rawText, targetGroupKey, thumbnailOutputDir)
    }

    var manifestJson: String? = null
    val pendingThumbBytes = LinkedHashMap<String, ByteArray>()
    var entryCount = 0

    ZipInputStream(pb).use { zip ->
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
    val decoded = json.decodeFromString<BookmarkPresetManifest>(rawManifest)
    val canonicalCode =
      decoded.presetCode?.let { BookmarkMediaMatcher.extractJvidCode(it) ?: it }
        ?: BookmarkMediaMatcher.extractJvidCode(decoded.sourceLabel)
    val manifest = decoded.copy(presetCode = canonicalCode)
    if (manifest.bookmarks.size > MAX_BOOKMARKS) {
      throw IllegalArgumentException("Preset archive exceeds maximum of $MAX_BOOKMARKS bookmarks")
    }

    val materializedPaths = HashMap<String, String>()
    val importedTags = ArrayList<List<String>>(manifest.bookmarks.size)
    val importedBookmarks =
      manifest.bookmarks.map { entry ->
        val savedThumbPath =
          entry.thumbnailEntry?.let { entryName ->
            pendingThumbBytes[entryName]?.let { bytes ->
              materializedPaths.getOrPut(entryName) {
                val extHint = entryName.substringAfterLast('.', "jpg")
                saveImportedThumbnailBytes(bytes, extHint, thumbnailOutputDir)
              }
            }
          }
        val cleanTags =
          (entry.tags.ifEmpty { listOfNotNull(entry.primaryTag) })
            .map { raw -> BookmarkTagRules.normalize(raw) }
            .filter { it.isNotBlank() }
            .distinct()
        importedTags.add(cleanTags)
        val created = entry.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        PlaybackBookmarkEntity(
          mediaId = targetGroupKey,
          positionMs = entry.positionMs.coerceAtLeast(0L),
          title = entry.title.trim().ifBlank { "Bookmark" },
          createdAt = created,
          bookTrackId = null,
          thumbnailPath = savedThumbPath,
          endPositionMs = entry.endPositionMs,
          primaryTag = entry.primaryTag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: cleanTags.firstOrNull(),
          syncUuid = entry.syncUuid?.trim()?.takeIf { it.isNotEmpty() },
          originApp = "preset_zip",
          updatedAt = entry.updatedAt.takeIf { it > 0L } ?: created,
        )
      }

    return ImportedBookmarkPreset(
      manifest = manifest,
      bookmarks = importedBookmarks,
      importedTagsByIndex = importedTags,
    )
  }

  private fun parseJsonImport(
    rawText: String,
    targetGroupKey: String,
    thumbnailOutputDir: File,
  ): ImportedBookmarkPreset {
    val root = json.parseToJsonElement(rawText)
    if (root is JsonObject && root.containsKey("schemaVersion")) {
      val decoded = json.decodeFromString<BookmarkPresetManifest>(rawText)
      val canonicalCode =
        decoded.presetCode?.let { BookmarkMediaMatcher.extractJvidCode(it) ?: it }
          ?: BookmarkMediaMatcher.extractJvidCode(decoded.sourceLabel)
      val manifest = decoded.copy(presetCode = canonicalCode)
      if (manifest.bookmarks.size > MAX_BOOKMARKS) {
        throw IllegalArgumentException("Preset JSON exceeds maximum of $MAX_BOOKMARKS bookmarks")
      }
      val importedTags = ArrayList<List<String>>(manifest.bookmarks.size)
      val importedBookmarks =
        manifest.bookmarks.map { entry ->
          val cleanTags =
            (entry.tags.ifEmpty { listOfNotNull(entry.primaryTag) })
              .map { raw -> BookmarkTagRules.normalize(raw) }
              .filter { it.isNotBlank() }
              .distinct()
          importedTags.add(cleanTags)
          val created = entry.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()
          PlaybackBookmarkEntity(
            mediaId = targetGroupKey,
            positionMs = entry.positionMs.coerceAtLeast(0L),
            title = entry.title.trim().ifBlank { "Bookmark" },
            createdAt = created,
            bookTrackId = null,
            thumbnailPath = null,
            endPositionMs = entry.endPositionMs,
            primaryTag = entry.primaryTag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: cleanTags.firstOrNull(),
            syncUuid = entry.syncUuid?.trim()?.takeIf { it.isNotEmpty() },
            originApp = "preset_json",
            updatedAt = entry.updatedAt.takeIf { it > 0L } ?: created,
          )
        }
      return ImportedBookmarkPreset(
        manifest = manifest,
        bookmarks = importedBookmarks,
        importedTagsByIndex = importedTags,
      )
    }

    val rawArray: JsonArray =
      when {
        root is JsonArray -> root
        root is JsonObject && root["bookmarks"] is JsonArray -> root["bookmarks"]!!.jsonArray
        root is JsonObject && root["scene_markers"] is JsonArray -> root["scene_markers"]!!.jsonArray
        root is JsonObject && root["data"] is JsonObject -> {
          val findMarkers = root["data"]!!.jsonObject["findSceneMarkers"] as? JsonObject
          findMarkers?.get("scene_markers") as? JsonArray ?: JsonArray(emptyList())
        }
        else -> JsonArray(emptyList())
      }
    if (rawArray.isEmpty()) {
      throw IllegalArgumentException("No bookmarks or markers found in JSON file")
    }
    if (rawArray.size > MAX_BOOKMARKS) {
      throw IllegalArgumentException("JSON exceeds maximum of $MAX_BOOKMARKS bookmarks")
    }

    var detectedCode: String? =
      if (root is JsonObject) {
        val rawCode = (root["presetCode"] ?: root["code"])?.jsonPrimitive?.content
        BookmarkMediaMatcher.extractJvidCode(rawCode)
      } else {
        null
      }

    val importedTags = ArrayList<List<String>>(rawArray.size)
    val importedEntries = ArrayList<BookmarkPresetEntry>(rawArray.size)
    val importedBookmarks =
      rawArray.mapNotNull { elem ->
        val obj = elem as? JsonObject ?: return@mapNotNull null
        val posMs =
          obj["positionMs"]?.jsonPrimitive?.longOrNull
            ?: ((obj["timestamp_sec"] ?: obj["seconds"])?.jsonPrimitive?.doubleOrNull?.times(1000.0))?.toLong()
            ?: return@mapNotNull null
        val endMs =
          obj["endPositionMs"]?.jsonPrimitive?.longOrNull
            ?: ((obj["end_sec"] ?: obj["end_seconds"])?.jsonPrimitive?.doubleOrNull?.times(1000.0))?.toLong()
        val itemCode =
          (obj["code"] ?: (obj["scene"] as? JsonObject)?.get("code") ?: (obj["scene"] as? JsonObject)?.get("title"))
            ?.jsonPrimitive?.content
        if (detectedCode == null && !itemCode.isNullOrBlank()) {
          detectedCode = BookmarkMediaMatcher.extractJvidCode(itemCode)
        }
        val tagNames = LinkedHashSet<String>()
        val primTagObj = obj["primary_tag"] as? JsonObject
        val rawPrimary =
          (primTagObj?.get("name") ?: obj["primaryTag"] ?: obj["primary_tag"])?.jsonPrimitive?.content
        if (!rawPrimary.isNullOrBlank()) {
          BookmarkTagRules.normalize(rawPrimary).takeIf { it.isNotBlank() }?.let { tagNames.add(it) }
        }
        val tagsArr = (obj["tags_list"] ?: obj["tags"]) as? JsonArray
        tagsArr?.forEach { tEl ->
          val tStr =
            when (tEl) {
              is JsonObject -> tEl["name"]?.jsonPrimitive?.content
              else -> runCatching { tEl.jsonPrimitive.content }.getOrNull()
            }
          if (!tStr.isNullOrBlank()) {
            BookmarkTagRules.normalize(tStr).takeIf { it.isNotBlank() }?.let { tagNames.add(it) }
          }
        }
        val cleanTags = tagNames.toList()
        importedTags.add(cleanTags)

        val thumbRaw = obj["thumbnail_base64"]?.jsonPrimitive?.content.orEmpty().trim()
        val thumbPath =
          if (thumbRaw.isNotEmpty()) {
            val b64 = thumbRaw.substringAfter("base64,", thumbRaw)
            val bytes = runCatching { Base64.getMimeDecoder().decode(b64) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
              saveImportedThumbnailBytes(bytes, "jpg", thumbnailOutputDir)
            } else {
              null
            }
          } else {
            null
          }
        val title = obj["title"]?.jsonPrimitive?.content?.trim()?.ifBlank { "Bookmark" } ?: "Bookmark"
        val syncUuid = obj["sync_uuid"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val now = System.currentTimeMillis()
        importedEntries.add(
          BookmarkPresetEntry(
            syncUuid = syncUuid,
            positionMs = posMs.coerceAtLeast(0L),
            endPositionMs = endMs,
            title = title,
            primaryTag = cleanTags.firstOrNull(),
            tags = cleanTags,
            createdAt = now,
            updatedAt = now,
          ),
        )
        PlaybackBookmarkEntity(
          mediaId = targetGroupKey,
          positionMs = posMs.coerceAtLeast(0L),
          title = title,
          createdAt = now,
          bookTrackId = null,
          thumbnailPath = thumbPath,
          endPositionMs = endMs,
          primaryTag = cleanTags.firstOrNull(),
          syncUuid = syncUuid,
          originApp = "json_import",
          updatedAt = now,
        )
      }

    if (importedBookmarks.isEmpty()) {
      throw IllegalArgumentException("No valid bookmarks found in JSON file")
    }
    val manifest =
      BookmarkPresetManifest(
        schemaVersion = 2,
        presetCode = detectedCode,
        bookmarks = importedEntries,
      )
    return ImportedBookmarkPreset(
      manifest = manifest,
      bookmarks = importedBookmarks,
      importedTagsByIndex = importedTags,
    )
  }

  private fun saveImportedThumbnailBytes(
    bytes: ByteArray,
    extHint: String,
    thumbnailOutputDir: File,
  ): String {
    val payload =
      runCatching {
        app.gyrolet.mpvrx.domain.secure.VaultCrypto.encryptBytes(bytes)
      }.getOrNull() ?: bytes
    val ext =
      if (app.gyrolet.mpvrx.domain.secure.VaultCrypto.isEncryptedBytes(payload)) {
        "dat"
      } else {
        extHint.lowercase()
          .takeIf { it == "jpg" || it == "jpeg" || it == "png" || it == "webp" } ?: "jpg"
      }
    val destFile = File(thumbnailOutputDir, "bm_import_${UUID.randomUUID()}.$ext")
    destFile.writeBytes(payload)
    return destFile.absolutePath
  }

  suspend fun exportToUri(
    context: Context,
    uri: Uri,
    group: BookmarkGroupEntity?,
    bookmarks: List<PlaybackBookmarkEntity>,
    tagsByBookmarkId: Map<Long, List<String>> = emptyMap(),
  ): Result<Int> =
    withContext(Dispatchers.IO) {
      runCatching {
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
          writeArchive(output, group, bookmarks, tagsByBookmarkId)
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
