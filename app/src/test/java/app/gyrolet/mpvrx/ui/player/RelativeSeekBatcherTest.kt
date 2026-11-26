/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelativeSeekBatcherTest {
  @Test
  fun rapidTapsPastSixtySecondsCoalesceSingleFlightPerWorker() {
    val batcher = RelativeSeekBatcher()
    val workerId = assertNotNull(batcher.enqueue(generation = 1L, offsetSeconds = 10))
    // First batch runs immediately without waiting for more taps.
    val first = assertNotNull(batcher.takeNext(workerId))
    assertEquals(10, first.offsetSeconds)
    assertTrue(batcher.isTokenValid(first.cancellationToken))

    // Six+ rapid follow-up taps (> 60s) arriving mid-seek reuse the active worker cleanly.
    repeat(7) {
      assertNull(batcher.enqueue(generation = 1L, offsetSeconds = 10))
    }
    val burst = assertNotNull(batcher.takeNext(workerId))
    assertEquals(70, burst.offsetSeconds)
    assertNull(batcher.takeNext(workerId))

    // Once retired, the next tap starts a fresh worker.
    val nextWorker = assertNotNull(batcher.enqueue(generation = 1L, offsetSeconds = -10))
    val rev = assertNotNull(batcher.takeNext(nextWorker))
    assertEquals(-10, rev.offsetSeconds)
  }

  @Test
  fun oppositeTapsCancelingToZeroRetireWithoutExtraSeek() {
    val batcher = RelativeSeekBatcher()
    val workerId = assertNotNull(batcher.enqueue(generation = 2L, offsetSeconds = 10))
    assertNotNull(batcher.takeNext(workerId))

    assertNull(batcher.enqueue(generation = 2L, offsetSeconds = 10))
    assertNull(batcher.enqueue(generation = 2L, offsetSeconds = -10))
    assertNull(batcher.takeNext(workerId))
  }

  @Test
  fun generationChangeAndCancelPendingDiscardStaleOffsetsWithoutBreakingFutureWorkers() {
    val batcher = RelativeSeekBatcher()
    val workerId = assertNotNull(batcher.enqueue(generation = 1L, offsetSeconds = 10))
    val inFlightBatch = assertNotNull(batcher.takeNext(workerId))

    // Switching media discards unstarted generation-1 taps in favor of generation 2.
    assertNull(batcher.enqueue(generation = 1L, offsetSeconds = 20))
    assertNull(batcher.enqueue(generation = 2L, offsetSeconds = -10))
    val gen2Batch = assertNotNull(batcher.takeNext(workerId))
    assertEquals(2L, gen2Batch.generation)
    assertEquals(-10, gen2Batch.offsetSeconds)

    // Explicit seek cancellation invalidates the in-flight batch token and clears pending offsets.
    batcher.cancelPending()
    assertFalse(batcher.isTokenValid(inFlightBatch.cancellationToken))
    assertNull(batcher.takeNext(workerId))

    // New taps after cancellation still start cleanly, and stale finishWorker cannot clobber a newer worker.
    val newWorker = assertNotNull(batcher.enqueue(generation = 2L, offsetSeconds = 10))
    batcher.finishWorker(workerId)
    val postCancel = assertNotNull(batcher.takeNext(newWorker))
    assertEquals(10, postCancel.offsetSeconds)
  }

  @Test
  fun accumulatesLargeOffsetsWithSaturationInsteadOfIntOverflow() {
    val batcher = RelativeSeekBatcher()
    val workerId = assertNotNull(batcher.enqueue(generation = 3L, offsetSeconds = 10))
    assertNotNull(batcher.takeNext(workerId))

    assertNull(batcher.enqueue(generation = 3L, offsetSeconds = Int.MAX_VALUE))
    assertNull(batcher.enqueue(generation = 3L, offsetSeconds = 50))
    val clamped = assertNotNull(batcher.takeNext(workerId))
    assertEquals(Int.MAX_VALUE, clamped.offsetSeconds)
  }
}
