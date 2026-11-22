/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.text

import kotlin.test.Test
import kotlin.test.assertEquals

class TextNormalizerTest {
  @Test
  fun stripsDiacriticsZeroWidthAndExtraWhitespace() {
    assertEquals("cafe del mar", TextNormalizer.normalizeForSearch("  Café​  dél   Mar  "))
    assertEquals("uber", TextNormalizer.normalizeForSearch("Über"))
    assertEquals("", TextNormalizer.normalizeForSearch("   "))
  }
}
