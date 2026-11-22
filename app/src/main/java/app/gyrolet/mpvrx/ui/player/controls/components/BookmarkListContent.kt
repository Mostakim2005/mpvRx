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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity
import app.gyrolet.mpvrx.domain.bookmark.BookmarkGalleryRepository
import app.gyrolet.mpvrx.domain.bookmark.BookmarkGalleryScope
import app.gyrolet.mpvrx.domain.bookmark.BookmarkThumbnailStore
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.Sheets
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import dev.vivvvek.seeker.Segment
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Locale
import kotlin.math.abs

private fun formatBookmarkTimecode(positionSeconds: Int): String {
  val total = positionSeconds.coerceAtLeast(0)
  val hours = total / 3600
  val minutes = (total % 3600) / 60
  val secs = total % 60
  return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, secs)
}

/**
 * Sidebar width stops, smallest first.
 *
 * 20% leads and is the default: at 25% the card column was wider than the 16:9 frame it held, so
 * every card carried grey letterbox flanks and the video paid for space nothing used.
 */
internal val BOOKMARK_PANEL_PERCENT_STEPS = listOf(20, 25, 30, 35)

internal const val DEFAULT_BOOKMARK_PANEL_PERCENT = 20

/** Converts a stored stop to the fraction of the window the panel occupies. */
internal fun bookmarkPanelFraction(percent: Int): Float {
  val clamped = if (percent in BOOKMARK_PANEL_PERCENT_STEPS) percent else DEFAULT_BOOKMARK_PANEL_PERCENT
  return clamped / 100f
}

internal fun formatGlobalBookmarkTitle(
  mediaTitle: String?,
  bookmarkTitle: String,
): String {
  val media = mediaTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return bookmarkTitle
  val title = bookmarkTitle.trim()
  return if (
    title.isEmpty() ||
    title.equals(media, ignoreCase = true) ||
    title.startsWith("$media · ", ignoreCase = true)
  ) {
    title.ifEmpty { media }
  } else {
    "$media · $title"
  }
}

@Composable
internal fun BookmarkThumbnailImage(
  thumbnailPath: String?,
  modifier: Modifier = Modifier,
  isSecure: Boolean = false,
  cornerRadius: Dp = 8.dp,
  placeholderTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
  contentScale: ContentScale = ContentScale.Fit,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
) {
  val context = LocalContext.current
  var bitmap by remember(thumbnailPath, isSecure) { mutableStateOf<Bitmap?>(null) }

  LaunchedEffect(thumbnailPath, isSecure) {
    bitmap = BookmarkThumbnailStore.loadBitmap(context, thumbnailPath, isSecure = isSecure)
  }

  Box(
    modifier = modifier.clip(RoundedCornerShape(cornerRadius)).background(containerColor),
    contentAlignment = Alignment.Center,
  ) {
    val loadedBitmap = bitmap
    if (loadedBitmap != null) {
      Image(
        bitmap = loadedBitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = contentScale,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Icon(
        imageVector = Icons.RoundedFilled.Bookmark,
        contentDescription = null,
        tint = placeholderTint.copy(alpha = 0.65f),
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@Composable
internal fun BookmarkActionMenuDialog(
  bookmark: PlaybackBookmarkEntity,
  formattedTimestamp: String,
  onRename: () -> Unit,
  onDelete: () -> Unit,
  onDismiss: () -> Unit,
  isSecure: Boolean = false,
) {
  app.gyrolet.mpvrx.presentation.components.AppAlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (!bookmark.thumbnailPath.isNullOrBlank()) {
          BookmarkThumbnailImage(
            thumbnailPath = bookmark.thumbnailPath,
            isSecure = isSecure,
            cornerRadius = 8.dp,
            contentScale = ContentScale.Fit,
            modifier = Modifier.width(88.dp).aspectRatio(16f / 9f),
          )
        }
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(
            text = bookmark.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = formattedTimestamp,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        // No "Jump to bookmark" row: tapping the card itself already seeks there, so repeating it
        // here only pushed the destructive action off a landscape dialog.
        BookmarkMenuActionRow(
          icon = Icons.RoundedFilled.Edit,
          label = stringResource(R.string.bookmark_edit_menu_rename),
          tint = MaterialTheme.colorScheme.onSurface,
          onClick = {
            onDismiss()
            onRename()
          },
        )
        BookmarkMenuActionRow(
          icon = Icons.RoundedFilled.Delete,
          label = stringResource(R.string.audiobook_delete_bookmark),
          tint = MaterialTheme.colorScheme.error,
          onClick = {
            onDelete()
            onDismiss()
          },
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.generic_cancel))
      }
    },
  )
}

@Composable
private fun BookmarkMenuActionRow(
  icon: AppIcon,
  label: String,
  tint: Color,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
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
  tagNames: List<String> = emptyList(),
  isSecure: Boolean = false,
  trailingContent: @Composable (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(10.dp)
  val containerColor =
    if (selected) {
      MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
      MaterialTheme.colorScheme.surfaceContainerLow
    }
  val border =
    if (selected) {
      BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
    } else {
      BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    }

  Surface(
    shape = shape,
    color = containerColor,
    border = border,
    modifier =
      modifier
        .fillMaxWidth()
        .clip(shape)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 6.dp else 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
    ) {
      BookmarkThumbnailImage(
        thumbnailPath = bookmark.thumbnailPath,
        isSecure = isSecure,
        cornerRadius = 6.dp,
        contentScale = ContentScale.Fit,
        modifier = Modifier.width(if (compact) 64.dp else 84.dp).aspectRatio(16f / 9f),
      )
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = bookmark.title,
          style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
          fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (tagNames.isNotEmpty()) {
          Text(
            text = tagNames.joinToString("  ") { "#$it" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          text = formatBookmarkTimecode(positionSeconds),
          style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
          color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
      }
      trailingContent?.invoke()
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkGridCard(
  bookmark: PlaybackBookmarkEntity,
  positionSeconds: Int,
  selected: Boolean,
  compactLabels: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  tagNames: List<String> = emptyList(),
  isSecure: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(10.dp)
  val border =
    if (selected) {
      BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
      BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    }

  Surface(
    shape = shape,
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = border,
    modifier =
      modifier
        .clip(shape)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
  ) {
    // Every label lives inside the frame: the card consumes exactly one 16:9 thumbnail, so no
    // vertical space is spent on text rows beside it. Crop (not Fit) keeps the frame filled and the
    // old letterbox flanks were only ever the empty container showing through.
    Box(modifier = Modifier.fillMaxSize()) {
      BookmarkThumbnailImage(
        thumbnailPath = bookmark.thumbnailPath,
        isSecure = isSecure,
        cornerRadius = 0.dp,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
      )

      // Top scrim carrying the bookmark name.
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .background(
              Brush.verticalGradient(
                colors = listOf(Color.Black.copy(alpha = 0.80f), Color.Black.copy(alpha = 0.28f), Color.Transparent),
              ),
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
      ) {
        Text(
          text = bookmark.title,
          style =
            MaterialTheme.typography.labelSmall.copy(
              fontSize = if (compactLabels) 10.sp else 11.sp,
              lineHeight = if (compactLabels) 12.sp else 13.sp,
              fontWeight = FontWeight.Medium,
            ),
          color = Color.White,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      // Bottom scrim carrying the tag chips and the timestamp.
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .background(
              Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.88f)),
              ),
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        if (tagNames.isNotEmpty()) {
          BookmarkTagChipRow(tags = tagNames, maxChips = if (compactLabels) 2 else 3, dense = true)
        }
        Text(
          text = formatBookmarkTimecode(positionSeconds),
          style =
            MaterialTheme.typography.labelSmall.copy(
              fontSize = if (compactLabels) 9.5.sp else 10.5.sp,
              fontFeatureSettings = "tnum",
              fontWeight = FontWeight.SemiBold,
            ),
          color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
          maxLines = 1,
          softWrap = false,
        )
      }
    }
  }
}

/**
 * Renders tag chips inside a thumbnail scrim. Uses translucent fills so it stays readable over video
 * frames without a solid background band.
 */
@Composable
internal fun BookmarkTagChipRow(
  tags: List<String>,
  maxChips: Int = 3,
  dense: Boolean = false,
) {
  if (tags.isEmpty()) return
  val shown = tags.take(maxChips)
  val overflow = tags.size - shown.size
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    shown.forEach { tag ->
      Box(
        modifier =
          Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.22f))
            .padding(horizontal = 4.dp, vertical = if (dense) 0.5.dp else 2.dp),
      ) {
        Text(
          text = tag,
          style =
            MaterialTheme.typography.labelSmall.copy(
              fontSize = if (dense) 7.sp else 10.sp,
              lineHeight = if (dense) 9.sp else 13.sp,
            ),
          color = Color.White,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (overflow > 0) {
      Text(
        text = "+$overflow",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = if (dense) 7.sp else 10.sp),
        color = Color.White.copy(alpha = 0.85f),
        maxLines = 1,
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelToggleChip(
  label: String,
  icon: AppIcon,
  contentDescription: String,
  highlighted: Boolean = false,
  onClick: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color =
      if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
      } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
      },
    border =
      BorderStroke(
        1.dp,
        if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
      ),
    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(13.dp),
      )
      if (label.isNotEmpty()) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontFeatureSettings = "tnum"),
          fontWeight = FontWeight.SemiBold,
          color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelIconAction(
  icon: AppIcon,
  contentDescription: String,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
) {
  Box(
    modifier =
      Modifier
        .size(28.dp)
        .clip(RoundedCornerShape(8.dp))
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(18.dp),
    )
  }
}

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
  val galleryRepository = koinInject<BookmarkGalleryRepository>()
  val context = LocalContext.current
  val bookmarks by viewModel.playbackBookmarks.collectAsStateWithLifecycle()
  val sourceChapters by viewModel.sourceChapters.collectAsStateWithLifecycle()
  val cardCount by viewModel.bookmarkGridCardCount.collectAsStateWithLifecycle()
  val panelSizePercent by viewModel.bookmarkPanelSizePercent.collectAsStateWithLifecycle()
  val activeGroup by viewModel.activeBookmarkGroup.collectAsStateWithLifecycle()
  val haptics = rememberAppHaptics()
  val scope = rememberCoroutineScope()

  var menuBookmark by remember { mutableStateOf<PlaybackBookmarkEntity?>(null) }
  var showPresetDialog by remember { mutableStateOf(false) }
  var showGallery by remember { mutableStateOf(false) }
  var showTagFilterDialog by remember { mutableStateOf(false) }
  val isGlobalScope by viewModel.inPlayerBookmarkScope.collectAsStateWithLifecycle()
  val isSecureScope by viewModel.isCurrentMediaSecure.collectAsStateWithLifecycle()
  var portraitColumns by rememberSaveable { mutableStateOf(2) }
  var selectedTags by rememberSaveable { mutableStateOf(listOf<String>()) }

  // Reuse one flow per scope: creating it inline on every recomposition replaced the collector and
  // could leave the global list empty after a scope toggle.
  val galleryScope =
    if (isSecureScope) BookmarkGalleryScope.SecureVideos else BookmarkGalleryScope.AllVideos
  val globalGalleryItems by remember(galleryScope) { galleryRepository.observeItems(galleryScope) }
    .collectAsStateWithLifecycle(initialValue = emptyList())
  val availableTags by remember(galleryScope) { galleryRepository.observeFilterTags(galleryScope) }
    .collectAsStateWithLifecycle(initialValue = emptyList())
  val tagsByBookmark by viewModel.bookmarkTags.collectAsStateWithLifecycle()
  val globalTagsById =
    remember(globalGalleryItems) {
      globalGalleryItems.associate { it.row.id to it.tags }
    }
  val globalRawTitleById =
    remember(globalGalleryItems) {
      globalGalleryItems.associate { it.row.id to it.row.title }
    }
  fun tagsFor(bookmark: PlaybackBookmarkEntity): List<String> =
    if (isGlobalScope) {
      globalTagsById[bookmark.id] ?: tagsByBookmark[bookmark.id].orEmpty()
    } else {
      tagsByBookmark[bookmark.id].orEmpty()
    }

  val bookmarkMediaId by viewModel.bookmarkMediaId.collectAsStateWithLifecycle()
  val currentMediaId = bookmarkMediaId ?: bookmarks.firstOrNull()?.mediaId
  val baseBookmarks = remember(isGlobalScope, bookmarks, globalGalleryItems) {
    if (isGlobalScope) {
      globalGalleryItems.map { item ->
        PlaybackBookmarkEntity(
          id = item.row.id,
          mediaId = item.row.mediaId,
          positionMs = item.row.positionMs,
          title = formatGlobalBookmarkTitle(item.mediaTitle, item.row.title),
          createdAt = item.row.createdAt,
          thumbnailPath = item.row.thumbnailPath,
        )
      }
    } else {
      bookmarks
    }
  }

  val displayedBookmarks = remember(baseBookmarks, selectedTags, tagsByBookmark, globalTagsById, isGlobalScope) {
    if (selectedTags.isEmpty()) {
      baseBookmarks
    } else {
      val tagSet = selectedTags.toSet()
      baseBookmarks.filter { bm ->
        val itemTags = tagsFor(bm)
        itemTags.any { it in tagSet }
      }
    }
  }

  val onCardClick: (PlaybackBookmarkEntity) -> Unit = { bookmark ->
    scope.launch {
      // Same video seeks in place; a global row launches its own video at the saved timestamp.
      if (!viewModel.openBookmark(bookmark, context)) {
        Toast.makeText(context, R.string.bookmark_gallery_unavailable, Toast.LENGTH_SHORT).show()
      }
    }
  }

  val onQuickAdd: () -> Unit = {
    if (viewModel.quickAddBookmark()) {
      haptics.confirm()
    }
  }
  val onLongPressAdd: () -> Unit = {
    if (viewModel.preparePlaybackBookmark(returnSheet = Sheets.None)) {
      haptics.tick()
      onOpenSheet(Sheets.BookmarkEditor)
    }
  }

  Surface(
    modifier = modifier,
    color = MaterialTheme.colorScheme.surfaceContainer,
    tonalElevation = 2.dp,
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      if (isPortrait) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            text = stringResource(R.string.bookmark_panel_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          // Tag Filter icon button in portrait
          PanelIconAction(
            icon = Icons.RoundedFilled.FilterList,
            contentDescription = stringResource(R.string.bookmark_gallery_filter_tags),
            onClick = {
              haptics.tick()
              showTagFilterDialog = true
            },
          )
          // Portrait columns selector: strictly 2 or 3
          PanelToggleChip(
            label = "$portraitColumns Cols",
            icon = Icons.RoundedFilled.GridView,
            contentDescription = stringResource(R.string.bookmark_gallery_columns),
            onClick = {
              haptics.tick()
              portraitColumns = if (portraitColumns == 2) 3 else 2
            },
          )
          // Same scope toggle as the landscape sidebar: this video ⇄ every library bookmark.
          PanelToggleChip(
            label = if (isGlobalScope) "Global" else "Video",
            icon = if (isGlobalScope) Icons.RoundedFilled.Language else Icons.RoundedFilled.Movie,
            contentDescription = "Toggle Bookmark Scope",
            highlighted = isGlobalScope,
            onClick = {
              haptics.tick()
              viewModel.toggleInPlayerBookmarkScope()
            },
          )
          Spacer(Modifier.weight(1f))
          // One tap switches from this video's bookmarks to every video's, without leaving playback.
          PanelIconAction(
            icon = Icons.RoundedFilled.Bookmarks,
            contentDescription = stringResource(R.string.bookmark_gallery_open),
            onClick = {
              haptics.tick()
              showGallery = true
            },
          )
          PanelIconAction(
            icon = Icons.RoundedFilled.Add,
            contentDescription = stringResource(R.string.audiobook_add_bookmark),
            onClick = onQuickAdd,
            onLongClick = onLongPressAdd,
          )
          PanelIconAction(
            icon = Icons.RoundedFilled.ZoomOutMap,
            contentDescription = stringResource(R.string.bookmark_switch_to_overlay),
            onClick = onSwitchToOverlay,
          )
          PanelIconAction(
            icon = Icons.RoundedFilled.Close,
            contentDescription = stringResource(R.string.bookmark_close_panel),
            onClick = onClose,
          )
        }
      } else {
        Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
          verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
          // Tier 1: Action Icons
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.bookmark_panel_title),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            PanelIconAction(
              icon = Icons.RoundedFilled.FilterList,
              contentDescription = stringResource(R.string.bookmark_gallery_filter_tags),
              onClick = {
                haptics.tick()
                showTagFilterDialog = true
              },
            )
            PanelIconAction(
              icon = Icons.RoundedFilled.Bookmarks,
              contentDescription = stringResource(R.string.bookmark_gallery_open),
              onClick = {
                haptics.tick()
                showGallery = true
              },
            )
            PanelIconAction(
              icon = Icons.RoundedFilled.Add,
              contentDescription = stringResource(R.string.audiobook_add_bookmark),
              onClick = onQuickAdd,
              onLongClick = onLongPressAdd,
            )
            PanelIconAction(
              icon = Icons.RoundedFilled.ZoomOutMap,
              contentDescription = stringResource(R.string.bookmark_switch_to_overlay),
              onClick = onSwitchToOverlay,
            )
            PanelIconAction(
              icon = Icons.RoundedFilled.Close,
              contentDescription = stringResource(R.string.bookmark_close_panel),
              onClick = onClose,
            )
          }
          // Tier 2: Compact Toggle Chips
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            PanelToggleChip(
              label = if (isGlobalScope) "Global" else "Video",
              icon = if (isGlobalScope) Icons.RoundedFilled.Language else Icons.RoundedFilled.Movie,
              contentDescription = "Toggle Bookmark Scope",
              highlighted = isGlobalScope,
              onClick = {
                haptics.tick()
                viewModel.toggleInPlayerBookmarkScope()
              },
            )
            PanelToggleChip(
              label = "${panelSizePercent}%",
              icon = Icons.RoundedFilled.AspectRatio,
              contentDescription = stringResource(R.string.bookmark_panel_size_cd),
              onClick = {
                haptics.tick()
                viewModel.cycleBookmarkPanelSizePercent()
              },
            )
            PanelToggleChip(
              label = "$cardCount",
              icon = Icons.RoundedFilled.GridView,
              contentDescription = stringResource(R.string.bookmark_panel_card_count_cd),
              onClick = {
                haptics.tick()
                viewModel.cycleBookmarkGridCardCount()
              },
            )
            if (!activeGroup?.presetCode.isNullOrBlank()) {
              PanelToggleChip(
                label = activeGroup?.presetCode?.take(5).orEmpty(),
                icon = Icons.RoundedFilled.Fingerprint,
                contentDescription = stringResource(R.string.bookmark_preset_button_cd),
                highlighted = true,
                onClick = {
                  haptics.tick()
                  showPresetDialog = true
                },
              )
            }
          }
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

      val activeChapterIndex =
        remember(sourceChapters, currentPositionSeconds) {
          sourceChapters.indexOfLast { it.start <= currentPositionSeconds }
        }
      val displayChapters =
        remember(sourceChapters, chapters) {
          if (sourceChapters.isNotEmpty()) sourceChapters else emptyList()
        }

      if (displayedBookmarks.isEmpty() && displayChapters.isEmpty()) {
        Box(
          modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.playback_bookmarks_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      } else {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
          val spacing = 6.dp
          val currentSecInt = currentPositionSeconds.toInt()

          if (isPortrait) {
            // Height follows the column width so a card is always exactly 16:9.
            val cols = portraitColumns
            val slotWidth = (maxWidth - 12.dp - spacing * (cols - 1)) / cols
            val rowHeight = (slotWidth * 9f / 16f).coerceIn(48.dp, 184.dp)
            LazyVerticalGrid(
              columns = GridCells.Fixed(cols),
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(6.dp),
              horizontalArrangement = Arrangement.spacedBy(spacing),
              verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
              items(
                items = displayedBookmarks,
                key = { "bm_${it.id}" },
              ) { bookmark ->
                val posSec = (viewModel.bookmarkPositionMs(bookmark) / 1000L).toInt().coerceAtLeast(0)
                val isSelected =
                  (!isGlobalScope || bookmark.mediaId == currentMediaId) &&
                    abs(posSec - currentSecInt) <= 2
                BookmarkGridCard(
                  bookmark = bookmark,
                  positionSeconds = posSec,
                  selected = isSelected,
                  compactLabels = cardCount >= 5,
                  tagNames = tagsFor(bookmark),
                  isSecure = isSecureScope,
                  onClick = { onCardClick(bookmark) },
                  onLongClick = {
                    haptics.tick()
                    menuBookmark = bookmark
                  },
                  modifier = Modifier.fillMaxWidth().height(rowHeight),
                )
              }

              if (displayChapters.isNotEmpty()) {
                item(span = { GridItemSpan(cols) }) {
                  Text(
                    text = stringResource(R.string.bookmark_list_section_chapters),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                  )
                }
                items(
                  count = displayChapters.size,
                  key = { idx -> "ch_$idx" },
                  span = { GridItemSpan(cols) },
                ) { idx ->
                  val chapter = displayChapters[idx]
                  val selected = idx == activeChapterIndex
                  Surface(
                    shape = RoundedCornerShape(8.dp),
                    color =
                      if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                      } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                      },
                    modifier =
                      Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.seekToPlaybackChapter(chapter) },
                  ) {
                    Row(
                      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                      Text(
                        text = chapter.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                      )
                      Spacer(Modifier.width(8.dp))
                      Text(
                        text = formatBookmarkTimecode(chapter.start.toInt()),
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }
                }
              }
            }
          } else {
            // Landscape sidebar: preserve full 16:9 frame height without an artificial 140dp cap
            // so wide sidebar widths (e.g. 30%-35%) show the full uncropped frame.
            val rowHeight = ((maxWidth - 12.dp) * 9f / 16f).coerceIn(48.dp, 220.dp)
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(6.dp),
              verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
              items(
                items = displayedBookmarks,
                key = { "bm_${it.id}" },
              ) { bookmark ->
                val posSec = (viewModel.bookmarkPositionMs(bookmark) / 1000L).toInt().coerceAtLeast(0)
                val isSelected =
                  (!isGlobalScope || bookmark.mediaId == currentMediaId) &&
                    abs(posSec - currentSecInt) <= 2
                BookmarkGridCard(
                  bookmark = bookmark,
                  positionSeconds = posSec,
                  selected = isSelected,
                  compactLabels = cardCount >= 5,
                  tagNames = tagsFor(bookmark),
                  isSecure = isSecureScope,
                  onClick = { onCardClick(bookmark) },
                  onLongClick = {
                    haptics.tick()
                    menuBookmark = bookmark
                  },
                  modifier = Modifier.fillMaxWidth().height(rowHeight),
                )
              }

              if (displayChapters.isNotEmpty()) {
                item(key = "ch_header") {
                  Text(
                    text = stringResource(R.string.bookmark_list_section_chapters),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                  )
                }
                items(
                  count = displayChapters.size,
                  key = { idx -> "ch_$idx" },
                ) { idx ->
                  val chapter = displayChapters[idx]
                  val selected = idx == activeChapterIndex
                  Surface(
                    shape = RoundedCornerShape(8.dp),
                    color =
                      if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                      } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                      },
                    modifier =
                      Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.seekToPlaybackChapter(chapter) },
                  ) {
                    Row(
                      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                      Text(
                        text = chapter.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                      )
                      Spacer(Modifier.width(6.dp))
                      Text(
                        text = formatBookmarkTimecode(chapter.start.toInt()),
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  menuBookmark?.let { target ->
    val targetPosSec = (viewModel.bookmarkPositionMs(target) / 1000L).toInt().coerceAtLeast(0)
    BookmarkActionMenuDialog(
      bookmark = target,
      formattedTimestamp = formatBookmarkTimecode(targetPosSec),
      isSecure = isSecureScope,
      onRename = {
        val editableTarget = globalRawTitleById[target.id]?.let { raw -> target.copy(title = raw) } ?: target
        if (viewModel.preparePlaybackBookmark(editableTarget, returnSheet = Sheets.None)) {
          onOpenSheet(Sheets.BookmarkEditor)
        }
      },
      onDelete = {
        scope.launch {
          viewModel.deletePlaybackBookmark(target)
          haptics.tick()
        }
      },
      onDismiss = { menuBookmark = null },
    )
  }

  if (showPresetDialog) {
    BookmarkPresetDialog(
      viewModel = viewModel,
      onDismiss = { showPresetDialog = false },
    )
  }

  if (showGallery) {
    app.gyrolet.mpvrx.ui.bookmarks.BookmarkGalleryOverlay(
      onClose = { showGallery = false },
      secureOnly = isSecureScope,
      currentMediaId = currentMediaId,
      initialOnlyCurrentVideo = !isGlobalScope,
    )
  }

  if (showTagFilterDialog) {
    Dialog(
      onDismissRequest = { showTagFilterDialog = false },
    ) {
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
      ) {
        Column(
          modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.bookmark_gallery_filter_tags),
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
            )
            Icon(
              Icons.RoundedFilled.Close,
              contentDescription = null,
              modifier = Modifier.size(18.dp).clickable { showTagFilterDialog = false },
            )
          }
          if (availableTags.isEmpty()) {
            Text(
              text = stringResource(R.string.bookmark_gallery_empty),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            FlowRow(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              availableTags.forEach { tag ->
                val isSelected = tag in selectedTags
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest,
                  modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    selectedTags = if (isSelected) selectedTags - tag else selectedTags + tag
                  },
                ) {
                  Text(
                    text = "#$tag",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                  )
                }
              }
            }
          }
          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
          ) {
            if (selectedTags.isNotEmpty()) {
              TextButton(onClick = { selectedTags = emptyList() }) {
                Text(stringResource(R.string.bookmark_gallery_clear_filters))
              }
            }
            TextButton(onClick = { showTagFilterDialog = false }) {
              Text("Done")
            }
          }
        }
      }
    }
  }
}
