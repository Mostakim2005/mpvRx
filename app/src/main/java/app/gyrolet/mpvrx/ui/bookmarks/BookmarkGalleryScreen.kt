/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.bookmarks

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.bookmark.BookmarkGalleryItem
import app.gyrolet.mpvrx.domain.bookmark.BookmarkGalleryRepository
import app.gyrolet.mpvrx.domain.bookmark.BookmarkGalleryScope
import app.gyrolet.mpvrx.domain.bookmark.BookmarkTagRules
import app.gyrolet.mpvrx.domain.bookmark.filterByTags
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.AppTextField
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.BookmarkTagChipRow
import app.gyrolet.mpvrx.ui.player.controls.components.BookmarkThumbnailImage
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.media.MediaUtils
import java.util.Locale
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/** Cards never shrink below this width; auto-fit divides the available width by it. */
private val MIN_CARD_WIDTH = 150.dp

/** Max columns on a phone in portrait. */
private const val PORTRAIT_MAX_COLUMNS = 3

/** Max columns in landscape, where two full rows still fit the height budget. */
private const val LANDSCAPE_MAX_COLUMNS = 4

/**
 * Cross-video bookmark browser.
 *
 * [secureOnly] selects the Secure Folder slice. The membership test lives in
 * [BookmarkGalleryRepository] so this screen never has to know how secure identity is derived.
 */
@Serializable
data class BookmarkGalleryScreen(
  val secureOnly: Boolean = false,
) : Screen {
  @Composable
  override fun Content() {
    val backStack = LocalBackStack.current
    BookmarkGalleryContent(secureOnly = secureOnly, onClose = { backStack.popSafely() })
  }
}

/**
 * Full-window gallery for the player, which has no navigation back stack of its own.
 *
 * The player opens this instead of pushing a screen: the bookmark sidebar lives inside the player
 * activity, so a dialog keeps its window, orientation and playback alive.
 */
@Composable
fun BookmarkGalleryOverlay(
  onClose: () -> Unit,
  secureOnly: Boolean = false,
) {
  Dialog(
    onDismissRequest = onClose,
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      BookmarkGalleryContent(secureOnly = secureOnly, onClose = onClose)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookmarkGalleryContent(
  secureOnly: Boolean,
  onClose: () -> Unit,
) {
  val context = LocalContext.current
  val repository = koinInject<BookmarkGalleryRepository>()
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

  var scope by rememberSaveable {
    mutableStateOf(if (secureOnly) BookmarkGalleryScope.SecureVideos else BookmarkGalleryScope.AllVideos)
  }
  var columnSetting by rememberSaveable { mutableStateOf(0) }
  var selectedTags by rememberSaveable { mutableStateOf(listOf<String>()) }
  var filterQuery by rememberSaveable { mutableStateOf("") }

  val items by repository.observeItems(scope).collectAsStateWithLifecycle(initialValue = emptyList())
  val vocabulary by repository.observeFilterTags().collectAsStateWithLifecycle(initialValue = emptyList())

  val filtered = remember(items, selectedTags) { items.filterByTags(selectedTags.toSet()) }
  val suggestions =
    remember(vocabulary, filterQuery, selectedTags) {
      val query = BookmarkTagRules.normalize(filterQuery)
      vocabulary
        .filter { it !in selectedTags }
        .filter { query.isEmpty() || it.lowercase().contains(query) }
        .take(if (query.isEmpty()) 6 else 10)
    }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val maxColumnCount = if (isLandscape) LANDSCAPE_MAX_COLUMNS else PORTRAIT_MAX_COLUMNS
    val columns =
      resolveColumnCount(
        setting = columnSetting,
        availableWidth = maxWidth,
        maxColumns = maxColumnCount,
      )
    val openItem: (BookmarkGalleryItem) -> Unit = { item ->
      val target = item.playableUri
      if (target.isNullOrBlank()) {
        Toast.makeText(context, R.string.bookmark_gallery_unavailable, Toast.LENGTH_SHORT).show()
      } else {
        // Reopen the video at this bookmark's own timestamp. Secure targets are gated by
        // MediaUtils before the player is ever started.
        MediaUtils.playFile(
          source = target,
          context = context,
          launchSource = "bookmark_gallery",
          title = item.mediaTitle ?: item.row.title,
          startPositionSeconds = item.row.positionMs / 1000.0,
        )
        onClose()
      }
    }

    Column(Modifier.fillMaxSize()) {
      // ── Controls + search ──────────────────────────────
      Column(Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          SmallIconAction(
            icon = Icons.RoundedFilled.ArrowBack,
            contentDescription = null,
            onClick = onClose,
          )
          Text(
            text = stringResource(R.string.bookmark_gallery_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = " ${filtered.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
          )
          Spacer(Modifier.width(4.dp))
          // Opened from the Secure Folder the gallery must not be able to widen back out to public
          // videos, so the All pill is only offered when it was opened from the public library.
          if (!secureOnly) {
            ScopePill(
              label = stringResource(R.string.bookmark_gallery_scope_all),
              selected = scope == BookmarkGalleryScope.AllVideos,
              onClick = { scope = BookmarkGalleryScope.AllVideos },
            )
          }
          ScopePill(
            label = stringResource(R.string.bookmark_gallery_scope_secure),
            selected = scope == BookmarkGalleryScope.SecureVideos,
            onClick = { scope = BookmarkGalleryScope.SecureVideos },
          )
          Spacer(Modifier.weight(1f))
          // 0 asks the grid to fit as many cards as the width allows; a number pins it.
          SmallIconAction(
            icon = Icons.RoundedFilled.GridView,
            contentDescription = stringResource(R.string.bookmark_gallery_columns),
            onClick = {
              columnSetting = nextColumnSetting(columnSetting, maxColumnCount)
            },
            label =
              if (columnSetting in 2..maxColumnCount) {
                columnSetting.toString()
              } else {
                stringResource(R.string.bookmark_gallery_columns_auto)
              },
          )
        }

        AppTextField(
          value = filterQuery,
          onValueChange = { filterQuery = it },
          modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
          singleLine = true,
          label = { Text(stringResource(R.string.bookmark_gallery_filter_tags)) },
          leadingIcon = {
            Icon(Icons.RoundedFilled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
          },
          trailingIcon = {
            if (filterQuery.isNotBlank() || selectedTags.isNotEmpty()) {
              SmallIconAction(
                icon = Icons.RoundedFilled.Close,
                contentDescription = null,
                onClick = {
                  filterQuery = ""
                  selectedTags = emptyList()
                },
              )
            }
          },
        )

        if (selectedTags.isNotEmpty()) {
          FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            selectedTags.forEach { tag ->
              TagPill(text = tag, onRemove = { selectedTags = selectedTags - tag })
            }
            Text(
              text = stringResource(R.string.bookmark_gallery_clear_filters),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              modifier =
                Modifier
                  .clip(RoundedCornerShape(6.dp))
                  .clickable {
                    selectedTags = emptyList()
                    filterQuery = ""
                  }
                  .padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }

        if (suggestions.isNotEmpty()) {
          LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            items(suggestions, key = { it }) { tag ->
              TagPill(
                text = tag,
                onClick = {
                  selectedTags = (selectedTags + tag).distinct()
                  filterQuery = ""
                },
              )
            }
          }
        }
      }

      // ── Naturally Vertically Scrollable Grid of 16:9 Cards ───────────────
      if (filtered.isEmpty()) {
        Box(
          Modifier.fillMaxWidth().weight(1f),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.bookmark_gallery_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
          )
        }
      } else {
        LazyVerticalGrid(
          columns = GridCells.Fixed(columns),
          modifier = Modifier.fillMaxWidth().weight(1f),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          items(filtered, key = { it.row.id }) { item ->
            BookmarkGalleryCard(
              item = item,
              onOpen = { openItem(item) },
            )
          }
        }
      }
    }
  }
}

/** 0 (auto) -> 2 -> 3 -> (4 in landscape) -> 0, never exceeding the orientation cap. */
private fun nextColumnSetting(
  current: Int,
  maxColumns: Int,
): Int {
  val highest = maxColumns.coerceAtLeast(2)
  return if (current in 2 until highest) current + 1 else if (current == highest) 0 else 2
}

private fun resolveColumnCount(
  setting: Int,
  availableWidth: Dp,
  maxColumns: Int,
): Int =
  if (setting in 2..maxColumns) {
    setting
  } else {
    // Cards stay 16:9, so auto-fit only has to keep them legible.
    (availableWidth.value / MIN_CARD_WIDTH.value).toInt().coerceIn(2, maxColumns)
  }

@Composable
private fun SmallIconAction(
  icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
  contentDescription: String?,
  onClick: () -> Unit,
  label: String? = null,
) {
  Row(
    modifier =
      Modifier
        .clip(RoundedCornerShape(8.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    if (label != null) {
      Text(text = label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun ScopePill(
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
    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
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
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
    )
  }
}

@Composable
private fun TagPill(
  text: String,
  onRemove: (() -> Unit)? = null,
  onClick: (() -> Unit)? = null,
) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier =
      Modifier
        .clip(RoundedCornerShape(8.dp))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(text = text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
      if (onRemove != null) {
        Icon(
          Icons.RoundedFilled.Close,
          contentDescription = null,
          modifier = Modifier.size(12.dp).clickable(onClick = onRemove),
        )
      }
    }
  }
}

@Composable
private fun BookmarkGalleryCard(
  item: BookmarkGalleryItem,
  onOpen: () -> Unit,
  width: Dp = Dp.Unspecified,
) {
  // Exactly one 16:9 frame: title at the top, tags and timestamp at the bottom, nothing outside.
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    modifier =
      Modifier
        .then(if (width != Dp.Unspecified && width > 0.dp) Modifier.width(width) else Modifier.fillMaxWidth())
        .aspectRatio(16f / 9f)
        .clip(RoundedCornerShape(10.dp))
        .clickable(onClick = onOpen),
  ) {
    Box(Modifier.fillMaxSize()) {
      BookmarkThumbnailImage(
        thumbnailPath = item.row.thumbnailPath,
        cornerRadius = 0.dp,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .background(
              Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.80f), Color.Black.copy(alpha = 0.26f), Color.Transparent),
              ),
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
      ) {
        Text(
          text = item.row.title,
          style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
          color = Color.White,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .background(
              Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.88f)),
              ),
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        if (item.tags.isNotEmpty()) {
          BookmarkTagChipRow(tags = item.tags, maxChips = 2, dense = true)
        }
        Text(
          text = formatGalleryTimestamp(item.row.positionMs),
          style =
            MaterialTheme.typography.labelSmall.copy(
              fontSize = 10.sp,
              fontFeatureSettings = "tnum",
              fontWeight = FontWeight.SemiBold,
            ),
          color = Color.White,
          maxLines = 1,
          softWrap = false,
        )
      }
    }
  }
}

private fun formatGalleryTimestamp(positionMs: Long): String {
  val total = (positionMs / 1000L).coerceAtLeast(0L)
  return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
}
