/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.bookmarks

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookmarkGalleryContent(
  secureOnly: Boolean,
  onClose: () -> Unit,
) {
  val context = LocalContext.current
  val repository = koinInject<BookmarkGalleryRepository>()

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
        .take(if (query.isEmpty()) 8 else 12)
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = stringResource(R.string.bookmark_gallery_title),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = stringResource(R.string.bookmark_gallery_count, filtered.size),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(Icons.RoundedFilled.ArrowBack, contentDescription = null)
          }
        },
        actions = {
          // 0 asks the grid to fit as many 16:9 cards as the width allows; a number pins it.
          TextButton(onClick = { columnSetting = nextColumnSetting(columnSetting) }) {
            Icon(
              imageVector = Icons.RoundedFilled.GridView,
              contentDescription = stringResource(R.string.bookmark_gallery_columns),
              modifier = Modifier.size(18.dp),
            )
            Text(
              text =
                if (columnSetting in 2..6) {
                  columnSetting.toString()
                } else {
                  stringResource(R.string.bookmark_gallery_columns_auto)
                },
              style = MaterialTheme.typography.labelMedium,
              modifier = Modifier.padding(start = 4.dp),
            )
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Opened from the Secure Folder the gallery must not be able to widen back out to public
        // videos, so the All chip is only offered when it was opened from the public library.
        if (!secureOnly) {
          FilterChip(
            selected = scope == BookmarkGalleryScope.AllVideos,
            onClick = { scope = BookmarkGalleryScope.AllVideos },
            label = { Text(stringResource(R.string.bookmark_gallery_scope_all)) },
          )
        }
        FilterChip(
          selected = scope == BookmarkGalleryScope.SecureVideos,
          onClick = { scope = BookmarkGalleryScope.SecureVideos },
          label = { Text(stringResource(R.string.bookmark_gallery_scope_secure)) },
        )
      }

      AppTextField(
        value = filterQuery,
        onValueChange = { filterQuery = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        singleLine = true,
        label = { Text(stringResource(R.string.bookmark_gallery_filter_tags)) },
        leadingIcon = {
          Icon(Icons.RoundedFilled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        trailingIcon = {
          if (filterQuery.isNotBlank() || selectedTags.isNotEmpty()) {
            IconButton(
              onClick = {
                filterQuery = ""
                selectedTags = emptyList()
              },
            ) {
              Icon(Icons.RoundedFilled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            }
          }
        },
      )

      if (selectedTags.isNotEmpty()) {
        FlowRow(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          selectedTags.forEach { tag ->
            FilterChip(
              selected = true,
              onClick = { selectedTags = selectedTags - tag },
              label = { Text(tag) },
              trailingIcon = {
                Icon(Icons.RoundedFilled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
              },
            )
          }
          Text(
            text = stringResource(R.string.bookmark_gallery_clear_filters),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier =
              Modifier
                .align(Alignment.CenterVertically)
                .clip(RoundedCornerShape(6.dp))
                .clickable {
                  selectedTags = emptyList()
                  filterQuery = ""
                }
                .padding(horizontal = 6.dp, vertical = 4.dp),
          )
        }
      }

      if (suggestions.isNotEmpty()) {
        FlowRow(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          suggestions.forEach { tag ->
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
              modifier =
                Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                  selectedTags = (selectedTags + tag).distinct()
                  filterQuery = ""
                },
            ) {
              Text(
                text = tag,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }
        }
      }

      if (filtered.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
          Text(
            text = stringResource(R.string.bookmark_gallery_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        BoxWithConstraints(Modifier.fillMaxSize()) {
          val columns =
            if (columnSetting in 2..6) {
              columnSetting
            } else {
              // Cards stay 16:9, so auto-fit only has to keep them legible.
              (maxWidth.value / 190f).toInt().coerceIn(2, 6)
            }
          LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(filtered, key = { it.row.id }) { item ->
              BookmarkGalleryCard(
                item = item,
                onOpen = {
                  val target = item.playableUri
                  if (target.isNullOrBlank()) {
                    Toast.makeText(context, R.string.bookmark_gallery_unavailable, Toast.LENGTH_SHORT).show()
                  } else {
                    // Reopen the video at this bookmark's own timestamp.
                    MediaUtils.playFile(
                      source = target,
                      context = context,
                      launchSource = "bookmark_gallery",
                      title = item.mediaTitle ?: item.row.title,
                      startPositionSeconds = item.row.positionMs / 1000.0,
                    )
                    // Leave the gallery so returning from playback lands on whatever opened it.
                    onClose()
                  }
                },
              )
            }
          }
        }
      }
    }
  }
}

/** 0 (auto) -> 2 -> 3 -> 4 -> 5 -> 6 -> 0. */
private fun nextColumnSetting(current: Int): Int = when (current) {
  2 -> 3
  3 -> 4
  4 -> 5
  5 -> 6
  6 -> 0
  else -> 2
}

@Composable
private fun BookmarkGalleryCard(
  item: BookmarkGalleryItem,
  onOpen: () -> Unit,
) {
  // Exactly one 16:9 frame: title at the top, tags and timestamp at the bottom, nothing outside.
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    modifier =
      Modifier
        .fillMaxWidth()
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
