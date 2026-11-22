/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.security

/**
 * Redacts sensitive query parameters (Subsonic tokens/salts/passwords, Jellyfin/Seerr API keys,
 * Bearer tokens) and basic-auth userinfo from URLs and log lines before logging or exporting.
 */
object LogRedactor {
  private val SENSITIVE_QUERY_PARAM =
    Regex(
      """([?&](?:t|s|p|password|token|api_key|apikey|access_token|auth|key|secret)=)([^&#\s]+)""",
      RegexOption.IGNORE_CASE,
    )
  private val USERINFO_IN_URL =
    Regex("""(https?://)([^/@\s:]+):([^/@\s]+)@""", RegexOption.IGNORE_CASE)
  private val BEARER_HEADER =
    Regex("""(Authorization:\s*(?:Bearer|Basic|MediaBrowser)\s+)([^
]+)""", RegexOption.IGNORE_CASE)

  fun redactUrl(url: String): String {
    if (url.isBlank()) return url
    val withoutUserInfo = USERINFO_IN_URL.replace(url, "$1***:***@")
    return SENSITIVE_QUERY_PARAM.replace(withoutUserInfo, "$1REDACTED")
  }

  fun redactText(text: String): String {
    if (text.isBlank()) return text
    var result = USERINFO_IN_URL.replace(text, "$1***:***@")
    result = SENSITIVE_QUERY_PARAM.replace(result, "$1REDACTED")
    result = BEARER_HEADER.replace(result, "$1REDACTED")
    return result
  }
}
