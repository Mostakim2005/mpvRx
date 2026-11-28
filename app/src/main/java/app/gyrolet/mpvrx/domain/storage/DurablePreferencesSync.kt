/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import android.util.Log
import app.gyrolet.mpvrx.domain.secure.VaultCrypto
import app.gyrolet.mpvrx.preferences.preference.defaultSharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Mirrors shared non-personal UI/player settings to `.store/settings.mvx` and Secure Folder config
 * (`secure_folder_*`) to `.vault/v_cfg.mvx`, encrypted with AES-128-CTR (`VaultCrypto`).
 *
 * Ephemeral / per-install / sensitive non-vault state is never mirrored here.
 */
object DurablePreferencesSync {
  private const val TAG = "DurablePreferencesSync"
  private const val DEBOUNCE_MS = 1_500L
  private const val SECURE_PREFIX = "secure_folder_"
  private const val BOOKMARK_SYNC_PREFIX = "bookmark_sync_"

  private val EXCLUDED_EXACT = setOf(
    "onboarding_completed",
    "measure_enabled",
    "mpv_conf_storage_uri",
  )

  private val EXCLUDED_PREFIXES = listOf(
    "auto_backup_",
    "recent_",
    "last_played_",
    "picker_path",
    "last_open_",
    "crash_",
    "video_metadata_",
    "search_history",
    "ytdl_history",
    "network_",
    "jellyfin_",
    "navidrome_",
    "audiobookshelf_",
    "syncplay_",
    "bookmark_sync_",
  )

  private enum class Kind { STRING, BOOLEAN, INT, LONG, FLOAT, STRING_SET }

  @Serializable
  private data class Entry(
    val kind: String,
    val value: String? = null,
    val values: List<String> = emptyList(),
  )

  @Serializable
  private data class Snapshot(
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val entries: Map<String, Entry> = emptyMap(),
  )

  private val json = Json { ignoreUnknownKeys = true }
  private val writeLock = Any()

  @Volatile private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

  @Volatile private var pendingFlushJob: Job? = null

  @Volatile private var isApplyingRestore = false

  fun isVaultKey(key: String): Boolean =
    key.startsWith(SECURE_PREFIX) || key.startsWith(BOOKMARK_SYNC_PREFIX)

  fun shouldMirrorPublicKey(key: String): Boolean {
    if (isVaultKey(key)) return false
    if (key in EXCLUDED_EXACT) return false
    return EXCLUDED_PREFIXES.none { prefix -> key.startsWith(prefix) }
  }

  /**
   * Restores durable preferences from `.store/settings.mvx` and `.vault/v_cfg.mvx` into Android
   * `SharedPreferences` synchronously using `commit()`.
   */
  fun restoreIfNeeded(
    context: Context,
    force: Boolean = false,
  ): Boolean {
    if (DurableStorageManager.durableRoot(context) == null) return false
    VaultCrypto.ensureInitialized(context)

    val settingsSnap = readSnapshot(DurableStorageManager.settingsMirrorFile(context))
    val vaultSnap = readSnapshot(DurableStorageManager.secureConfigFile(context))
    if (settingsSnap == null && vaultSnap == null) return false

    val prefs = context.applicationContext.defaultSharedPreferences()
    val editor = prefs.edit()
    var modified = false

    isApplyingRestore = true
    try {
      settingsSnap?.entries?.forEach { (key, entry) ->
        if (shouldMirrorPublicKey(key) && (force || !prefs.contains(key))) {
          if (applyEntry(editor, key, entry)) modified = true
        }
      }
      vaultSnap?.entries?.forEach { (key, entry) ->
        val pinUnset =
          key == "secure_folder_pin_hash" && prefs.getString(key, "").isNullOrBlank()
        if (isVaultKey(key) && (force || !prefs.contains(key) || pinUnset)) {
          if (applyEntry(editor, key, entry)) modified = true
        }
      }
      if (modified) {
        editor.commit()
      }
    } finally {
      isApplyingRestore = false
    }
    return modified
  }

  /** Registers a strong-referenced listener so changes sync incrementally to `Documents/mpvRx/`. */
  fun startObserving(
    context: Context,
    scope: CoroutineScope,
  ) {
    val appContext = context.applicationContext
    val prefs = appContext.defaultSharedPreferences()
    synchronized(writeLock) {
      listener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
      val newListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
          if (isApplyingRestore || key == null) return@OnSharedPreferenceChangeListener
          if (!isVaultKey(key) && !shouldMirrorPublicKey(key)) return@OnSharedPreferenceChangeListener
          synchronized(writeLock) {
            pendingFlushJob?.cancel()
            pendingFlushJob =
              scope.launch(Dispatchers.IO) {
                delay(DEBOUNCE_MS)
                flushNow(appContext)
              }
          }
        }
      listener = newListener
      prefs.registerOnSharedPreferenceChangeListener(newListener)
    }
  }

  fun flushNow(context: Context) {
    val appContext = context.applicationContext
    if (DurableStorageManager.durableRoot(appContext) == null) return
    VaultCrypto.ensureInitialized(appContext)

    synchronized(writeLock) {
      val allPrefs = appContext.defaultSharedPreferences().all.orEmpty()
      val publicEntries = LinkedHashMap<String, Entry>()
      val vaultEntries = LinkedHashMap<String, Entry>()

      allPrefs.forEach { (key, rawValue) ->
        val encoded = encodeEntry(rawValue) ?: return@forEach
        when {
          isVaultKey(key) -> vaultEntries[key] = encoded
          shouldMirrorPublicKey(key) -> publicEntries[key] = encoded
        }
      }

      val publicFile = DurableStorageManager.settingsMirrorFile(appContext)
      if (publicEntries.isNotEmpty()) {
        writeSnapshot(publicFile, Snapshot(entries = publicEntries))
      } else if (publicFile.isFile) {
        runCatching { publicFile.delete() }
      }
      val vaultFile = DurableStorageManager.secureConfigFile(appContext)
      if (vaultEntries.isNotEmpty()) {
        writeSnapshot(vaultFile, Snapshot(entries = vaultEntries))
      } else if (vaultFile.isFile) {
        runCatching { vaultFile.delete() }
      }
    }
  }

  fun deleteSecureConfig(context: Context) {
    synchronized(writeLock) {
      val target = DurableStorageManager.secureConfigFile(context.applicationContext)
      runCatching { target.delete() }
      runCatching { File("${target.path}.bak").delete() }
    }
  }

  private fun encodeEntry(raw: Any?): Entry? =
    when (raw) {
      is String -> Entry(kind = Kind.STRING.name, value = raw)
      is Boolean -> Entry(kind = Kind.BOOLEAN.name, value = raw.toString())
      is Int -> Entry(kind = Kind.INT.name, value = raw.toString())
      is Long -> Entry(kind = Kind.LONG.name, value = raw.toString())
      is Float -> Entry(kind = Kind.FLOAT.name, value = raw.toString())
      is Set<*> -> Entry(kind = Kind.STRING_SET.name, values = raw.filterIsInstance<String>())
      else -> null
    }

  private fun applyEntry(
    editor: SharedPreferences.Editor,
    key: String,
    entry: Entry,
  ): Boolean {
    return when (runCatching { Kind.valueOf(entry.kind) }.getOrNull()) {
      Kind.STRING -> {
        editor.putString(key, entry.value.orEmpty())
        true
      }
      Kind.BOOLEAN -> {
        val v = entry.value?.toBooleanStrictOrNull() ?: return false
        editor.putBoolean(key, v)
        true
      }
      Kind.INT -> {
        val v = entry.value?.toIntOrNull() ?: return false
        editor.putInt(key, v)
        true
      }
      Kind.LONG -> {
        val v = entry.value?.toLongOrNull() ?: return false
        editor.putLong(key, v)
        true
      }
      Kind.FLOAT -> {
        val v = entry.value?.toFloatOrNull() ?: return false
        editor.putFloat(key, v)
        true
      }
      Kind.STRING_SET -> {
        editor.putStringSet(key, entry.values.toSet())
        true
      }
      null -> false
    }
  }

  private fun readSnapshot(file: File): Snapshot? {
    val atomic = AtomicFile(file)
    if (!atomic.baseFile.exists() && !File("${atomic.baseFile.path}.bak").exists()) return null
    return runCatching {
      val raw = atomic.openRead().use { it.readBytes() }
      val plain = VaultCrypto.decryptBytes(raw)
      json.decodeFromString<Snapshot>(String(plain, Charsets.UTF_8))
    }.onFailure { error ->
      Log.w(TAG, "Unable to read ${file.name}", error)
    }.getOrNull()
  }

  private fun writeSnapshot(
    file: File,
    snapshot: Snapshot,
  ) {
    file.parentFile?.let { if (!it.exists()) it.mkdirs() }
    val atomic = AtomicFile(file)
    val out = atomic.startWrite()
    try {
      val encoded = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
      val encrypted = VaultCrypto.encryptBytes(encoded)
      out.write(encrypted)
      atomic.finishWrite(out)
    } catch (error: Exception) {
      atomic.failWrite(out)
      Log.w(TAG, "Unable to write ${file.name}", error)
    }
  }
}
