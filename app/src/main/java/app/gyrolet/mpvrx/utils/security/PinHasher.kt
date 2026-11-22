/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Key-stretched PBKDF2-HMAC-SHA256 hashing for the Secure Folder PIN and security-question answer,
 * with backwards-compatible verification for legacy single-round salted SHA-256 hashes.
 */
object PinHasher {
  private const val LEGACY_ALGORITHM = "SHA-256"
  private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
  private const val PBKDF2_PREFIX = "pbkdf2$"
  private const val PBKDF2_ITERATIONS = 120_000
  private const val PBKDF2_KEY_BITS = 256
  private const val SALT_BYTES = 16

  /** Generates a new random salt, Base64-encoded for storage in SharedPreferences. */
  fun generateSalt(): String {
    val bytes = ByteArray(SALT_BYTES)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
  }

  /** Hashes [value] with [salt] using PBKDF2-HMAC-SHA256 (120,000 iterations). */
  fun hash(
    value: String,
    salt: String,
  ): String {
    val saltBytes = Base64.decode(salt, Base64.NO_WRAP)
    val derived = derivePbkdf2(value.trim().toCharArray(), saltBytes, PBKDF2_ITERATIONS)
    val encoded = Base64.encodeToString(derived, Base64.NO_WRAP)
    return "$PBKDF2_PREFIX$PBKDF2_ITERATIONS$$encoded"
  }

  /** Returns true when [storedHash] uses the legacy single-round SHA-256 format and should be re-hashed. */
  fun needsRehash(storedHash: String): Boolean = !storedHash.startsWith(PBKDF2_PREFIX)

  /** Constant-time comparison against either a PBKDF2 hash or a legacy salted SHA-256 hash. */
  fun verify(
    value: String,
    salt: String,
    expectedHash: String,
  ): Boolean {
    if (salt.isBlank() || expectedHash.isBlank()) return false
    return runCatching {
      val saltBytes = Base64.decode(salt, Base64.NO_WRAP)
      if (expectedHash.startsWith(PBKDF2_PREFIX)) {
        val parts = expectedHash.split('$')
        if (parts.size != 3) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 10_000..1_000_000 } ?: return false
        val expected = Base64.decode(parts[2], Base64.NO_WRAP)
        val actual = derivePbkdf2(value.trim().toCharArray(), saltBytes, iterations)
        MessageDigest.isEqual(actual, expected)
      } else {
        val digest = MessageDigest.getInstance(LEGACY_ALGORITHM)
        digest.update(saltBytes)
        val actual = digest.digest(value.trim().toByteArray(Charsets.UTF_8))
        val expected = Base64.decode(expectedHash, Base64.NO_WRAP)
        MessageDigest.isEqual(actual, expected)
      }
    }.getOrDefault(false)
  }

  private fun derivePbkdf2(
    chars: CharArray,
    salt: ByteArray,
    iterations: Int,
  ): ByteArray {
    val spec = PBEKeySpec(chars, salt, iterations, PBKDF2_KEY_BITS)
    return try {
      SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
    } finally {
      spec.clearPassword()
      chars.fill('\u0000')
    }
  }
}
