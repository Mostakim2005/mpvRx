/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.bookmark

object BookmarkTagRules {
  /** Tags are short labels, not sentences. */
  const val MAX_TAG_LENGTH = 32

  /** Keeps a single bookmark's chip row bounded and stops accidental paste floods. */
  const val MAX_TAGS_PER_BOOKMARK = 12

  /**
   * Normalizes a tag for storage and comparison.
   *
   * Stored names are lower-case with collapsed internal whitespace so "Deep Work", "deep  work"
   * and "DEEP WORK" are one tag. Only the separator characters are rewritten; the user's original
   * casing is kept separately as `displayName` for the chip label.
   */
  fun normalize(raw: String): String =
    raw
      .trim()
      .replace(Regex("\\s+"), " ")
      .take(MAX_TAG_LENGTH)
      .lowercase()

  /**
   * Splits a comma-separated entry box into normalized tags.
   *
   * Both commas and the Arabic/Persian comma are accepted because the app ships RTL locales and a
   * keyboard there produces U+060C.
   */
  fun parse(raw: String): List<String> =
    raw
      .split(',', '\u060C', '\n')
      .map(::normalize)
      .filter { it.isNotBlank() }
      .distinct()
      .take(MAX_TAGS_PER_BOOKMARK)
}
