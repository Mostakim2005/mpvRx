/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.securefolder

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.gyrolet.mpvrx.domain.secure.SecureFolderSession

/**
 * Authentication gate for a playback launch that targets Secure Folder storage.
 *
 * Playback entry points funnel through [app.gyrolet.mpvrx.utils.media.MediaUtils], which knows a
 * target is protected but has no activity to prompt from. Instead of launching the player directly
 * it hands the fully built intent to this activity, which asks for the device credential and only
 * then starts playback. Cancelling or failing the prompt drops the launch entirely — playback is
 * never started "to be checked later".
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
        },
      )
    }
  }

  private var promptShown = false

  private val legacyCredentialLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        continueToTarget()
      } else {
        abort()
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState != null) {
      // A recreated gate has already consumed its one-shot launch; never resume into playback.
      abort()
      return
    }
    authenticate()
  }

  @Suppress("DEPRECATION")
  private fun targetIntent(): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(EXTRA_TARGET_INTENT, Intent::class.java)
    } else {
      intent.getParcelableExtra(EXTRA_TARGET_INTENT)
    }

  private fun authenticate() {
    if (targetIntent() == null) {
      abort()
      return
    }
    if (SecureFolderSession.isUnlocked) {
      // The session already cleared a credential gate; never ask twice for one unlock.
      continueToTarget()
      return
    }
    val keyguard = getSystemService(KeyguardManager::class.java)
    if (keyguard == null || !keyguard.isDeviceSecure) {
      // Nothing to authenticate against: refuse rather than play private media unguarded.
      abort(showMessage = true)
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      showBiometricPrompt()
    } else {
      // DEVICE_CREDENTIAL alone is unsupported by BiometricPrompt before Android 11.
      val legacyIntent =
        keyguard.createConfirmDeviceCredentialIntent(
          getString(android.R.string.dialog_alert_title),
          "Unlock to play this file",
        )
      if (legacyIntent == null) abort() else legacyCredentialLauncher.launch(legacyIntent)
    }
  }

  private fun showBiometricPrompt() {
    if (promptShown) return
    promptShown = true
    val callback =
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          continueToTarget()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          abort()
        }

        override fun onAuthenticationFailed() {
          // Let the prompt stay up so the user can retry the credential.
        }
      }
    val info =
      BiometricPrompt.PromptInfo
        .Builder()
        .setTitle("Unlock Secure Folder")
        .setSubtitle("Authenticate to play this private file")
        .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        .build()
    BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback).authenticate(info)
  }

  private fun continueToTarget() {
    val target = targetIntent()
    // Every path here is an authorization success, so it opens the whole session.
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
