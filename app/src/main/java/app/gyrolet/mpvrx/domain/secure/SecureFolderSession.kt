/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.secure

/**
 * Session-scoped unlock token shared by every Secure Folder credential gate.
 *
 * The folder-entry gate ([app.gyrolet.mpvrx.ui.securefolder.SecureFolderGateScreen]) and the
 * playback gate ([app.gyrolet.mpvrx.ui.securefolder.SecureUnlockActivity]) are independent
 * surfaces, so without a shared token each one asks for the credential on its own — the reported
 * "prompts twice on entry" flow. One successful unlock marks the whole session and every gate
 * reads this token; it is cleared the moment the app is backgrounded (the same security contract
 * as Secret Mode in `ProfileScreen`), so returning to the app always re-authenticates.
 */
object SecureFolderSession {
  @Volatile
  var isUnlocked: Boolean = false
    private set

  fun markUnlocked() {
    isUnlocked = true
  }

  fun clear() {
    isUnlocked = false
  }
}
