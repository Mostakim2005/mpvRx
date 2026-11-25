package app.gyrolet.mpvrx.repository.bookmark

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.database.MpvRxDatabase
import app.gyrolet.mpvrx.database.entities.BookmarkGroupEntity
import app.gyrolet.mpvrx.database.entities.BookmarkMediaLinkEntity
import app.gyrolet.mpvrx.database.entities.BookmarkMediaRefEntity
import app.gyrolet.mpvrx.database.entities.BookmarkTagEntity
import app.gyrolet.mpvrx.database.entities.BookmarkTagMemoryEntity
import app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity
import app.gyrolet.mpvrx.domain.bookmark.BookmarkMediaMatcher
import app.gyrolet.mpvrx.domain.bookmark.BookmarkTagRules
import app.gyrolet.mpvrx.domain.bookmark.BookmarkThumbnailStore
import app.gyrolet.mpvrx.domain.bookmark.OshashCalculator
import app.gyrolet.mpvrx.domain.secure.VaultCrypto
import app.gyrolet.mpvrx.preferences.BookmarkSyncPreferences
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

@Serializable
data class SyncGroupDto(
  @SerialName("group_key") val groupKey: String,
  val code: String = "",
  val oshash: String = "",
  val fingerprint: String = "",
  @SerialName("stem_key") val stemKey: String = "",
  @SerialName("duration_sec") val durationSec: Int = 0,
  val width: Int = 0,
  val height: Int = 0,
  @SerialName("file_size_bytes") val fileSizeBytes: Long = 0L,
  @SerialName("source_label") val sourceLabel: String = "",
  @SerialName("stash_scene_id") val stashSceneId: String = "",
  @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class SyncBookmarkDto(
  @SerialName("sync_uuid") val syncUuid: String,
  @SerialName("group_key") val groupKey: String,
  val code: String = "",
  @SerialName("timestamp_sec") val timestampSec: Double,
  @SerialName("end_sec") val endSec: Double? = null,
  @SerialName("formatted_time") val formattedTime: String = "",
  val title: String = "",
  @SerialName("primary_tag") val primaryTag: String = "",
  val tags: List<String> = emptyList(),
  @SerialName("origin_app") val originApp: String = "mpvrx",
  @SerialName("stash_marker_id") val stashMarkerId: String = "",
  @SerialName("created_at") val createdAt: String = "",
  @SerialName("updated_at") val updatedAt: String = "",
  @SerialName("has_thumbnail") val hasThumbnail: Boolean = false,
  @SerialName("thumbnail_base64") val thumbnailBase64: String? = null,
)

@Serializable
data class SyncTombstoneDto(
  @SerialName("sync_uuid") val syncUuid: String,
  @SerialName("group_key") val groupKey: String = "",
  @SerialName("deleted_at") val deletedAt: String = "",
)

@Serializable
data class SyncBundleResponse(
  @SerialName("server_time") val serverTime: String = "",
  @SerialName("stash_reachable") val stashReachable: Boolean = false,
  val groups: List<SyncGroupDto> = emptyList(),
  val bookmarks: List<SyncBookmarkDto> = emptyList(),
  val tombstones: List<SyncTombstoneDto> = emptyList(),
)

@Serializable
data class SyncMergeRequest(
  val groups: List<SyncGroupDto> = emptyList(),
  val bookmarks: List<SyncBookmarkDto> = emptyList(),
  val tombstones: List<SyncTombstoneDto> = emptyList(),
)

@Serializable
data class SyncMergeStatsDto(
  @SerialName("groups_upserted") val groupsUpserted: Int = 0,
  @SerialName("bookmarks_inserted") val bookmarksInserted: Int = 0,
  @SerialName("bookmarks_updated") val bookmarksUpdated: Int = 0,
  @SerialName("tombstones_applied") val tombstonesApplied: Int = 0,
  @SerialName("thumbnails_saved") val thumbnailsSaved: Int = 0,
)

@Serializable
data class SyncMergeResponse(
  val success: Boolean = false,
  val stats: SyncMergeStatsDto = SyncMergeStatsDto(),
  val bundle: SyncBundleResponse = SyncBundleResponse(),
)

@Serializable
data class SyncLocalDriveStatusDto(
  @SerialName("vault_open") val vaultOpen: Boolean = false,
  val label: String = "",
)

@Serializable
data class SyncStashStatusDto(
  val reachable: Boolean = false,
  @SerialName("marker_count") val markerCount: Int = 0,
)

@Serializable
data class SyncStatusResponse(
  val success: Boolean = false,
  @SerialName("local_status") val localStatus: SyncLocalDriveStatusDto = SyncLocalDriveStatusDto(),
  val stash: SyncStashStatusDto = SyncStashStatusDto(),
)

@Serializable
data class SyncThumbnailUploadRequest(
  @SerialName("thumbnail_base64") val thumbnailBase64: String,
)

data class PcSyncResult(
  val inserted: Int,
  val updated: Int,
  val pushed: Int,
  val thumbnailsDownloaded: Int,
  val thumbnailsUploaded: Int,
  val stashReachable: Boolean,
)

data class LinkedOrphanResult(
  val groupKey: String,
  val displayName: String,
  val bookmarkCount: Int,
)

/**
 * Coordinates two-way LAN bookmark sync with AV Manager (`vault.db`) <-> Stash (`localhost:9999`)
 * and orphan BookmarkGroup linking in [MpvRxDatabase].
 *
 * Security boundary (`R-11`): Never reads from or writes to `SecureVaultDatabase` so Secure Vault
 * paths, URIs, and secrets remain isolated on-device.
 */
class PcBookmarkSyncRepository(
  private val context: Context,
  private val database: MpvRxDatabase,
  private val prefs: BookmarkSyncPreferences,
  private val httpClient: OkHttpClient,
  private val json: Json,
) {
  private val bookmarkDao get() = database.playbackBookmarkDao()
  private val tagDao get() = database.bookmarkTagDao()
  private val tombstoneDao get() = database.bookmarkSyncTombstoneDao()
  private val syncClient: OkHttpClient by lazy {
    httpClient
      .newBuilder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(90, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .callTimeout(120, TimeUnit.SECONDS)
      .build()
  }

  suspend fun testConnection(
    hubUrl: String = prefs.hubUrl.get(),
    token: String = prefs.syncToken.get(),
  ): Result<String> =
    withContext(Dispatchers.IO) {
      val baseUrl = BookmarkSyncPreferences.normalizeHubUrl(hubUrl)
      runCatching {
        require(baseUrl.isNotEmpty()) { "Enter AV Manager URL" }
        val req = buildRequestBuilder("$baseUrl/api/sync/status", token).get().build()
        val body =
          syncClient.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
              error("Invalid Sync Token (HTTP ${resp.code})")
            }
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body.string()
          }
        val status = json.decodeFromString<SyncStatusResponse>(body)
        val vaultMsg = if (status.localStatus.vaultOpen) "Vault open" else "Vault locked"
        val stashMsg =
          if (status.stash.reachable) "Stash OK (${status.stash.markerCount} markers)" else "Stash offline"
        "$vaultMsg • $stashMsg"
      }.recoverCatching { err ->
        throw friendlyNetworkError(baseUrl, err)
      }
    }

  suspend fun syncWithPcHub(
    hubUrl: String = prefs.hubUrl.get(),
    token: String = prefs.syncToken.get(),
  ): Result<PcSyncResult> =
    withContext(Dispatchers.IO) {
      val baseUrl = BookmarkSyncPreferences.normalizeHubUrl(hubUrl)
      runCatching {
        require(baseUrl.isNotEmpty()) { "Set AV Manager URL in Media Servers settings" }
        VaultCrypto.ensureInitialized(context)

        val localGroups = bookmarkDao.getAllGroups()
        val rawBookmarks = bookmarkDao.getPublicBookmarks()
        val allTags = tagDao.getAllTagsOnce().groupBy { it.bookmarkId }

        val localBookmarks =
          rawBookmarks.map { bm ->
            if (!bm.syncUuid.isNullOrBlank()) {
              bm
            } else {
              val generatedUuid = UUID.randomUUID().toString()
              bookmarkDao.updateBookmarkSyncMetadata(
                id = bm.id,
                endPositionMs = bm.endPositionMs,
                primaryTag = bm.primaryTag ?: allTags[bm.id]?.firstOrNull()?.name,
                syncUuid = generatedUuid,
                originApp = bm.originApp.ifBlank { "mpvrx" },
                updatedAt = bm.updatedAt.takeIf { it > 0L } ?: bm.createdAt,
              )
              bm.copy(syncUuid = generatedUuid)
            }
          }

        val groupDtos =
          localGroups.map { g ->
            SyncGroupDto(
              groupKey = g.groupKey,
              code = g.presetCode.orEmpty(),
              oshash = g.oshash.orEmpty(),
              fingerprint = g.fingerprint.orEmpty(),
              stemKey = g.stemKey.orEmpty(),
              durationSec = g.durationSec,
              width = g.width,
              height = g.height,
              fileSizeBytes = g.fileSizeBytes,
              sourceLabel = g.sourceLabel,
              updatedAt = formatIsoUtc(g.updatedAt),
            )
          }

        val bookmarkDtos =
          localBookmarks.map { bm ->
            val tagsList = allTags[bm.id].orEmpty().map { it.displayName.ifBlank { it.name } }.distinct()
            val pTag = bm.primaryTag?.takeIf { it.isNotBlank() } ?: tagsList.firstOrNull().orEmpty()
            SyncBookmarkDto(
              syncUuid = bm.syncUuid!!,
              groupKey = bm.mediaId,
              code = localGroups.firstOrNull { it.groupKey == bm.mediaId }?.presetCode.orEmpty(),
              timestampSec = bm.positionMs / 1000.0,
              endSec = bm.endPositionMs?.let { it / 1000.0 },
              title = bm.title,
              primaryTag = pTag,
              tags = tagsList,
              originApp = bm.originApp.ifBlank { "mpvrx" },
              createdAt = formatIsoUtc(bm.createdAt),
              updatedAt = formatIsoUtc(bm.updatedAt.takeIf { it > 0L } ?: bm.createdAt),
              hasThumbnail = !bm.thumbnailPath.isNullOrBlank(),
            )
          }

        val tombDtos =
          tombstoneDao.getAll().map { t ->
            SyncTombstoneDto(
              syncUuid = t.syncUuid,
              groupKey = t.groupKey.orEmpty(),
              deletedAt = formatIsoUtc(t.deletedAt),
            )
          }

        val mergePayload = json.encodeToString(SyncMergeRequest(groupDtos, bookmarkDtos, tombDtos))
        val mergeReq =
          buildRequestBuilder("$baseUrl/api/sync/merge?sync_stash=true", token)
            .post(mergePayload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val mergeBody =
          syncClient.newCall(mergeReq).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
              error("Invalid Sync Token (HTTP ${resp.code})")
            }
            if (!resp.isSuccessful) error("Sync HTTP ${resp.code}")
            resp.body.string()
          }
        val mergeResp = json.decodeFromString<SyncMergeResponse>(mergeBody)
        val bundle = mergeResp.bundle

        var insertedCount = 0
        var updatedCount = 0
        var downloadedThumbs = 0
        var uploadedThumbs = 0

        // 1. Apply remote tombstones locally
        val tombstoneSet = HashSet<String>()
        bundle.tombstones.forEach { tomb ->
          val uuid = tomb.syncUuid.trim()
          if (uuid.isEmpty()) return@forEach
          tombstoneSet.add(uuid)
          val existing = bookmarkDao.findBookmarkBySyncUuid(uuid)
          if (existing != null) {
            val tombMs = parseIsoMillis(tomb.deletedAt)
            if (tombMs <= 0L || tombMs >= existing.updatedAt) {
              bookmarkDao.delete(existing.id)
              val thumbPath = existing.thumbnailPath
              if (!thumbPath.isNullOrBlank() && bookmarkDao.countByThumbnailPath(thumbPath) == 0) {
                BookmarkThumbnailStore.deleteThumbnail(context, thumbPath, isSecure = false)
              }
            }
          }
        }

        // 2. Reconcile groups (match existing phone media groups by groupKey / oshash / code / fingerprint)
        val groupKeyMap = HashMap<String, String>()
        val refreshedGroups = bookmarkDao.getAllGroups()
        bundle.groups.forEach { rg ->
          val matched =
            refreshedGroups.firstOrNull { it.groupKey == rg.groupKey }
              ?: rg.oshash.takeIf { it.isNotBlank() }?.let { oh ->
                refreshedGroups.firstOrNull { !it.oshash.isNullOrBlank() && it.oshash.equals(oh, ignoreCase = true) }
              }
              ?: rg.code.takeIf { it.isNotBlank() }?.let { code ->
                refreshedGroups.firstOrNull {
                  !it.presetCode.isNullOrBlank() && it.presetCode.equals(code, ignoreCase = true)
                }
              }
              ?: rg.fingerprint.takeIf { it.isNotBlank() }?.let { fp ->
                refreshedGroups.firstOrNull { it.fingerprint == fp }
              }

          val targetGroupKey = matched?.groupKey ?: rg.groupKey
          groupKeyMap[rg.groupKey] = targetGroupKey

          val mergedGroup =
            BookmarkGroupEntity(
              groupKey = targetGroupKey,
              presetCode = matched?.presetCode?.takeIf { it.isNotBlank() } ?: rg.code.takeIf { it.isNotBlank() },
              oshash = matched?.oshash?.takeIf { it.isNotBlank() } ?: rg.oshash.takeIf { it.isNotBlank() },
              fingerprint =
                matched?.fingerprint?.takeIf { it.isNotBlank() } ?: rg.fingerprint.takeIf { it.isNotBlank() },
              stemKey = matched?.stemKey?.takeIf { it.isNotBlank() } ?: rg.stemKey.takeIf { it.isNotBlank() },
              durationSec = matched?.durationSec?.takeIf { it > 0 } ?: rg.durationSec,
              width = matched?.width?.takeIf { it > 0 } ?: rg.width,
              height = matched?.height?.takeIf { it > 0 } ?: rg.height,
              fileSizeBytes = matched?.fileSizeBytes?.takeIf { it > 0L } ?: rg.fileSizeBytes,
              sourceLabel = matched?.sourceLabel?.takeIf { it.isNotBlank() } ?: rg.sourceLabel,
              updatedAt = maxOf(matched?.updatedAt ?: 0L, parseIsoMillis(rg.updatedAt)),
            )
          bookmarkDao.upsertGroup(mergedGroup)
        }

        // 3. Reconcile bookmarks + tags + thumbnails
        val currentPublic = bookmarkDao.getPublicBookmarks().toMutableList()
        val thumbDir = BookmarkThumbnailStore.privateDirectory(context, isSecure = false)

        bundle.bookmarks.forEach { rb ->
          val uuid = rb.syncUuid.trim()
          if (uuid.isEmpty() || uuid in tombstoneSet) return@forEach

          val targetGroupKey = groupKeyMap[rb.groupKey] ?: rb.groupKey
          val posMs = (rb.timestampSec * 1000.0).roundToLong().coerceAtLeast(0L)
          val endMs = rb.endSec?.let { (it * 1000.0).roundToLong().coerceAtLeast(posMs) }
          val cleanTags = rb.tags.map { BookmarkTagRules.normalize(it) }.filter { it.isNotBlank() }.distinct()
          val primaryTag =
            rb.primaryTag.trim().lowercase().takeIf { it.isNotEmpty() } ?: cleanTags.firstOrNull()
          val mergedTags =
            buildList {
              if (!primaryTag.isNullOrBlank()) add(primaryTag)
              cleanTags.forEach { t -> if (t !in this) add(t) }
            }
          val remoteUpdMs =
            parseIsoMillis(rb.updatedAt).takeIf { it > 0L }
              ?: parseIsoMillis(rb.createdAt).takeIf { it > 0L }
              ?: System.currentTimeMillis()
          val remoteCreatedMs = parseIsoMillis(rb.createdAt).takeIf { it > 0L } ?: remoteUpdMs

          val existing =
            currentPublic.firstOrNull { it.syncUuid == uuid }
              ?: currentPublic.firstOrNull {
                it.mediaId == targetGroupKey && abs(it.positionMs - posMs) <= 1000L
              }

          val targetBookmarkId: Long
          var localThumbPath = existing?.thumbnailPath

          if (existing == null) {
            val insertedEntity =
              PlaybackBookmarkEntity(
                mediaId = targetGroupKey,
                positionMs = posMs,
                title = rb.title.trim().ifBlank { "Bookmark" },
                createdAt = remoteCreatedMs,
                bookTrackId = null,
                thumbnailPath = null,
                endPositionMs = endMs,
                primaryTag = primaryTag,
                syncUuid = uuid,
                originApp = rb.originApp.ifBlank { "av_manager" },
                updatedAt = remoteUpdMs,
              )
            targetBookmarkId = bookmarkDao.insertSyncBookmark(insertedEntity)
            currentPublic.add(insertedEntity.copy(id = targetBookmarkId))
            if (mergedTags.isNotEmpty()) {
              applyBookmarkTags(targetBookmarkId, mergedTags)
            }
            insertedCount++
          } else {
            targetBookmarkId = existing.id
            val localUpd = existing.updatedAt.takeIf { it > 0L } ?: existing.createdAt
            if (remoteUpdMs > localUpd) {
              bookmarkDao.rename(existing.id, rb.title.trim().ifBlank { existing.title })
              bookmarkDao.updateBookmarkSyncMetadata(
                id = existing.id,
                endPositionMs = endMs,
                primaryTag = primaryTag,
                syncUuid = uuid,
                originApp = existing.originApp,
                updatedAt = remoteUpdMs,
              )
              applyBookmarkTags(existing.id, mergedTags)
              updatedCount++
            } else if (existing.syncUuid.isNullOrBlank()) {
              bookmarkDao.updateBookmarkSyncMetadata(
                id = existing.id,
                endPositionMs = existing.endPositionMs ?: endMs,
                primaryTag = existing.primaryTag ?: primaryTag,
                syncUuid = uuid,
                originApp = existing.originApp,
                updatedAt = localUpd,
              )
            }
          }

          if (rb.hasThumbnail && localThumbPath.isNullOrBlank()) {
            val downloaded = downloadAndEncryptThumbnail(baseUrl, token, uuid, thumbDir)
            if (downloaded != null) {
              bookmarkDao.updateThumbnail(targetBookmarkId, downloaded)
              localThumbPath = downloaded
              downloadedThumbs++
            }
          } else if (!rb.hasThumbnail && !localThumbPath.isNullOrBlank()) {
            if (uploadDecryptedThumbnail(baseUrl, token, uuid, localThumbPath)) {
              uploadedThumbs++
            }
          }
        }

        val nowIso = formatIsoUtc(System.currentTimeMillis())
        prefs.lastSyncIso.set(nowIso)
        val pushedCount = mergeResp.stats.bookmarksInserted + mergeResp.stats.bookmarksUpdated
        prefs.lastSyncSummary.set("+$insertedCount / ~$updatedCount • ↑$pushedCount")

        PcSyncResult(
          inserted = insertedCount,
          updated = updatedCount,
          pushed = pushedCount,
          thumbnailsDownloaded = downloadedThumbs,
          thumbnailsUploaded = uploadedThumbs,
          stashReachable = bundle.stashReachable,
        )
      }.recoverCatching { err ->
        throw friendlyNetworkError(baseUrl, err)
      }
    }

  suspend fun linkOrphanGroupToUri(
    groupKey: String,
    uri: Uri,
  ): Result<LinkedOrphanResult> =
    withContext(Dispatchers.IO) {
      runCatching {
        runCatching {
          context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val uriString = uri.toString()
        val mediaId = PlaybackIdentity.forUri(uriString)
        val resolvedKey = bookmarkDao.findLinkedGroupKey(groupKey) ?: groupKey
        val displayName = resolveUriDisplayName(uri)
        val fileSize = resolveUriFileSize(uri)
        val oshash = OshashCalculator.computeOshash(context, uri)
        val stemKey = BookmarkMediaMatcher.normalizedStem(displayName)
        val now = System.currentTimeMillis()
        val existingGroup = bookmarkDao.getGroup(resolvedKey)

        val updatedGroup =
          (existingGroup ?: BookmarkGroupEntity(groupKey = resolvedKey)).copy(
            oshash = oshash ?: existingGroup?.oshash,
            stemKey = stemKey ?: existingGroup?.stemKey,
            fileSizeBytes = fileSize.takeIf { it > 0L } ?: existingGroup?.fileSizeBytes ?: 0L,
            sourceLabel = displayName.ifBlank { existingGroup?.sourceLabel.orEmpty() },
            updatedAt = now,
          )
        bookmarkDao.upsertGroup(updatedGroup)

        val mediaIds =
          buildSet {
            add(mediaId)
            if (uri.scheme.equals("file", ignoreCase = true)) {
              uri.path?.let { add(PlaybackIdentity.normalizeLocalPath(it)) }
            }
            add(uriString)
          }
        mediaIds.forEach { candidateId ->
          bookmarkDao.upsertMediaLink(
            BookmarkMediaLinkEntity(mediaId = candidateId, groupKey = resolvedKey, updatedAt = now),
          )
          bookmarkDao.upsertSyncMediaRef(
            BookmarkMediaRefEntity(
              mediaId = candidateId,
              playableUri = uriString,
              title = displayName.ifBlank { null },
              updatedAt = now,
            ),
          )
        }
        bookmarkDao.upsertSyncMediaRef(
          BookmarkMediaRefEntity(
            mediaId = resolvedKey,
            playableUri = uriString,
            title = displayName.ifBlank { null },
            updatedAt = now,
          ),
        )

        val count = bookmarkDao.countInGroup(resolvedKey)
        LinkedOrphanResult(
          groupKey = resolvedKey,
          displayName = displayName.ifBlank { resolvedKey },
          bookmarkCount = count,
        )
      }
    }

  private suspend fun applyBookmarkTags(
    bookmarkId: Long,
    tags: List<String>,
  ) {
    tagDao.clearForBookmark(bookmarkId)
    val now = System.currentTimeMillis()
    val clean = tags.map { BookmarkTagRules.normalize(it) }.filter { it.isNotBlank() }.distinct()
    if (clean.isEmpty()) return
    tagDao.insertAll(
      clean.map { name ->
        BookmarkTagEntity(
          bookmarkId = bookmarkId,
          name = name,
          displayName = name,
          createdAt = now,
        )
      },
    )
    clean.forEach { name ->
      tagDao.insertMemory(BookmarkTagMemoryEntity(name = name, displayName = name, lastUsedAt = now))
      tagDao.bumpMemory(name = name, displayName = name, now = now)
    }
  }

  private fun downloadAndEncryptThumbnail(
    baseUrl: String,
    token: String,
    syncUuid: String,
    thumbDir: File,
  ): String? {
    val req = buildRequestBuilder("$baseUrl/api/sync/thumbnail/$syncUuid", token).get().build()
    val bytes =
      runCatching {
        syncClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) return@use null
          resp.body.bytes()
        }
      }.getOrNull() ?: return null
    if (bytes.isEmpty()) return null
    val encrypted = runCatching { VaultCrypto.encryptBytes(bytes) }.getOrNull() ?: bytes
    val ext = if (VaultCrypto.isEncryptedBytes(encrypted)) "dat" else "jpg"
    val dest = File(thumbDir, "bm_sync_${UUID.randomUUID()}.$ext")
    dest.writeBytes(encrypted)
    return dest.absolutePath
  }

  private fun uploadDecryptedThumbnail(
    baseUrl: String,
    token: String,
    syncUuid: String,
    localPath: String,
  ): Boolean {
    val file = File(localPath)
    if (!file.isFile || !file.canRead()) return false
    val plain = runCatching { VaultCrypto.decryptBytes(file.readBytes()) }.getOrNull() ?: return false
    if (plain.isEmpty()) return false
    val b64 = "data:image/jpeg;base64," + Base64.encodeToString(plain, Base64.NO_WRAP)
    val payload = json.encodeToString(SyncThumbnailUploadRequest(b64))
    val req =
      buildRequestBuilder("$baseUrl/api/sync/thumbnail/$syncUuid", token)
        .post(payload.toRequestBody(JSON_MEDIA_TYPE))
        .build()
    return runCatching {
      syncClient.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)
  }

  private fun resolveUriDisplayName(uri: Uri): String {
    if (uri.scheme.equals("file", ignoreCase = true)) {
      return File(uri.path ?: uri.toString()).name
    }
    runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
          val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) {
            val v = c.getString(idx)
            if (!v.isNullOrBlank()) return v
          }
        }
      }
    }
    return DocumentFile.fromSingleUri(context, uri)?.name
      ?: BookmarkMediaMatcher.extractFileName(uri.toString())
  }

  private fun resolveUriFileSize(uri: Uri): Long {
    if (uri.scheme.equals("file", ignoreCase = true)) {
      return uri.path?.let { File(it).length() } ?: 0L
    }
    runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
          val idx = c.getColumnIndex(OpenableColumns.SIZE)
          if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
        }
      }
    }
    return DocumentFile.fromSingleUri(context, uri)?.length() ?: 0L
  }

  private fun buildRequestBuilder(
    url: String,
    token: String,
  ): Request.Builder {
    val builder = Request.Builder().url(url)
    val trimmedToken = token.trim()
    if (trimmedToken.isNotEmpty()) {
      builder.header("X-Sync-Token", trimmedToken)
    }
    return builder
  }

  companion object {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun parseIsoMillis(raw: String?): Long {
      val trimmed = raw?.trim().orEmpty()
      if (trimmed.isEmpty()) return 0L
      return runCatching { Instant.parse(trimmed).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
        .getOrDefault(0L)
    }

    fun formatIsoUtc(epochMs: Long): String {
      val safeMs = epochMs.coerceAtLeast(0L)
      return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(safeMs).atOffset(ZoneOffset.UTC))
    }

    internal fun isLoopbackUrl(baseUrl: String): Boolean {
      val host = Uri.parse(baseUrl).host?.lowercase().orEmpty()
      return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    internal fun friendlyNetworkError(
      baseUrl: String,
      err: Throwable,
    ): IllegalStateException {
      val detail =
        when (err) {
          is SocketTimeoutException ->
            "Timed out connecting to $baseUrl (PC hub may be busy — retry shortly)"
          is ConnectException,
          is UnknownHostException,
          ->
            if (isLoopbackUrl(baseUrl)) {
              "127.0.0.1 / localhost points to this phone, not your PC. Use AV Manager LAN IP"
            } else {
              "Could not reach $baseUrl — ensure AV Manager is running on PC & on same LAN"
            }
          else -> {
            val raw = err.message?.trim().orEmpty()
            when {
              raw.contains("401") || raw.contains("403") ->
                "Invalid Sync Token — copy the token from AV Manager Settings"
              raw.isNotEmpty() -> raw
              else -> err::class.java.simpleName
            }
          }
        }
      return IllegalStateException(detail, err)
    }
  }
}
