/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.core.data.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * Exercises the reacquisition state machine directly with a [TestTimeSource], so every branch — including the positive
 * alert path that [TrackedNodeMonitorTest] can only assert once end-to-end — is covered deterministically without
 * touching coroutines or compose-resources.
 */
class ReacquisitionTrackerTest {

    private val node = 9
    private val timeout = 10.minutes

    @Test
    fun firstSightingSeedsAndNeverAlerts() {
        val time = TestTimeSource()
        val tracker = ReacquisitionTracker(time)

        assertFalse(tracker.evaluate(node, tracked = true, timeout).alert)
    }

    @Test
    fun longGapAfterSeedAlertsWithGapMinutes() {
        val time = TestTimeSource()
        val tracker = ReacquisitionTracker(time)

        tracker.evaluate(node, tracked = true, timeout) // seed
        time += 20.minutes

        // A single fix before the loss is enough: the very next position after the gap is a reacquisition.
        val decision = tracker.evaluate(node, tracked = true, timeout)
        assertTrue(decision.alert)
        assertEquals(20L, decision.gapMinutes)
    }

    @Test
    fun gapUnderTimeoutDoesNotAlert() {
        val time = TestTimeSource()
        val tracker = ReacquisitionTracker(time)

        tracker.evaluate(node, tracked = true, timeout) // seed
        time += 9.minutes

        assertFalse(tracker.evaluate(node, tracked = true, timeout).alert)
    }

    @Test
    fun consecutiveLongGapsEachAlert() {
        val time = TestTimeSource()
        val tracker = ReacquisitionTracker(time)

        tracker.evaluate(node, tracked = true, timeout) // seed
        time += 15.minutes
        assertTrue(tracker.evaluate(node, tracked = true, timeout).alert) // first loss

        time += 15.minutes
        // Every gap of at least the timeout is its own reacquisition — no arming between them.
        assertTrue(tracker.evaluate(node, tracked = true, timeout).alert)
    }

    @Test
    fun shortGapBetweenLongGapsOnlyTheLongGapsAlert() {
        val time = TestTimeSource()
        val tracker = ReacquisitionTracker(time)

        tracker.evaluate(node, tracked = true, timeout) // seed
        time += 15.minutes
        assertTrue(tracker.evaluate(node, tracked = true, timeout).alert) // loss
        time += 1.minutes
        assertFalse(tracker.evaluate(node, tracked = true, timeout).alert) // routine report
        time += 15.minutes

        assertTrue(tracker.evaluate(node, tracked = true, timeout).alert) // second loss
    }

    @Test
    fun gapExactlyEqualToTimeoutAlerts() {
        val time = TestTimeSource()
        val tracker = ReacquisitionTracker(time)

        tracker.evaluate(node, tracked = true, timeout) // seed
        time += 10.minutes // exactly the timeout

        assertTrue(tracker.evaluate(node, tracked = true, timeout).alert)
    }

    @Test
    fun untrackingDropsStateSoRetrackingBehavesLikeAFreshSeed() {
        val time = TestTimeSource()
        val tracker = ReacquisitionTracker(time)

        tracker.evaluate(node, tracked = true, timeout) // seed

        // Untracking drops the state and never alerts.
        assertFalse(tracker.evaluate(node, tracked = false, timeout).alert)
        time += 15.minutes

        // The first position after retracking is a fresh seed, even though the wall gap exceeded the timeout —
        // otherwise adding a node to tracking would alert instantly off a stale baseline.
        assertFalse(tracker.evaluate(node, tracked = true, timeout).alert)
    }
}
