/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.controls.components.BookmarkListRow
import app.gyrolet.mpvrx.ui.player.controls.components.PanelToggleChip
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import dev.vivvvek.seeker.Segment
import `is`.xyz.mpv.Utils
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ChaptersSheet(
  chapters: ImmutableList<Segment>,
  currentChapter: Segment?,
  onClick: (Segment) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  actions: @Composable RowScope.() -> Unit = {},
  bookmarks: List<PlaybackBookmarkEntity> = emptyList(),
  bookmarkPositionSec: (PlaybackBookmarkEntity) -> Int = { (it.positionMs / 1000L).toInt().coerceAtLeast(0) },
  selectedBookmarkId: Long? = null,
  syncScrollIndex: Int? = null,
  isSecureBookmarks: Boolean = false,
  onBookmarkClick: (PlaybackBookmarkEntity) -> Unit = {},
  onBookmarkLongClick: (PlaybackBookmarkEntity) -> Unit = {},
  bookmarkTags: (PlaybackBookmarkEntity) -> List<String> = { emptyList() },
  itemActions: @Composable (Segment) -> Unit = {},
) {
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val haptics = rememberAppHaptics()

  val chapterScrollIndex =
    remember(chapters, currentChapter, bookmarks.size) {
      val chapterOffset = bookmarks.size + if (bookmarks.isNotEmpty() && chapters.isNotEmpty()) 1 else 0
      val index = if (currentChapter != null) chapters.indexOf(currentChapter) else -1
      if (index >= 0) chapterOffset + index else -1
    }
  val resolvedSyncIndex =
    syncScrollIndex?.takeIf { it >= 0 }
      ?: selectedBookmarkId?.let { id -> bookmarks.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
      ?: chapterScrollIndex

  // Scroll once on sheet open so browsing up/down is never interrupted by playback progress ticks.
  var didInitialScroll by remember { mutableStateOf(false) }
  LaunchedEffect(bookmarks.size, chapters.size) {
    if (!didInitialScroll && (bookmarks.isNotEmpty() || chapters.isNotEmpty())) {
      if (resolvedSyncIndex >= 0) {
        listState.scrollToItem(resolvedSyncIndex)
      }
      didInitialScroll = true
    }
  }

  val isAudiobook = app.gyrolet.mpvrx.ui.player.PlaybackSession.state.collectAsStateWithLifecycle().value.currentItem?.audiobook != null
  PlayerSheet(
    onDismissRequest,
    title = stringResource(if (isAudiobook) R.string.audiobook_chapters else R.string.btn_label_bookmarks),
    actions = actions,
  ) {
    Column(modifier = modifier.fillMaxWidth()) {
      if (bookmarks.isNotEmpty() || chapters.isNotEmpty()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          PanelToggleChip(
            label = stringResource(R.string.bookmark_sync_to_video),
            icon = Icons.RoundedFilled.AlignVerticalCenter,
            contentDescription = stringResource(R.string.bookmark_sync_to_video),
            onClick = {
              if (resolvedSyncIndex >= 0) {
                haptics.tick()
                scope.launch { listState.animateScrollToItem(resolvedSyncIndex) }
              } else {
                Toast.makeText(context, R.string.bookmark_sync_to_video_none, Toast.LENGTH_SHORT).show()
              }
            },
          )
        }
      }
      LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
      ) {
        if (bookmarks.isEmpty() && chapters.isEmpty()) {
          item {
            Text(
              stringResource(R.string.playback_bookmarks_empty),
              Modifier.padding(MaterialTheme.spacing.medium),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        if (bookmarks.isNotEmpty()) {
          items(
            items = bookmarks,
            key = { "sheet_bm_${it.id}" },
          ) { bookmark ->
            BookmarkListRow(
              bookmark = bookmark,
              positionSeconds = bookmarkPositionSec(bookmark),
              selected = bookmark.id == selectedBookmarkId,
              compact = false,
              tagNames = bookmarkTags(bookmark),
              isSecure = isSecureBookmarks,
              onClick = { onBookmarkClick(bookmark) },
              onLongClick = { onBookmarkLongClick(bookmark) },
            )
          }
        }
        if (chapters.isNotEmpty() && bookmarks.isNotEmpty()) {
          item(key = "sheet_chapters_header") {
            Text(
              text = stringResource(R.string.bookmark_list_section_chapters),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
          }
        }
        itemsIndexed(
          chapters,
          key = { index, chapter -> "${chapter.start}_${chapter.name}_$index" },
        ) { index, chapter ->
          ChapterTrack(
            chapter = chapter,
            index = index,
            chapterCount = chapters.size,
            selected = currentChapter == chapter,
            onClick = { onClick(chapter) },
            trailingContent = { itemActions(chapter) },
          )
        }
      }
    }
  }
}

@Composable
fun ChapterTrack(
  chapter: Segment,
  index: Int,
  chapterCount: Int,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  trailingContent: @Composable () -> Unit = {},
) {
  val shape =
    rememberConnectedShape(
      index = index,
      itemCount = chapterCount,
      layout = ConnectedLayout.Vertical,
    )
  val containerColor by
    animateColorAsState(
      targetValue =
        playerSheetSelectionColor(selected),
      animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
      label = "chapterSelection",
    )
  Surface(
    modifier =
      modifier
        .fillMaxWidth(),
    shape = shape,
    color = containerColor,
    tonalElevation = if (selected) 1.dp else 0.dp,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .heightIn(min = 64.dp)
          .clickable(onClick = onClick)
          .padding(vertical = 10.dp, horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          stringResource(R.string.player_sheets_track_title_wo_lang, index + 1, chapter.name),
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(Utils.prettyTime(chapter.start.toInt()), style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      trailingContent()
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaybackBookmarkEditor(viewModel: PlayerViewModel, onSaved: () -> Unit, onDismiss: () -> Unit) {
  val draft by viewModel.bookmarkDraft.collectAsStateWithLifecycle()
  val mediaId by viewModel.bookmarkMediaId.collectAsStateWithLifecycle()
  // Existing bookmarks (id != 0L) carry their own authoritative mediaId (including Secure Folder
  // vault keys and cross-video Global bookmarks) and must not self-dismiss while bookmarkMediaId
  // resolves or differs from the currently playing video.
  val bookmark =
    draft?.takeIf { candidate ->
      candidate.id != 0L || mediaId == null || candidate.mediaId == mediaId
    }
  if (bookmark == null) {
    LaunchedEffect(draft, mediaId) { onDismiss() }
    return
  }
  val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
  val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
  val localView = androidx.compose.ui.platform.LocalView.current
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val shouldForcePortrait = configuration.smallestScreenWidthDp < 600
  val editorActivity = LocalContext.current.findActivity()
  val hideKeyboardAndClearFocus = {
    focusManager.clearFocus(force = true)
    keyboardController?.hide()
    hideImeWindow(localView, editorActivity)
  }
  // On phone-sized screens, hold portrait while editing so the IME does not crush the sheet,
  // then hide the keyboard and restore the prior orientation on close
  // (TRAP: Portrait-Only-Editors-Must-Restore-Orientation).
  DisposableEffect(editorActivity, shouldForcePortrait) {
    val previous = editorActivity?.requestedOrientation
    if (editorActivity != null && shouldForcePortrait) {
      editorActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    onDispose {
      hideKeyboardAndClearFocus()
      if (editorActivity != null && shouldForcePortrait && previous != null && previous != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
        editorActivity.requestedOrientation = previous
      }
      // Run once more after orientation/sheet window detachment completes so Android's IME never sticks.
      editorActivity?.window?.decorView?.postDelayed(
        { hideImeWindow(null, editorActivity) },
        140L,
      )
    }
  }
  val isNewDraft = bookmark.id == 0L
  var nameValue by rememberSaveable(
    bookmark.mediaId,
    bookmark.id,
    bookmark.positionMs,
    stateSaver = TextFieldValue.Saver,
  ) {
    mutableStateOf(
      if (isNewDraft) {
        TextFieldValue("")
      } else {
        TextFieldValue(text = bookmark.title, selection = TextRange(0, bookmark.title.length))
      },
    )
  }
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(bookmark.mediaId, bookmark.id, bookmark.positionMs) {
    runCatching { focusRequester.requestFocus() }
  }
  var tagInput by remember(bookmark.id) { mutableStateOf("") }
  var selectedTags by remember(bookmark.id) { mutableStateOf<List<String>>(emptyList()) }
  // An edit reads its tags from disk; saving before that lands would rewrite the bookmark with an
  // empty tag list, so the save button waits for the read to finish.
  var tagsLoaded by remember(bookmark.id) { mutableStateOf(bookmark.id == 0L) }
  LaunchedEffect(bookmark.id) {
    selectedTags = if (bookmark.id == 0L) emptyList() else viewModel.tagsForBookmark(bookmark.id)
    tagsLoaded = true
  }
  val rememberedTags by viewModel.tagSuggestions.collectAsStateWithLifecycle()
  val tagSuggestions =
    remember(rememberedTags, tagInput, selectedTags) {
      val query = app.gyrolet.mpvrx.domain.bookmark.BookmarkTagRules.normalize(tagInput)
      rememberedTags
        .filter { it.isNotBlank() && it !in selectedTags }
        .filter { query.isEmpty() || it.lowercase().startsWith(query) || it.lowercase().contains(query) }
        .take(8)
    }
  var saving by remember(bookmark) { mutableStateOf(false) }
  var failed by remember(bookmark) { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val haptics = rememberAppHaptics()

  fun commitTag(raw: String) {
    val parsed = app.gyrolet.mpvrx.domain.bookmark.BookmarkTagRules.parse(raw)
    if (parsed.isEmpty()) return
    selectedTags =
      (selectedTags +
        parsed.filter { it !in selectedTags })
        .take(app.gyrolet.mpvrx.domain.bookmark.BookmarkTagRules.MAX_TAGS_PER_BOOKMARK)
    tagInput = ""
    haptics.tick()
  }

  PlayerSheet(
    onDismissRequest = {
      if (!saving) {
        hideKeyboardAndClearFocus()
        onDismiss()
      }
    },
    title = stringResource(R.string.audiobook_bookmark_name),
  ) {
    Column(
      Modifier
        .fillMaxWidth()
        .clickable(
          interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
          indication = null,
          onClick = hideKeyboardAndClearFocus,
        )
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        Utils.prettyTime((viewModel.bookmarkPositionMs(bookmark) / 1000).toInt()),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
        value = nameValue,
        onValueChange = { nameValue = it },
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        enabled = !saving,
        maxLines = 3,
        label = { Text(stringResource(R.string.audiobook_bookmark_name)) },
        placeholder =
          if (isNewDraft) {
            { Text(bookmark.title) }
          } else {
            null
          },
      )

      BookmarkTagEditor(
        tagInput = tagInput,
        onTagInputChange = { tagInput = it },
        selectedTags = selectedTags,
        suggestions = tagSuggestions,
        enabled = !saving,
        onAddTag = ::commitTag,
        onRemoveTag = { tag -> selectedTags = selectedTags - tag },
      )

      if (failed) {
        Text(stringResource(R.string.playback_bookmark_update_failed), color = MaterialTheme.colorScheme.error)
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(
          onClick = {
            hideKeyboardAndClearFocus()
            onDismiss()
          },
          enabled = !saving,
        ) { Text(stringResource(R.string.generic_cancel)) }
        TextButton(
          enabled = !saving && tagsLoaded && (isNewDraft || nameValue.text.isNotBlank()),
          onClick = {
            val resolvedTitle = nameValue.text.trim().ifBlank { bookmark.title }
            val pendingTags = app.gyrolet.mpvrx.domain.bookmark.BookmarkTagRules.parse(tagInput)
            val finalTags =
              (selectedTags + pendingTags.filter { it !in selectedTags })
                .take(app.gyrolet.mpvrx.domain.bookmark.BookmarkTagRules.MAX_TAGS_PER_BOOKMARK)
            saving = true
            failed = false
            hideKeyboardAndClearFocus()
            scope.launch {
              try {
                viewModel.savePlaybackBookmark(bookmark, resolvedTitle, finalTags)
                hideKeyboardAndClearFocus()
                haptics.confirm()
                onSaved()
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (_: Exception) {
                failed = true
              } finally {
                saving = false
              }
            }
          },
        ) { Text(stringResource(R.string.audiobook_save)) }
      }
    }
  }
}

/**
 * Tag entry with chips plus a live suggestion list drawn from persistent tag memory.
 *
 * Suggestions are not filtered to the current video: the point of the memory is that a tag invented
 * on one video is offered on the next.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookmarkTagEditor(
  tagInput: String,
  onTagInputChange: (String) -> Unit,
  selectedTags: List<String>,
  suggestions: List<String>,
  enabled: Boolean,
  onAddTag: (String) -> Unit,
  onRemoveTag: (String) -> Unit,
) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text = stringResource(R.string.bookmark_tags_label),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (selectedTags.isNotEmpty()) {
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        selectedTags.forEach { tag ->
          InputChip(
            selected = false,
            onClick = { onRemoveTag(tag) },
            enabled = enabled,
            label = { Text(tag) },
            trailingIcon = {
              app.gyrolet.mpvrx.ui.icons.Icon(
                imageVector = app.gyrolet.mpvrx.ui.icons.Icons.RoundedFilled.Close,
                contentDescription = stringResource(R.string.bookmark_tags_remove, tag),
                modifier = Modifier.size(14.dp),
              )
            },
          )
        }
      }
    }
    OutlinedTextField(
      value = tagInput,
      onValueChange = { raw ->
        // A typed comma commits immediately, so pasting "a, b, c" works without extra taps.
        if (raw.contains(',') || raw.contains('\u060C')) {
          onAddTag(raw)
        } else {
          onTagInputChange(raw)
        }
      },
      modifier = Modifier.fillMaxWidth(),
      enabled = enabled,
      singleLine = true,
      textStyle = MaterialTheme.typography.bodyMedium,
      label = { Text(stringResource(R.string.bookmark_tags_hint)) },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { onAddTag(tagInput) }),
      trailingIcon = {
        if (tagInput.isNotBlank()) {
          IconButton(onClick = { onAddTag(tagInput) }, enabled = enabled) {
            app.gyrolet.mpvrx.ui.icons.Icon(
              imageVector = app.gyrolet.mpvrx.ui.icons.Icons.RoundedFilled.Add,
              contentDescription = stringResource(R.string.bookmark_tags_add),
              modifier = Modifier.size(18.dp),
            )
          }
        }
      },
    )
    if (suggestions.isNotEmpty()) {
      Text(
        text = stringResource(R.string.bookmark_tags_suggestions),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        suggestions.forEach { suggestion ->
          SuggestionChip(
            onClick = { onAddTag(suggestion) },
            enabled = enabled,
            label = { Text(suggestion) },
          )
        }
      }
    }
  }
}

/** Unwraps the activity behind a (possibly themed) context. */
private fun Context.findActivity(): Activity? {
  var current: Context? = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return current as? Activity
}

/** Forces software keyboard dismissal across both the Compose dialog window and host Activity window. */
private fun hideImeWindow(
  view: View?,
  activity: Activity?,
) {
  val context = activity ?: view?.context ?: return
  val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
  view?.let { v ->
    v.clearFocus()
    imm?.hideSoftInputFromWindow(v.windowToken, 0)
  }
  activity?.window?.let { win ->
    val decor = win.decorView
    WindowInsetsControllerCompat(win, decor).hide(WindowInsetsCompat.Type.ime())
    win.currentFocus?.clearFocus()
    decor.windowToken?.let { token ->
      imm?.hideSoftInputFromWindow(token, 0)
    }
  }
}
