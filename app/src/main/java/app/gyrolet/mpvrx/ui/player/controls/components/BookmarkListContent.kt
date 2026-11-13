/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity
import app.gyrolet.mpvrx.domain.bookmark.BookmarkThumbnailStore
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.Sheets
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import dev.vivvvek.seeker.Segment
import `is`.xyz.mpv.Utils
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun BookmarkThumbnailImage(
  thumbnailPath: String?,
  modifier: Modifier = Modifier,
  cornerRadius: Dp = 8.dp,
  placeholderTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  val context = LocalContext.current
  var bitmap by remember(thumbnailPath) { mutableStateOf<Bitmap?>(null) }

  LaunchedEffect(thumbnailPath) {
    bitmap = BookmarkThumbnailStore.loadBitmap(context, thumbnailPath)
  }

  val imageBitmap = remember(bitmap) { bitmap?.takeIf { !it.isRecycled }?.asImageBitmap() }
  Surface(
    modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
    shape = RoundedCornerShape(cornerRadius),
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
  ) {
    if (imageBitmap != null) {
      Image(
        bitmap = imageBitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Bookmark,
          contentDescription = null,
          tint = placeholderTint.copy(alpha = 0.65f),
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

@Composable
internal fun BookmarkActionMenuDialog(
  bookmark: PlaybackBookmarkEntity,
  formattedTimestamp: String,
  onJump: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
  onDismiss: () -> Unit,
) {
  AppAlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = bookmark.title,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = formattedTimestamp,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!bookmark.thumbnailPath.isNullOrBlank()) {
          BookmarkThumbnailImage(
            thumbnailPath = bookmark.thumbnailPath,
            modifier =
              Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .padding(bottom = 8.dp),
            cornerRadius = 12.dp,
          )
        }
        BookmarkMenuActionRow(
          icon = {
            Icon(
              imageVector = Icons.RoundedFilled.PlayArrow,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
            )
          },
          label = stringResource(R.string.bookmark_edit_menu_jump),
          onClick = {
            onJump()
            onDismiss()
          },
        )
        BookmarkMenuActionRow(
          icon = {
            Icon(
              imageVector = Icons.RoundedFilled.Edit,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurface,
            )
          },
          label = stringResource(R.string.bookmark_edit_menu_rename),
          onClick = {
            onRename()
            onDismiss()
          },
        )
        BookmarkMenuActionRow(
          icon = {
            Icon(
              imageVector = Icons.RoundedFilled.Delete,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
            )
          },
          label = stringResource(R.string.audiobook_delete_bookmark),
          labelColor = MaterialTheme.colorScheme.error,
          onClick = {
            onDelete()
            onDismiss()
          },
        )
      }
    },
    confirmButton = {
      AppDialogTextButton(onClick = onDismiss) {
        Text(stringResource(R.string.generic_cancel))
      }
    },
  )
}

@Composable
private fun BookmarkMenuActionRow(
  icon: @Composable () -> Unit,
  label: String,
  labelColor: Color = MaterialTheme.colorScheme.onSurface,
  onClick: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp),
  ) {
    icon()
    Text(
      text = label,
      style = MaterialTheme.typography.bodyLarge,
      color = labelColor,
      modifier = Modifier.weight(1f),
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookmarkListRow(
  bookmark: PlaybackBookmarkEntity,
  positionSeconds: Int,
  selected: Boolean,
  compact: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  trailingContent: @Composable (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(10.dp)
  val containerColor =
    if (selected) {
      MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
      Color.Transparent
    }
  val titleColor =
    if (selected) {
      MaterialTheme.colorScheme.primary
    } else {
      MaterialTheme.colorScheme.onSurface
    }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    modifier =
      modifier
        .fillMaxWidth()
        .clip(shape)
        .background(containerColor)
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
          onLongClickLabel = stringResource(R.string.bookmark_edit_menu_rename),
        ).padding(
          horizontal = if (compact) 8.dp else 12.dp,
          vertical = if (compact) 6.dp else 8.dp,
        ),
  ) {
    BookmarkThumbnailImage(
      thumbnailPath = bookmark.thumbnailPath,
      modifier =
        Modifier
          .width(if (compact) 56.dp else 76.dp)
          .aspectRatio(16f / 9f),
      cornerRadius = if (compact) 6.dp else 8.dp,
    )
    Column(
      verticalArrangement = Arrangement.spacedBy(2.dp),
      modifier = Modifier.weight(1f),
    ) {
      Text(
        text = bookmark.title,
        style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        color = titleColor,
        maxLines = if (compact) 2 else 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = Utils.prettyTime(positionSeconds),
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
    trailingContent?.invoke()
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookmarkSplitPanel(
  viewModel: PlayerViewModel,
  chapters: ImmutableList<Segment>,
  currentPositionSeconds: Float,
  isPortrait: Boolean,
  onOpenSheet: (Sheets) -> Unit,
  onSwitchToOverlay: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val bookmarks by viewModel.playbackBookmarks.collectAsStateWithLifecycle()
  val sourceChapters by viewModel.sourceChapters.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val haptics = rememberAppHaptics()
  var menuBookmark by remember { mutableStateOf<PlaybackBookmarkEntity?>(null) }

  val insetsPadding =
    if (isPortrait) {
      WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    } else {
      WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Right)
    }

  val nearestBookmarkId =
    remember(bookmarks, currentPositionSeconds) {
      bookmarks.minByOrNull { bookmark ->
        kotlin.math.abs(viewModel.bookmarkPositionMs(bookmark) / 1000f - currentPositionSeconds)
      }?.takeIf { bookmark ->
        kotlin.math.abs(viewModel.bookmarkPositionMs(bookmark) / 1000f - currentPositionSeconds) <= 2f
      }?.id
    }

  val displayChapters =
    remember(sourceChapters, chapters) {
      if (sourceChapters.isNotEmpty()) sourceChapters else emptyList()
    }

  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor = MaterialTheme.colorScheme.onSurface,
    modifier = modifier.fillMaxSize(),
  ) {
    val contentBlock: @Composable (Modifier) -> Unit = { containerModifier ->
      Column(
        modifier = containerModifier.fillMaxSize().windowInsetsPadding(insetsPadding),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(2.dp),
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
          Text(
            text = "${stringResource(R.string.bookmark_panel_title)} (${bookmarks.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Box(
            contentAlignment = Alignment.Center,
            modifier =
              Modifier
                .size(32.dp)
                .clip(CircleShape)
                .combinedClickable(
                  onClick = {
                    if (viewModel.quickAddBookmark()) haptics.confirm()
                  },
                  onLongClick = {
                    if (viewModel.preparePlaybackBookmark(returnSheet = Sheets.None)) {
                      haptics.confirm()
                      onOpenSheet(Sheets.BookmarkEditor)
                    }
                  },
                  onLongClickLabel = stringResource(R.string.audiobook_bookmark_name),
                ),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Add,
              contentDescription = stringResource(R.string.audiobook_add_bookmark),
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
            )
          }
          IconButton(
            onClick = onSwitchToOverlay,
            modifier = Modifier.size(32.dp),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.ListAlt,
              contentDescription = stringResource(R.string.bookmark_switch_to_overlay),
              modifier = Modifier.size(17.dp),
            )
          }
          IconButton(
            onClick = onClose,
            modifier = Modifier.size(32.dp),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Close,
              contentDescription = stringResource(R.string.bookmark_close_panel),
              modifier = Modifier.size(18.dp),
            )
          }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        if (bookmarks.isEmpty() && displayChapters.isEmpty()) {
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Bookmarks,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(28.dp),
              )
              Text(
                text = stringResource(R.string.playback_bookmarks_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            items(
              items = bookmarks,
              key = { "bm_${it.id}" },
            ) { bookmark ->
              val positionSec = (viewModel.bookmarkPositionMs(bookmark) / 1000L).toInt().coerceAtLeast(0)
              BookmarkListRow(
                bookmark = bookmark,
                positionSeconds = positionSec,
                selected = bookmark.id == nearestBookmarkId,
                compact = true,
                onClick = { viewModel.seekToBookmark(bookmark) },
                onLongClick = { menuBookmark = bookmark },
              )
            }

            if (displayChapters.isNotEmpty()) {
              item(key = "chapters_header") {
                Text(
                  text = stringResource(R.string.bookmark_list_section_chapters),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
              }
              items(
                items = displayChapters,
                key = { "ch_${it.start}_${it.name}" },
              ) { segment ->
                val isCurrent =
                  displayChapters.indexOfLast { it.start <= currentPositionSeconds }
                    .let { idx -> idx >= 0 && displayChapters[idx] == segment }
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .clip(RoundedCornerShape(10.dp))
                      .background(
                        if (isCurrent) {
                          MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        } else {
                          Color.Transparent
                        },
                      ).clickable { viewModel.seekToPlaybackChapter(segment) }
                      .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                  Text(
                    text = segment.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                  )
                  Text(
                    text = Utils.prettyTime(segment.start.toInt().coerceAtLeast(0)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      }
    }

    if (isPortrait) {
      Column(Modifier.fillMaxSize()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        contentBlock(Modifier.weight(1f))
      }
    } else {
      Row(Modifier.fillMaxSize()) {
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        contentBlock(Modifier.weight(1f))
      }
    }
  }

  menuBookmark?.let { target ->
    val targetPosSec = (viewModel.bookmarkPositionMs(target) / 1000L).toInt().coerceAtLeast(0)
    BookmarkActionMenuDialog(
      bookmark = target,
      formattedTimestamp = Utils.prettyTime(targetPosSec),
      onJump = { viewModel.seekToBookmark(target) },
      onRename = {
        if (viewModel.preparePlaybackBookmark(target, returnSheet = Sheets.None)) {
          onOpenSheet(Sheets.BookmarkEditor)
        }
      },
      onDelete = {
        scope.launch {
          try {
            viewModel.deletePlaybackBookmark(target)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            Toast.makeText(context, R.string.playback_bookmark_update_failed, Toast.LENGTH_SHORT).show()
          }
        }
      },
      onDismiss = { menuBookmark = null },
    )
  }
}
