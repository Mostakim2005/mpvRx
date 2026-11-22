/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactorTest {
  @Test
  fun redactsSubsonicAndJellyfinQueryTokens() {
    val raw = "https://music.example.com/rest/ping.view?u=alice&t=deadbeef1234&s=saltsalt&api_key=secret999"
    val redacted = LogRedactor.redactUrl(raw)
    assertFalse(redacted.contains("deadbeef1234"))
    assertFalse(redacted.contains("saltsalt"))
    assertFalse(redacted.contains("secret999"))
    assertTrue(redacted.contains("t=REDACTED"))
    assertTrue(redacted.contains("s=REDACTED"))
    assertTrue(redacted.contains("api_key=REDACTED"))
  }

  @Test
  fun redactsBasicAuthUserInfoAndBearerHeader() {
    val logLine = "GET https://alice:hunter2@media.example.com/stream Authorization: Bearer eyJhbGciOiJIUzI1NiJ9"
    val redacted = LogRedactor.redactText(logLine)
    assertFalse(redacted.contains("hunter2"))
    assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9"))
    assertTrue(redacted.contains("***:***@"))
    assertTrue(redacted.contains("Authorization: Bearer REDACTED"))
  }
}
