/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.repository

import app.gyrolet.mpvrx.database.entities.RecentlyPlayedEntity
import app.gyrolet.mpvrx.domain.secure.SecureMediaRegistry

/** True when this history row points at a file the Secure Folder owns. */
internal fun RecentlyPlayedEntity.isProtected(): Boolean =
  SecureMediaRegistry.isProtectedReference(filePath)
