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
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.defaultMinSize
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
import app.gyrolet.mpvrx.domain.bookmark.BookmarkMediaMatcher
import app.gyrolet.mpvrx.domain.bookmark.BookmarkTagRules
import app.gyrolet.mpvrx.domain.bookmark.filterByTags
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppTextField
import app.gyrolet.mpvrx.repository.bookmark.PcBookmarkSyncRepository
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.BookmarkTagChipRow
import app.gyrolet.mpvrx.ui.player.controls.components.BookmarkThumbnailImage
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.launch
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
  currentMediaId: String? = null,
  initialOnlyCurrentVideo: Boolean = false,
) {
  Dialog(
    onDismissRequest = onClose,
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      BookmarkGalleryContent(
        secureOnly = secureOnly,
        onClose = onClose,
        currentMediaId = currentMediaId,
        initialOnlyCurrentVideo = initialOnlyCurrentVideo,
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookmarkGalleryContent(
  secureOnly: Boolean,
  onClose: () -> Unit,
  currentMediaId: String? = null,
  initialOnlyCurrentVideo: Boolean = false,
) {
  val context = LocalContext.current
  val repository = koinInject<BookmarkGalleryRepository>()
  val syncRepository = koinInject<PcBookmarkSyncRepository>()
  val coroutineScope = rememberCoroutineScope()
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val isUnlocked by app.gyrolet.mpvrx.domain.secure.SecureFolderSession.unlockedState.collectAsStateWithLifecycle()

  LaunchedEffect(secureOnly, isUnlocked) {
    if (secureOnly && !isUnlocked) onClose()
  }

  val scope = if (secureOnly) BookmarkGalleryScope.SecureVideos else BookmarkGalleryScope.AllVideos
  // The player passes its current group key, so the full-window gallery respects Video ⇄ Global
  // directly. Public gallery never widens to SecureVideos, and Secure gallery never widens to AllVideos.
  var onlyCurrentVideo by rememberSaveable(currentMediaId, initialOnlyCurrentVideo) {
    mutableStateOf(initialOnlyCurrentVideo && currentMediaId != null)
  }
  var onlyUnlinked by rememberSaveable { mutableStateOf(false) }
  var isSyncing by remember { mutableStateOf(false) }
  var showJavidCatalog by remember { mutableStateOf(false) }
  var selectedCatalogGroupKey by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingLinkGroupKey by remember { mutableStateOf<String?>(null) }
  var pendingLinkPresetCode by remember { mutableStateOf<String?>(null) }
  var pendingLinkSourceUrl by remember { mutableStateOf<String?>(null) }
  var managingLinkItem by remember { mutableStateOf<BookmarkGalleryItem?>(null) }
  var columnSetting by rememberSaveable { mutableStateOf(0) }
  var selectedTags by rememberSaveable { mutableStateOf(listOf<String>()) }
  var filterQuery by rememberSaveable { mutableStateOf("") }

  val syncProgress by syncRepository.syncProgress.collectAsStateWithLifecycle()
  val catalogEntries by syncRepository.observeJavidCatalog().collectAsStateWithLifecycle(initialValue = emptyList())

  LaunchedEffect(secureOnly) {
    if (!secureOnly) {
      val matched = syncRepository.autoMatchOrphanGroupsIfNeeded(force = true)
      if (matched > 0) {
        Toast.makeText(
          context,
          context.getString(R.string.bookmark_gallery_auto_matched_toast, matched),
          Toast.LENGTH_SHORT,
        ).show()
      }
    }
  }

  val linkVideoLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pickedUri ->
      val targetKey = pendingLinkGroupKey
      val overrideCode = pendingLinkPresetCode
      val overrideUrl = pendingLinkSourceUrl
      pendingLinkGroupKey = null
      pendingLinkPresetCode = null
      pendingLinkSourceUrl = null
      if (pickedUri != null && !targetKey.isNullOrBlank()) {
        coroutineScope.launch {
          syncRepository
            .linkOrphanGroupToUri(
              targetKey,
              pickedUri,
              overridePresetCode = overrideCode,
              overrideSourceUrl = overrideUrl,
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
      }
    }

  val items by remember(scope) {
    repository.observeItems(scope)
  }.collectAsStateWithLifecycle(initialValue = emptyList())
  val vocabulary by remember(scope) {
    repository.observeFilterTags(scope)
  }.collectAsStateWithLifecycle(initialValue = emptyList())
  val hasUnlinkedItems = remember(items, secureOnly) { !secureOnly && items.any { it.playableUri.isNullOrBlank() } }

  val filtered =
    remember(
      items,
      selectedTags,
      onlyCurrentVideo,
      onlyUnlinked,
      selectedCatalogGroupKey,
      currentMediaId,
      secureOnly,
    ) {
      items
        .filterByTags(selectedTags.toSet())
        .filter { item ->
          val matchesCatalog =
            selectedCatalogGroupKey == null || item.row.mediaId == selectedCatalogGroupKey
          val matchesVideo = !onlyCurrentVideo || currentMediaId == null || item.row.mediaId == currentMediaId
          val matchesUnlinked = !onlyUnlinked || (!secureOnly && item.playableUri.isNullOrBlank())
          matchesCatalog && matchesVideo && matchesUnlinked
        }
    }
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
    val launchPointToVideo: (BookmarkGalleryItem, String?) -> Unit = { item, codeOverride ->
      pendingLinkGroupKey = item.row.mediaId
      pendingLinkPresetCode = codeOverride
      linkVideoLauncher.launch(arrayOf("video/*", "*/*"))
    }
    val openItem: (BookmarkGalleryItem) -> Unit = { item ->
      val target = item.playableUri
      if (target.isNullOrBlank()) {
        if (!secureOnly) {
          managingLinkItem = item
        } else {
          Toast.makeText(context, R.string.bookmark_gallery_unavailable, Toast.LENGTH_SHORT).show()
        }
      } else {
        MediaUtils.playFile(
          source = target,
          context = context,
          launchSource = if (secureOnly) "secure_folder" else "bookmark_gallery",
          title = item.mediaTitle ?: item.row.title,
          startPositionSeconds = item.row.positionMs / 1000.0,
        )
        onClose()
      }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
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
          if (currentMediaId != null) {
            ScopePill(
              label = stringResource(R.string.bookmark_gallery_scope_video),
              selected = onlyCurrentVideo,
              onClick = { onlyCurrentVideo = true },
            )
          }
          if (secureOnly) {
            ScopePill(
              label = stringResource(R.string.bookmark_gallery_scope_secure),
              selected = !onlyCurrentVideo,
              onClick = { onlyCurrentVideo = false },
            )
          } else {
            ScopePill(
              label = stringResource(R.string.bookmark_gallery_scope_all),
              selected = !onlyCurrentVideo,
              onClick = { onlyCurrentVideo = false },
            )
          }
          Spacer(Modifier.weight(1f))
          if (!secureOnly) {
            SmallIconAction(
              icon = Icons.RoundedFilled.VideoLibrary,
              contentDescription = stringResource(R.string.bookmark_javid_catalog_button),
              onClick = { showJavidCatalog = true },
            )
            SmallIconAction(
              icon = Icons.RoundedFilled.AutoFixHigh,
              contentDescription = stringResource(R.string.bookmark_gallery_auto_match),
              onClick = {
                if (!isSyncing) {
                  isSyncing = true
                  coroutineScope.launch {
                    val count = syncRepository.autoMatchOrphanGroupsWithLocalLibrary()
                    Toast.makeText(
                      context,
                      if (count > 0) {
                        context.getString(R.string.bookmark_gallery_auto_matched_toast, count)
                      } else {
                        context.getString(R.string.bookmark_gallery_auto_match_none)
                      },
                      Toast.LENGTH_LONG,
                    ).show()
                    isSyncing = false
                  }
                }
              },
            )
            SmallIconAction(
              icon = Icons.RoundedFilled.Refresh,
              contentDescription = stringResource(R.string.bookmark_gallery_sync_pc),
              onClick = {
                if (!isSyncing) {
                  isSyncing = true
                  coroutineScope.launch {
                    syncRepository
                      .syncWithPcHub()
                      .onSuccess { res ->
                        val baseMsg =
                          context.getString(
                            R.string.bookmark_gallery_sync_done,
                            res.inserted,
                            res.updated,
                            res.thumbnailsDownloaded,
                          )
                        val msg =
                          if (res.autoMatchedGroups > 0) {
                            "$baseMsg • ${res.autoMatchedGroups} matched"
                          } else {
                            baseMsg
                          }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                      }.onFailure { err ->
                        Toast.makeText(
                          context,
                          context.getString(R.string.bookmark_gallery_sync_failed, err.message ?: "Error"),
                          Toast.LENGTH_LONG,
                        ).show()
                      }
                    isSyncing = false
                  }
                }
              },
              label = if (isSyncing) "..." else null,
            )
          }
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

        if (!secureOnly && syncProgress.active) {
          SyncProgressBanner(
            progress = syncProgress,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }

        val activeScopeGroupKey = selectedCatalogGroupKey ?: if (onlyCurrentVideo) currentMediaId else null
        val activeScopeGroup =
          if (!secureOnly && activeScopeGroupKey != null) {
            catalogEntries.firstOrNull { it.groupKey == activeScopeGroupKey }
          } else {
            null
          }
        if (selectedCatalogGroupKey != null || activeScopeGroup?.sourceUrl != null) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text =
                  stringResource(
                    R.string.bookmark_javid_clear_group_filter,
                    activeScopeGroup?.javidCode ?: selectedCatalogGroupKey.orEmpty(),
                  ),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              val validGroupUrl =
                activeScopeGroup?.sourceUrl?.takeIf {
                  it.startsWith("http://", ignoreCase = true) ||
                    it.startsWith("https://", ignoreCase = true)
                }
              if (validGroupUrl != null) {
                TextButton(
                  onClick = {
                    runCatching {
                      context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(validGroupUrl)))
                    }
                  },
                ) {
                  Icon(Icons.RoundedFilled.Link, contentDescription = null, modifier = Modifier.size(15.dp))
                  Spacer(Modifier.width(4.dp))
                  Text(stringResource(R.string.bookmark_gallery_open_url))
                }
              }
              if (selectedCatalogGroupKey != null) {
                SmallIconAction(
                  icon = Icons.RoundedFilled.Close,
                  contentDescription = null,
                  onClick = { selectedCatalogGroupKey = null },
                )
              }
            }
          }
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

        if (suggestions.isNotEmpty() || hasUnlinkedItems || onlyUnlinked) {
          LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            if (hasUnlinkedItems || onlyUnlinked) {
              item(key = "__unlinked_filter") {
                ScopePill(
                  label = stringResource(R.string.bookmark_gallery_filter_unlinked),
                  selected = onlyUnlinked,
                  onClick = {
                    onlyUnlinked = !onlyUnlinked
                    if (onlyUnlinked) onlyCurrentVideo = false
                  },
                )
              }
            }
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
              onLinkVideo = if (!secureOnly) {
                { managingLinkItem = item }
              } else {
                null
              },
              isSecure = secureOnly,
              showMediaTitle = !onlyCurrentVideo,
            )
          }
        }
      }
    }
  }

  managingLinkItem?.let { item ->
    ManageBookmarkGroupLinkDialog(
      item = item,
      syncRepository = syncRepository,
      onDismiss = { managingLinkItem = null },
      onPickFile = { codeText, urlText ->
        managingLinkItem = null
        pendingLinkGroupKey = item.row.mediaId
        pendingLinkPresetCode = codeText
        pendingLinkSourceUrl = urlText
        linkVideoLauncher.launch(arrayOf("video/*", "*/*"))
      },
      onSaveJavid = { codeText, urlText ->
        managingLinkItem = null
        coroutineScope.launch {
          val (savedCode, matched) =
            syncRepository.assignGroupPresetCodeAndAutoMatch(
              item.row.mediaId,
              codeText,
              rawSourceUrl = urlText,
            )
          val msg =
            when {
              matched > 0 -> context.getString(R.string.bookmark_gallery_auto_matched_toast, matched)
              !savedCode.isNullOrBlank() -> context.getString(R.string.bookmark_gallery_javid_saved_toast, savedCode)
              else -> context.getString(R.string.bookmark_gallery_auto_match_none)
            }
          Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
      },
      onMergeOverlaps = {
        managingLinkItem = null
        coroutineScope.launch {
          syncRepository.mergeOverlappingBookmarksForGroup(
            item.row.mediaId,
            windowSec = 10.0,
            strategy = "earliest",
            keepAlternates = true,
          ).onSuccess { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
          }.onFailure { err ->
            Toast.makeText(context, err.message ?: "Merge failed", Toast.LENGTH_SHORT).show()
          }
        }
      },
    )
  }

  if (!secureOnly) {
    JavidCatalogDialog(
      isOpen = showJavidCatalog,
      syncRepository = syncRepository,
      onDismiss = { showJavidCatalog = false },
      onSelectGroupForGallery = { entry ->
        selectedCatalogGroupKey = entry.groupKey
        onlyCurrentVideo = false
        onlyUnlinked = false
      },
    )
  }
}

@Composable
private fun ManageBookmarkGroupLinkDialog(
  item: BookmarkGalleryItem,
  syncRepository: PcBookmarkSyncRepository,
  onDismiss: () -> Unit,
  onPickFile: (String, String) -> Unit,
  onSaveJavid: (String, String) -> Unit,
  onMergeOverlaps: () -> Unit,
) {
  val context = LocalContext.current
  var javidText by remember(item.row.mediaId) {
    mutableStateOf(
      BookmarkMediaMatcher.extractJvidCode(item.mediaTitle)
        ?: BookmarkMediaMatcher.extractJvidCode(item.row.mediaId)
        ?: "",
    )
  }
  var sourceUrlText by remember(item.row.mediaId) { mutableStateOf("") }
  LaunchedEffect(item.row.mediaId) {
    val existing = syncRepository.getGroupForMedia(item.row.mediaId)
    val knownCode =
      existing?.presetCode?.takeIf { it.isNotBlank() }
        ?: BookmarkMediaMatcher.extractJvidCode(existing?.sourceLabel)
        ?: BookmarkMediaMatcher.extractJvidCode(item.mediaTitle)
    if (!knownCode.isNullOrBlank()) {
      javidText = knownCode
    }
    if (!existing?.sourceUrl.isNullOrBlank()) {
      sourceUrlText = existing?.sourceUrl.orEmpty()
    }
  }

  val validHttpUrl =
    remember(sourceUrlText) {
      val t = sourceUrlText.trim()
      t.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }

  AppAlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.bookmark_gallery_manage_dialog_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text =
            if (!item.playableUri.isNullOrBlank()) {
              stringResource(
                R.string.bookmark_gallery_current_video,
                item.mediaTitle?.takeIf { it.isNotBlank() } ?: item.row.mediaId,
              )
            } else {
              stringResource(R.string.bookmark_gallery_unlinked_status)
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppTextField(
          value = javidText,
          onValueChange = { javidText = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text(stringResource(R.string.bookmark_gallery_javid_label)) },
        )
        AppTextField(
          value = sourceUrlText,
          onValueChange = { sourceUrlText = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text(stringResource(R.string.bookmark_gallery_source_url_label)) },
        )
        if (validHttpUrl != null) {
          FilledTonalButton(
            onClick = {
              runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(validHttpUrl)))
              }
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(stringResource(R.string.bookmark_gallery_open_url))
          }
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilledTonalButton(
            onClick = { onPickFile(javidText, sourceUrlText) },
            modifier = Modifier.weight(1f),
          ) {
            Text(stringResource(R.string.bookmark_gallery_pick_video_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          FilledTonalButton(
            onClick = { onSaveJavid(javidText, sourceUrlText) },
            modifier = Modifier.weight(1f),
          ) {
            Text(stringResource(R.string.bookmark_gallery_save_javid_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
        FilledTonalButton(
          onClick = onMergeOverlaps,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.bookmark_gallery_merge_overlaps), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(android.R.string.cancel))
      }
    },
  )
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
        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        .clip(RoundedCornerShape(8.dp))
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .padding(horizontal = 6.dp, vertical = 6.dp),
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
    modifier =
      Modifier
        .defaultMinSize(minHeight = 40.dp)
        .clip(RoundedCornerShape(50))
        .semantics { this.selected = selected }
        .clickable(role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick),
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
  onLinkVideo: (() -> Unit)? = null,
  width: Dp = Dp.Unspecified,
  isSecure: Boolean = false,
  showMediaTitle: Boolean = true,
) {
  val cardTitle =
    if (showMediaTitle) {
      app.gyrolet.mpvrx.ui.player.controls.components.formatGlobalBookmarkTitle(
        item.mediaTitle,
        item.row.title,
      )
    } else {
      item.row.title
    }
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
        isSecure = isSecure,
        cornerRadius = 0.dp,
        contentScale = ContentScale.Fit,
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
            .padding(
              start = 6.dp,
              top = 4.dp,
              bottom = 4.dp,
              end = if (onLinkVideo != null) 92.dp else 6.dp,
            ),
      ) {
        Text(
          text = cardTitle,
          style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
          color = Color.White,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (onLinkVideo != null) {
        val isUnlinked = item.playableUri.isNullOrBlank()
        Surface(
          shape = RoundedCornerShape(6.dp),
          color =
            if (isUnlinked) {
              MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.94f)
            } else {
              Color.Black.copy(alpha = 0.68f)
            },
          modifier =
            Modifier
              .align(Alignment.TopEnd)
              .padding(4.dp)
              .clip(RoundedCornerShape(6.dp))
              .clickable(onClick = onLinkVideo),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
          ) {
            val pillTint =
              if (isUnlinked) {
                MaterialTheme.colorScheme.onTertiaryContainer
              } else {
                Color.White
              }
            Icon(
              imageVector = Icons.RoundedFilled.Link,
              contentDescription =
                if (isUnlinked) {
                  stringResource(R.string.bookmark_gallery_link_video)
                } else {
                  stringResource(R.string.bookmark_gallery_manage_link)
                },
              tint = pillTint,
              modifier = Modifier.size(11.dp),
            )
            Text(
              text =
                if (isUnlinked) {
                  stringResource(R.string.bookmark_gallery_link_video)
                } else {
                  stringResource(R.string.bookmark_gallery_manage_link)
                },
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
              color = pillTint,
              maxLines = 1,
            )
          }
        }
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
