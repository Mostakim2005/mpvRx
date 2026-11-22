/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerUrlUtilsTest {
  @Test
  fun privateAndLocalHostsReturnTrue() {
    assertTrue(ServerUrlUtils.isLocalOrPrivateHost("localhost"))
    assertTrue(ServerUrlUtils.isLocalOrPrivateHost("127.0.0.1"))
    assertTrue(ServerUrlUtils.isLocalOrPrivateHost("::1"))
    assertTrue(ServerUrlUtils.isLocalOrPrivateHost("10.0.0.5"))
    assertTrue(ServerUrlUtils.isLocalOrPrivateHost("172.16.4.2"))
    assertTrue(ServerUrlUtils.isLocalOrPrivateHost("192.168.1.50"))
    assertTrue(ServerUrlUtils.isLocalOrPrivateHost("100.64.10.2"))
    assertTrue(ServerUrlUtils.isLocalOrPrivateHost("nas.local"))
    assertTrue(ServerUrlUtils.isLocalOrPrivateHost("homeserver"))
  }

  @Test
  fun publicIpv4AndFqdnsReturnFalse() {
    assertFalse(ServerUrlUtils.isLocalOrPrivateHost("8.8.8.8"))
    assertFalse(ServerUrlUtils.isLocalOrPrivateHost("1.1.1.1"))
    assertFalse(ServerUrlUtils.isLocalOrPrivateHost("203.0.113.42"))
    assertFalse(ServerUrlUtils.isLocalOrPrivateHost("media.example.com"))
  }

  @Test
  fun publicIpv4PrioritizesHttpsFirst() {
    val candidates = ServerUrlUtils.generateCandidateUrls("203.0.113.42:8096")
    assertEquals("https://203.0.113.42:8096", candidates.first())
  }
}
