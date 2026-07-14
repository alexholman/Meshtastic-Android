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
 * Exercises the reacquisition arming state machine directly with a [TestTimeSource], so every branch — including the
 * positive alert path that [TrackedNodeMonitorTest] can only assert once end-to-end — is covered deterministically
 * without touching coroutines or compose-resources.
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
    fun longGapAfterSeedDoesNotAlertBecauseNodeIsNotYetArmed() {
        val time = TestTimeSource()
        val tracker = ReacquisitionTracker(time)

        tracker.evaluate(node, tracked = true, timeout) // seed only
        time += 20.minutes

        // A node must demonstrate one healthy interval before it may alert; the seed did not.
        assertFalse(tracker.evaluate(node, tracked = true, timeout).alert)
    }

    @Test
    fun healthyIntervalArmsThenLongGapAlertsOnceWithGapMinutes() {
        val time = TestTimeSource()
        val tracker = ReacquisitionTracker(time)

        tracker.evaluate(node, tracked = true, timeout) // seed
        time += 1.minutes
        assertFalse(tracker.evaluate(node, tracked = true, timeout).alert) // healthy interval -> arm
        time += 15.minutes

        val decision = tracker.evaluate(node, tracked = true, timeout)
        assertTrue(decision.alert)
        assertEquals(15L, decision.gapMinutes)
    }

    @Test
    fun secondConsecutiveLongGapDoesNotReAlert() {
        val time = TestTimeSource()
        val tracker = ReacquisitionTracker(time)

        tracker.evaluate(node, tracked = true, timeout) // seed
        time += 1.minutes
        tracker.evaluate(node, tracked = true, timeout) // arm
        time += 15.minutes
        assertTrue(tracker.evaluate(node, tracked = true, timeout).alert) // first loss
        time += 15.minutes

        // Not re-armed (previous gap was not healthy), so a second long gap stays silent.
        assertFalse(tracker.evaluate(node, tracked = true, timeout).alert)
    }

    @Test
    fun healthyIntervalReArmsAndNextLongGapAlertsAgain() {
        val time = TestTimeSource()
        val tracker = ReacquisitionTracker(time)

        tracker.evaluate(node, tracked = true, timeout) // seed
        time += 1.minutes
        tracker.evaluate(node, tracked = true, timeout) // arm
        time += 15.minutes
        assertTrue(tracker.evaluate(node, tracked = true, timeout).alert) // first loss, disarms
        time += 1.minutes
        assertFalse(tracker.evaluate(node, tracked = true, timeout).alert) // healthy interval -> re-arm
        time += 15.minutes

        assertTrue(tracker.evaluate(node, tracked = true, timeout).alert) // second loss
    }

    @Test
    fun gapExactlyEqualToTimeoutAlertsWhenArmed() {
        val time = TestTimeSource()
        val tracker = ReacquisitionTracker(time)

        tracker.evaluate(node, tracked = true, timeout) // seed
        time += 1.minutes
        tracker.evaluate(node, tracked = true, timeout) // arm
        time += 10.minutes // exactly the timeout

        assertTrue(tracker.evaluate(node, tracked = true, timeout).alert)
    }

    @Test
    fun untrackingDropsStateSoRetrackingBehavesLikeAFreshSeed() {
        val time = TestTimeSource()
        val tracker = ReacquisitionTracker(time)

        tracker.evaluate(node, tracked = true, timeout) // seed
        time += 1.minutes
        tracker.evaluate(node, tracked = true, timeout) // arm

        // Untracking drops the state and never alerts.
        assertFalse(tracker.evaluate(node, tracked = false, timeout).alert)
        time += 1.minutes
        assertFalse(tracker.evaluate(node, tracked = true, timeout).alert) // fresh seed after retrack
        time += 15.minutes

        // A long gap right after retracking must NOT alert: the node is a fresh, unarmed seed again.
        assertFalse(tracker.evaluate(node, tracked = true, timeout).alert)
    }
}
