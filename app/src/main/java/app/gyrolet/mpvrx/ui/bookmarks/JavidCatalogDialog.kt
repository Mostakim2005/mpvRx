/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.bookmarks

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.gyrolet.mpvrx.database.repository.SecureFolderRepository
import app.gyrolet.mpvrx.domain.secure.SecureFolderSession
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppTextField
import app.gyrolet.mpvrx.repository.bookmark.JavidCatalogEntry
import app.gyrolet.mpvrx.repository.bookmark.PcBookmarkSyncRepository
import app.gyrolet.mpvrx.repository.bookmark.SyncProgressState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File
import kotlin.math.roundToInt

/** Shared live progress indicator rendered during PC / per-JAVID bookmark & thumbnail syncs. */
@Composable
internal fun SyncProgressBanner(
  progress: SyncProgressState,
  modifier: Modifier = Modifier,
) {
  if (!progress.active) return
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = progress.stageLabel.ifBlank { stringResource(R.string.bookmark_gallery_syncing) },
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        val countOrPct =
          if (progress.total > 1 && progress.current > 0) {
            "${progress.current}/${progress.total} (${(progress.fraction * 100f).roundToInt()}%)"
          } else {
            "${(progress.fraction * 100f).roundToInt()}%"
          }
        Text(
          text = countOrPct,
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      if (progress.detail.isNotBlank()) {
        Text(
          text = progress.detail,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      LinearProgressIndicator(
        progress = { progress.fraction },
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

/**
 * Interactive browser for every independent JAVID bookmark set in Room:
 * lets the user search sets, open the single JAVID source URL, attach any local video file
 * (even when the filename lacks a JAVID code), filter gallery view, or pull fresh set data from PC.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JavidCatalogDialog(
  isOpen: Boolean,
  syncRepository: PcBookmarkSyncRepository,
  onDismiss: () -> Unit,
  onSelectGroupForGallery: ((JavidCatalogEntry) -> Unit)? = null,
) {
  if (!isOpen) return
  val vaultMode by SecureFolderSession.modeState.collectAsStateWithLifecycle()
  if (vaultMode != SecureFolderSession.VaultMode.PRIMARY) return
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val secureFolderRepository = koinInject<SecureFolderRepository>()
  val entries by syncRepository.observeJavidCatalog().collectAsStateWithLifecycle(initialValue = emptyList())
  val vaultVideos by secureFolderRepository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
  val syncProgress by syncRepository.syncProgress.collectAsStateWithLifecycle()

  var query by remember { mutableStateOf("") }
  var onlyUnlinked by remember { mutableStateOf(false) }
  var pendingAttachEntry by remember { mutableStateOf<JavidCatalogEntry?>(null) }
  var vaultPickEntry by remember { mutableStateOf<JavidCatalogEntry?>(null) }

  val attachVideoLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pickedUri ->
      val target = pendingAttachEntry
      pendingAttachEntry = null
      if (pickedUri != null && target != null) {
        scope.launch {
          syncRepository
            .linkOrphanGroupToUri(
              groupKey = target.groupKey,
              uri = pickedUri,
              overridePresetCode = target.javidCode,
              overrideSourceUrl = target.sourceUrl,
            ).onSuccess { linked ->
              Toast.makeText(
                context,
                context.getString(R.string.bookmark_gallery_linked_toast, linked.displayName, linked.bookmarkCount),
                Toast.LENGTH_LONG,
              ).show()
            }.onFailure { err ->
              Toast.makeText(
                context,
                err.message ?: context.getString(R.string.bookmark_gallery_unavailable),
                Toast.LENGTH_SHORT,
              ).show()
            }
        }
      }
    }

  val filtered =
    remember(entries, query, onlyUnlinked) {
      val q = query.trim().lowercase()
      entries.filter { entry ->
        if (onlyUnlinked && !entry.playableUri.isNullOrBlank()) return@filter false
        if (q.isEmpty()) return@filter true
        entry.javidCode.lowercase().contains(q) ||
          entry.sourceLabel.lowercase().contains(q) ||
          entry.mediaTitle?.lowercase()?.contains(q) == true ||
          entry.tags.any { it.lowercase().contains(q) }
      }
    }

  AppAlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = stringResource(R.string.bookmark_javid_catalog_title, entries.size),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        SyncProgressBanner(progress = syncProgress)
        AppTextField(
          value = query,
          onValueChange = { query = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text(stringResource(R.string.bookmark_javid_catalog_search)) },
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilterChipButton(
            label = stringResource(R.string.bookmark_javid_filter_all),
            selected = !onlyUnlinked,
            onClick = { onlyUnlinked = false },
          )
          FilterChipButton(
            label = stringResource(R.string.bookmark_javid_filter_unlinked),
            selected = onlyUnlinked,
            onClick = { onlyUnlinked = true },
          )
        }
        if (filtered.isEmpty()) {
          Text(
            text = stringResource(R.string.bookmark_javid_catalog_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
          )
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(filtered, key = { it.groupKey }) { entry ->
              val isLinked = !entry.playableUri.isNullOrBlank()
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(
                  modifier = Modifier.padding(10.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      text = entry.javidCode,
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.primary,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.weight(1f),
                    )
                    Text(
                      text = stringResource(R.string.bookmark_preset_count_fmt, entry.bookmarkCount),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  val subLabel =
                    when {
                      isLinked -> entry.mediaTitle?.takeIf { it.isNotBlank() } ?: entry.sourceLabel
                      entry.sourceLabel.isNotBlank() -> "${entry.sourceLabel} • Unlinked"
                      else -> stringResource(R.string.bookmark_gallery_unlinked_status)
                    }
                  if (subLabel.isNotBlank()) {
                    Text(
                      text = subLabel,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                  if (entry.tags.isNotEmpty()) {
                    Text(
                      text = entry.tags.joinToString(" • ") { "#$it" },
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.secondary,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                  FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    if (onSelectGroupForGallery != null) {
                      FilledTonalButton(
                        onClick = {
                          onSelectGroupForGallery(entry)
                          onDismiss()
                        },
                      ) {
                        Text(stringResource(R.string.bookmark_javid_view_bookmarks))
                      }
                    }
                    OutlinedButton(
                      onClick = { vaultPickEntry = entry },
                    ) {
                      Icon(Icons.RoundedFilled.Videocam, contentDescription = null, modifier = Modifier.size(15.dp))
                      Spacer(Modifier.size(4.dp))
                      Text(stringResource(R.string.bookmark_gallery_link_vault_video))
                    }
                    OutlinedButton(
                      onClick = {
                        pendingAttachEntry = entry
                        attachVideoLauncher.launch(arrayOf("video/*", "*/*"))
                      },
                    ) {
                      Icon(Icons.RoundedFilled.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                      Spacer(Modifier.size(4.dp))
                      Text(stringResource(R.string.bookmark_javid_attach_video))
                    }
                    OutlinedButton(
                      enabled = !syncProgress.active,
                      onClick = {
                        scope.launch {
                          syncRepository
                            .syncSingleJavidFromPcHub(entry.javidCode.ifBlank { entry.groupKey })
                            .onSuccess { res ->
                              Toast.makeText(
                                context,
                                context.getString(
                                  R.string.bookmark_javid_pulled_toast,
                                  entry.javidCode,
                                  res.inserted,
                                  res.updated,
                                  res.thumbnailsDownloaded,
                                ),
                                Toast.LENGTH_LONG,
                              ).show()
                            }.onFailure { err ->
                              Toast.makeText(
                                context,
                                context.getString(
                                  R.string.bookmark_gallery_sync_failed,
                                  err.message ?: "Error",
                                ),
                                Toast.LENGTH_LONG,
                              ).show()
                            }
                        }
                      },
                    ) {
                      Icon(
                        Icons.RoundedFilled.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                      )
                      Spacer(Modifier.size(4.dp))
                      Text(stringResource(R.string.bookmark_javid_pull_pc))
                    }
                    val validUrl =
                      entry.sourceUrl?.takeIf {
                        it.startsWith("http://", ignoreCase = true) ||
                          it.startsWith("https://", ignoreCase = true)
                      }
                    if (validUrl != null) {
                      TextButton(
                        onClick = {
                          runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(validUrl)))
                          }
                        },
                      ) {
                        Icon(Icons.RoundedFilled.Link, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.bookmark_gallery_open_url))
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.generic_ok))
      }
    },
  )

  vaultPickEntry?.let { entry ->
    AppAlertDialog(
      onDismissRequest = { vaultPickEntry = null },
      title = { Text(stringResource(R.string.bookmark_gallery_link_vault_video)) },
      text = {
        if (vaultVideos.isEmpty()) {
          Text(
            text = stringResource(R.string.bookmark_gallery_no_vault_videos),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            items(vaultVideos, key = { it.id }) { video ->
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clickable {
                      vaultPickEntry = null
                      scope.launch {
                        syncRepository
                          .linkOrphanGroupToUri(
                            groupKey = entry.groupKey,
                            uri = Uri.fromFile(File(video.secureFilePath)),
                            overridePresetCode = entry.javidCode,
                            overrideSourceUrl = entry.sourceUrl,
                          )
                          .onSuccess { linked ->
                            Toast.makeText(
                              context,
                              context.getString(R.string.bookmark_gallery_linked_toast, linked.displayName, linked.bookmarkCount),
                              Toast.LENGTH_LONG,
                            ).show()
                          }.onFailure { err ->
                            Toast.makeText(
                              context,
                              err.message ?: context.getString(R.string.bookmark_gallery_unavailable),
                              Toast.LENGTH_SHORT,
                            ).show()
                          }
                      }
                    },
              ) {
                Text(
                  text = video.fileName,
                  style = MaterialTheme.typography.bodyMedium,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { vaultPickEntry = null }) {
          Text(stringResource(android.R.string.cancel))
        }
      },
    )
  }
}

@Composable
private fun FilterChipButton(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(50),
    color =
      if (selected) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
      },
    onClick = onClick,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
      color =
        if (selected) {
          MaterialTheme.colorScheme.onPrimary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
    )
  }
}
