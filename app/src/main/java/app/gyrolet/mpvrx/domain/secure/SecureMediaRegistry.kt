/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.secure

import app.gyrolet.mpvrx.database.entities.SecureMediaEntity
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity

/**
 * Process-wide index of everything the Secure Folder currently holds.
 *
 * Public data stores (bookmark gallery, recently played, watch statistics) and the playback
 * launcher need a synchronous answer to "is this file private?" while querying Room or starting an
 * activity, which a [SecureFolderRepository] flow cannot give them. This object is the sync mirror
 * of that flow: [app.gyrolet.mpvrx.database.repository.SecureFolderRepository] keeps it current, so
 * a file that is restored out of the folder drops out of the index automatically.
 *
 * Matching covers both the private path a secure file is played from and the identity
 * (`PlaybackIdentity.forLocalPath`) the bookmark store uses, so no caller can leak a private file by
 * querying the "other" kind of key.
 */
object SecureMediaRegistry {
  private data class Snapshot(
    val paths: Set<String>,
    val identities: Set<String>,
  )

  private val EMPTY = Snapshot(emptySet(), emptySet())

  @Volatile
  private var snapshot: Snapshot = EMPTY

  /** Replaces the index with [entities]; call with the full secure listing, not a delta. */
  fun update(entities: List<SecureMediaEntity>) {
    if (entities.isEmpty()) {
      snapshot = EMPTY
      return
    }
    val paths = HashSet<String>(entities.size * 2)
    val identities = HashSet<String>(entities.size)
    entities.forEach { entity ->
      normalize(entity.secureFilePath)?.let { securePath ->
        paths += securePath
        identities += PlaybackIdentity.forLocalPath(entity.secureFilePath)
      }
      normalize(entity.originalPath)?.let { paths += it }
    }
    snapshot = Snapshot(paths, identities)
  }

  fun clear() {
    snapshot = EMPTY
  }

  /**
   * True when [value] names a file held by the Secure Folder — as an absolute path, a `file://`
   * URI, a content URI whose path is private, or a playback identity (stable id).
   */
  fun isProtectedReference(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    val current = snapshot
    if (current.paths.isEmpty() && current.identities.isEmpty()) return false
    if (value in current.identities) return true
    val path = normalize(value)
    if (path != null && path in current.paths) return true
    return PlaybackIdentity.forLocalPath(value) in current.identities
  }

  private fun normalize(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val withoutScheme = trimmed.removePrefix("file://").removePrefix("FILE://")
    return withoutScheme.trimEnd('/').takeIf { it.isNotEmpty() }
  }
}
