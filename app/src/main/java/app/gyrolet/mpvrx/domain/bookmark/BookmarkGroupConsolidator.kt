/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.bookmark

import app.gyrolet.mpvrx.database.entities.BookmarkGroupEntity
import app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity
import java.util.Locale
import kotlin.math.roundToLong

internal data class JvidGroupConsolidationPlan(
  val canonicalCode: String,
  val winnerGroup: BookmarkGroupEntity,
  val losingGroupKeys: List<String>,
)

internal data class LocalTimestampMergePlan(
  val keeper: PlaybackBookmarkEntity,
  val targetPositionMs: Long,
  val targetEndPositionMs: Long?,
  val targetTitle: String,
  val targetPrimaryTag: String?,
  val mergedTags: List<String>,
  val losingBookmarks: List<PlaybackBookmarkEntity>,
)

internal object BookmarkGroupConsolidator {
  private val CANONICAL_JAVID_REGEX = Regex(
    """^(?:FC2-PPV-\d{5,8}|[A-Z]{2,8}-\d{2,6}(?:-[A-Z0-9]{1,3})?)$""",
  )

  fun extractCanonicalJavid(group: BookmarkGroupEntity): String? {
    val rawCandidates = listOf(group.presetCode, group.sourceLabel, group.groupKey)
    for (cand in rawCandidates) {
      val code = BookmarkMediaMatcher.extractJvidCode(cand)?.uppercase(Locale.ROOT) ?: continue
      if (CANONICAL_JAVID_REGEX.matches(code)) return code
    }
    return null
  }

  /**
   * Collapses duplicate groups sharing the exact same canonical JAVID (`ABC-123`) into one winner
   * preserving `oshash`, `stemKey`, `sourceUrl`, `sourceLabel`, and media dimensions.
   */
  fun planDuplicateJvidConsolidation(
    groups: List<BookmarkGroupEntity>,
    countsByKey: Map<String, Int>,
    onlyCode: String? = null,
  ): List<JvidGroupConsolidationPlan> {
    val targetFilter =
      onlyCode
        ?.let { BookmarkMediaMatcher.extractJvidCode(it)?.uppercase(Locale.ROOT) }
        ?.takeIf { CANONICAL_JAVID_REGEX.matches(it) }
    val buckets = LinkedHashMap<String, MutableList<BookmarkGroupEntity>>()
    groups.forEach { grp ->
      val canon = extractCanonicalJavid(grp) ?: return@forEach
      if (targetFilter != null && canon != targetFilter) return@forEach
      buckets.getOrPut(canon) { mutableListOf() }.add(grp)
    }

    val plans = ArrayList<JvidGroupConsolidationPlan>()
    buckets.forEach { (canon, bucket) ->
      if (bucket.size < 2 && bucket.all { it.presetCode == canon }) return@forEach
      val winner =
        bucket.maxWithOrNull(
          compareBy<BookmarkGroupEntity>(
            { if (it.groupKey.equals(canon, ignoreCase = true)) 1 else 0 },
            { countsByKey[it.groupKey] ?: 0 },
            { if (!it.oshash.isNullOrBlank()) 1 else 0 },
            { if (!it.sourceUrl.isNullOrBlank()) 1 else 0 },
            { it.updatedAt },
          ),
        ) ?: return@forEach

      var merged = winner.copy(presetCode = canon)
      val losing = ArrayList<String>()
      bucket.forEach { cand ->
        if (cand.groupKey == winner.groupKey) return@forEach
        losing.add(cand.groupKey)
        merged =
          merged.copy(
            oshash = merged.oshash?.takeIf { it.isNotBlank() } ?: cand.oshash?.takeIf { it.isNotBlank() },
            fingerprint =
              merged.fingerprint?.takeIf { it.isNotBlank() } ?: cand.fingerprint?.takeIf { it.isNotBlank() },
            stemKey = merged.stemKey?.takeIf { it.isNotBlank() } ?: cand.stemKey?.takeIf { it.isNotBlank() },
            durationSec = merged.durationSec.takeIf { it > 0 } ?: cand.durationSec,
            width = merged.width.takeIf { it > 0 } ?: cand.width,
            height = merged.height.takeIf { it > 0 } ?: cand.height,
            fileSizeBytes = merged.fileSizeBytes.takeIf { it > 0L } ?: cand.fileSizeBytes,
            sourceLabel =
              merged.sourceLabel.takeIf { it.isNotBlank() && !it.equals(canon, ignoreCase = true) }
                ?: cand.sourceLabel.takeIf { it.isNotBlank() }
                ?: canon,
            sourceUrl = merged.sourceUrl?.takeIf { it.isNotBlank() } ?: cand.sourceUrl?.takeIf { it.isNotBlank() },
            updatedAt = maxOf(merged.updatedAt, cand.updatedAt),
          )
      }
      plans.add(
        JvidGroupConsolidationPlan(
          canonicalCode = canon,
          winnerGroup = merged,
          losingGroupKeys = losing,
        ),
      )
    }
    return plans
  }

  /**
   * Groups bookmarks sorted chronologically using a rolling [windowSec] span and merges clusters
   * using [strategy] (`earliest`, `average`, or `weighted_average`).
   */
  fun planRollingWindowTimestampMerges(
    bookmarks: List<PlaybackBookmarkEntity>,
    tagsByBookmarkId: Map<Long, List<String>> = emptyMap(),
    windowSec: Double = 10.0,
    strategy: String = "earliest",
  ): List<LocalTimestampMergePlan> {
    if (bookmarks.size < 2) return emptyList()
    val sorted = bookmarks.sortedWith(compareBy<PlaybackBookmarkEntity>({ it.positionMs }, { it.id }))
    val windowMs = (windowSec.coerceIn(0.5, 60.0) * 1000.0).roundToLong()
    val cleanStrategy = strategy.trim().lowercase(Locale.ROOT)
    val plans = ArrayList<LocalTimestampMergePlan>()

    var i = 0
    while (i < sorted.size) {
      val anchorMs = sorted[i].positionMs
      val chunk = ArrayList<PlaybackBookmarkEntity>().apply { add(sorted[i]) }
      var j = i + 1
      while (j < sorted.size && (sorted[j].positionMs - anchorMs) <= windowMs) {
        chunk.add(sorted[j])
        j++
      }
      if (chunk.size >= 2) {
        val keeper =
          chunk.firstOrNull { !it.thumbnailPath.isNullOrBlank() }
            ?: chunk.firstOrNull { !it.syncUuid.isNullOrBlank() }
            ?: chunk.first()

        val targetPosMs =
          when (cleanStrategy) {
            "average" -> (chunk.sumOf { it.positionMs.toDouble() } / chunk.size).roundToLong()
            "weighted_average" -> {
              var num = 0.0
              var den = 0.0
              chunk.forEach { bm ->
                val tagCnt = tagsByBookmarkId[bm.id].orEmpty().size.coerceAtMost(4)
                val w = 1.0 + (if (!bm.thumbnailPath.isNullOrBlank()) 0.5 else 0.0) + tagCnt * 0.15
                num += bm.positionMs.toDouble() * w
                den += w
              }
              if (den > 0.0) (num / den).roundToLong() else chunk.minOf { it.positionMs }
            }
            else -> chunk.minOf { it.positionMs }
          }.coerceAtLeast(0L)

        val endMax = chunk.mapNotNull { it.endPositionMs }.maxOrNull()
        val finalEnd = endMax?.coerceAtLeast(targetPosMs)
        val unionTags =
          buildList {
            chunk.forEach { bm ->
              val rowTags = tagsByBookmarkId[bm.id].orEmpty()
              val pt = bm.primaryTag?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
              if (pt != null && pt !in this) add(pt)
              rowTags.forEach { t ->
                val nt = BookmarkTagRules.normalize(t)
                if (nt.isNotBlank() && nt !in this) add(nt)
              }
            }
          }.take(12)
        val bestTitle =
          chunk
            .map { it.title.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("Bookmark", ignoreCase = true) }
            ?: keeper.title

        plans.add(
          LocalTimestampMergePlan(
            keeper = keeper,
            targetPositionMs = targetPosMs,
            targetEndPositionMs = finalEnd,
            targetTitle = bestTitle.ifBlank { "Bookmark" },
            targetPrimaryTag = unionTags.firstOrNull() ?: keeper.primaryTag,
            mergedTags = unionTags,
            losingBookmarks = chunk.filter { it.id != keeper.id },
          ),
        )
      }
      i = j
    }
    return plans
  }
}
