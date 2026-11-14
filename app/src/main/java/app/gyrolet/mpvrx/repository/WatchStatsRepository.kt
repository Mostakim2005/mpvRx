package app.gyrolet.mpvrx.repository

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import app.gyrolet.mpvrx.domain.secure.SecureMediaRegistry
import app.gyrolet.mpvrx.ui.player.DeclaredPlaybackMediaKind
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.declaredMediaKind
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WatchStatsSnapshot(
  val totalSeconds: Long = 0,
  val audioSeconds: Long = 0,
  val videoSeconds: Long = 0,
  val sessions: Long = 0,
  val days: Map<String, Long> = emptyMap(),
  val media: Map<String, WatchMediaStats> = emptyMap(),
)

@Serializable
data class WatchMediaStats(
  val title: String,
  val artworkUri: String? = null,
  /** Original playable identity used to regenerate local/video thumbnails. */
  val sourceUri: String? = null,
  val seconds: Long = 0,
  val sessions: Long = 0,
  val isAudio: Boolean = false,
  val lastWatchedAt: Long = 0,
)

/**
 * Watch statistics for the public library, plus a quarantined store for Secure Folder playback.
 *
 * Two files back this repository:
 * - `watch_stats.json` — everything the normal library plays. Secure entries are stripped on read,
 *   so statistics written before the sandbox existed stop leaking private titles.
 * - `watch_stats_secure.json` — only Secure Folder playback. It is read exclusively through
 *   [snapshot]`(includeSecure = true)`, which only the stealth-unlocked Secret Statistics view asks
 *   for, so private files never reach Profile "Most watched" or the weekly totals.
 */
class WatchStatsRepository(context: Context) {
  private val publicFile = AtomicFile(File(context.filesDir, STATS_FILE_NAME))
  private val secureFile = AtomicFile(File(context.filesDir, SECURE_STATS_FILE_NAME))
  private val json = Json { ignoreUnknownKeys = true }
  private val mutex = Mutex()
  private val _resetVersion = MutableStateFlow(0L)
  val resetVersion = _resetVersion.asStateFlow()
  private val _revision = MutableStateFlow(0L)
  val revision = _revision.asStateFlow()

  // Only this repository writes the files, so the decoded copies stay current. Playback records
  // every 15 s; re-reading and decoding the whole file each time was steady garbage during playback.
  private var cachedPublic: WatchStatsSnapshot? = null
  private var cachedSecure: WatchStatsSnapshot? = null

  suspend fun recordSession(item: PlaybackItem) {
    val secure = isProtected(item)
    update(secure) { current ->
      val key = item.stableId.ifBlank { item.originalUri }
      val existing = current.media[key]
      current.copy(
        sessions = current.sessions + 1,
        media = current.media + (key to existingStats(item, existing).copy(
          sessions = (existing?.sessions ?: 0) + 1,
          lastWatchedAt = System.currentTimeMillis(),
        )),
      )
    }
  }

  suspend fun recordPlayback(item: PlaybackItem, seconds: Long) {
    if (seconds <= 0) return
    val secure = isProtected(item)
    update(secure) { current ->
      val isAudio = item.declaredMediaKind() == DeclaredPlaybackMediaKind.AUDIO
      val key = item.stableId.ifBlank { item.originalUri }
      val existing = current.media[key]
      val day = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate().toString()
      current.copy(
        totalSeconds = current.totalSeconds + seconds,
        audioSeconds = current.audioSeconds + seconds.takeIf { isAudio }.orZero(),
        videoSeconds = current.videoSeconds + seconds.takeUnless { isAudio }.orZero(),
        days = (current.days + (day to ((current.days[day] ?: 0) + seconds))).trimDays(),
        media = (current.media + (key to existingStats(item, existing).copy(
          seconds = (existing?.seconds ?: 0) + seconds,
          lastWatchedAt = System.currentTimeMillis(),
        ))).trimMedia(),
      )
    }
  }

  /**
   * Statistics for one mode: the public library by default, or the quarantined Secure Folder store
   * when [includeSecure] is true (Secret Mode only).
   */
  suspend fun snapshot(includeSecure: Boolean = false): WatchStatsSnapshot =
    mutex.withLock { withContext(Dispatchers.IO) { if (includeSecure) readCachedSecure() else readCachedPublic() } }

  suspend fun clear() = mutex.withLock {
    withContext(Dispatchers.IO) {
      publicFile.delete()
      secureFile.delete()
    }
    cachedPublic = WatchStatsSnapshot()
    cachedSecure = WatchStatsSnapshot()
    _resetVersion.value++
    _revision.value++
  }

  private suspend fun update(
    secure: Boolean,
    transform: (WatchStatsSnapshot) -> WatchStatsSnapshot,
  ) = mutex.withLock {
    withContext(Dispatchers.IO) {
      val updated = transform(if (secure) readCachedSecure() else readCachedPublic())
      // Kept even if the write fails, so the next update persists it instead of losing it.
      if (secure) cachedSecure = updated else cachedPublic = updated
      val file = if (secure) secureFile else publicFile
      val output = file.startWrite()
      try {
        output.write(json.encodeToString(updated).toByteArray(Charsets.UTF_8))
        file.finishWrite(output)
        _revision.value++
      } catch (cancelled: CancellationException) {
        file.failWrite(output)
        throw cancelled
      } catch (error: Exception) {
        file.failWrite(output)
        Log.e(TAG, "Unable to save watch statistics", error)
      }
    }
  }

  private fun readCachedPublic(): WatchStatsSnapshot =
    cachedPublic ?: read(publicFile).sanitized().also { cachedPublic = it }

  private fun readCachedSecure(): WatchStatsSnapshot = cachedSecure ?: read(secureFile).also { cachedSecure = it }

  private fun read(file: AtomicFile): WatchStatsSnapshot =
    if (!file.baseFile.exists() && !File("${file.baseFile.path}.bak").exists()) {
      WatchStatsSnapshot()
    } else {
      runCatching {
        file.openRead().bufferedReader().use { json.decodeFromString<WatchStatsSnapshot>(it.readText()) }
      }.onFailure { error -> Log.e(TAG, "Unable to read watch statistics", error) }
        .getOrDefault(WatchStatsSnapshot())
    }

  /** Drops anything the Secure Folder owns, including rows written before the sandbox existed. */
  private fun WatchStatsSnapshot.sanitized(): WatchStatsSnapshot {
    if (media.isEmpty()) return this
    val kept = media.filterKeys { key ->
      val protectedKey = runCatching { SecureMediaRegistry.isProtectedReference(key) }.getOrDefault(false)
      val protectedSource = runCatching { SecureMediaRegistry.isProtectedReference(media[key]?.sourceUri) }.getOrDefault(false)
      !protectedKey && !protectedSource
    }
    if (kept.size == media.size) return this
    val keptSeconds = kept.values.sumOf { it.seconds }
    val keptSessions = kept.values.sumOf { it.sessions }
    val keptAudioSeconds = kept.values.filter { it.isAudio }.sumOf { it.seconds }
    val removedVideoSeconds =
      media.filterKeys { it !in kept }.values.filterNot { it.isAudio }.sumOf { it.seconds }
    return copy(
      media = kept,
      sessions = keptSessions.coerceAtMost(sessions),
      videoSeconds = (videoSeconds - removedVideoSeconds).coerceIn(0, videoSeconds),
      audioSeconds = keptAudioSeconds.coerceAtMost(audioSeconds),
      totalSeconds = (keptSeconds).coerceIn(0, totalSeconds),
    )
  }

  private fun isProtected(item: PlaybackItem): Boolean =
    runCatching {
      SecureMediaRegistry.isProtectedReference(item.stableId) ||
        SecureMediaRegistry.isProtectedReference(item.originalUri)
    }.getOrDefault(false)

  private fun existingStats(item: PlaybackItem, existing: WatchMediaStats?): WatchMediaStats =
    WatchMediaStats(
      title = item.title?.takeIf(String::isNotBlank) ?: existing?.title ?: item.originalUri.substringAfterLast('/'),
      artworkUri = item.artworkUri ?: existing?.artworkUri,
      sourceUri = item.originalUri.takeIf(String::isNotBlank) ?: existing?.sourceUri,
      seconds = existing?.seconds ?: 0,
      sessions = existing?.sessions ?: 0,
      isAudio = item.declaredMediaKind() == DeclaredPlaybackMediaKind.AUDIO,
      lastWatchedAt = existing?.lastWatchedAt ?: 0,
    )

  private fun Long?.orZero(): Long = this ?: 0
  private fun Map<String, Long>.trimDays(): Map<String, Long> = entries.sortedByDescending { it.key }.take(90).associate { it.toPair() }
  private fun Map<String, WatchMediaStats>.trimMedia(): Map<String, WatchMediaStats> =
    entries.sortedByDescending { it.value.lastWatchedAt }.take(250).associate { it.toPair() }

  private companion object {
    const val TAG = "WatchStatsRepository"
    const val STATS_FILE_NAME = "watch_stats.json"
    const val SECURE_STATS_FILE_NAME = "watch_stats_secure.json"
  }
}
