/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.bookmark

import kotlin.math.abs

/**
 * Pure identity and content-matching helpers for surviving renames, folder moves,
 * Secure Folder hides/restores, and quality upgrades without colliding across unrelated files.
 */
object BookmarkMediaMatcher {
  private val KNOWN_EXTENSIONS =
    setOf(
      "mp4", "mkv", "webm", "mov", "avi", "m4v", "ts", "m2ts", "flv", "wmv",
      "mpg", "mpeg", "3gp", "ogv", "mp3", "flac", "m4a", "opus", "ogg", "aac",
      "wav", "wma",
    )

  private val RELEASE_OR_QUALITY_TOKENS =
    setOf(
      "4320p", "2160p", "1440p", "1080p", "1080i", "720p", "576p", "480p", "360p",
      "8k", "4k", "uhd", "fhd", "hd", "sd", "qhd",
      "x264", "x265", "h264", "h265", "avc", "hevc", "av1", "vp9", "vp8", "xvid",
      "10bit", "8bit", "12bit", "hi10p", "hi10",
      "hdr", "hdr10", "hdr10plus", "dv", "dovi", "dolby", "vision", "sdr", "hlg",
      "aac", "ac3", "eac3", "ddp", "dd", "dts", "dtshd", "truehd", "atmos", "flac", "opus", "mp3",
      "2ch", "6ch", "8ch", "5.1", "7.1", "2.0",
      "bluray", "bdrip", "brrip", "webrip", "webdl", "web", "hdtv", "dvdrip", "remux",
      "proper", "repack", "extended", "uncut", "unrated", "directors", "cut",
      "multi", "dual", "sub", "dub", "subbed", "dubbed", "vostfr",
      "nf", "amzn", "dsnp", "hmax", "atvp", "yts", "yify", "rarbg",
    )

  private val RESOLUTION_TOKEN = Regex("""^\d{3,4}x\d{3,4}$""")
  private val CRC32_HEX_TOKEN = Regex("""^[0-9a-f]{8}$""")
  private val DUPLICATE_COPY_SUFFIX = Regex("""(?:\s*\(\d{1,2}\)|\s+-\s*copy)$""", RegexOption.IGNORE_CASE)
  private val SEPARATORS = Regex("""[\s._\-\[\](){},;:+~]+""")

  fun exactFingerprint(
    fileSizeBytes: Long,
    width: Int,
    height: Int,
    durationSec: Int,
  ): String? {
    if (fileSizeBytes <= 0L || width <= 0 || height <= 0 || durationSec <= 0) return null
    return "fp:${fileSizeBytes}x${width}x${height}x${durationSec}"
  }

  fun extractFileName(rawPathOrUri: String): String {
    val clean = rawPathOrUri.substringBefore('?').substringBefore('#')
    val rawLeaf = clean.substringAfterLast('/').substringAfterLast('\\')
    val decoded = runCatching {
      if ('%' in rawLeaf) java.net.URLDecoder.decode(rawLeaf, "UTF-8") else rawLeaf
    }.getOrDefault(rawLeaf)
    return decoded.trim()
  }

  fun normalizedStem(rawNameOrUri: String): String? {
    var base = extractFileName(rawNameOrUri)
    if (base.isBlank()) return null

    val dotIdx = base.lastIndexOf('.')
    if (dotIdx > 0 && dotIdx < base.length - 1) {
      val ext = base.substring(dotIdx + 1).lowercase()
      if (ext in KNOWN_EXTENSIONS) {
        base = base.substring(0, dotIdx)
      }
    }

    base = base.replace(DUPLICATE_COPY_SUFFIX, "").trim()
    if (base.isBlank()) return null

    // Collapse dot-separated codec/version patterns like H.264 / H.265 / DD5.1 / WEB-DL before splitting
    val collapsed =
      base
        .replace(Regex("""\bH\.?(264|265)\b""", RegexOption.IGNORE_CASE), "h$1")
        .replace(Regex("""\bWEB[-.]?DL\b""", RegexOption.IGNORE_CASE), "webdl")
        .replace(Regex("""\bBlu[-.]?Ray\b""", RegexOption.IGNORE_CASE), "bluray")
        .replace(Regex("""\bDDP?\s*5\.1\b""", RegexOption.IGNORE_CASE), "ddp")
        .replace(Regex("""\b(5\.1|7\.1|2\.0)\b"""), "")

    val tokens =
      collapsed
        .lowercase()
        .split(SEPARATORS)
        .filter { it.isNotBlank() }
        .filterNot { token ->
          token in RELEASE_OR_QUALITY_TOKENS ||
            RESOLUTION_TOKEN.matches(token) ||
            CRC32_HEX_TOKEN.matches(token)
        }

    if (tokens.isEmpty()) return null
    val joined = tokens.joinToString(" ")
    if (joined.count { it.isLetterOrDigit() } < 3) return null
    return joined
  }

  fun isUpgradeMatch(
    groupStemKey: String?,
    groupDurationSec: Int,
    candidateStemKey: String?,
    candidateDurationSec: Int,
  ): Boolean {
    if (groupStemKey.isNullOrBlank() || candidateStemKey.isNullOrBlank()) return false
    if (groupStemKey != candidateStemKey) return false
    if (groupDurationSec <= 0 || candidateDurationSec <= 0) return false
    return abs(groupDurationSec - candidateDurationSec) <= 2
  }

  fun normalizePresetCode(raw: String): String = raw.trim().replace(Regex("""\s+"""), " ")

  fun displaySourceLabel(
    title: String?,
    pathOrUri: String?,
  ): String {
    val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
    val leaf = pathOrUri?.let(::extractFileName)?.takeIf { it.isNotBlank() }
    return cleanTitle ?: leaf ?: "Video"
  }
}
