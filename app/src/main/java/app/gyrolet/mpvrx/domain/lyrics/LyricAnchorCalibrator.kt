/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.lyrics

import java.util.Locale

/**
 * 1-Point Lyric Anchor Calibration Engine.
 * Allows fixing timing offsets across an entire song by anchoring a single lyric line
 * to the active music playhead time.
 */
object LyricAnchorCalibrator {

  data class CalibrationResult(
    val offsetMs: Long,
    val calibratedLines: List<SyncedLine>,
    val lrcContent: String,
  )

  /**
   * Calculates the global delta between [targetPlayheadMs] and [selectedLineOriginalTimeMs],
   * uniformly shifts all [lines], and serializes the output into standard LRC format.
   */
  fun calibrate(
    lines: List<SyncedLine>,
    selectedLineOriginalTimeMs: Int,
    targetPlayheadMs: Long,
  ): CalibrationResult {
    val offsetMs = targetPlayheadMs - selectedLineOriginalTimeMs.toLong()

    val calibratedLines = lines.map { line ->
      val newTime = (line.time + offsetMs).coerceAtLeast(0L).toInt()
      val newEndTime = line.endTime?.let { (it + offsetMs).coerceAtLeast(0L).toInt() }
      val newWords = line.words?.map { word ->
        val wordTime = (word.time + offsetMs).coerceAtLeast(0L).toInt()
        word.copy(time = wordTime)
      }
      line.copy(
        time = newTime,
        endTime = newEndTime,
        words = newWords,
      )
    }

    val lrcContent = toLrcString(calibratedLines)

    return CalibrationResult(
      offsetMs = offsetMs,
      calibratedLines = calibratedLines,
      lrcContent = lrcContent,
    )
  }

  /**
   * Formats a millisecond timestamp to [mm:ss.xx] standard LRC notation.
   */
  fun formatLrcTimestamp(timeMs: Int): String {
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hundredths = (timeMs % 1000) / 10
    return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, hundredths)
  }

  /**
   * Serializes a list of [SyncedLine] into a valid LRC document string.
   */
  fun toLrcString(lines: List<SyncedLine>): String {
    val builder = StringBuilder()
    for (line in lines) {
      if (line.line.isBlank()) continue
      builder.append(formatLrcTimestamp(line.time))
      builder.append(" ")
      builder.append(line.line.trim())
      builder.append("\n")
    }
    return builder.toString()
  }
}
