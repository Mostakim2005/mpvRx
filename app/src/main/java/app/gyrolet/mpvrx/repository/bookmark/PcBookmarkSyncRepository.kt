package app.gyrolet.mpvrx.repository.bookmark

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.database.MpvRxDatabase
import app.gyrolet.mpvrx.database.SecureVaultDatabase
import app.gyrolet.mpvrx.database.entities.BookmarkGroupEntity
import app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkGroupEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkMediaLinkEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkMediaRefEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkSyncTombstoneEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkTagEntity
import app.gyrolet.mpvrx.database.entities.SecureBookmarkTagMemoryEntity
import app.gyrolet.mpvrx.domain.bookmark.BookmarkGroupConsolidator
import app.gyrolet.mpvrx.domain.bookmark.BookmarkMediaMatcher
import app.gyrolet.mpvrx.domain.bookmark.BookmarkTagRules
import app.gyrolet.mpvrx.domain.bookmark.BookmarkThumbnailStore
import app.gyrolet.mpvrx.domain.bookmark.OshashCalculator
import app.gyrolet.mpvrx.domain.bookmark.SecureBookmarkStore
import app.gyrolet.mpvrx.domain.secure.SecureFolderSession
import app.gyrolet.mpvrx.domain.secure.SecureMediaRegistry
import app.gyrolet.mpvrx.domain.secure.VaultCrypto
import app.gyrolet.mpvrx.domain.storage.DurableStorageManager
import app.gyrolet.mpvrx.preferences.BookmarkSyncPreferences
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URLEncoder
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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
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
  @SerialName("source_url") val sourceUrl: String = "",
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

@Serializable
data class SyncPingResponse(
  val app: String = "",
  val version: String = "",
  @SerialName("lan_ip") val lanIp: String = "",
  val port: Int = 8000,
)

@Serializable
data class ThumbnailCandidateDto(
  val source: String = "",
  val label: String = "",
  @SerialName("data_uri") val dataUri: String = "",
  @SerialName("is_active") val isActive: Boolean = false,
)

@Serializable
data class ThumbnailCandidatesResponse(
  @SerialName("bookmark_id") val bookmarkId: Long = 0,
  @SerialName("sync_uuid") val syncUuid: String = "",
  val code: String = "",
  @SerialName("group_key") val groupKey: String = "",
  val title: String = "",
  @SerialName("active_thumbnail") val activeThumbnail: String = "",
  val candidates: List<ThumbnailCandidateDto> = emptyList(),
)

@Serializable
data class ThumbnailSelectRequest(
  val source: String,
  @SerialName("thumbnail_base64") val thumbnailBase64: String = "",
)

@Serializable
data class HubDensityMergeRequest(
  @SerialName("window_sec") val windowSec: Double = 10.0,
  @SerialName("default_strategy") val defaultStrategy: String = "earliest",
  @SerialName("keep_alternates") val keepAlternates: Boolean = true,
  @SerialName("sync_stash") val syncStash: Boolean = true,
)

@Serializable
data class HubDensityMergeResponse(
  val success: Boolean = false,
  @SerialName("merged_clusters") val mergedClusters: Int = 0,
  @SerialName("tombstoned_count") val tombstonedCount: Int = 0,
)

data class PcSyncResult(
  val inserted: Int,
  val updated: Int,
  val pushed: Int,
  val thumbnailsDownloaded: Int,
  val thumbnailsUploaded: Int,
  val stashReachable: Boolean,
  val autoMatchedGroups: Int = 0,
)

data class LinkedOrphanResult(
  val groupKey: String,
  val displayName: String,
  val bookmarkCount: Int,
)

data class SyncProgressState(
  val active: Boolean = false,
  val stageLabel: String = "",
  val detail: String = "",
  val current: Int = 0,
  val total: Int = 0,
  val fraction: Float = 0f,
)

data class JavidCatalogEntry(
  val groupKey: String,
  val javidCode: String,
  val sourceLabel: String,
  val sourceUrl: String?,
  val bookmarkCount: Int,
  val playableUri: String?,
  val mediaTitle: String?,
  val tags: List<String>,
  val updatedAt: Long,
)

/**
 * Coordinates two-way LAN bookmark sync with AV Manager (`vault.db`) <-> Stash (`localhost:9999`)
 * and orphan BookmarkGroup linking strictly inside the encrypted Primary [SecureVaultDatabase].
 *
 * Security boundary (`Alpha 1 / Alpha 3 / Bravo 1`):
 * - Every remote sync, catalog, and vault-matching API requires `SecureFolderSession.isPrimaryUnlocked`.
 * - Locked sessions and Decoy sessions fail closed and emit empty state.
 * - Synced thumbnails live solely in `.vault/thumbs/`.
 * - One-time quarantine migration moves legacy AV Manager rows out of public [MpvRxDatabase] when
 *   the Primary Vault unlocks, never leaking back to public tables.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PcBookmarkSyncRepository(
  private val context: Context,
  private val secureDatabase: SecureVaultDatabase,
  private val publicDatabase: MpvRxDatabase,
  private val secureBookmarkStore: SecureBookmarkStore,
  private val prefs: BookmarkSyncPreferences,
  private val httpClient: OkHttpClient,
  private val json: Json,
  @Suppress("UNUSED_PARAMETER") private val mediaFileRepository: MediaFileRepository? = null,
) {
  private val bookmarkDao get() = secureDatabase.secureBookmarkDao()
  private val tagDao get() = secureDatabase.secureBookmarkTagDao()
  private val tombstoneDao get() = secureDatabase.secureBookmarkSyncTombstoneDao()
  private val vaultMediaDao get() = secureDatabase.secureVaultMediaDao()
  private val vaultMetaDao get() = secureDatabase.secureVideoMetadataDao()

  private val publicBookmarkDao get() = publicDatabase.playbackBookmarkDao()
  private val publicTagDao get() = publicDatabase.bookmarkTagDao()
  private val publicTombstoneDao get() = publicDatabase.bookmarkSyncTombstoneDao()

  private val quarantineMutex = Mutex()
  private val _syncProgress = MutableStateFlow(SyncProgressState())
  val syncProgress: StateFlow<SyncProgressState> = _syncProgress.asStateFlow()

  init {
    SecureFolderSession.registerOnLockListener {
      _syncProgress.value = SyncProgressState()
    }
  }

  private fun requirePrimaryUnlocked() {
    check(SecureFolderSession.isPrimaryUnlocked) {
      "Unlock Primary Secret Folder to use AV Manager Sync"
    }
  }

  private fun enc(value: String): String {
    VaultCrypto.ensureInitialized(context)
    return if (value.isEmpty()) "" else VaultCrypto.encryptString(value)
  }

  private fun dec(value: String): String {
    VaultCrypto.ensureInitialized(context)
    return if (value.isEmpty()) "" else runCatching { VaultCrypto.decryptString(value) }.getOrDefault(value)
  }

  private fun encNullable(value: String?): String? =
    value?.takeIf { it.isNotEmpty() }?.let(::enc)

  private fun decNullable(value: String?): String? =
    value?.takeIf { it.isNotEmpty() }?.let(::dec)

  private fun SecureBookmarkGroupEntity.toDomain(): BookmarkGroupEntity =
    BookmarkGroupEntity(
      groupKey = groupKey,
      presetCode = decNullable(presetCode),
      oshash = oshash,
      fingerprint = fingerprint,
      stemKey = decNullable(stemKey),
      durationSec = durationSec,
      width = width,
      height = height,
      fileSizeBytes = fileSizeBytes,
      sourceLabel = dec(sourceLabel),
      sourceUrl = decNullable(sourceUrl),
      updatedAt = updatedAt,
    )

  private fun BookmarkGroupEntity.toSecure(
    originApp: String? = null,
    lastSyncedAt: Long = 0L,
  ): SecureBookmarkGroupEntity =
    SecureBookmarkGroupEntity(
      groupKey = groupKey,
      presetCode = encNullable(presetCode),
      oshash = oshash,
      fingerprint = fingerprint,
      stemKey = encNullable(stemKey),
      durationSec = durationSec,
      width = width,
      height = height,
      fileSizeBytes = fileSizeBytes,
      sourceLabel = enc(sourceLabel),
      sourceUrl = encNullable(sourceUrl),
      originApp = encNullable(originApp),
      lastSyncedAt = lastSyncedAt,
      updatedAt = updatedAt,
    )

  private fun SecureBookmarkEntity.toDomain(originApp: String = "av_manager"): PlaybackBookmarkEntity =
    PlaybackBookmarkEntity(
      id = id,
      mediaId = mediaId,
      positionMs = positionMs,
      title = dec(title),
      createdAt = createdAt,
      bookTrackId = bookTrackId,
      thumbnailPath = decNullable(thumbnailPath),
      endPositionMs = endPositionMs,
      primaryTag = decNullable(primaryTag),
      syncUuid = syncUuid,
      originApp = originApp,
      updatedAt = updatedAt,
    )

  private fun emitProgress(
    active: Boolean,
    stageLabel: String = "",
    detail: String = "",
    current: Int = 0,
    total: Int = 0,
    fraction: Float = 0f,
  ) {
    _syncProgress.value =
      SyncProgressState(
        active = active,
        stageLabel = stageLabel,
        detail = detail,
        current = current,
        total = total,
        fraction = fraction.coerceIn(0f, 1f),
      )
  }

  /**
   * Quarantine migration (`Bravo 1`): moves any legacy AV Manager synced rows (`syncUuid != null`,
   * `originApp == av_manager`, `sourceUrl != null`, or `javid:` groups) and their `.store/thumbs/`
   * thumbnails out of public [MpvRxDatabase] into encrypted Primary [SecureVaultDatabase].
   * Runs only when Primary Secret Folder is unlocked.
   */
  suspend fun migrateLegacyPublicAvDataToPrimaryVault(): Int =
    quarantineMutex.withLock {
      withContext(Dispatchers.IO) {
        if (!SecureFolderSession.isPrimaryUnlocked) return@withContext 0
        VaultCrypto.ensureInitialized(context)

        val pubGroups = publicBookmarkDao.getAllGroups()
        val pubBookmarks = publicBookmarkDao.getPublicBookmarks()
        val pubLinks = publicBookmarkDao.getAllLinks()
        val pubRefs = publicTagDao.getAllMediaRefs()
        val pubTombs = publicTombstoneDao.getAll()

        val avGroupKeys = HashSet<String>()
        pubGroups.forEach { g ->
          if (!g.sourceUrl.isNullOrBlank() || g.groupKey.startsWith("javid:", ignoreCase = true)) {
            avGroupKeys += g.groupKey
          }
        }
        pubBookmarks.forEach { b ->
          if (
            !b.syncUuid.isNullOrBlank() ||
            b.originApp.equals("av_manager", ignoreCase = true) ||
            b.mediaId.startsWith("javid:", ignoreCase = true)
          ) {
            avGroupKeys += b.mediaId
          }
        }
        if (avGroupKeys.isEmpty() && pubTombs.isEmpty()) return@withContext 0

        val now = System.currentTimeMillis()
        val secGroupsByKey = bookmarkDao.getAllGroups().map { it.toDomain() }.associateBy { it.groupKey }
        val primaryThumbDir = BookmarkThumbnailStore.privateDirectory(context, isSecure = true, forceDecoy = false)

        avGroupKeys.forEach { gk ->
          val pg = pubGroups.firstOrNull { it.groupKey == gk }
          if (pg != null) {
            val existing = secGroupsByKey[gk]
            val merged =
              BookmarkGroupEntity(
                groupKey = gk,
                presetCode = existing?.presetCode?.takeIf { it.isNotBlank() } ?: pg.presetCode,
                oshash = existing?.oshash?.takeIf { it.isNotBlank() } ?: pg.oshash,
                fingerprint = existing?.fingerprint?.takeIf { it.isNotBlank() } ?: pg.fingerprint,
                stemKey = existing?.stemKey?.takeIf { it.isNotBlank() } ?: pg.stemKey,
                durationSec = existing?.durationSec?.takeIf { it > 0 } ?: pg.durationSec,
                width = existing?.width?.takeIf { it > 0 } ?: pg.width,
                height = existing?.height?.takeIf { it > 0 } ?: pg.height,
                fileSizeBytes = existing?.fileSizeBytes?.takeIf { it > 0L } ?: pg.fileSizeBytes,
                sourceLabel = existing?.sourceLabel?.takeIf { it.isNotBlank() } ?: pg.sourceLabel,
                sourceUrl = existing?.sourceUrl?.takeIf { it.isNotBlank() } ?: pg.sourceUrl,
                updatedAt = maxOf(existing?.updatedAt ?: 0L, pg.updatedAt),
              )
            bookmarkDao.upsertGroup(merged.toSecure(originApp = "av_manager", lastSyncedAt = now))
          }
        }

        val currentVaultBms = bookmarkDao.getAll().map { it.toDomain() }.toMutableList()
        val movingBms = pubBookmarks.filter { it.mediaId in avGroupKeys }
        var movedCount = 0
        movingBms.forEach { oldBm ->
          val duplicate =
            oldBm.syncUuid?.let { uuid -> currentVaultBms.firstOrNull { it.syncUuid == uuid } }
              ?: currentVaultBms.firstOrNull {
                it.mediaId == oldBm.mediaId && abs(it.positionMs - oldBm.positionMs) <= 1000L && it.title == oldBm.title
              }
          if (duplicate == null) {
            val migratedThumb =
              if (!oldBm.thumbnailPath.isNullOrBlank()) {
                BookmarkThumbnailStore.migrateThumbnailFile(
                  context = context,
                  existingPath = oldBm.thumbnailPath,
                  toSecure = true,
                ) ?: oldBm.thumbnailPath?.takeIf { DurableStorageManager.isPrimaryVaultPath(it) }
              } else {
                null
              }
            val insertedEntity =
              SecureBookmarkEntity(
                mediaId = oldBm.mediaId,
                positionMs = oldBm.positionMs,
                title = enc(oldBm.title),
                createdAt = oldBm.createdAt,
                bookTrackId = oldBm.bookTrackId,
                thumbnailPath = encNullable(migratedThumb),
                endPositionMs = oldBm.endPositionMs,
                primaryTag = encNullable(oldBm.primaryTag),
                syncUuid = oldBm.syncUuid,
                updatedAt = oldBm.updatedAt.takeIf { it > 0L } ?: oldBm.createdAt,
              )
            val newId = bookmarkDao.add(insertedEntity)
            val oldTags = publicTagDao.tagsFor(oldBm.id).map { it.displayName.ifBlank { it.name } }
            if (oldTags.isNotEmpty()) {
              applyBookmarkTags(newId, oldTags)
            }
            currentVaultBms.add(insertedEntity.copy(id = newId).toDomain())
            movedCount++
          }
        }

        pubTombs.forEach { t ->
          tombstoneDao.upsert(
            SecureBookmarkSyncTombstoneEntity(syncUuid = t.syncUuid, groupKey = t.groupKey, deletedAt = t.deletedAt),
          )
          publicTombstoneDao.deleteByUuid(t.syncUuid)
        }

        // Clean public AV Manager rows inside MpvRxDatabase and old .store/thumbs files
        val idsToDelete = movingBms.map { it.id }
        if (idsToDelete.isNotEmpty()) {
          idsToDelete.chunked(900).forEach { publicBookmarkDao.deleteByIds(it) }
          idsToDelete.forEach { publicTagDao.clearForBookmark(it) }
        }
        pubLinks.forEach { link ->
          if (link.groupKey in avGroupKeys || link.mediaId in avGroupKeys) {
            publicBookmarkDao.deleteMediaLink(link.mediaId)
          }
        }
        pubRefs.forEach { ref ->
          if (ref.mediaId in avGroupKeys || DurableStorageManager.isVaultPath(ref.playableUri)) {
            publicTagDao.deleteMediaRef(ref.mediaId)
          }
        }
        avGroupKeys.forEach { gk ->
          if (publicBookmarkDao.countInGroup(gk) == 0 && publicBookmarkDao.countLinksForGroup(gk) == 0) {
            publicBookmarkDao.deleteGroup(gk)
          }
          publicTagDao.deleteMediaRef(gk)
        }
        movingBms.forEach { oldBm ->
          val oldThumb = oldBm.thumbnailPath
          if (!oldThumb.isNullOrBlank() && publicBookmarkDao.countByThumbnailPath(oldThumb) == 0) {
            BookmarkThumbnailStore.deleteThumbnail(context, oldThumb, isSecure = false)
          }
        }
        // Keep primaryThumbDir touched only when needed
        if (primaryThumbDir.isDirectory && movedCount > 0) {
          BookmarkThumbnailStore.evictSecureMemoryCache()
        }
        movedCount
      }
    }

  /** Observes all independent JAVID / bookmark groups stored in Primary Vault (empty when locked or Decoy). */
  fun observeJavidCatalog(): Flow<List<JavidCatalogEntry>> =
    SecureFolderSession.modeState.flatMapLatest { mode ->
      if (mode != SecureFolderSession.VaultMode.PRIMARY) {
        flowOf(emptyList())
      } else {
        combine(
          bookmarkDao.observeAllGroups(),
          bookmarkDao.observeGallery(),
          tagDao.observeMediaRefs(),
          tagDao.observeAllTags(),
        ) { rawGroups, galleryRows, rawRefs, rawTags ->
          val refsByMedia =
            rawRefs.associate { r ->
              r.mediaId to
                Pair(
                  dec(r.playableUri).takeIf { u -> !DurableStorageManager.isVaultPath(u) || File(u).isFile },
                  decNullable(r.title),
                )
            }
          val rowsByGroup = galleryRows.groupBy { it.mediaId }
          val tagsByBookmark = rawTags.groupBy({ it.bookmarkId }, { dec(it.displayName).ifBlank { dec(it.name) } })

          rawGroups
            .mapNotNull { rawGrp ->
              val grp = rawGrp.toDomain()
              val gRows = rowsByGroup[grp.groupKey].orEmpty()
              if (gRows.isEmpty()) return@mapNotNull null
              val refPair = refsByMedia[grp.groupKey]
              val extractedJavid =
                grp.presetCode?.takeIf { it.isNotBlank() }
                  ?: BookmarkMediaMatcher.extractJvidCode(grp.sourceLabel)
                  ?: BookmarkMediaMatcher.extractJvidCode(grp.groupKey)
                  ?: grp.groupKey
              val tagList =
                gRows
                  .flatMap { r -> tagsByBookmark[r.id].orEmpty() }
                  .distinct()
                  .sorted()
                  .take(8)
              JavidCatalogEntry(
                groupKey = grp.groupKey,
                javidCode = extractedJavid,
                sourceLabel = grp.sourceLabel.ifBlank { refPair?.second.orEmpty() },
                sourceUrl = grp.sourceUrl?.trim()?.takeIf { it.isNotEmpty() },
                bookmarkCount = gRows.size,
                playableUri = refPair?.first,
                mediaTitle = refPair?.second,
                tags = tagList,
                updatedAt = grp.updatedAt,
              )
            }.sortedWith(
              compareByDescending<JavidCatalogEntry> { it.updatedAt }
                .thenBy { it.javidCode },
            )
        }.distinctUntilChanged()
      }
    }

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
    usbMode: Boolean = prefs.usbModeEnabled.get(),
  ): Result<String> =
    withContext(Dispatchers.IO) {
      val baseUrl = BookmarkSyncPreferences.normalizeHubUrl(hubUrl)
      runCatching {
        requirePrimaryUnlocked()
        require(baseUrl.isNotEmpty()) { "Enter AV Manager URL or tap Find PC (Wi-Fi)" }
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
        throw friendlyNetworkError(baseUrl, err, usbMode = usbMode)
      }
    }

  suspend fun discoverPcHubOnLan(includeUsbLoopback: Boolean = true): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        requirePrimaryUnlocked()
        val probeClient =
          httpClient
            .newBuilder()
            .connectTimeout(900, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .callTimeout(2200, TimeUnit.MILLISECONDS)
            .build()

        val candidates = LinkedHashSet<String>()
        if (includeUsbLoopback) {
          candidates.add("http://127.0.0.1:8000")
        }
        BookmarkSyncPreferences.normalizeHubUrl(prefs.hubUrl.get()).takeIf { it.isNotEmpty() }?.let {
          candidates.add(it)
        }

        val prefixes = LinkedHashSet<String>()
        runCatching {
          val ifaces = NetworkInterface.getNetworkInterfaces()
          while (ifaces != null && ifaces.hasMoreElements()) {
            val nif = ifaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
              val addr = addrs.nextElement()
              if (addr is Inet4Address && !addr.isLoopbackAddress) {
                val host = addr.hostAddress.orEmpty()
                val parts = host.split('.')
                if (parts.size == 4 && parts[0] != "127") {
                  prefixes.add("${parts[0]}.${parts[1]}.${parts[2]}")
                }
              }
            }
          }
        }
        val priorityOctets = listOf(100, 101, 102, 2, 10, 50, 150, 200, 1)
        for (prefix in prefixes) {
          priorityOctets.forEach { oct -> candidates.add("http://$prefix.$oct:8000") }
          for (oct in 2..254) {
            if (oct !in priorityOctets) {
              candidates.add("http://$prefix.$oct:8000")
            }
          }
        }

        val gate = Semaphore(24)
        var discoveredUrl: String? = null
        coroutineScope {
          val jobs =
            candidates.map { base ->
              async {
                if (discoveredUrl != null) return@async null
                gate.withPermit {
                  if (discoveredUrl != null) return@withPermit null
                  runCatching {
                    val req = Request.Builder().url("$base/api/sync/ping").get().build()
                    probeClient.newCall(req).execute().use { resp ->
                      if (!resp.isSuccessful) return@use null
                      val body = resp.body.string()
                      val ping = json.decodeFromString<SyncPingResponse>(body)
                      if (ping.app == "av-manager") base else null
                    }
                  }.getOrNull()?.also { found ->
                    discoveredUrl = found
                  }
                }
              }
            }
          jobs.awaitAll()
        }
        discoveredUrl ?: error("No AV Manager server found on Wi-Fi or USB")
      }
    }

  suspend fun syncWithPcHub(
    hubUrl: String = prefs.hubUrl.get(),
    token: String = prefs.syncToken.get(),
    usbMode: Boolean = prefs.usbModeEnabled.get(),
  ): Result<PcSyncResult> =
    withContext(Dispatchers.IO) {
      val baseUrl = BookmarkSyncPreferences.normalizeHubUrl(hubUrl)
      try {
        runCatching {
          requirePrimaryUnlocked()
          require(baseUrl.isNotEmpty()) { "Set AV Manager URL in Vault Settings" }
          VaultCrypto.ensureInitialized(context)
          emitProgress(true, "Consolidating JAVID groups…", "", 0, 1, 0.05f)
          consolidateDuplicateJvidGroupsInDb()

          val localGroups = bookmarkDao.getAllGroups().map { it.toDomain() }
          val rawBookmarks = bookmarkDao.getAll().map { it.toDomain() }
          val allTags =
            tagDao.getAllTagsOnce().groupBy(
              { it.bookmarkId },
              { dec(it.displayName).ifBlank { dec(it.name) } },
            )

          val localBookmarks =
            rawBookmarks.map { bm ->
              val fallbackPrimary = bm.primaryTag ?: allTags[bm.id]?.firstOrNull()
              if (!bm.syncUuid.isNullOrBlank()) {
                bm
              } else {
                val generatedUuid = UUID.randomUUID().toString()
                bookmarkDao.updateBookmarkSyncMetadata(
                  id = bm.id,
                  endPositionMs = bm.endPositionMs,
                  primaryTag = encNullable(fallbackPrimary),
                  syncUuid = generatedUuid,
                  updatedAt = bm.updatedAt.takeIf { it > 0L } ?: bm.createdAt,
                )
                bm.copy(syncUuid = generatedUuid, primaryTag = fallbackPrimary)
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
                sourceUrl = g.sourceUrl.orEmpty(),
                updatedAt = formatIsoUtc(g.updatedAt),
              )
            }

          val bookmarkDtos =
            localBookmarks.map { bm ->
              val tagsList = allTags[bm.id].orEmpty().filter { it.isNotBlank() }.distinct()
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

          emitProgress(true, "Syncing with AV Manager & Stash…", baseUrl, 0, 1, 0.20f)
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
          val pushedCount = mergeResp.stats.bookmarksInserted + mergeResp.stats.bookmarksUpdated
          val res =
            reconcileRemoteBundle(
              baseUrl = baseUrl,
              token = token,
              bundle = mergeResp.bundle,
              pushedCount = pushedCount,
            )
          val nowIso = formatIsoUtc(System.currentTimeMillis())
          prefs.lastSyncIso.set(nowIso)
          prefs.lastSyncSummary.set("+${res.inserted} / ~${res.updated} • ↑$pushedCount")
          res
        }.recoverCatching { err ->
          throw friendlyNetworkError(baseUrl, err, usbMode = usbMode)
        }
      } finally {
        emitProgress(false)
      }
    }

  /** Pulls and merges one specific independent JAVID set from AV Manager without syncing unrelated groups. */
  suspend fun syncSingleJavidFromPcHub(
    groupKeyOrCode: String,
    hubUrl: String = prefs.hubUrl.get(),
    token: String = prefs.syncToken.get(),
    usbMode: Boolean = prefs.usbModeEnabled.get(),
  ): Result<PcSyncResult> =
    withContext(Dispatchers.IO) {
      val baseUrl = BookmarkSyncPreferences.normalizeHubUrl(hubUrl)
      val cleanTarget = groupKeyOrCode.trim()
      try {
        runCatching {
          requirePrimaryUnlocked()
          require(baseUrl.isNotEmpty()) { "Set AV Manager URL in Vault Settings" }
          require(cleanTarget.isNotEmpty()) { "JAVID code or group key required" }
          VaultCrypto.ensureInitialized(context)
          emitProgress(true, "Fetching JAVID set $cleanTarget…", baseUrl, 0, 1, 0.18f)

          val normCode = BookmarkMediaMatcher.extractJvidCode(cleanTarget).orEmpty()
          val encCode = URLEncoder.encode(normCode, "UTF-8")
          val encGroup = URLEncoder.encode(cleanTarget, "UTF-8")
          val bundleReq =
            buildRequestBuilder(
              "$baseUrl/api/sync/bundle?code=$encCode&group_key=$encGroup",
              token,
            ).get().build()
          val body =
            syncClient.newCall(bundleReq).execute().use { resp ->
              if (resp.code == 401 || resp.code == 403) {
                error("Invalid Sync Token (HTTP ${resp.code})")
              }
              if (!resp.isSuccessful) error("Sync HTTP ${resp.code}")
              resp.body.string()
            }
          val bundle = json.decodeFromString<SyncBundleResponse>(body)
          reconcileRemoteBundle(baseUrl = baseUrl, token = token, bundle = bundle, pushedCount = 0)
        }.recoverCatching { err ->
          throw friendlyNetworkError(baseUrl, err, usbMode = usbMode)
        }
      } finally {
        emitProgress(false)
      }
    }

  private suspend fun reconcileRemoteBundle(
    baseUrl: String,
    token: String,
    bundle: SyncBundleResponse,
    pushedCount: Int,
  ): PcSyncResult {
    requirePrimaryUnlocked()
    VaultCrypto.ensureInitialized(context)
    var insertedCount = 0
    var updatedCount = 0
    var downloadedThumbs = 0
    var uploadedThumbs = 0

    // 1. Apply remote tombstones locally
    emitProgress(true, "Applying deletions…", "${bundle.tombstones.size} tombstone(s)", 0, 1, 0.30f)
    val tombstoneSet = HashSet<String>()
    bundle.tombstones.forEach { tomb ->
      val uuid = tomb.syncUuid.trim()
      if (uuid.isEmpty()) return@forEach
      tombstoneSet.add(uuid)
      val existing = bookmarkDao.findBookmarkBySyncUuid(uuid)?.toDomain()
      if (existing != null) {
        val tombMs = parseIsoMillis(tomb.deletedAt)
        if (tombMs <= 0L || tombMs >= existing.updatedAt) {
          bookmarkDao.delete(existing.id)
          tagDao.clearForBookmark(existing.id)
          val thumbPath = existing.thumbnailPath
          if (!thumbPath.isNullOrBlank() && secureBookmarkStore.countByThumbnailPath(thumbPath) == 0) {
            BookmarkThumbnailStore.deleteThumbnail(context, thumbPath, isSecure = true)
          }
        }
      }
    }

    // 2. Reconcile groups (match by groupKey / oshash / JAVID / fingerprint, using LWW for sourceUrl & label)
    emitProgress(true, "Reconciling JAVID groups…", "${bundle.groups.size} group(s)", 0, 1, 0.38f)
    val syncStampMs = System.currentTimeMillis()
    val groupKeyMap = HashMap<String, String>()
    val refreshedGroups = bookmarkDao.getAllGroups().map { it.toDomain() }
    bundle.groups.forEach { rg ->
      val rgJvid =
        BookmarkMediaMatcher.extractJvidCode(rg.code)
          ?: BookmarkMediaMatcher.extractJvidCode(rg.sourceLabel)
          ?: BookmarkMediaMatcher.extractJvidCode(rg.groupKey)
      val matched =
        refreshedGroups.firstOrNull { it.groupKey == rg.groupKey }
          ?: rg.oshash.takeIf { it.isNotBlank() }?.let { oh ->
            refreshedGroups.firstOrNull { !it.oshash.isNullOrBlank() && it.oshash.equals(oh, ignoreCase = true) }
          }
          ?: rgJvid?.let { code ->
            refreshedGroups.firstOrNull { cand ->
              val cCode =
                BookmarkMediaMatcher.extractJvidCode(cand.presetCode)
                  ?: BookmarkMediaMatcher.extractJvidCode(cand.sourceLabel)
                  ?: BookmarkMediaMatcher.extractJvidCode(cand.groupKey)
              cCode != null && cCode.equals(code, ignoreCase = true)
            }
          }
          ?: rg.fingerprint.takeIf { it.isNotBlank() }?.let { fp ->
            refreshedGroups.firstOrNull { it.fingerprint == fp }
          }

      val targetGroupKey = matched?.groupKey ?: rg.groupKey
      groupKeyMap[rg.groupKey] = targetGroupKey

      val remoteGrpUpd = parseIsoMillis(rg.updatedAt)
      val localGrpUpd = matched?.updatedAt ?: 0L
      val remoteSourceUrl = rg.sourceUrl.trim().takeIf { it.isNotEmpty() }
      val localSourceUrl = matched?.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
      val effectiveSourceUrl =
        when {
          matched == null || remoteGrpUpd > localGrpUpd -> remoteSourceUrl ?: localSourceUrl
          else -> localSourceUrl ?: remoteSourceUrl
        }
      val effectiveSourceLabel =
        if (matched != null && remoteGrpUpd > localGrpUpd && rg.sourceLabel.isNotBlank()) {
          rg.sourceLabel
        } else {
          matched?.sourceLabel?.takeIf { it.isNotBlank() } ?: rg.sourceLabel
        }

      val mergedGroup =
        BookmarkGroupEntity(
          groupKey = targetGroupKey,
          presetCode =
            matched?.presetCode?.takeIf { it.isNotBlank() }
              ?: rgJvid
              ?: rg.code.takeIf { it.isNotBlank() },
          oshash = matched?.oshash?.takeIf { it.isNotBlank() } ?: rg.oshash.takeIf { it.isNotBlank() },
          fingerprint =
            matched?.fingerprint?.takeIf { it.isNotBlank() } ?: rg.fingerprint.takeIf { it.isNotBlank() },
          stemKey = matched?.stemKey?.takeIf { it.isNotBlank() } ?: rg.stemKey.takeIf { it.isNotBlank() },
          durationSec = matched?.durationSec?.takeIf { it > 0 } ?: rg.durationSec,
          width = matched?.width?.takeIf { it > 0 } ?: rg.width,
          height = matched?.height?.takeIf { it > 0 } ?: rg.height,
          fileSizeBytes = matched?.fileSizeBytes?.takeIf { it > 0L } ?: rg.fileSizeBytes,
          sourceLabel = effectiveSourceLabel,
          sourceUrl = effectiveSourceUrl,
          updatedAt = maxOf(localGrpUpd, remoteGrpUpd),
        )
      bookmarkDao.upsertGroup(mergedGroup.toSecure(originApp = "av_manager", lastSyncedAt = syncStampMs))
    }

    // 3. Reconcile bookmarks + tags, queueing thumbnail transfers strictly inside Primary `.vault/thumbs/`
    val totalRemoteBm = bundle.bookmarks.size
    emitProgress(true, "Merging bookmarks & tags…", "0 / $totalRemoteBm", 0, totalRemoteBm, 0.45f)
    val currentVault = bookmarkDao.getAll().map { it.toDomain() }.toMutableList()
    val thumbDir = BookmarkThumbnailStore.privateDirectory(context, isSecure = true, forceDecoy = false)
    val thumbJobs = ArrayList<Triple<Boolean, Long, Triple<String, String?, Long>>>()

    bundle.bookmarks.forEachIndexed { idx, rb ->
      if (totalRemoteBm > 0 && (idx == totalRemoteBm - 1 || idx % 10 == 0)) {
        val frac = 0.45f + (0.13f * (idx + 1).toFloat() / totalRemoteBm.toFloat())
        emitProgress(
          true,
          "Merging bookmarks & tags…",
          "${idx + 1} / $totalRemoteBm",
          idx + 1,
          totalRemoteBm,
          frac,
        )
      }
      val uuid = rb.syncUuid.trim()
      if (uuid.isEmpty() || uuid in tombstoneSet) return@forEachIndexed

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
        currentVault.firstOrNull { it.syncUuid == uuid }
          ?: currentVault.firstOrNull {
            it.mediaId == targetGroupKey && abs(it.positionMs - posMs) <= 1000L
          }

      val targetBookmarkId: Long
      val localThumbPath = existing?.thumbnailPath
      val localPreSyncUpd = existing?.updatedAt?.takeIf { it > 0L } ?: existing?.createdAt ?: 0L

      if (existing == null) {
        val cleanTitle = rb.title.trim().ifBlank { "Bookmark" }
        val domainBookmark =
          PlaybackBookmarkEntity(
            mediaId = targetGroupKey,
            positionMs = posMs,
            title = cleanTitle,
            createdAt = remoteCreatedMs,
            bookTrackId = null,
            thumbnailPath = null,
            endPositionMs = endMs,
            primaryTag = primaryTag,
            syncUuid = uuid,
            originApp = rb.originApp.ifBlank { "av_manager" },
            updatedAt = remoteUpdMs,
          )
        val encryptedEntity =
          SecureBookmarkEntity(
            mediaId = targetGroupKey,
            positionMs = posMs,
            title = enc(cleanTitle),
            createdAt = remoteCreatedMs,
            bookTrackId = null,
            thumbnailPath = null,
            endPositionMs = endMs,
            primaryTag = encNullable(primaryTag),
            syncUuid = uuid,
            updatedAt = remoteUpdMs,
          )
        targetBookmarkId = bookmarkDao.add(encryptedEntity)
        currentVault.add(domainBookmark.copy(id = targetBookmarkId))
        if (mergedTags.isNotEmpty()) {
          applyBookmarkTags(targetBookmarkId, mergedTags)
        }
        insertedCount++
      } else {
        targetBookmarkId = existing.id
        if (remoteUpdMs > localPreSyncUpd) {
          bookmarkDao.updatePositionAndTitle(
            id = existing.id,
            positionMs = posMs,
            title = enc(rb.title.trim().ifBlank { existing.title }),
            updatedAt = remoteUpdMs,
          )
          bookmarkDao.updateBookmarkSyncMetadata(
            id = existing.id,
            endPositionMs = endMs,
            primaryTag = encNullable(primaryTag),
            syncUuid = uuid,
            updatedAt = remoteUpdMs,
          )
          applyBookmarkTags(existing.id, mergedTags)
          updatedCount++
        } else if (existing.syncUuid.isNullOrBlank()) {
          bookmarkDao.updateBookmarkSyncMetadata(
            id = existing.id,
            endPositionMs = existing.endPositionMs ?: endMs,
            primaryTag = encNullable(existing.primaryTag ?: primaryTag),
            syncUuid = uuid,
            updatedAt = localPreSyncUpd,
          )
        }
      }

      val shouldUploadLocalThumb =
        !localThumbPath.isNullOrBlank() &&
          (!rb.hasThumbnail || (existing != null && localPreSyncUpd > remoteUpdMs))
      val shouldDownloadRemoteThumb =
        rb.hasThumbnail &&
          (localThumbPath.isNullOrBlank() || (existing != null && remoteUpdMs > localPreSyncUpd))

      if (shouldUploadLocalThumb && !localThumbPath.isNullOrBlank()) {
        thumbJobs.add(Triple(true, targetBookmarkId, Triple(uuid, localThumbPath, remoteUpdMs)))
      } else if (shouldDownloadRemoteThumb) {
        thumbJobs.add(Triple(false, targetBookmarkId, Triple(uuid, localThumbPath, remoteUpdMs)))
      }
    }

    val totalThumbs = thumbJobs.size
    if (totalThumbs > 0) {
      thumbJobs.forEachIndexed { idx, (isUpload, bmId, meta) ->
        val (uuid, prevThumb, remoteUpdMs) = meta
        val frac = 0.58f + (0.36f * (idx + 1).toFloat() / totalThumbs.toFloat())
        emitProgress(
          true,
          if (isUpload) "Uploading thumbnails…" else "Downloading thumbnails…",
          "${idx + 1} / $totalThumbs",
          idx + 1,
          totalThumbs,
          frac,
        )
        if (isUpload && !prevThumb.isNullOrBlank()) {
          if (uploadDecryptedThumbnail(baseUrl, token, uuid, prevThumb)) {
            uploadedThumbs++
          }
        } else if (!isUpload) {
          val downloaded = downloadAndEncryptThumbnail(baseUrl, token, uuid, thumbDir)
          if (downloaded != null) {
            val stamp = if (remoteUpdMs > 0L) remoteUpdMs else System.currentTimeMillis()
            bookmarkDao.updateThumbnail(bmId, enc(downloaded), updatedAt = stamp)
            if (!prevThumb.isNullOrBlank() &&
              prevThumb != downloaded &&
              secureBookmarkStore.countByThumbnailPath(prevThumb) == 0
            ) {
              BookmarkThumbnailStore.deleteThumbnail(context, prevThumb, isSecure = true)
            }
            downloadedThumbs++
          }
        }
      }
    }

    emitProgress(true, "Matching vault video files…", "", 0, 1, 0.96f)
    consolidateDuplicateJvidGroupsInDb()
    val matchedCount = runCatching { autoMatchOrphanGroupsWithLocalLibrary() }.getOrDefault(0)
    emitProgress(true, "Sync complete", "", 1, 1, 1f)

    return PcSyncResult(
      inserted = insertedCount,
      updated = updatedCount,
      pushed = pushedCount,
      thumbnailsDownloaded = downloadedThumbs,
      thumbnailsUploaded = uploadedThumbs,
      stashReachable = bundle.stashReachable,
      autoMatchedGroups = matchedCount,
    )
  }

  private val lastAutoMatchAtMs = java.util.concurrent.atomic.AtomicLong(0L)

  /** Throttled trigger: matches orphan AV/JAVID groups strictly against unlocked Primary Vault videos. */
  suspend fun autoMatchOrphanGroupsIfNeeded(
    force: Boolean = false,
    minIntervalMs: Long = 20_000L,
  ): Int =
    withContext(Dispatchers.IO) {
      if (!SecureFolderSession.isPrimaryUnlocked) return@withContext 0
      val now = System.currentTimeMillis()
      val prev = lastAutoMatchAtMs.get()
      if (!force && (now - prev) < minIntervalMs) return@withContext 0
      val hasUnlinked =
        bookmarkDao.getAllGroups().any { g ->
          tagDao.mediaRef(g.groupKey) == null && bookmarkDao.countInGroup(g.groupKey) > 0
        }
      if (!hasUnlinked) return@withContext 0
      if (!force && !lastAutoMatchAtMs.compareAndSet(prev, now)) return@withContext 0
      if (force) lastAutoMatchAtMs.set(now)
      autoMatchOrphanGroupsWithLocalLibrary()
    }

  suspend fun autoMatchOrphanGroupsWithLocalLibrary(): Int =
    withContext(Dispatchers.IO) {
      if (!SecureFolderSession.isPrimaryUnlocked) return@withContext 0
      VaultCrypto.ensureInitialized(context)
      val groups = bookmarkDao.getAllGroups().map { it.toDomain() }
      val unlinked =
        groups.filter { g ->
          tagDao.mediaRef(g.groupKey) == null && bookmarkDao.countInGroup(g.groupKey) > 0
        }
      if (unlinked.isEmpty()) return@withContext 0

      val secureMedia = vaultMediaDao.getAll()
      if (secureMedia.isEmpty()) return@withContext 0
      val metaByHash = vaultMetaDao.getAll().associateBy { it.pathHash }

      val byJvid = HashMap<String, Pair<String, Pair<String, Int>>>()
      val byStem = HashMap<String, MutableList<Pair<String, Pair<String, Int>>>>()
      secureMedia.forEach { entity ->
        if (!File(entity.secureFilePath).isFile) return@forEach
        val decryptedName = dec(entity.fileName).ifBlank { File(entity.secureFilePath).name }
        val decryptedOrigName = File(dec(entity.originalPath)).name
        val meta = metaByHash[VaultCrypto.hmacKey(entity.secureFilePath)]
        val durationSec = ((meta?.duration ?: 0L) / 1000L).toInt()
        val entry = entity.secureFilePath to (decryptedName to durationSec)

        val candidateNames = listOf(decryptedName, decryptedOrigName).filter { it.isNotBlank() }.distinct()
        candidateNames.forEach { rawName ->
          BookmarkMediaMatcher.extractJvidCode(rawName)?.let { code ->
            byJvid.putIfAbsent(code.uppercase(java.util.Locale.ROOT), entry)
          }
          BookmarkMediaMatcher.normalizedStem(rawName)?.let { stem ->
            byStem.getOrPut(stem) { ArrayList() }.add(entry)
          }
        }
      }
      if (byJvid.isEmpty() && byStem.isEmpty()) return@withContext 0

      val now = System.currentTimeMillis()
      var matchedCount = 0
      unlinked.forEach { grp ->
        val gJvid =
          BookmarkMediaMatcher.extractJvidCode(grp.presetCode)
            ?: BookmarkMediaMatcher.extractJvidCode(grp.sourceLabel)
            ?: BookmarkMediaMatcher.extractJvidCode(grp.groupKey)
        val cand =
          gJvid?.let { byJvid[it.uppercase(java.util.Locale.ROOT)] }
            ?: grp.stemKey?.let { sk ->
              byStem[sk]?.firstOrNull { (_, info) ->
                val vDur = info.second
                grp.durationSec <= 0 || vDur <= 0 || abs(grp.durationSec - vDur) <= 3
              }
            }
        if (cand != null) {
          val vaultPath = cand.first
          val displayTitle = cand.second.first
          val uriStr = Uri.fromFile(File(vaultPath)).toString()
          val mediaIds =
            buildSet {
              SecureMediaRegistry.identitiesForPath(vaultPath).forEach(::add)
              add(PlaybackIdentity.forUri(uriStr))
              add(PlaybackIdentity.normalizeLocalPath(vaultPath))
              add(uriStr)
            }
          mediaIds.forEach { cid ->
            bookmarkDao.upsertMediaLink(
              SecureBookmarkMediaLinkEntity(mediaId = cid, groupKey = grp.groupKey, updatedAt = now),
            )
            tagDao.upsertMediaRef(
              SecureBookmarkMediaRefEntity(
                mediaId = cid,
                playableUri = enc(uriStr),
                title = encNullable(displayTitle),
                updatedAt = now,
              ),
            )
          }
          tagDao.upsertMediaRef(
            SecureBookmarkMediaRefEntity(
              mediaId = grp.groupKey,
              playableUri = enc(uriStr),
              title = encNullable(displayTitle),
              updatedAt = now,
            ),
          )
          bookmarkDao.upsertGroup(
            grp.copy(
              presetCode = grp.presetCode?.takeIf { it.isNotBlank() } ?: gJvid,
              sourceLabel = grp.sourceLabel.ifBlank { displayTitle },
              updatedAt = now,
            ).toSecure(originApp = "av_manager", lastSyncedAt = now),
          )
          matchedCount++
        }
      }
      matchedCount
    }

  suspend fun fetchThumbnailCandidates(
    syncUuid: String,
    hubUrl: String = prefs.hubUrl.get(),
    token: String = prefs.syncToken.get(),
  ): Result<ThumbnailCandidatesResponse> =
    withContext(Dispatchers.IO) {
      val baseUrl = BookmarkSyncPreferences.normalizeHubUrl(hubUrl)
      runCatching {
        requirePrimaryUnlocked()
        require(baseUrl.isNotEmpty()) { "Set AV Manager URL in Vault Settings" }
        val req =
          buildRequestBuilder("$baseUrl/api/bookmarks/$syncUuid/thumbnails", token).get().build()
        val body =
          syncClient.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) error("Invalid Sync Token")
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body.string()
          }
        json.decodeFromString<ThumbnailCandidatesResponse>(body)
      }
    }

  suspend fun selectRemoteThumbnailCandidate(
    bookmark: PlaybackBookmarkEntity,
    source: String,
    hubUrl: String = prefs.hubUrl.get(),
    token: String = prefs.syncToken.get(),
  ): Boolean =
    withContext(Dispatchers.IO) {
      if (!SecureFolderSession.isPrimaryUnlocked) return@withContext false
      val uuid = bookmark.syncUuid?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext false
      val baseUrl = BookmarkSyncPreferences.normalizeHubUrl(hubUrl)
      if (baseUrl.isEmpty()) return@withContext false
      runCatching {
        VaultCrypto.ensureInitialized(context)
        val payload = json.encodeToString(ThumbnailSelectRequest(source = source))
        val req =
          buildRequestBuilder("$baseUrl/api/bookmarks/$uuid/thumbnails/select", token)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val respBody =
          syncClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body.string()
          }
        val parsed = json.decodeFromString<ThumbnailCandidatesResponse>(respBody)
        val activeData = parsed.activeThumbnail.trim()
        val rawBytes =
          if (activeData.isNotEmpty()) {
            val b64 = activeData.substringAfter("base64,", activeData)
            Base64.decode(b64, Base64.DEFAULT)
          } else {
            val imgReq = buildRequestBuilder("$baseUrl/api/sync/thumbnail/$uuid", token).get().build()
            syncClient.newCall(imgReq).execute().use { r ->
              if (r.isSuccessful) r.body.bytes() else ByteArray(0)
            }
          }
        if (rawBytes.isEmpty()) return@runCatching false
        val newPath = BookmarkThumbnailStore.saveImageBytes(context, rawBytes, isSecure = true)
          ?: return@runCatching false
        val now = System.currentTimeMillis()
        bookmarkDao.updateThumbnail(bookmark.id, enc(newPath), updatedAt = now)
        val oldPath = bookmark.thumbnailPath
        if (!oldPath.isNullOrBlank() &&
          oldPath != newPath &&
          secureBookmarkStore.countByThumbnailPath(oldPath) == 0
        ) {
          BookmarkThumbnailStore.deleteThumbnail(context, oldPath, isSecure = true)
        }
        true
      }.getOrDefault(false)
    }

  suspend fun uploadBookmarkThumbnailById(
    bookmarkId: Long,
    hubUrl: String = prefs.hubUrl.get(),
    token: String = prefs.syncToken.get(),
  ): Boolean =
    withContext(Dispatchers.IO) {
      if (!SecureFolderSession.isPrimaryUnlocked) return@withContext false
      val baseUrl = BookmarkSyncPreferences.normalizeHubUrl(hubUrl)
      if (baseUrl.isEmpty()) return@withContext false
      val bm = bookmarkDao.getAll().firstOrNull { it.id == bookmarkId }?.toDomain() ?: return@withContext false
      val uuid = bm.syncUuid?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext false
      val localPath = bm.thumbnailPath?.takeIf { it.isNotBlank() } ?: return@withContext false
      uploadDecryptedThumbnail(baseUrl, token, uuid, localPath)
    }

  suspend fun getGroupForMedia(groupKey: String): BookmarkGroupEntity? =
    withContext(Dispatchers.IO) {
      if (!SecureFolderSession.isPrimaryUnlocked) return@withContext null
      val resolved = bookmarkDao.findLinkedGroupKey(groupKey) ?: groupKey
      bookmarkDao.getGroup(resolved)?.toDomain()
    }

  suspend fun assignGroupPresetCodeAndAutoMatch(
    groupKey: String,
    rawPresetCode: String,
    rawSourceUrl: String? = null,
  ): Pair<String?, Int> =
    withContext(Dispatchers.IO) {
      requirePrimaryUnlocked()
      VaultCrypto.ensureInitialized(context)
      val resolved = bookmarkDao.findLinkedGroupKey(groupKey) ?: groupKey
      val normalized =
        rawPresetCode.trim()
          .takeIf { it.isNotEmpty() }
          ?.let { BookmarkMediaMatcher.normalizePresetCode(it).uppercase(java.util.Locale.ROOT) }
      val cleanUrl =
        rawSourceUrl?.trim()?.takeIf {
          it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
      val now = System.currentTimeMillis()
      val existing = bookmarkDao.getGroup(resolved)?.toDomain()
      if (existing != null) {
        bookmarkDao.upsertGroup(
          existing.copy(
            presetCode = normalized,
            sourceUrl = if (rawSourceUrl != null) cleanUrl else existing.sourceUrl,
            updatedAt = now,
          ).toSecure(originApp = "av_manager", lastSyncedAt = now),
        )
      } else if (normalized != null || cleanUrl != null) {
        bookmarkDao.upsertGroup(
          BookmarkGroupEntity(
            groupKey = resolved,
            presetCode = normalized,
            sourceLabel = resolved,
            sourceUrl = cleanUrl,
            updatedAt = now,
          ).toSecure(originApp = "av_manager", lastSyncedAt = now),
        )
      }
      if (!normalized.isNullOrBlank()) {
        consolidateDuplicateJvidGroupsInDb(onlyCode = normalized)
      }
      val matchedCount = if (!normalized.isNullOrBlank()) autoMatchOrphanGroupsWithLocalLibrary() else 0
      normalized to matchedCount
    }

  suspend fun consolidateDuplicateJvidGroupsInDb(onlyCode: String? = null): Int =
    withContext(Dispatchers.IO) {
      if (!SecureFolderSession.isPrimaryUnlocked) return@withContext 0
      VaultCrypto.ensureInitialized(context)
      val groups = bookmarkDao.getAllGroups().map { it.toDomain() }
      if (groups.isEmpty()) return@withContext 0
      val counts = HashMap<String, Int>()
      groups.forEach { g -> counts[g.groupKey] = bookmarkDao.countInGroup(g.groupKey) }
      val plans = BookmarkGroupConsolidator.planDuplicateJvidConsolidation(groups, counts, onlyCode = onlyCode)
      var consolidated = 0
      val now = System.currentTimeMillis()
      plans.forEach { plan ->
        val winnerKey = plan.winnerGroup.groupKey
        bookmarkDao.upsertGroup(plan.winnerGroup.toSecure(originApp = "av_manager", lastSyncedAt = now))
        val winnerHasRef = tagDao.mediaRef(winnerKey) != null
        plan.losingGroupKeys.forEach { loserKey ->
          bookmarkDao.reassignBookmarks(oldMediaId = loserKey, newMediaId = winnerKey, updatedAt = now)
          bookmarkDao.reassignLinks(sourceGroupKey = loserKey, targetGroupKey = winnerKey)
          bookmarkDao.upsertMediaLink(
            SecureBookmarkMediaLinkEntity(mediaId = loserKey, groupKey = winnerKey, updatedAt = now),
          )
          val loserRef = tagDao.mediaRef(loserKey)
          if (loserRef != null) {
            if (!winnerHasRef) {
              tagDao.upsertMediaRef(loserRef.copy(mediaId = winnerKey, updatedAt = now))
            }
            tagDao.deleteMediaRef(loserKey)
          }
          bookmarkDao.deleteGroup(loserKey)
          consolidated++
        }
      }
      consolidated
    }

  suspend fun mergeOverlappingBookmarksForGroup(
    groupKey: String,
    windowSec: Double = 10.0,
    strategy: String = "earliest",
    keepAlternates: Boolean = true,
  ): Result<String> =
    withContext(Dispatchers.IO) {
      val resolvedKey = bookmarkDao.findLinkedGroupKey(groupKey) ?: groupKey
      val hubUrl = BookmarkSyncPreferences.normalizeHubUrl(prefs.hubUrl.get())
      val token = prefs.syncToken.get()
      runCatching {
        requirePrimaryUnlocked()
        VaultCrypto.ensureInitialized(context)
        if (hubUrl.isNotEmpty()) {
          val remoteAttempt =
            runCatching {
              val reqBody =
                json.encodeToString(
                  HubDensityMergeRequest(
                    windowSec = windowSec,
                    defaultStrategy = strategy,
                    keepAlternates = keepAlternates,
                    syncStash = true,
                  ),
                )
              val req =
                buildRequestBuilder(
                  "$hubUrl/api/bookmarks/groups/${Uri.encode(resolvedKey)}/merge-clusters",
                  token,
                )
                  .post(reqBody.toRequestBody(JSON_MEDIA_TYPE))
                  .build()
              syncClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Merge HTTP ${resp.code}")
                json.decodeFromString<HubDensityMergeResponse>(resp.body.string())
              }
            }
          if (remoteAttempt.isSuccess) {
            val out = remoteAttempt.getOrThrow()
            syncWithPcHub()
            return@runCatching "Merged ${out.mergedClusters} cluster(s) on PC & Stash (-${out.tombstonedCount} duplicates)"
          }
        }

        val localItems = bookmarkDao.getInGroup(resolvedKey).map { it.toDomain() }
        val tagsMap =
          localItems.associate { bm ->
            bm.id to tagDao.tagsFor(bm.id).map { dec(it.displayName).ifBlank { dec(it.name) } }
          }
        val plans =
          BookmarkGroupConsolidator.planRollingWindowTimestampMerges(
            bookmarks = localItems,
            tagsByBookmarkId = tagsMap,
            windowSec = windowSec,
            strategy = strategy,
          )
        if (plans.isEmpty()) return@runCatching "No overlapping bookmarks within ${windowSec.toInt()}s"
        val now = System.currentTimeMillis()
        var removed = 0
        plans.forEach { plan ->
          bookmarkDao.updatePositionAndTitle(
            id = plan.keeper.id,
            positionMs = plan.targetPositionMs,
            title = enc(plan.targetTitle),
            updatedAt = now,
          )
          bookmarkDao.updateBookmarkSyncMetadata(
            id = plan.keeper.id,
            endPositionMs = plan.targetEndPositionMs,
            primaryTag = encNullable(plan.targetPrimaryTag),
            syncUuid = plan.keeper.syncUuid ?: UUID.randomUUID().toString(),
            updatedAt = now,
          )
          if (plan.mergedTags.isNotEmpty()) {
            applyBookmarkTags(plan.keeper.id, plan.mergedTags)
          }
          plan.losingBookmarks.forEach { loser ->
            loser.syncUuid?.takeIf { it.isNotBlank() }?.let { uuid ->
              tombstoneDao.upsert(
                SecureBookmarkSyncTombstoneEntity(syncUuid = uuid, groupKey = resolvedKey, deletedAt = now),
              )
            }
            bookmarkDao.delete(loser.id)
            tagDao.clearForBookmark(loser.id)
            loser.thumbnailPath?.takeIf { it.isNotBlank() }?.let { tp ->
              if (secureBookmarkStore.countByThumbnailPath(tp) == 0) {
                BookmarkThumbnailStore.deleteThumbnail(context, tp, isSecure = true)
              }
            }
            removed++
          }
        }
        "Merged ${plans.size} cluster(s) locally (-$removed duplicates)"
      }
    }

  suspend fun linkOrphanGroupToUri(
    groupKey: String,
    uri: Uri,
    overridePresetCode: String? = null,
    overrideSourceUrl: String? = null,
  ): Result<LinkedOrphanResult> =
    withContext(Dispatchers.IO) {
      runCatching {
        requirePrimaryUnlocked()
        VaultCrypto.ensureInitialized(context)
        val rawUriString = uri.toString()
        require(!DurableStorageManager.isDecoyVaultPath(rawUriString)) {
          "Decoy files cannot be linked to Primary Vault bookmarks"
        }
        runCatching {
          context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val mediaId = PlaybackIdentity.forUri(rawUriString)
        val resolvedKey = bookmarkDao.findLinkedGroupKey(groupKey) ?: groupKey
        val displayName = resolveUriDisplayName(uri)
        val fileSize = resolveUriFileSize(uri)
        val oshash = OshashCalculator.computeOshash(context, uri)
        val stemKey = BookmarkMediaMatcher.normalizedStem(displayName)
        val now = System.currentTimeMillis()
        val existingGroup = bookmarkDao.getGroup(resolvedKey)?.toDomain()
        val normalizedOverride =
          overridePresetCode?.trim()?.takeIf { it.isNotEmpty() }?.let {
            BookmarkMediaMatcher.normalizePresetCode(it).uppercase(java.util.Locale.ROOT)
          }

        val updatedGroup =
          (existingGroup ?: BookmarkGroupEntity(groupKey = resolvedKey)).copy(
            presetCode = normalizedOverride
              ?: existingGroup?.presetCode?.takeIf { it.isNotBlank() }
              ?: BookmarkMediaMatcher.extractJvidCode(displayName),
            oshash = oshash ?: existingGroup?.oshash,
            stemKey = stemKey ?: existingGroup?.stemKey,
            fileSizeBytes = fileSize.takeIf { it > 0L } ?: existingGroup?.fileSizeBytes ?: 0L,
            sourceLabel = displayName.ifBlank { existingGroup?.sourceLabel.orEmpty() },
            sourceUrl = overrideSourceUrl?.trim()?.takeIf { it.isNotEmpty() } ?: existingGroup?.sourceUrl,
            updatedAt = now,
          )
        bookmarkDao.upsertGroup(updatedGroup.toSecure(originApp = "av_manager", lastSyncedAt = now))

        val mediaIds =
          buildSet {
            add(mediaId)
            if (uri.scheme.equals("file", ignoreCase = true)) {
              uri.path?.let { localPath ->
                SecureMediaRegistry.identitiesForPath(localPath).forEach(::add)
                add(PlaybackIdentity.normalizeLocalPath(localPath))
              }
            }
            add(rawUriString)
          }
        mediaIds.forEach { candidateId ->
          bookmarkDao.upsertMediaLink(
            SecureBookmarkMediaLinkEntity(mediaId = candidateId, groupKey = resolvedKey, updatedAt = now),
          )
          tagDao.upsertMediaRef(
            SecureBookmarkMediaRefEntity(
              mediaId = candidateId,
              playableUri = enc(rawUriString),
              title = encNullable(displayName.ifBlank { null }),
              updatedAt = now,
            ),
          )
        }
        tagDao.upsertMediaRef(
          SecureBookmarkMediaRefEntity(
            mediaId = resolvedKey,
            playableUri = enc(rawUriString),
            title = encNullable(displayName.ifBlank { null }),
            updatedAt = now,
          ),
        )

        if (!updatedGroup.presetCode.isNullOrBlank()) {
          consolidateDuplicateJvidGroupsInDb(onlyCode = updatedGroup.presetCode)
        }
        val finalKey = bookmarkDao.findLinkedGroupKey(resolvedKey) ?: resolvedKey
        val count = bookmarkDao.countInGroup(finalKey)
        LinkedOrphanResult(
          groupKey = finalKey,
          displayName = displayName.ifBlank { finalKey },
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
        val hash = VaultCrypto.hmacKey(name)
        SecureBookmarkTagEntity(
          bookmarkId = bookmarkId,
          nameHash = hash,
          name = enc(name),
          displayName = enc(name),
          createdAt = now,
        )
      },
    )
    clean.forEach { name ->
      val hash = VaultCrypto.hmacKey(name)
      val prev = tagDao.getMemory(hash)
      tagDao.upsertMemory(
        SecureBookmarkTagMemoryEntity(
          nameHash = hash,
          name = enc(name),
          displayName = enc(name),
          useCount = (prev?.useCount ?: 0) + 1,
          lastUsedAt = now,
        ),
      )
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
    VaultCrypto.ensureInitialized(context)
    val encrypted = VaultCrypto.encryptBytes(bytes)
    thumbDir.mkdirs()
    val dest = File(thumbDir, "bm_sync_${UUID.randomUUID()}.dat")
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
      val host =
        runCatching { URI(baseUrl).host?.lowercase().orEmpty() }
          .getOrDefault("")
      return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
    }

    internal fun friendlyNetworkError(
      baseUrl: String,
      err: Throwable,
      usbMode: Boolean = false,
    ): IllegalStateException {
      val detail =
        when (err) {
          is SocketTimeoutException ->
            "Timed out connecting to $baseUrl (PC hub may be busy — retry shortly)"
          is ConnectException,
          is UnknownHostException,
          ->
            when {
              usbMode && isLoopbackUrl(baseUrl) ->
                "USB ADB reverse not active on $baseUrl — run enable-usb-sync.bat on PC with USB Debugging enabled"
              isLoopbackUrl(baseUrl) ->
                "127.0.0.1 / localhost points to this phone, not your PC. Use AV Manager LAN IP"
              else ->
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
