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
import app.gyrolet.mpvrx.database.dao.BookmarkGalleryRow
import app.gyrolet.mpvrx.database.dao.BookmarkGroupSummary
import app.gyrolet.mpvrx.database.dao.BookmarkTagDao
import app.gyrolet.mpvrx.database.dao.PlaybackBookmarkDao
import app.gyrolet.mpvrx.database.entities.BookmarkGroupEntity
import app.gyrolet.mpvrx.database.entities.BookmarkMediaLinkEntity
import app.gyrolet.mpvrx.database.entities.BookmarkMediaRefEntity
import app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity
import app.gyrolet.mpvrx.domain.secure.SecureFolderSession
import app.gyrolet.mpvrx.domain.secure.SecureMediaRegistry
import app.gyrolet.mpvrx.domain.storage.DurableStorageManager
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.preferences.HiddenScanScope
import app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.Locale

/** One gallery card: the bookmark, its media, and its tag chips. */
data class BookmarkGalleryItem(
  val row: BookmarkGalleryRow,
  val tags: List<String>,
  /** Playable URI for the bookmarked video, or null when that media was never replayed since. */
  val playableUri: String? = null,
  val mediaTitle: String? = null,
)

/** Which slice of the library the gallery is showing. */
enum class BookmarkGalleryScope { AllVideos, SecureVideos }

/**
 * Builds the cross-video bookmark gallery from strictly segregated storage:
 * - [BookmarkGalleryScope.AllVideos] reads only from [MpvRxDatabase], filtering out any vault
 *   links/refs/paths, unmigrated AV Manager sync rows, and (when hidden scanning is off)
 *   `.nomedia` / dot-folder media.
 * - [BookmarkGalleryScope.SecureVideos] reads only from [SecureBookmarkStore] (Primary or Decoy
 *   compartment depending on [SecureFolderSession.modeState]), emitting empty when locked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkGalleryRepository(
  private val bookmarkDao: PlaybackBookmarkDao,
  private val tagDao: BookmarkTagDao,
  private val secureBookmarkStore: SecureBookmarkStore,
  private val foldersPreferences: FoldersPreferences? = null,
) {
  private data class HiddenVisibilityPolicy(
    val allowHiddenFolders: Boolean,
    val scanDotFolders: Boolean,
    val markerNames: Set<String>,
  )

  private fun observeHiddenVisibilityPolicy(): Flow<HiddenVisibilityPolicy> {
    val prefs = foldersPreferences
      ?: return flowOf(HiddenVisibilityPolicy(allowHiddenFolders = true, scanDotFolders = true, markerNames = setOf(".nomedia")))
    return combine(
      prefs.includeNoMediaFolders.changes(),
      prefs.libraryHiddenScanEnabled.changes(),
      prefs.treeHiddenScanEnabled.changes(),
      prefs.foldersHiddenScanEnabled.changes(),
      combine(
        prefs.sharedScanDotFolders.changes(),
        prefs.sharedScanMarkedFolders.changes(),
        prefs.hiddenFolderMarkerNames.changes(),
        prefs.sharedIgnoredMarkers.changes(),
      ) { dot, marked, markers, ignored ->
        Triple(dot, marked, markers - ignored)
      },
    ) { master, libOn, treeOn, fldOn, (dot, marked, activeMarkers) ->
      val anyScopeOn = master && (libOn || treeOn || fldOn)
      HiddenVisibilityPolicy(
        allowHiddenFolders = anyScopeOn,
        scanDotFolders = dot,
        markerNames = if (marked) activeMarkers else emptySet(),
      )
    }.distinctUntilChanged()
  }

  private fun observeFilteredPublicEntries(): Flow<List<Triple<PlaybackBookmarkEntity, BookmarkGroupEntity?, BookmarkGalleryItem>>> {
    val linksAndGroupsFlow =
      combine(
        bookmarkDao.observeAllPublicBookmarks(),
        bookmarkDao.observeAllLinks(),
        bookmarkDao.observeAllGroups(),
      ) { bookmarks, links, groups -> Triple(bookmarks, links, groups) }

    val tagsRefsPolicyFlow =
      combine(
        tagDao.observeAllPairs(),
        tagDao.observeMediaRefs(),
        observeHiddenVisibilityPolicy(),
      ) { pairs, refs, policy -> Triple(pairs, refs, policy) }

    return combine(linksAndGroupsFlow, tagsRefsPolicyFlow) { (bookmarks, links, groups), (pairs, refs, policy) ->
      val tagsByBookmark = pairs.groupBy({ it.bookmarkId }, { it.name })
      val refsByMedia: Map<String, BookmarkMediaRefEntity> = refs.associateBy { it.mediaId }
      val linksByMedia: Map<String, BookmarkMediaLinkEntity> = links.associateBy { it.mediaId }
      val linksByGroup: Map<String, List<BookmarkMediaLinkEntity>> = links.groupBy { it.groupKey }
      val groupsByKey: Map<String, BookmarkGroupEntity> = groups.associateBy { it.groupKey }
      val dirHiddenCache = HashMap<String, Boolean>()

      bookmarks.mapNotNull { bm ->
        val groupKey = linksByMedia[bm.mediaId]?.groupKey ?: bm.mediaId
        val group = groupsByKey[groupKey] ?: groupsByKey[bm.mediaId]
        val groupLinks = linksByGroup[groupKey].orEmpty()
        val ref = refsByMedia[bm.mediaId] ?: refsByMedia[groupKey]

        if (isExcludedPublicEntry(bm, group, ref, groupLinks, refsByMedia, policy, dirHiddenCache)) {
          return@mapNotNull null
        }

        val galleryRow =
          BookmarkGalleryRow(
            id = bm.id,
            mediaId = bm.mediaId,
            positionMs = bm.positionMs,
            title = bm.title,
            createdAt = bm.createdAt,
            thumbnailPath = bm.thumbnailPath,
          )
        Triple(
          bm,
          group,
          BookmarkGalleryItem(
            row = galleryRow,
            tags = tagsByBookmark[bm.id].orEmpty().sorted(),
            playableUri = ref?.playableUri,
            mediaTitle = ref?.title ?: group?.sourceLabel?.takeIf { it.isNotBlank() },
          ),
        )
      }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()
  }

  private fun isExcludedPublicEntry(
    bookmark: PlaybackBookmarkEntity,
    group: BookmarkGroupEntity?,
    ref: BookmarkMediaRefEntity?,
    groupLinks: List<BookmarkMediaLinkEntity>,
    refsByMedia: Map<String, BookmarkMediaRefEntity>,
    policy: HiddenVisibilityPolicy,
    dirHiddenCache: MutableMap<String, Boolean>,
  ): Boolean {
    if (
      DurableStorageManager.isVaultPath(bookmark.mediaId) ||
      SecureMediaRegistry.isProtectedReference(bookmark.mediaId) ||
      DurableStorageManager.isVaultPath(bookmark.thumbnailPath) ||
      DurableStorageManager.isVaultPath(ref?.playableUri) ||
      SecureMediaRegistry.isProtectedReference(ref?.playableUri)
    ) {
      return true
    }

    // Bravo 1 quarantine suppression: anything synced from/to AV Manager or Stash stays invisible
    // in Normal Mode until migrated to Primary Vault on unlock.
    if (
      !bookmark.syncUuid.isNullOrBlank() ||
      bookmark.originApp.equals("av_manager", ignoreCase = true) ||
      !group?.sourceUrl.isNullOrBlank() ||
      bookmark.mediaId.startsWith("javid:", ignoreCase = true) ||
      (group?.groupKey?.startsWith("javid:", ignoreCase = true) == true)
    ) {
      return true
    }

    for (link in groupLinks) {
      val linkedRef = refsByMedia[link.mediaId]
      if (
        DurableStorageManager.isVaultPath(link.mediaId) ||
        SecureMediaRegistry.isProtectedReference(link.mediaId) ||
        DurableStorageManager.isVaultPath(linkedRef?.playableUri) ||
        SecureMediaRegistry.isProtectedReference(linkedRef?.playableUri)
      ) {
        return true
      }
    }

    // Delta 1: Hide bookmarks on `.nomedia` / dot hidden folders whenever hidden scanning is turned off.
    if (!policy.allowHiddenFolders) {
      val pathCandidates =
        buildList {
          ref?.playableUri?.let(::add)
          groupLinks.forEach { link ->
            refsByMedia[link.mediaId]?.playableUri?.let(::add)
            if (link.mediaId.startsWith("/") || link.mediaId.startsWith("file:", ignoreCase = true)) {
              add(link.mediaId)
            }
          }
          if (bookmark.mediaId.startsWith("/") || bookmark.mediaId.startsWith("file:", ignoreCase = true)) {
            add(bookmark.mediaId)
          }
        }
      if (pathCandidates.any { isHiddenFolderReference(it, policy, dirHiddenCache) }) {
        return true
      }
    }

    return false
  }

  private fun isHiddenFolderReference(
    rawUriOrPath: String,
    policy: HiddenVisibilityPolicy,
    cache: MutableMap<String, Boolean>,
  ): Boolean {
    val localPath = resolveLocalPathOrNull(rawUriOrPath) ?: return false
    val parentDir = File(localPath).parentFile ?: return false
    val key = parentDir.absolutePath
    cache[key]?.let { return it }
    val hidden = isDirectoryHidden(parentDir, policy)
    cache[key] = hidden
    return hidden
  }

  private fun resolveLocalPathOrNull(raw: String): String? {
    val trimmed = raw.trim().takeIf { it.isNotEmpty() } ?: return null
    return when {
      trimmed.startsWith("/") -> trimmed
      trimmed.startsWith("file://", ignoreCase = true) -> runCatching { Uri.parse(trimmed).path }.getOrNull()
      trimmed.startsWith("content://", ignoreCase = true) ->
        runCatching { ScopedHiddenFolderScanner.localPathOf(Uri.parse(trimmed)) }.getOrNull()
      else -> null
    }
  }

  private fun isDirectoryHidden(
    directory: File,
    policy: HiddenVisibilityPolicy,
  ): Boolean {
    var cursor: File? = directory
    while (cursor != null) {
      val name = cursor.name
      if (name.startsWith(".") && name.length > 1) {
        return true
      }
      val hasMarker =
        policy.markerNames.any { marker ->
          marker.isNotBlank() && File(cursor, marker).exists()
        } || File(cursor, ".nomedia").exists()
      if (hasMarker) return true
      cursor = cursor.parentFile
    }
    return false
  }

  fun observeItems(scope: BookmarkGalleryScope): Flow<List<BookmarkGalleryItem>> =
    when (scope) {
      BookmarkGalleryScope.AllVideos ->
        observeFilteredPublicEntries().map { entries -> entries.map { it.third } }.distinctUntilChanged()

      BookmarkGalleryScope.SecureVideos ->
        SecureFolderSession.modeState.flatMapLatest { mode ->
          when (mode) {
            SecureFolderSession.VaultMode.LOCKED -> flowOf(emptyList())
            SecureFolderSession.VaultMode.PRIMARY -> secureBookmarkStore.observeGalleryItems(forceDecoy = false)
            SecureFolderSession.VaultMode.DECOY -> secureBookmarkStore.observeGalleryItems(forceDecoy = true)
          }
        }.distinctUntilChanged()
    }

  /** Tag vocabulary for the filter bar in the requested database slice (never leaks vault/AV tags publicly). */
  fun observeFilterTags(scope: BookmarkGalleryScope = BookmarkGalleryScope.AllVideos): Flow<List<String>> =
    when (scope) {
      BookmarkGalleryScope.AllVideos ->
        observeItems(BookmarkGalleryScope.AllVideos)
          .map { items ->
            items.flatMap { it.tags }.distinct().sorted()
          }.distinctUntilChanged()

      BookmarkGalleryScope.SecureVideos ->
        SecureFolderSession.modeState.flatMapLatest { mode ->
          when (mode) {
            SecureFolderSession.VaultMode.LOCKED -> flowOf(emptyList())
            SecureFolderSession.VaultMode.PRIMARY -> secureBookmarkStore.observeFilterTags(forceDecoy = false)
            SecureFolderSession.VaultMode.DECOY -> secureBookmarkStore.observeFilterTags(forceDecoy = true)
          }
        }
    }

  /** Segregated preset summaries for Saved Presets picker (excludes vault/AV/.nomedia groups from Normal Mode). */
  fun observePresetSummaries(isSecure: Boolean): Flow<List<app.gyrolet.mpvrx.database.dao.BookmarkGroupSummary>> =
    if (isSecure) {
      SecureFolderSession.modeState.flatMapLatest { mode ->
        when (mode) {
          SecureFolderSession.VaultMode.LOCKED -> flowOf(emptyList())
          SecureFolderSession.VaultMode.PRIMARY -> secureBookmarkStore.observePresetSummaries(forceDecoy = false)
          SecureFolderSession.VaultMode.DECOY -> secureBookmarkStore.observePresetSummaries(forceDecoy = true)
        }
      }
    } else {
      observeFilteredPublicEntries().map { entries ->
        entries
          .groupBy { (_, group, item) -> group?.groupKey ?: item.row.mediaId }
          .values
          .mapNotNull { bucket ->
            val group = bucket.firstNotNullOfOrNull { it.second } ?: return@mapNotNull null
            app.gyrolet.mpvrx.database.dao.BookmarkGroupSummary(
              groupKey = group.groupKey,
              presetCode = group.presetCode,
              fingerprint = group.fingerprint,
              stemKey = group.stemKey,
              durationSec = group.durationSec,
              width = group.width,
              height = group.height,
              fileSizeBytes = group.fileSizeBytes,
              sourceLabel = group.sourceLabel,
              updatedAt = group.updatedAt,
              bookmarkCount = bucket.size,
            )
          }.sortedByDescending { it.updatedAt }
      }.distinctUntilChanged()
    }

  /** Permanently deletes one bookmark (plus orphan thumbnail and vault tombstone) in its isolated compartment. */
  suspend fun deleteBookmark(
    context: Context,
    item: BookmarkGalleryItem,
    isSecure: Boolean,
  ) {
    val entity =
      PlaybackBookmarkEntity(
        id = item.row.id,
        mediaId = item.row.mediaId,
        positionMs = item.row.positionMs,
        title = item.row.title,
        createdAt = item.row.createdAt,
        bookTrackId = item.row.bookTrackId,
        thumbnailPath = item.row.thumbnailPath,
        syncUuid = item.row.syncUuid,
      )
    if (isSecure) {
      secureBookmarkStore.deleteBookmarkSafely(entity)
    } else {
      PlaybackBookmarkOps.deleteBookmarkSafely(dao = bookmarkDao, context = context, bookmark = entity)
      tagDao.clearForBookmark(entity.id)
    }
  }
}

/** Splits a gallery list by selected tags; an empty selection is "no filter". */
fun List<BookmarkGalleryItem>.filterByTags(selected: Set<String>): List<BookmarkGalleryItem> {
  val required = selected.map { BookmarkTagRules.normalize(it) }.filter { it.isNotBlank() }.toSet()
  if (required.isEmpty()) return this
  return filter { item ->
    val itemTags = item.tags.mapTo(HashSet()) { BookmarkTagRules.normalize(it) }
    itemTags.containsAll(required)
  }
}
