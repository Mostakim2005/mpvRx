package app.gyrolet.mpvrx.ui.preferences

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.BookmarkSyncPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.repository.bookmark.PcBookmarkSyncRepository
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun BookmarkSyncServerRow(
  onOpenConfig: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val prefs = koinInject<BookmarkSyncPreferences>()
  val syncRepo = koinInject<PcBookmarkSyncRepository>()
  val scope = rememberCoroutineScope()

  val hubUrl by prefs.hubUrl.collectAsState()
  val lastIso by prefs.lastSyncIso.collectAsState()
  val lastSummary by prefs.lastSyncSummary.collectAsState()
  var syncing by remember { mutableStateOf(false) }

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(onClick = onOpenConfig)
        .padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(
      imageVector = Icons.RoundedFilled.Bookmarks,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(32.dp),
    )

    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      Text(
        text = stringResource(R.string.bookmark_sync_pref_section),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text =
          if (lastIso.isNotBlank() && lastSummary.isNotBlank()) {
            stringResource(R.string.bookmark_sync_last_format, "$lastSummary • $hubUrl")
          } else {
            stringResource(R.string.bookmark_sync_pref_summary)
          },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }

    OutlinedButton(
      enabled = !syncing,
      onClick = {
        syncing = true
        scope.launch {
          val result = syncRepo.syncWithPcHub()
          syncing = false
          result
            .onSuccess { res ->
              val msg =
                context.getString(
                  R.string.bookmark_gallery_sync_done,
                  res.inserted,
                  res.updated,
                  res.thumbnailsDownloaded,
                )
              Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }.onFailure { err ->
              val msg =
                context.getString(
                  R.string.bookmark_gallery_sync_failed,
                  err.message ?: "Error",
                )
              Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
      },
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.Refresh,
        contentDescription = stringResource(R.string.bookmark_sync_now_button),
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

@Composable
internal fun BookmarkSyncConfigDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
) {
  if (!isOpen) return
  val prefs = koinInject<BookmarkSyncPreferences>()
  val syncRepo = koinInject<PcBookmarkSyncRepository>()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var urlInput by remember(isOpen) { mutableStateOf(prefs.hubUrl.get()) }
  var tokenInput by remember(isOpen) { mutableStateOf(prefs.syncToken.get()) }
  var usbMode by remember(isOpen) { mutableStateOf(prefs.usbModeEnabled.get()) }
  var statusText by remember { mutableStateOf<String?>(null) }
  var isError by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text(stringResource(R.string.bookmark_sync_pref_section)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = stringResource(R.string.bookmark_sync_pref_summary),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = urlInput,
          onValueChange = { urlInput = it },
          label = { Text(stringResource(R.string.bookmark_sync_hub_url)) },
          placeholder = { Text(stringResource(R.string.bookmark_sync_hub_url_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedButton(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
              busy = true
              isError = false
              statusText = "Scanning Wi-Fi / USB for AV Manager..."
              scope.launch {
                syncRepo
                  .discoverPcHubOnLan(includeUsbLoopback = true)
                  .onSuccess { foundUrl ->
                    urlInput = foundUrl
                    val isUsb = PcBookmarkSyncRepository.isLoopbackUrl(foundUrl)
                    usbMode = isUsb
                    prefs.hubUrl.set(foundUrl)
                    prefs.usbModeEnabled.set(isUsb)
                    isError = false
                    statusText = context.getString(R.string.bookmark_sync_found_pc, foundUrl)
                  }.onFailure {
                    isError = true
                    statusText = context.getString(R.string.bookmark_sync_not_found)
                  }
                busy = false
              }
            },
          ) {
            Text(stringResource(R.string.bookmark_sync_find_pc), maxLines = 1)
          }
        }
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .clickable(enabled = !busy) {
                val next = !usbMode
                usbMode = next
                if (next) {
                  urlInput = "http://127.0.0.1:8000"
                } else if (PcBookmarkSyncRepository.isLoopbackUrl(urlInput)) {
                  urlInput = ""
                }
              },
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Checkbox(
            checked = usbMode,
            enabled = !busy,
            onCheckedChange = { checked ->
              usbMode = checked
              if (checked) {
                urlInput = "http://127.0.0.1:8000"
              } else if (PcBookmarkSyncRepository.isLoopbackUrl(urlInput)) {
                urlInput = ""
              }
            },
          )
          Text(
            text = stringResource(R.string.bookmark_sync_usb_mode),
            style = MaterialTheme.typography.bodySmall,
          )
        }
        if (usbMode) {
          Text(
            text = stringResource(R.string.bookmark_sync_usb_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else if (urlInput.contains("127.0.0.1") || urlInput.contains("localhost", ignoreCase = true)) {
          Text(
            text = stringResource(R.string.bookmark_sync_loopback_warning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
        OutlinedTextField(
          value = tokenInput,
          onValueChange = { tokenInput = it },
          label = { Text(stringResource(R.string.bookmark_sync_token)) },
          placeholder = { Text(stringResource(R.string.bookmark_sync_token_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            enabled = !busy,
            modifier = Modifier.weight(1f),
            onClick = {
              val cleanUrl = BookmarkSyncPreferences.normalizeHubUrl(urlInput)
              prefs.hubUrl.set(cleanUrl)
              prefs.syncToken.set(tokenInput.trim())
              prefs.usbModeEnabled.set(usbMode)
              busy = true
              scope.launch {
                syncRepo
                  .testConnection(cleanUrl, tokenInput.trim(), usbMode = usbMode)
                  .onSuccess { msg ->
                    isError = false
                    statusText = msg
                  }.onFailure { err ->
                    isError = true
                    statusText = err.message ?: "Connection failed"
                  }
                busy = false
              }
            },
          ) {
            Text(stringResource(R.string.bookmark_sync_test_button), maxLines = 1)
          }
          Button(
            enabled = !busy,
            modifier = Modifier.weight(1f),
            onClick = {
              val cleanUrl = BookmarkSyncPreferences.normalizeHubUrl(urlInput)
              prefs.hubUrl.set(cleanUrl)
              prefs.syncToken.set(tokenInput.trim())
              prefs.usbModeEnabled.set(usbMode)
              busy = true
              scope.launch {
                syncRepo
                  .syncWithPcHub(cleanUrl, tokenInput.trim(), usbMode = usbMode)
                  .onSuccess { res ->
                    isError = false
                    val baseSummary =
                      context.getString(
                        R.string.bookmark_gallery_sync_done,
                        res.inserted,
                        res.updated,
                        res.thumbnailsDownloaded,
                      )
                    statusText =
                      if (res.autoMatchedGroups > 0) {
                        "$baseSummary • ${res.autoMatchedGroups} matched"
                      } else {
                        baseSummary
                      }
                  }.onFailure { err ->
                    isError = true
                    statusText = err.message ?: "Sync failed"
                  }
                busy = false
              }
            },
          ) {
            Text(stringResource(R.string.bookmark_sync_now_button), maxLines = 1)
          }
        }
        statusText?.let { msg ->
          Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = !busy,
        onClick = {
          prefs.hubUrl.set(BookmarkSyncPreferences.normalizeHubUrl(urlInput))
          prefs.syncToken.set(tokenInput.trim())
          prefs.usbModeEnabled.set(usbMode)
          onDismiss()
        },
      ) {
        Text(stringResource(R.string.generic_ok))
      }
    },
  )
}
