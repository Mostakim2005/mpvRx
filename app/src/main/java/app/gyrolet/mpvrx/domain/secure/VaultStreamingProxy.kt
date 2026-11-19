/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.secure

import android.util.Log
import app.gyrolet.mpvrx.data.network.proxy.HttpByteRange
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Loopback-only capability proxy that streams and byte-range seeks encrypted Secure Vault media
 * straight into libmpv without ever writing a decrypted copy to storage.
 *
 * Every active token is revoked immediately when [SecureFolderSession] locks.
 */
class VaultStreamingProxy private constructor() : NanoHTTPD("127.0.0.1", 0) {
  companion object {
    private const val TAG = "VaultStreamingProxy"
    private const val TOKEN_BYTES = 24

    @Volatile
    private var instance: VaultStreamingProxy? = null

    fun getInstance(): VaultStreamingProxy =
      instance ?: synchronized(this) {
        instance ?: VaultStreamingProxy().also {
          it.start(SOCKET_READ_TIMEOUT, false)
          instance = it
        }
      }

    fun revokeAllActiveStreams() {
      instance?.revokeAllStreams()
    }

    fun stopInstance() {
      synchronized(this) {
        instance?.let { proxy ->
          proxy.revokeAllStreams()
          proxy.stop()
          instance = null
        }
      }
    }
  }

  private data class VaultStreamInfo(
    val file: File,
    val mimeType: String,
  )

  private class HeadResponse(
    status: Response.IStatus,
    mimeType: String,
    contentLength: Long,
  ) : Response(status, mimeType, ByteArrayInputStream(ByteArray(0)), contentLength)

  private val random = SecureRandom()
  private val tokenByStreamId = ConcurrentHashMap<String, String>()
  private val streamsByToken = ConcurrentHashMap<String, VaultStreamInfo>()

  @Synchronized
  fun registerStream(
    streamId: String,
    file: File,
    mimeType: String = "video/*",
  ): String {
    unregisterStream(streamId)
    val token = nextToken()
    tokenByStreamId[streamId] = token
    streamsByToken[token] = VaultStreamInfo(file = file, mimeType = mimeType.ifBlank { "application/octet-stream" })
    return "http://127.0.0.1:$listeningPort/vault/$token"
  }

  @Synchronized
  fun unregisterStream(streamId: String) {
    val token = tokenByStreamId.remove(streamId) ?: return
    streamsByToken.remove(token)
  }

  @Synchronized
  fun revokeAllStreams() {
    tokenByStreamId.clear()
    streamsByToken.clear()
  }

  override fun serve(session: IHTTPSession): Response {
    val headOnly = session.method == Method.HEAD
    if (session.method != Method.GET && !headOnly) {
      return textResponse(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed", headOnly)
    }
    if (!SecureFolderSession.isUnlocked) {
      return textResponse(Response.Status.FORBIDDEN, "Vault locked", headOnly)
    }

    val uriPath = session.uri.trimStart('/')
    if (!uriPath.startsWith("vault/")) {
      return textResponse(Response.Status.NOT_FOUND, "Not found", headOnly)
    }
    val token = uriPath.removePrefix("vault/").substringBefore('/')
    val streamInfo = streamsByToken[token]
      ?: return textResponse(Response.Status.NOT_FOUND, "Stream expired", headOnly)
    val file = streamInfo.file
    if (!file.isFile) {
      return textResponse(Response.Status.NOT_FOUND, "File unavailable", headOnly)
    }

    return try {
      val totalLength = VaultCrypto.plaintextLength(file)
      val rangeHeader = session.headers["range"]
      val response =
        if (rangeHeader.isNullOrBlank()) {
          serveFull(headOnly, file, totalLength, streamInfo.mimeType)
        } else {
          serveRange(headOnly, file, totalLength, streamInfo.mimeType, rangeHeader)
        }
      response.addHeader("Connection", "close")
      response
    } catch (error: Exception) {
      Log.e(TAG, "Vault stream failed (${error::class.java.simpleName})")
      textResponse(Response.Status.INTERNAL_ERROR, "Vault stream failed", headOnly)
    }
  }

  private fun serveFull(
    headOnly: Boolean,
    file: File,
    totalLength: Long,
    mimeType: String,
  ): Response {
    if (headOnly) {
      return HeadResponse(Response.Status.OK, mimeType, totalLength).apply {
        addHeader("Accept-Ranges", "bytes")
      }
    }
    if (totalLength <= 0L) {
      return newFixedLengthResponse(Response.Status.OK, mimeType, ByteArrayInputStream(ByteArray(0)), 0L).apply {
        addHeader("Accept-Ranges", "bytes")
      }
    }
    val stream = VaultCrypto.openDecryptedRangeStream(file, 0L, totalLength)
    return newFixedLengthResponse(Response.Status.OK, mimeType, stream, totalLength).apply {
      addHeader("Accept-Ranges", "bytes")
    }
  }

  private fun serveRange(
    headOnly: Boolean,
    file: File,
    totalLength: Long,
    mimeType: String,
    rangeHeader: String,
  ): Response {
    val range = HttpByteRange.parse(rangeHeader, totalLength)
      ?: return textResponse(Response.Status.RANGE_NOT_SATISFIABLE, "Invalid range", headOnly).apply {
        addHeader("Content-Range", "bytes */$totalLength")
      }

    val response =
      if (headOnly) {
        HeadResponse(Response.Status.PARTIAL_CONTENT, mimeType, range.length)
      } else {
        val stream = VaultCrypto.openDecryptedRangeStream(file, range.start, range.length)
        newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, stream, range.length)
      }
    response.addHeader("Accept-Ranges", "bytes")
    response.addHeader("Content-Range", "bytes ${range.start}-${range.endInclusive}/$totalLength")
    return response
  }

  private fun textResponse(
    status: Response.IStatus,
    body: String,
    headOnly: Boolean,
  ): Response =
    if (headOnly) {
      HeadResponse(status, MIME_PLAINTEXT, 0L)
    } else {
      newFixedLengthResponse(status, MIME_PLAINTEXT, body)
    }

  private fun nextToken(): String {
    val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }
}
