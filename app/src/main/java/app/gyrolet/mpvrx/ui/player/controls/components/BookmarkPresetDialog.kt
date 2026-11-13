/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import app.gyrolet.mpvrx.database.dao.BookmarkGroupSummary
import app.gyrolet.mpvrx.domain.bookmark.BookmarkGroupLoadResult
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppTextField
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.launch

@Composable
internal fun BookmarkPresetDialog(
  viewModel: PlayerViewModel,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val activeGroup by viewModel.activeBookmarkGroup.collectAsStateWithLifecycle()
  val activeGroupKey by viewModel.bookmarkMediaId.collectAsStateWithLifecycle()
  val bookmarks by viewModel.playbackBookmarks.collectAsStateWithLifecycle()
  val savedPresets by viewModel.savedBookmarkPresets.collectAsStateWithLifecycle()

  var codeInput by remember(activeGroup?.groupKey, activeGroup?.presetCode) {
    mutableStateOf(activeGroup?.presetCode.orEmpty())
  }
  var statusMessage by remember { mutableStateOf<String?>(null) }
  var isErrorStatus by remember { mutableStateOf(false) }
  var conflictGroup by remember { mutableStateOf<BookmarkGroupSummary?>(null) }
  var busy by remember { mutableStateOf(false) }

  val exportLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      busy = true
      scope.launch {
        val result = viewModel.exportBookmarkPreset(uri)
        busy = false
        result.fold(
          onSuccess = { count ->
            isErrorStatus = false
            statusMessage = context.getString(R.string.bookmark_preset_export_success, count)
          },
          onFailure = {
            isErrorStatus = true
            statusMessage = context.getString(R.string.bookmark_preset_export_failed)
          },
        )
      }
    }

  val importLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      busy = true
      scope.launch {
        val result = viewModel.importBookmarkPreset(uri)
        busy = false
        result.fold(
          onSuccess = { count ->
            isErrorStatus = false
            statusMessage = context.getString(R.string.bookmark_preset_import_success, count)
          },
          onFailure = {
            isErrorStatus = true
            statusMessage = context.getString(R.string.bookmark_preset_import_failed)
          },
        )
      }
    }

  val otherPresets =
    remember(savedPresets, activeGroupKey) {
      savedPresets.filter { it.groupKey != activeGroupKey && it.bookmarkCount > 0 }
    }

  AppAlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Fingerprint,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = stringResource(R.string.bookmark_preset_dialog_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        AppTextField(
          value = codeInput,
          onValueChange = {
            codeInput = it
            conflictGroup = null
          },
          modifier = Modifier.fillMaxWidth(),
          enabled = !busy,
          singleLine = true,
          label = { Text(stringResource(R.string.bookmark_preset_code_label)) },
          placeholder = { Text(stringResource(R.string.bookmark_preset_code_hint)) },
        )

        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          FilledTonalButton(
            onClick = {
              busy = true
              scope.launch {
                val existing = viewModel.findExistingBookmarkGroupByCode(codeInput)
                if (existing != null) {
                  conflictGroup = existing
                  busy = false
                } else {
                  val saved = viewModel.assignBookmarkPresetCode(codeInput)
                  conflictGroup = null
                  busy = false
                  isErrorStatus = false
                  statusMessage =
                    context.getString(
                      if (saved != null) R.string.bookmark_preset_code_saved else R.string.bookmark_preset_code_cleared,
                    )
                }
              }
            },
            enabled = !busy,
          ) {
            Icon(Icons.RoundedFilled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.bookmark_preset_save_code))
          }

          OutlinedButton(
            onClick = { exportLauncher.launch(viewModel.suggestedBookmarkPresetFilename()) },
            enabled = !busy && bookmarks.isNotEmpty(),
          ) {
            Icon(Icons.RoundedFilled.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.bookmark_preset_export_file))
          }

          OutlinedButton(
            onClick = {
              importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
            enabled = !busy,
          ) {
            Icon(Icons.RoundedFilled.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.bookmark_preset_import_file))
          }
        }

        conflictGroup?.let { conflict ->
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                text =
                  stringResource(
                    R.string.bookmark_preset_code_conflict,
                    conflict.presetCode ?: codeInput.trim(),
                    conflict.bookmarkCount,
                  ),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
              )
              if (conflict.sourceLabel.isNotBlank()) {
                Text(
                  text = conflict.sourceLabel,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                  onClick = {
                    busy = true
                    scope.launch {
                      val loaded = viewModel.loadBookmarkGroup(conflict.groupKey)
                      conflictGroup = null
                      busy = false
                      isErrorStatus = false
                      val count =
                        when (loaded) {
                          is BookmarkGroupLoadResult.Linked -> loaded.count
                          is BookmarkGroupLoadResult.Merged -> loaded.addedCount
                          BookmarkGroupLoadResult.AlreadyActive -> 0
                        }
                      statusMessage = context.getString(R.string.bookmark_preset_loaded_count, count)
                    }
                  },
                  enabled = !busy,
                ) {
                  Text(stringResource(R.string.bookmark_preset_load_existing, conflict.bookmarkCount))
                }
                TextButton(
                  onClick = {
                    busy = true
                    scope.launch {
                      viewModel.assignBookmarkPresetCode(codeInput)
                      conflictGroup = null
                      busy = false
                      isErrorStatus = false
                      statusMessage = context.getString(R.string.bookmark_preset_code_saved)
                    }
                  },
                  enabled = !busy,
                ) {
                  Text(stringResource(R.string.bookmark_preset_save_code))
                }
              }
            }
          }
        }

        statusMessage?.let { msg ->
          Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = if (isErrorStatus) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
          )
        }

        HorizontalDivider()

        Text(
          text = stringResource(R.string.bookmark_preset_saved_list),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontWeight = FontWeight.SemiBold,
        )

        if (otherPresets.isEmpty()) {
          Text(
            text = stringResource(R.string.bookmark_preset_saved_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
          )
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            otherPresets.forEach { preset ->
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                  ) {
                    val heading =
                      buildString {
                        preset.presetCode?.takeIf { it.isNotBlank() }?.let { append("[$it] ") }
                        append(preset.sourceLabel.ifBlank { preset.groupKey.take(12) })
                      }
                    Text(
                      text = heading,
                      style = MaterialTheme.typography.bodySmall,
                      fontWeight = FontWeight.SemiBold,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                    val metaText =
                      buildList {
                        add(stringResource(R.string.bookmark_preset_count_fmt, preset.bookmarkCount))
                        if (preset.width > 0 && preset.height > 0) add("${preset.width}x${preset.height}")
                        if (preset.durationSec > 0) add(Utils.prettyTime(preset.durationSec))
                      }.joinToString(" • ")
                    Text(
                      text = metaText,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                  TextButton(
                    onClick = {
                      busy = true
                      scope.launch {
                        val loaded = viewModel.loadBookmarkGroup(preset.groupKey)
                        busy = false
                        isErrorStatus = false
                        val count =
                          when (loaded) {
                            is BookmarkGroupLoadResult.Linked -> loaded.count
                            is BookmarkGroupLoadResult.Merged -> loaded.addedCount
                            BookmarkGroupLoadResult.AlreadyActive -> 0
                          }
                        statusMessage = context.getString(R.string.bookmark_preset_loaded_count, count)
                      }
                    },
                    enabled = !busy,
                  ) {
                    Text(stringResource(R.string.bookmark_preset_load_button))
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss, enabled = !busy) {
        Text(stringResource(R.string.generic_ok))
      }
    },
  )
}
