/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.securefolder

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.gyrolet.mpvrx.domain.secure.SecureFolderSession
import app.gyrolet.mpvrx.preferences.SecureFolderPreferences
import org.koin.core.context.GlobalContext

/**
 * Authentication gate for a playback launch that targets Secure Folder storage.
 *
 * Playback entry points funnel through [app.gyrolet.mpvrx.utils.media.MediaUtils], which knows a
 * target is protected but has no activity to prompt from. This gate asks for the Secure Folder PIN
 * itself — never the phone's lock-screen credential — and starts playback only after the PIN is
 * verified. Entering the folder already marks the session unlocked, so this screen is skipped for
 * the rest of that session.
 */
class SecureUnlockActivity : FragmentActivity() {
  companion object {
    private const val EXTRA_TARGET_INTENT = "app.gyrolet.mpvrx.secure.EXTRA_TARGET_INTENT"

    /** Starts the gate for [target]; the intent is only launched after successful authentication. */
    fun launch(context: Context, target: Intent) {
      context.startActivity(
        Intent(context, SecureUnlockActivity::class.java).apply {
          putExtra(EXTRA_TARGET_INTENT, target)
          addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
          if (context !is android.app.Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        },
      )
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState != null) {
      // A recreated gate has already consumed its one-shot launch; never resume into playback.
      abort()
      return
    }
    if (SecureFolderSession.isUnlocked) {
      // The folder gate already cleared a credential check this session; never ask twice.
      continueToTarget()
      return
    }
    val preferences =
      runCatching { GlobalContext.getOrNull()?.get<SecureFolderPreferences>() }.getOrNull()
    if (preferences == null || !preferences.isPinSet()) {
      abort(showMessage = true)
      return
    }
    val pinLength = preferences.pinLength.get().coerceIn(4, 8)
    setContent {
      app.gyrolet.mpvrx.ui.theme.MpvrxTheme {
        SecurePinPrompt(
          pinLength = pinLength,
          verify = preferences::verifyPin,
          onSuccess = ::continueToTarget,
          onCancel = { abort() },
        )
      }
    }
  }

  @Suppress("DEPRECATION")
  private fun targetIntent(): Intent? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(EXTRA_TARGET_INTENT, Intent::class.java)
    } else {
      intent.getParcelableExtra(EXTRA_TARGET_INTENT)
    }

  private fun continueToTarget() {
    val target = targetIntent()
    SecureFolderSession.markUnlocked()
    finish()
    if (target == null) return
    runCatching { startActivity(target) }
      .onFailure { abort(showMessage = true) }
  }

  private fun abort(showMessage: Boolean = false) {
    if (showMessage) Toast.makeText(this, "Playback locked", Toast.LENGTH_SHORT).show()
    finish()
  }
}

@Composable
private fun SecurePinPrompt(
  pinLength: Int,
  verify: (String) -> Boolean,
  onSuccess: () -> Unit,
  onCancel: () -> Unit,
) {
  var pin by remember { mutableStateOf("") }
  var error by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }

  fun submit() {
    if (pin.length != pinLength) return
    if (verify(pin)) {
      onSuccess()
    } else {
      error = true
      pin = ""
    }
  }

  LaunchedEffect(pin) {
    if (pin.length == pinLength) submit()
  }
  LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = "Secure Folder",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
      )
      Spacer(Modifier.height(8.dp))
      Text(
        text = "Enter your PIN to play this private file.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(24.dp))
      OutlinedTextField(
        value = pin,
        onValueChange = { value ->
          pin = value.filter(Char::isDigit).take(pinLength)
          error = false
        },
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        singleLine = true,
        isError = error,
        label = { Text("PIN") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions =
          KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
      )
      if (error) {
        Spacer(Modifier.height(8.dp))
        Text(
          text = "Incorrect PIN",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
      Spacer(Modifier.height(20.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(Modifier.height(0.dp))
        Button(onClick = { submit() }, enabled = pin.length == pinLength) { Text("Unlock") }
      }
    }
  }
}
