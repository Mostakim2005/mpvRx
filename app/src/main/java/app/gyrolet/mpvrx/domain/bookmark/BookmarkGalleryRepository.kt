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
import app.gyrolet.mpvrx.database.repository.SecureFolderRepository
import app.gyrolet.mpvrx.domain.secure.SecureMediaRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

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
 * Builds the cross-video bookmark gallery.
 *
 * The secure slice is resolved here rather than in SQL. A bookmark belongs to secure storage when
 * its `mediaId` is a secure identity, or when that identity was linked to the bookmark's group while
 * the file moved in or out of the vault. Without the link lookup a moved file keeps its old public
 * identity, so its row would stay in the public gallery and disappear from the secure one.
 */
class BookmarkGalleryRepository(
  private val bookmarkDao: PlaybackBookmarkDao,
  private val tagDao: BookmarkTagDao,
  private val secureFolderRepository: SecureFolderRepository,
) {
  fun observeItems(scope: BookmarkGalleryScope): Flow<List<BookmarkGalleryItem>> {
    val scopedRows: Flow<List<BookmarkGalleryRow>> =
      combine(
        bookmarkDao.observeGallery(),
        secureFolderRepository.observeAll(),
        bookmarkDao.observeAllLinks(),
      ) { rows, entries, links ->
        if (entries.isEmpty()) {
          return@combine if (scope == BookmarkGalleryScope.SecureVideos) emptyList() else rows
        }
        val secureIds = HashSet<String>(entries.size * 3)
        entries.forEach { entry ->
          secureIds += SecureMediaRegistry.identitiesForPath(entry.secureFilePath)
        }
        val secureKeys = HashSet<String>(secureIds.size * 2)
        secureKeys += secureIds
        links.forEach { link ->
          if (link.mediaId in secureIds) secureKeys += link.groupKey
        }
        when (scope) {
          // A private bookmark must never surface in the public slice, even when the bookmark was
          // created before the file moved into the vault and still carries the old group key.
          BookmarkGalleryScope.AllVideos -> rows.filterNot { it.mediaId in secureKeys }
          BookmarkGalleryScope.SecureVideos -> rows.filter { it.mediaId in secureKeys }
        }
      }
    val paired =
      combine(scopedRows, tagDao.observeAllPairs(), tagDao.observeMediaRefs()) { rows, pairs, refs ->
        val tagsByBookmark = pairs.groupBy({ it.bookmarkId }, { it.name })
        val refsByMedia = refs.associateBy { it.mediaId }
        rows.map { row ->
          val ref = refsByMedia[row.mediaId]
          BookmarkGalleryItem(
            row = row,
            tags = tagsByBookmark[row.id].orEmpty().sorted(),
            playableUri = ref?.playableUri,
            mediaTitle = ref?.title,
          )
        }
      }
    return paired.distinctUntilChanged()
  }

  /** Tag vocabulary for the filter bar: live tags first, then remembered tags with no bookmark now. */
  fun observeFilterTags(): Flow<List<String>> =
    combine(tagDao.observeAllPairs(), tagDao.observeMemory()) { pairs, memory ->
      val live = pairs.map { it.name }
      (live + memory.map { it.displayName.ifBlank { it.name } }).distinct().sorted()
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
