/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricAnchorCalibratorTest {

  @Test
  fun calibrateShiftsAllLinesUniformlyWithSinglePointAnchor() {
    val lines = listOf(
      SyncedLine(time = 15_500, line = "Let's Go"),
      SyncedLine(time = 22_000, line = "Midnight calls"),
      SyncedLine(time = 30_000, line = "Electric dreams"),
    )

    // User heard "Let's Go" at 18.20s (18_200ms) instead of 15.50s (15_500ms)
    // Offset should be +2700ms
    val result = LyricAnchorCalibrator.calibrate(
      lines = lines,
      selectedLineOriginalTimeMs = 15_500,
      targetPlayheadMs = 18_200,
    )

    assertEquals(2_700L, result.offsetMs)
    assertEquals(18_200, result.calibratedLines[0].time)
    assertEquals(24_700, result.calibratedLines[1].time)
    assertEquals(32_700, result.calibratedLines[2].time)

    assertTrue(result.lrcContent.contains("[00:18.20] Let's Go"))
    assertTrue(result.lrcContent.contains("[00:24.70] Midnight calls"))
    assertTrue(result.lrcContent.contains("[00:32.70] Electric dreams"))
  }

  @Test
  fun calibrateClampsNegativeOffsetsCleanly() {
    val lines = listOf(
      SyncedLine(time = 2_000, line = "Early line"),
      SyncedLine(time = 10_000, line = "Second line"),
    )

    // Negative offset of -3000ms clamps first line to 0ms
    val result = LyricAnchorCalibrator.calibrate(
      lines = lines,
      selectedLineOriginalTimeMs = 10_000,
      targetPlayheadMs = 7_000,
    )

    assertEquals(-3_000L, result.offsetMs)
    assertEquals(0, result.calibratedLines[0].time)
    assertEquals(7_000, result.calibratedLines[1].time)
  }

  @Test
  fun formatLrcTimestampMatchesStandardLrcSpec() {
    assertEquals("[00:00.00]", LyricAnchorCalibrator.formatLrcTimestamp(0))
    assertEquals("[00:15.50]", LyricAnchorCalibrator.formatLrcTimestamp(15_500))
    assertEquals("[01:05.43]", LyricAnchorCalibrator.formatLrcTimestamp(65_430))
  }
}
