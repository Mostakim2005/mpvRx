/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.playbackstate.repository

import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.database.entities.SecurePlaybackStateEntity

interface PlaybackStateRepository {
  suspend fun upsert(playbackState: PlaybackStateEntity)

  suspend fun getVideoDataByTitle(mediaTitle: String): PlaybackStateEntity?

  suspend fun getAllPlaybackStates(): List<PlaybackStateEntity>

  suspend fun clearAllPlaybackStates()

  suspend fun deleteByTitle(mediaTitle: String)

  suspend fun updateMediaTitle(
    oldTitle: String,
    newTitle: String,
  )

  suspend fun migrateState(
    oldMediaTitles: List<String>,
    newMediaTitle: String,
    toSecure: Boolean,
    displayLabel: String = "",
  )

  suspend fun getAllSecurePlaybackStates(): List<Pair<SecurePlaybackStateEntity, String>>

  suspend fun deleteSecureStatesByKeys(mediaKeys: List<String>)

  suspend fun clearSecurePlaybackStates(decoyOnly: Boolean = false)
}
