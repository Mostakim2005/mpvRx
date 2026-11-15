/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.gyrolet.mpvrx.database.dao.MusicCompanionDao
import app.gyrolet.mpvrx.database.entities.MusicCompanionEntity
import app.gyrolet.mpvrx.domain.lyrics.LyricAnchorCalibrator
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import app.gyrolet.mpvrx.domain.metadata.AudioTaggingEngine
import kotlinx.coroutines.launch

/**
 * 1-Point Lyric Anchor Calibration Dialog.
 * Directly fulfills user feedback Image 2:
 * 1. Shows active music playhead time.
 * 2. Displays selected line to match (e.g. "Let's Go").
 * 3. Tapping "Anchor Match" calculates the global uniform offset.
 * 4. Tapping "Apply & Close" embeds lyrics into the audio file, writes the .lrc file,
 *    and maps Artist + Song + YouTube Link + Audio + LRC to the SQLite local database.
 */
@Composable
fun LyricAnchorDialog(
  artist: String,
  songName: String,
  audioPath: String,
  youtubeUrl: String?,
  currentPlayheadMs: Long,
  selectedLine: SyncedLine,
  allLines: List<SyncedLine>,
  taggingEngine: AudioTaggingEngine,
  musicCompanionDao: MusicCompanionDao,
  onDismiss: () -> Unit,
  onApplied: (LyricAnchorCalibrator.CalibrationResult) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var calibrationResult by remember {
    mutableStateOf<LyricAnchorCalibrator.CalibrationResult?>(null)
  }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
    ) {
      Column(
        modifier = Modifier.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // Dialog Title
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "⏱️ 1-Point Lyric Anchor",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          IconButton(onClick = onDismiss) {
            Text("✕", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }

        // Active Playhead Monitor
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(10.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(
            text = "ACTIVE MUSIC PLAYHEAD TIME",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = LyricAnchorCalibrator.formatLrcTimestamp(currentPlayheadMs.toInt()),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }

        // Selected Anchor Line Card
        Card(
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
          border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(
                text = "Selected Line to Match:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = "Original: ${LyricAnchorCalibrator.formatLrcTimestamp(selectedLine.time)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Text(
              text = "\"${selectedLine.line}\"",
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }

        // Anchor Match Trigger Button
        Button(
          onClick = {
            val result = LyricAnchorCalibrator.calibrate(
              lines = allLines,
              selectedLineOriginalTimeMs = selectedLine.time,
              targetPlayheadMs = currentPlayheadMs,
            )
            calibrationResult = result
          },
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
        ) {
          Text(
            text = "📍 Anchor Match to Current Time",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiary,
          )
        }

        // Calculated Offset Banner (Shows after tapping Anchor)
        calibrationResult?.let { res ->
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
              .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            Text(
              text = "✓ Offset: ${if (res.offsetMs >= 0) "+" else ""}${res.offsetMs}ms applied to all lines",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
              text = "All lines locked to new timeline.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }

        // SQLite DB Mapping Banner (Matches User Image 2)
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(8.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(
            text = "💾 DB Mapping: Artist + Song ↔ YouTube Link ↔ Audio ↔ LRC",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = "$artist • $songName",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
          )
        }

        // Apply & Close Button
        Button(
          onClick = {
            scope.launch {
              val res = calibrationResult ?: LyricAnchorCalibrator.calibrate(
                lines = allLines,
                selectedLineOriginalTimeMs = selectedLine.time,
                targetPlayheadMs = currentPlayheadMs,
              )

              // 1. Embed shifted lyrics into audio file tags
              taggingEngine.embedLyricsIntoAudioFile(
                audioFilePath = audioPath,
                lrcContent = res.lrcContent,
                offsetMs = res.offsetMs,
              )

              // 2. Persist in local SQLite database mapping
              val existing = musicCompanionDao.getByAudioPath(audioPath)
              val entity = (existing ?: MusicCompanionEntity(
                artist = artist,
                songName = songName,
                audioPath = audioPath,
                youtubeUrl = youtubeUrl,
              )).copy(
                artist = artist,
                songName = songName,
                youtubeUrl = youtubeUrl ?: existing?.youtubeUrl,
                hasEmbeddedLyrics = true,
                lyricOffsetMs = res.offsetMs,
                updatedAt = System.currentTimeMillis(),
              )
              musicCompanionDao.insertOrReplace(entity)

              onApplied(res)
              onDismiss()
            }
          },
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
          Text("✓ Apply & Close (Save to DB & Audio File)", fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}
