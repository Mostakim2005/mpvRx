/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.bookmark

import app.gyrolet.mpvrx.database.dao.BookmarkGalleryRow
import app.gyrolet.mpvrx.database.dao.BookmarkTagDao
import app.gyrolet.mpvrx.database.dao.PlaybackBookmarkDao
import app.gyrolet.mpvrx.domain.secure.SecureFolderSession
import app.gyrolet.mpvrx.domain.secure.SecureMediaRegistry
import app.gyrolet.mpvrx.domain.storage.DurableStorageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

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
 * [BookmarkGalleryScope.AllVideos] reads only from [MpvRxDatabase], while
 * [BookmarkGalleryScope.SecureVideos] reads only from [SecureBookmarkStore] ([SecureVaultDatabase])
 * while [SecureFolderSession.unlockedState] is true.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkGalleryRepository(
  private val bookmarkDao: PlaybackBookmarkDao,
  private val tagDao: BookmarkTagDao,
  private val secureBookmarkStore: SecureBookmarkStore,
) {
  fun observeItems(scope: BookmarkGalleryScope): Flow<List<BookmarkGalleryItem>> =
    when (scope) {
      BookmarkGalleryScope.AllVideos ->
        combine(
          bookmarkDao.observeGallery(),
          tagDao.observeAllPairs(),
          tagDao.observeMediaRefs(),
        ) { rows, pairs, refs ->
          val tagsByBookmark = pairs.groupBy({ it.bookmarkId }, { it.name })
          val refsByMedia = refs.associateBy { it.mediaId }
          rows.mapNotNull { row ->
            val ref = refsByMedia[row.mediaId]
            if (
              DurableStorageManager.isVaultPath(ref?.playableUri) ||
              SecureMediaRegistry.isProtectedReference(ref?.playableUri) ||
              DurableStorageManager.isVaultPath(row.thumbnailPath)
            ) {
              return@mapNotNull null
            }
            BookmarkGalleryItem(
              row = row,
              tags = tagsByBookmark[row.id].orEmpty().sorted(),
              playableUri = ref?.playableUri,
              mediaTitle = ref?.title,
            )
          }
        }.distinctUntilChanged()

      BookmarkGalleryScope.SecureVideos ->
        SecureFolderSession.unlockedState.flatMapLatest { unlocked ->
          if (!unlocked) flowOf(emptyList()) else secureBookmarkStore.observeGalleryItems()
        }.distinctUntilChanged()
    }

  /** Tag vocabulary for the filter bar in the requested database slice. */
  fun observeFilterTags(scope: BookmarkGalleryScope = BookmarkGalleryScope.AllVideos): Flow<List<String>> =
    when (scope) {
      BookmarkGalleryScope.AllVideos ->
        combine(tagDao.observeAllPairs(), tagDao.observeMemory()) { pairs, memory ->
          val live = pairs.map { it.name }
          (live + memory.map { it.displayName.ifBlank { it.name } }).distinct().sorted()
        }

      BookmarkGalleryScope.SecureVideos ->
        SecureFolderSession.unlockedState.flatMapLatest { unlocked ->
          if (!unlocked) flowOf(emptyList()) else secureBookmarkStore.observeFilterTags()
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
