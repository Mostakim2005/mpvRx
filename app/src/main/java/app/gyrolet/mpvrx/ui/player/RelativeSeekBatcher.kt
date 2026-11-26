/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

/**
 * Thread-safe single-flight accumulator for rapid relative tap seeks.
 *
 * The first tap starts a background worker immediately with zero delay, while additional taps that
 * arrive while libmpv executes the active seek coalesce into one batch per playback generation.
 */
internal class RelativeSeekBatcher {
  data class Batch(
    val generation: Long,
    val offsetSeconds: Int,
    val cancellationToken: Long,
  )

  private val lock = Any()
  private var inFlight = false
  private var activeWorkerId = 0L
  private var workerSeq = 0L
  private var cancellationSeq = 0L
  private var pendingGeneration: Long? = null
  private var pendingOffset = 0

  /** Accumulates [offsetSeconds] for [generation] and returns a worker token if a new loop must start. */
  fun enqueue(
    generation: Long,
    offsetSeconds: Int,
  ): Long? =
    synchronized(lock) {
      if (offsetSeconds == 0) return@synchronized null
      if (pendingGeneration != generation) {
        pendingOffset = 0
        pendingGeneration = generation
      }
      val summed = (pendingOffset.toLong() + offsetSeconds)
        .coerceIn(-Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong())
        .toInt()
      pendingOffset = summed
      if (inFlight || summed == 0) return@synchronized null
      inFlight = true
      val workerId = ++workerSeq
      activeWorkerId = workerId
      workerId
    }

  /** Pops the next pending batch for [workerId], or retires this worker when nothing remains. */
  fun takeNext(workerId: Long): Batch? =
    synchronized(lock) {
      if (!inFlight || activeWorkerId != workerId) return@synchronized null
      val generation = pendingGeneration
      val offset = pendingOffset
      if (generation == null || offset == 0) {
        inFlight = false
        return@synchronized null
      }
      pendingOffset = 0
      Batch(
        generation = generation,
        offsetSeconds = offset,
        cancellationToken = cancellationSeq,
      )
    }

  fun isTokenValid(token: Long): Boolean = synchronized(lock) { cancellationSeq == token }

  /** Discards unstarted queued seeks and invalidates the current batch token before explicit seeks/teardown. */
  fun cancelPending() {
    synchronized(lock) {
      cancellationSeq++
      pendingOffset = 0
      pendingGeneration = null
    }
  }

  fun finishWorker(workerId: Long) {
    synchronized(lock) {
      if (activeWorkerId == workerId) {
        inFlight = false
      }
    }
  }
}
