/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.text

import java.text.Normalizer
import java.util.Locale

/**
 * Unicode diacritic, full-width punctuation, and zero-width character normalizer inspired by
 * Namida's `string_clean_utils` for reliable local search and lyrics provider matching.
 */
object TextNormalizer {
  private val COMBINING_MARKS = Regex("""\p{InCombiningDiacriticalMarks}+""")
  private val ZERO_WIDTH_CHARS = Regex("""[\u200B-\u200D\uFEFF]""")
  private val COLLAPSE_WHITESPACE = Regex("""\s+""")

  /** Normalizes [input] to NFKD, strips combining marks and zero-width chars, and collapses whitespace. */
  fun normalizeForSearch(input: String): String {
    if (input.isBlank()) return ""
    val strippedZeroWidth = ZERO_WIDTH_CHARS.replace(input, "")
    val decomposed = Normalizer.normalize(strippedZeroWidth, Normalizer.Form.NFKD)
    val noMarks = COMBINING_MARKS.replace(decomposed, "")
    return COLLAPSE_WHITESPACE.replace(noMarks, " ").trim().lowercase(Locale.ROOT)
  }
}
