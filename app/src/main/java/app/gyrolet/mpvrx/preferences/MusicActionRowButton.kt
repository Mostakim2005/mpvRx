/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import androidx.annotation.StringRes
import app.gyrolet.mpvrx.R

/**
 * Hideable buttons of the music player action row (R-02). The Queue button and the transport
 * controls are pinned in fixed slots and deliberately absent here: the queue sheet must stay
 * reachable and play/pause plus seek are never hideable.
 */
enum class MusicActionRowButton(@StringRes val titleRes: Int) {
  EQUALIZER(R.string.btn_label_equalizer),
  LYRICS(R.string.player_lyrics_title),
  SHUFFLE(R.string.btn_label_shuffle),
  REPEAT(R.string.btn_label_repeat_mode),
  VISUALIZER(R.string.audio_footer_show_visualizer),
  EDIT_TAGS(R.string.music_action_edit_tags),
  BACKGROUND_PLAYBACK(R.string.btn_label_background_playback),
  DOWNLOAD(R.string.downloads_download),
  ;

  companion object {
    fun defaultVisibility(): Set<String> = values().mapTo(mutableSetOf()) { it.name }

    /** Parses a stored visibility set, silently dropping names that no longer resolve. */
    fun parse(stored: Set<String>): Set<MusicActionRowButton> =
      stored.mapNotNull { name -> values().firstOrNull { it.name == name } }.toSet()
  }
}
