/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gyrolet.mpvrx.domain.metadata.AudioTaggingEngine
import app.gyrolet.mpvrx.domain.metadata.OnlineMetadataScraper
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import kotlinx.coroutines.launch

/**
 * Fullscreen Metadata & Artwork Editor Sheet.
 * Directly fulfills user feedback with:
 * 1. Top Artwork Candidate Carousel (browse and pick from multiple artwork options).
 * 2. Editable tag fields (Title, Artist, Album, Year, Genre, Track Number).
 * 3. Bottom Metadata 1 / Metadata 2 candidate selector cards.
 * 4. Musicolet-style container tag injection and clean file renaming on disk.
 */
@Composable
fun FullscreenMetadataEditorSheet(
  viewModel: PlayerViewModel,
  currentTitle: String,
  currentArtist: String,
  currentAudioPath: String,
  taggingEngine: AudioTaggingEngine,
  onDismiss: () -> Unit,
) {
  val scope = rememberCoroutineScope()

  var titleInput by remember { mutableStateOf(currentTitle) }
  var artistInput by remember { mutableStateOf(currentArtist) }
  var albumInput by remember { mutableStateOf("") }
  var yearInput by remember { mutableStateOf("") }
  var genreInput by remember { mutableStateOf("") }
  var trackNumberInput by remember { mutableStateOf("") }
  var autoRenameFile by remember { mutableStateOf(true) }

  var isSearching by remember { mutableStateOf(false) }
  var selectedArtIndex by remember { mutableIntStateOf(0) }
  var selectedMetaIndex by remember { mutableIntStateOf(0) }

  var metadataCandidates by remember { mutableStateOf<List<OnlineMetadataScraper.MetadataCandidate>>(emptyList()) }
  var artworkCandidates by remember { mutableStateOf<List<OnlineMetadataScraper.ArtworkCandidate>>(emptyList()) }

  val runOnlineSearch = {
    scope.launch {
      isSearching = true
      val result = OnlineMetadataScraper.searchCandidates(titleInput, artistInput)
      metadataCandidates = result.metadataCandidates
      artworkCandidates = result.artworkCandidates
      if (result.metadataCandidates.isNotEmpty()) {
        val first = result.metadataCandidates.first()
        albumInput = first.album
        yearInput = first.year
        genreInput = first.genre
        trackNumberInput = first.trackNumber ?: "01"
      }
      isSearching = false
    }
  }

  LaunchedEffect(currentTitle, currentArtist) {
    runOnlineSearch()
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.background,
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 16.dp, vertical = 12.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column {
            Text(
              text = "Edit Song Metadata & Artwork",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
              text = "Universal container tagging & multi-source candidates",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(onClick = onDismiss) {
            Text("✕", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }

        // Auto-fetch Button
        Button(
          onClick = { runOnlineSearch() },
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
          if (isSearching) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
          }
          Text("⚡ Auto-Fetch Tags & Artwork Online", fontWeight = FontWeight.Bold)
        }

        // TOP SECTION: ARTWORK CANDIDATES CAROUSEL (User Markup Image 1)
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "🖼️ Artwork Candidates (Swipe & Select)",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = if (artworkCandidates.isNotEmpty()) "Option ${selectedArtIndex + 1} of ${artworkCandidates.size}" else "Default",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            itemsIndexed(artworkCandidates) { index, art ->
              val isSelected = index == selectedArtIndex
              Box(
                modifier = Modifier
                  .size(90.dp)
                  .clip(RoundedCornerShape(10.dp))
                  .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp),
                  )
                  .clickable { selectedArtIndex = index },
              ) {
                RemoteImage(
                  url = art.url,
                  contentDescription = art.title,
                  modifier = Modifier.fillMaxSize(),
                  contentScale = ContentScale.Crop,
                )
                if (isSelected) {
                  Box(
                    modifier = Modifier
                      .align(Alignment.TopEnd)
                      .padding(4.dp)
                      .size(18.dp)
                      .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                  ) {
                    Text("✓", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                  }
                }
              }
            }
          }
        }

        // MIDDLE SECTION: EDITABLE FIELDS
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = titleInput,
            onValueChange = { titleInput = it },
            label = { Text("Song Title") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
          )
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = artistInput,
              onValueChange = { artistInput = it },
              label = { Text("Artist") },
              modifier = Modifier.weight(1f),
              singleLine = true,
            )
            OutlinedTextField(
              value = albumInput,
              onValueChange = { albumInput = it },
              label = { Text("Album") },
              modifier = Modifier.weight(1f),
              singleLine = true,
            )
          }
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = yearInput,
              onValueChange = { yearInput = it },
              label = { Text("Year") },
              modifier = Modifier.weight(1f),
              singleLine = true,
            )
            OutlinedTextField(
              value = trackNumberInput,
              onValueChange = { trackNumberInput = it },
              label = { Text("Track #") },
              modifier = Modifier.weight(1f),
              singleLine = true,
            )
            OutlinedTextField(
              value = genreInput,
              onValueChange = { genreInput = it },
              label = { Text("Genre") },
              modifier = Modifier.weight(1f),
              singleLine = true,
            )
          }
        }

        // BOTTOM SECTION: METADATA 1 / METADATA 2 CARDS (User Markup Image 1)
        if (metadataCandidates.isNotEmpty()) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
              .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = "📋 Online Metadata Matches (Tap to Auto-Fill)",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              metadataCandidates.take(2).forEachIndexed { idx, candidate ->
                val isSelected = idx == selectedMetaIndex
                Box(
                  modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    .border(
                      width = if (isSelected) 2.dp else 1.dp,
                      color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                      shape = RoundedCornerShape(8.dp),
                    )
                    .clickable {
                      selectedMetaIndex = idx
                      titleInput = candidate.title
                      artistInput = candidate.artist
                      albumInput = candidate.album
                      yearInput = candidate.year
                      genreInput = candidate.genre
                      trackNumberInput = candidate.trackNumber ?: "01"
                    }
                    .padding(8.dp),
                ) {
                  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                      Text(
                        text = "Metadata ${idx + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                      )
                      if (isSelected) {
                        Text("✓ Active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                      }
                    }
                    Text(text = candidate.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = candidate.album, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                  }
                }
              }
            }
          }
        }

        // Auto-Rename File Switch
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Auto-Rename File on Disk", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
              "Renames file to '${taggingEngine.sanitizeFilename(artistInput)} - ${taggingEngine.sanitizeFilename(titleInput)}'",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = autoRenameFile, onCheckedChange = { autoRenameFile = it })
        }

        // Save Action
        Button(
          onClick = {
            scope.launch {
              val chosenArtwork = artworkCandidates.getOrNull(selectedArtIndex)?.url
              taggingEngine.updateMetadataAndRename(
                audioFilePath = currentAudioPath,
                update = AudioTaggingEngine.MetadataUpdate(
                  title = titleInput,
                  artist = artistInput,
                  album = albumInput,
                  year = yearInput,
                  genre = genreInput,
                  trackNumber = trackNumberInput,
                  artworkUrl = chosenArtwork,
                  autoRenameFile = autoRenameFile,
                ),
              )
              onDismiss()
            }
          },
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
          Text("💾 Save Tags & Inject Into Audio", fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}
