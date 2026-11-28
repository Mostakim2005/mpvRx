/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.securefolder

import android.text.format.Formatter
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.domain.bookmark.SecureBookmarkStore
import app.gyrolet.mpvrx.domain.bookmark.VaultSavedBookmarkInventory
import app.gyrolet.mpvrx.domain.secure.SecureFolderSession
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton
import app.gyrolet.mpvrx.repository.WatchMediaStats
import app.gyrolet.mpvrx.repository.WatchStatsRepository
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Locale

private enum class VaultDataCategory {
  GROUPS,
  BOOKMARKS,
  THUMBNAILS,
  TAGS,
  TIMESTAMPS,
  STATS,
}

private data class VaultDataRowUi(
  val key: String,
  val title: String,
  val subtitle: String,
  val badge: String? = null,
)

private data class VaultTimestampItem(
  val key: String,
  val isPlaybackState: Boolean,
  val rawId: String,
  val title: String,
  val subtitle: String,
)

/**
 * Stealth Control Hub unlocked by triple-tapping the "Secure Folder Settings" title and verifying
 * the Primary PIN. Hosts Decoy Vault management, AV Manager & Stash sync settings, and the
 * Vault-Safe Data Manager while keeping those controls absent from visible settings screens.
 */
@Composable
internal fun VaultControlHubDialog(
  isOpen: Boolean,
  viewModel: SecureFolderViewModel,
  onDismiss: () -> Unit,
  onOpenAvSync: () -> Unit,
  onOpenJavidCatalog: () -> Unit,
  onOpenDataManager: () -> Unit,
) {
  if (!isOpen) return

  val context = LocalContext.current
  val prefs = viewModel.preferences
  val isDecoyEnabled by prefs.isDecoyEnabled.collectAsState()
  val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
  val decoyClearedMsg = stringResource(R.string.vault_control_hub_decoy_cleared)

  var decoyPinSet by remember(isOpen) { mutableStateOf(prefs.isDecoyPinSet()) }
  var decoyPinDialogOpen by remember { mutableStateOf(false) }

  DecoyPinDialog(
    isOpen = decoyPinDialogOpen,
    preferences = prefs,
    onDismiss = { decoyPinDialogOpen = false },
    onSaved = { decoyPinSet = prefs.isDecoyPinSet() },
  )

  AppAlertDialog(
    onDismissRequest = { if (!isBusy) onDismiss() },
    icon = { Icon(Icons.RoundedFilled.Lock, contentDescription = null) },
    title = { Text(stringResource(R.string.vault_control_hub_title)) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = stringResource(R.string.vault_control_hub_decoy_section),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                  text = stringResource(R.string.vault_control_hub_decoy_enable),
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                )
                Text(
                  text = stringResource(R.string.vault_control_hub_decoy_enable_summary),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = isDecoyEnabled,
                enabled = !isBusy,
                onCheckedChange = { wantEnable ->
                  if (wantEnable && !prefs.isDecoyPinSet()) {
                    decoyPinDialogOpen = true
                  } else {
                    prefs.isDecoyEnabled.set(wantEnable)
                  }
                },
              )
            }
            OutlinedButton(
              enabled = !isBusy,
              modifier = Modifier.fillMaxWidth(),
              onClick = { decoyPinDialogOpen = true },
            ) {
              Text(
                stringResource(
                  if (decoyPinSet) R.string.vault_control_hub_decoy_change_pin else R.string.vault_control_hub_decoy_set_pin,
                ),
              )
            }
            Text(
              text = stringResource(R.string.vault_control_hub_decoy_pin_summary),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (decoyPinSet || isDecoyEnabled) {
              OutlinedButton(
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                  viewModel.destroyDecoyFolder { ok ->
                    decoyPinSet = prefs.isDecoyPinSet()
                    if (ok) {
                      Toast.makeText(context, decoyClearedMsg, Toast.LENGTH_SHORT).show()
                    }
                  }
                },
              ) {
                Icon(Icons.RoundedFilled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.vault_control_hub_decoy_clear))
              }
            }
          }
        }

        HorizontalDivider()

        Text(
          text = stringResource(R.string.vault_control_hub_av_section),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenAvSync,
          ) {
            Icon(Icons.RoundedFilled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.vault_control_hub_av_configure))
          }
          OutlinedButton(
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenJavidCatalog,
          ) {
            Icon(Icons.RoundedFilled.VideoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.bookmark_javid_catalog_button))
          }
        }

        HorizontalDivider()

        Text(
          text = stringResource(R.string.vault_control_hub_data_section),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = stringResource(R.string.vault_control_hub_data_summary),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
          enabled = !isBusy,
          modifier = Modifier.fillMaxWidth(),
          onClick = onOpenDataManager,
        ) {
          Icon(Icons.RoundedFilled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.size(6.dp))
          Text(stringResource(R.string.vault_control_hub_data_open))
        }
      }
    },
    confirmButton = {
      AppDialogTextButton(onClick = onDismiss) {
        Text(stringResource(android.R.string.ok))
      }
    },
  )
}

/**
 * Interactive manager for retained Primary Vault data (`Echo 1`): bookmarks, JAVID groups,
 * thumbnails, tags, playback timestamps/metadata, and watch stats survive video restore/delete
 * until explicitly deleted here.
 */
@Composable
internal fun VaultDataManagerDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
) {
  if (!isOpen) return
  val vaultMode by SecureFolderSession.modeState.collectAsStateWithLifecycle()
  if (vaultMode != SecureFolderSession.VaultMode.PRIMARY) return

  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val bookmarkStore = koinInject<SecureBookmarkStore>()
  val playbackStateRepo = koinInject<PlaybackStateRepository>()
  val metadataRepo = koinInject<VideoMetadataCacheRepository>()
  val watchStatsRepo = koinInject<WatchStatsRepository>()

  var selectedCategory by rememberSaveable { mutableStateOf(VaultDataCategory.GROUPS) }
  var selectedKeys by remember(selectedCategory) { mutableStateOf<Set<String>>(emptySet()) }
  var reloadTick by remember { mutableIntStateOf(0) }
  var loading by remember { mutableStateOf(true) }
  var busy by remember { mutableStateOf(false) }

  var inventory by remember {
    mutableStateOf(
      VaultSavedBookmarkInventory(
        groups = emptyList(),
        bookmarks = emptyList(),
        thumbnails = emptyList(),
        tags = emptyList(),
        tombstoneCount = 0,
      ),
    )
  }
  var timestampRows by remember { mutableStateOf<List<VaultTimestampItem>>(emptyList()) }
  var watchStatEntries by remember { mutableStateOf<Map<String, WatchMediaStats>>(emptyMap()) }

  LaunchedEffect(isOpen, reloadTick, vaultMode) {
    if (vaultMode != SecureFolderSession.VaultMode.PRIMARY) return@LaunchedEffect
    loading = true
    runCatching {
      val inv = bookmarkStore.loadPrimaryVaultInventory()
      val states = playbackStateRepo.getAllSecurePlaybackStates()
      val metas = metadataRepo.getAllSecureMetadata()
      val stats = watchStatsRepo.snapshot(includeSecure = true)

      val tsList =
        buildList {
          states.forEach { (st, label) ->
            val posText = formatSeconds(st.lastPosition.toLong())
            val stateLabel = label.ifBlank { st.mediaKey }
            val status = if (st.hasBeenWatched) "Watched" else "Pos $posText"
            add(
              VaultTimestampItem(
                key = "ps:${st.mediaKey}",
                isPlaybackState = true,
                rawId = st.mediaKey,
                title = stateLabel,
                subtitle = "Timestamp • $status",
              ),
            )
          }
          metas.forEach { (meta, label) ->
            val durText = formatSeconds((meta.duration / 1000L).coerceAtLeast(0L))
            val res = if (meta.width > 0 && meta.height > 0) "${meta.width}x${meta.height}" else "Metadata"
            add(
              VaultTimestampItem(
                key = "vm:${meta.pathHash}",
                isPlaybackState = false,
                rawId = meta.pathHash,
                title = label.ifBlank { meta.pathHash.take(12) },
                subtitle = "$res • $durText • ${Formatter.formatShortFileSize(context, meta.size)}",
              ),
            )
          }
        }

      inventory = inv
      timestampRows = tsList
      watchStatEntries = stats.media
    }
    loading = false
  }

  val attachedLabel = stringResource(R.string.vault_data_manager_attached)
  val retainedLabel = stringResource(R.string.vault_data_manager_retained)
  val orphanThumbLabel = stringResource(R.string.vault_data_manager_orphan_thumb)

  val activeRows: List<VaultDataRowUi> =
    remember(selectedCategory, inventory, timestampRows, watchStatEntries, attachedLabel, retainedLabel, orphanThumbLabel) {
      when (selectedCategory) {
        VaultDataCategory.GROUPS ->
          inventory.groups.map { g ->
            val title = g.presetCode?.takeIf { it.isNotBlank() }?.let { "$it • ${g.sourceLabel}" } ?: g.sourceLabel
            val status = if (g.mediaAttached) attachedLabel else retainedLabel
            VaultDataRowUi(
              key = g.groupKey,
              title = title,
              subtitle = "${g.bookmarkCount} bm • $status",
            )
          }
        VaultDataCategory.BOOKMARKS ->
          inventory.bookmarks.map { b ->
            val tagStr = if (b.tags.isNotEmpty()) " • " + b.tags.joinToString(" ") { "#$it" } else ""
            val status = if (b.mediaAttached) attachedLabel else retainedLabel
            VaultDataRowUi(
              key = b.id.toString(),
              title = b.title,
              subtitle = "${b.groupLabel} @ ${formatSeconds(b.positionMs / 1000L)}$tagStr • $status",
            )
          }
        VaultDataCategory.THUMBNAILS ->
          inventory.thumbnails.map { t ->
            val status = if (t.isOrphaned) orphanThumbLabel else attachedLabel
            val kind = if (t.isCache) "Cache" else "Bookmark"
            VaultDataRowUi(
              key = t.path,
              title = t.name,
              subtitle = "$kind • ${Formatter.formatShortFileSize(context, t.sizeBytes)} • $status",
            )
          }
        VaultDataCategory.TAGS ->
          inventory.tags.map { tag ->
            VaultDataRowUi(
              key = tag.nameHash,
              title = "#${tag.displayName}",
              subtitle = "${tag.attachedBookmarkCount} bm • used ${tag.useCount}x",
            )
          }
        VaultDataCategory.TIMESTAMPS ->
          timestampRows.map { item ->
            VaultDataRowUi(
              key = item.key,
              title = item.title,
              subtitle = item.subtitle,
            )
          }
        VaultDataCategory.STATS ->
          watchStatEntries.entries.sortedByDescending { it.value.lastWatchedAt }.map { (k, v) ->
            VaultDataRowUi(
              key = k,
              title = v.title.ifBlank { k },
              subtitle = "${v.sessions} sessions • ${formatSeconds(v.seconds)}",
            )
          }
      }
    }

  fun deleteSelection() {
    val keys = selectedKeys.toList()
    if (keys.isEmpty() || busy) return
    busy = true
    scope.launch {
      runCatching {
        when (selectedCategory) {
          VaultDataCategory.GROUPS -> bookmarkStore.deletePrimaryGroupsByKeys(keys)
          VaultDataCategory.BOOKMARKS -> bookmarkStore.deletePrimaryBookmarksByIds(keys.mapNotNull { it.toLongOrNull() })
          VaultDataCategory.THUMBNAILS -> bookmarkStore.deletePrimaryThumbnailFiles(keys)
          VaultDataCategory.TAGS -> bookmarkStore.deletePrimaryTagMemory(keys)
          VaultDataCategory.TIMESTAMPS -> {
            val psKeys = keys.mapNotNull { k -> k.removePrefix("ps:").takeIf { k.startsWith("ps:") } }
            val metaHashes = keys.mapNotNull { k -> k.removePrefix("vm:").takeIf { k.startsWith("vm:") } }
            if (psKeys.isNotEmpty()) playbackStateRepo.deleteSecureStatesByKeys(psKeys)
            if (metaHashes.isNotEmpty()) metadataRepo.deleteSecureMetadataByHashes(metaHashes)
          }
          VaultDataCategory.STATS -> watchStatsRepo.removeSecureMediaEntries(keys.toSet())
        }
      }.onSuccess {
        Toast.makeText(
          context,
          context.getString(R.string.vault_data_manager_deleted_toast, keys.size),
          Toast.LENGTH_SHORT,
        ).show()
        selectedKeys = emptySet()
        reloadTick++
      }.onFailure { err ->
        Toast.makeText(context, err.message ?: "Delete failed", Toast.LENGTH_SHORT).show()
      }
      busy = false
    }
  }

  AppAlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text(stringResource(R.string.vault_data_manager_title)) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        LazyRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          items(VaultDataCategory.entries) { cat ->
            val labelRes =
              when (cat) {
                VaultDataCategory.GROUPS -> R.string.vault_data_manager_tab_groups
                VaultDataCategory.BOOKMARKS -> R.string.vault_data_manager_tab_bookmarks
                VaultDataCategory.THUMBNAILS -> R.string.vault_data_manager_tab_thumbs
                VaultDataCategory.TAGS -> R.string.vault_data_manager_tab_tags
                VaultDataCategory.TIMESTAMPS -> R.string.vault_data_manager_tab_timestamps
                VaultDataCategory.STATS -> R.string.vault_data_manager_tab_stats
              }
            val count =
              when (cat) {
                VaultDataCategory.GROUPS -> inventory.groups.size
                VaultDataCategory.BOOKMARKS -> inventory.bookmarks.size
                VaultDataCategory.THUMBNAILS -> inventory.thumbnails.size
                VaultDataCategory.TAGS -> inventory.tags.size
                VaultDataCategory.TIMESTAMPS -> timestampRows.size
                VaultDataCategory.STATS -> watchStatEntries.size
              }
            FilterChip(
              selected = selectedCategory == cat,
              onClick = {
                selectedCategory = cat
                selectedKeys = emptySet()
              },
              label = { Text("${stringResource(labelRes)} ($count)", maxLines = 1) },
            )
          }
        }

        if (activeRows.isNotEmpty()) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            AppDialogTextButton(
              enabled = !busy,
              onClick = {
                selectedKeys =
                  if (selectedKeys.size == activeRows.size) {
                    emptySet()
                  } else {
                    activeRows.mapTo(HashSet()) { it.key }
                  }
              },
            ) {
              Text(
                stringResource(
                  if (selectedKeys.size == activeRows.size) {
                    R.string.vault_data_manager_clear_selection
                  } else {
                    R.string.vault_data_manager_select_all
                  },
                ),
              )
            }
            Spacer(Modifier.weight(1f))
            Button(
              enabled = !busy && selectedKeys.isNotEmpty(),
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
              onClick = ::deleteSelection,
            ) {
              Text(
                stringResource(R.string.vault_data_manager_delete_selected, selectedKeys.size),
                maxLines = 1,
              )
            }
          }
        }

        if (loading) {
          Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
          }
        } else if (activeRows.isEmpty()) {
          Text(
            text = stringResource(R.string.vault_data_manager_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 20.dp),
          )
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            items(activeRows, key = { it.key }) { row ->
              val checked = row.key in selectedKeys
              Surface(
                shape = RoundedCornerShape(8.dp),
                color =
                  if (checked) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                  } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                  },
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) {
                      selectedKeys = if (checked) selectedKeys - row.key else selectedKeys + row.key
                    },
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Checkbox(
                    checked = checked,
                    enabled = !busy,
                    onCheckedChange = {
                      selectedKeys = if (it) selectedKeys + row.key else selectedKeys - row.key
                    },
                  )
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = row.title,
                      style = MaterialTheme.typography.bodyMedium,
                      fontWeight = FontWeight.Medium,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                      text = row.subtitle,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
              }
            }
          }
        }

        if (inventory.tombstoneCount > 0) {
          AppDialogTextButton(
            enabled = !busy,
            onClick = {
              busy = true
              scope.launch {
                bookmarkStore.clearPrimaryTombstones()
                reloadTick++
                busy = false
              }
            },
          ) {
            Text(stringResource(R.string.vault_data_manager_clear_tombstones, inventory.tombstoneCount))
          }
        }
      }
    },
    confirmButton = {
      AppDialogTextButton(enabled = !busy, onClick = onDismiss) {
        Text(stringResource(android.R.string.ok))
      }
    },
  )
}

private fun formatSeconds(totalSeconds: Long): String {
  val safe = totalSeconds.coerceAtLeast(0L)
  val h = safe / 3600L
  val m = (safe % 3600L) / 60L
  val s = safe % 60L
  return if (h > 0L) {
    String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
  } else {
    String.format(Locale.ROOT, "%02d:%02d", m, s)
  }
}
