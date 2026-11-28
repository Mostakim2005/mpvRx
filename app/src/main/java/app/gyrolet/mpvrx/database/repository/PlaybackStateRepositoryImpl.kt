/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.repository

import app.gyrolet.mpvrx.database.MpvRxDatabase
import app.gyrolet.mpvrx.database.SecureVaultDatabase
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.database.entities.SecurePlaybackStateEntity
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.domain.secure.SecureFolderSession
import app.gyrolet.mpvrx.domain.secure.SecureMediaRegistry
import app.gyrolet.mpvrx.domain.secure.VaultCrypto
import app.gyrolet.mpvrx.domain.storage.DurableStorageManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackStateRepositoryImpl(
  private val database: MpvRxDatabase,
  private val secureDatabase: SecureVaultDatabase,
  private val decoyDatabase: SecureVaultDatabase? = null,
) : PlaybackStateRepository {
  // Library screens can ask for the complete playback-state set several times while navigating or
  // refreshing. Keep one process-local snapshot after the first unavoidable Room load and maintain
  // it incrementally on writes instead of allocating/querying the full table on every request.
  private val stateCache = ConcurrentHashMap<String, PlaybackStateEntity>()
  private val secureStateCache = ConcurrentHashMap<String, PlaybackStateEntity>()
  private val decoyStateCache = ConcurrentHashMap<String, PlaybackStateEntity>()
  private val allStatesLoaded = AtomicBoolean(false)
  private val fullLoadMutex = Mutex()

  init {
    SecureFolderSession.registerOnLockListener {
      secureStateCache.clear()
      decoyStateCache.clear()
    }
  }

  private fun isSecureKey(mediaTitle: String): Boolean =
    SecureMediaRegistry.isProtectedReference(mediaTitle) || DurableStorageManager.isVaultPath(mediaTitle)

  private fun isDecoyKey(mediaTitle: String): Boolean =
    DurableStorageManager.isDecoyVaultPath(mediaTitle) ||
      (SecureFolderSession.isDecoyUnlocked && isSecureKey(mediaTitle) && !DurableStorageManager.isPrimaryVaultPath(mediaTitle))

  private fun targetVaultDb(mediaTitle: String): SecureVaultDatabase =
    if (isDecoyKey(mediaTitle) && decoyDatabase != null) decoyDatabase else secureDatabase

  private fun targetVaultCache(mediaTitle: String): ConcurrentHashMap<String, PlaybackStateEntity> =
    if (isDecoyKey(mediaTitle)) decoyStateCache else secureStateCache

  private fun encryptField(value: String): String =
    if (value.isEmpty()) "" else runCatching { VaultCrypto.encryptString(value) }.getOrDefault(value)

  private fun decryptField(value: String): String =
    if (value.isEmpty()) "" else runCatching { VaultCrypto.decryptString(value) }.getOrDefault(value)

  private fun PlaybackStateEntity.toSecureEntity(
    mediaKey: String,
    displayLabel: String = "",
  ): SecurePlaybackStateEntity =
    SecurePlaybackStateEntity(
      mediaKey = mediaKey,
      lastPosition = lastPosition,
      playbackSpeed = playbackSpeed,
      videoZoom = videoZoom,
      sid = sid,
      secondarySid = secondarySid,
      subDelay = subDelay,
      subSpeed = subSpeed,
      aid = aid,
      audioDelay = audioDelay,
      timeRemaining = timeRemaining,
      externalSubtitles = encryptField(externalSubtitles),
      hasBeenWatched = hasBeenWatched,
      newLabelOverride = newLabelOverride,
      displayLabel = encryptField(displayLabel.ifBlank { mediaTitle.substringAfterLast('/') }),
      updatedAt = System.currentTimeMillis(),
    )

  private fun SecurePlaybackStateEntity.toDomain(mediaTitle: String): PlaybackStateEntity =
    PlaybackStateEntity(
      mediaTitle = mediaTitle,
      lastPosition = lastPosition,
      playbackSpeed = playbackSpeed,
      videoZoom = videoZoom,
      sid = sid,
      secondarySid = secondarySid,
      subDelay = subDelay,
      subSpeed = subSpeed,
      aid = aid,
      audioDelay = audioDelay,
      timeRemaining = timeRemaining,
      externalSubtitles = decryptField(externalSubtitles),
      hasBeenWatched = hasBeenWatched,
      newLabelOverride = newLabelOverride,
    )

  override suspend fun upsert(playbackState: PlaybackStateEntity) {
    if (isSecureKey(playbackState.mediaTitle)) {
      val db = targetVaultDb(playbackState.mediaTitle)
      val hashKey = VaultCrypto.hmacKey(playbackState.mediaTitle)
      val existingLabel =
        db.securePlaybackStateDao().getByMediaKey(hashKey)?.displayLabel?.let(::decryptField).orEmpty()
      db.securePlaybackStateDao().upsert(playbackState.toSecureEntity(hashKey, existingLabel))
      if (SecureFolderSession.isUnlocked) {
        targetVaultCache(playbackState.mediaTitle)[playbackState.mediaTitle] = playbackState
      }
      return
    }
    fullLoadMutex.withLock {
      database.videoDataDao().upsert(playbackState)
      stateCache[playbackState.mediaTitle] = playbackState
    }
  }

  override suspend fun getVideoDataByTitle(mediaTitle: String): PlaybackStateEntity? {
    if (isSecureKey(mediaTitle)) {
      if (!SecureFolderSession.isUnlocked) return null
      val isDecoy = isDecoyKey(mediaTitle)
      if (SecureFolderSession.isDecoyUnlocked != isDecoy) return null
      val cache = targetVaultCache(mediaTitle)
      cache[mediaTitle]?.let { return it }
      val hashKey = VaultCrypto.hmacKey(mediaTitle)
      return targetVaultDb(mediaTitle).securePlaybackStateDao().getByMediaKey(hashKey)?.toDomain(mediaTitle)?.also { state ->
        if (SecureFolderSession.isUnlocked) {
          cache[mediaTitle] = state
        }
      }
    }
    stateCache[mediaTitle]?.let { return it }
    // Every write goes through this repository, so once the full table is cached a miss is
    // authoritative. Never-played media would otherwise cost one Room query per lookup.
    if (allStatesLoaded.get()) return null
    return database.videoDataDao().getVideoDataByTitle(mediaTitle)?.also { state ->
      stateCache[state.mediaTitle] = state
    }
  }

  override suspend fun getAllPlaybackStates(): List<PlaybackStateEntity> {
    if (allStatesLoaded.get()) return stateCache.values.toList()

    return fullLoadMutex.withLock {
      if (!allStatesLoaded.get()) {
        val loaded = database.videoDataDao().getAllPlaybackStates()
        stateCache.clear()
        loaded.forEach { state -> stateCache[state.mediaTitle] = state }
        allStatesLoaded.set(true)
      }
      stateCache.values.toList()
    }
  }

  override suspend fun clearAllPlaybackStates() {
    fullLoadMutex.withLock {
      database.videoDataDao().clearAllPlaybackStates()
      stateCache.clear()
      allStatesLoaded.set(true)
    }
  }

  override suspend fun deleteByTitle(mediaTitle: String) {
    if (isSecureKey(mediaTitle)) {
      targetVaultDb(mediaTitle).securePlaybackStateDao().deleteByMediaKey(VaultCrypto.hmacKey(mediaTitle))
      targetVaultCache(mediaTitle).remove(mediaTitle)
      return
    }
    fullLoadMutex.withLock {
      database.videoDataDao().deleteByTitle(mediaTitle)
      stateCache.remove(mediaTitle)
    }
  }

  override suspend fun updateMediaTitle(
    oldTitle: String,
    newTitle: String,
  ) {
    val oldSecure = isSecureKey(oldTitle)
    val newSecure = isSecureKey(newTitle)
    if (oldSecure != newSecure) {
      migrateState(listOf(oldTitle), newTitle, toSecure = newSecure)
      return
    }
    if (oldSecure) {
      val secDao = targetVaultDb(oldTitle).securePlaybackStateDao()
      val existing = secDao.getByMediaKey(VaultCrypto.hmacKey(oldTitle)) ?: return
      secDao.upsert(existing.copy(mediaKey = VaultCrypto.hmacKey(newTitle), updatedAt = System.currentTimeMillis()))
      secDao.deleteByMediaKey(VaultCrypto.hmacKey(oldTitle))
      val cache = targetVaultCache(oldTitle)
      cache.remove(oldTitle)?.let { state ->
        if (SecureFolderSession.isUnlocked) {
          cache[newTitle] = state.copy(mediaTitle = newTitle)
        }
      }
      return
    }
    fullLoadMutex.withLock {
      database.videoDataDao().updateMediaTitle(oldTitle, newTitle)
      stateCache.remove(oldTitle)?.let { state ->
        stateCache[newTitle] = state.copy(mediaTitle = newTitle)
      }
    }
  }

  override suspend fun migrateState(
    oldMediaTitles: List<String>,
    newMediaTitle: String,
    toSecure: Boolean,
    displayLabel: String,
  ) {
    val cleanOld = oldMediaTitles.filter { it.isNotBlank() }.distinct()
    val secDao = targetVaultDb(newMediaTitle).securePlaybackStateDao()
    val pubDao = database.videoDataDao()
    val cache = targetVaultCache(newMediaTitle)
    if (toSecure) {
      val sourceState =
        cleanOld.firstNotNullOfOrNull { key ->
          pubDao.getVideoDataByTitle(key)
        }
      if (sourceState != null) {
        val targetHash = VaultCrypto.hmacKey(newMediaTitle)
        secDao.upsert(sourceState.toSecureEntity(targetHash, displayLabel))
        if (SecureFolderSession.isUnlocked) {
          cache[newMediaTitle] = sourceState.copy(mediaTitle = newMediaTitle)
        }
      }
      cleanOld.forEach { key ->
        pubDao.deleteByTitle(key)
        stateCache.remove(key)
      }
    } else {
      val sourceSecure =
        cleanOld.firstNotNullOfOrNull { key ->
          secDao.getByMediaKey(VaultCrypto.hmacKey(key))
        }
      if (sourceSecure != null) {
        val domainState = sourceSecure.toDomain(newMediaTitle)
        pubDao.upsert(domainState)
        stateCache[newMediaTitle] = domainState
      }
      cleanOld.forEach { key ->
        secDao.deleteByMediaKey(VaultCrypto.hmacKey(key))
        cache.remove(key)
      }
    }
  }

  override suspend fun getAllSecurePlaybackStates(): List<Pair<SecurePlaybackStateEntity, String>> =
    secureDatabase.securePlaybackStateDao().getAll().map { row ->
      row to decryptField(row.displayLabel)
    }

  override suspend fun deleteSecureStatesByKeys(mediaKeys: List<String>) {
    if (mediaKeys.isEmpty()) return
    mediaKeys.chunked(900).forEach { chunk ->
      secureDatabase.securePlaybackStateDao().deleteByMediaKeys(chunk)
    }
    secureStateCache.clear()
  }

  override suspend fun clearSecurePlaybackStates(decoyOnly: Boolean) {
    if (decoyOnly) {
      decoyDatabase?.securePlaybackStateDao()?.clearAll()
      decoyStateCache.clear()
    } else {
      secureDatabase.securePlaybackStateDao().clearAll()
      secureStateCache.clear()
    }
  }
}
