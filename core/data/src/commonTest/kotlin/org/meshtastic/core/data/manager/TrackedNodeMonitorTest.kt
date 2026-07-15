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

import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.exactly
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.NodeManager
import org.meshtastic.core.repository.NotificationManager
import org.meshtastic.core.repository.TrackingPrefs
import org.meshtastic.proto.Position
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * Wiring-level coverage of the monitor: the skip gates, the tracked/enabled gates, and — unlike the pure
 * [ReacquisitionTrackerTest] — the full positive dispatch through the mocked [NotificationManager]. Time is made
 * deterministic by swapping in a [ReacquisitionTracker] backed by a [TestTimeSource], and the `getStringSuspend`
 * compose-resources hang is sidestepped by overriding [TrackedNodeMonitor.resolveString]. Each delivered position is
 * drained to completion before the test source advances, so the serial consumer evaluates it at a known instant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackedNodeMonitorTest {

    private val sender = 9
    private val myNodeNum = 1
    private val fix = Position(latitude_i = 100_000_000, longitude_i = 200_000_000)
    private val noFix = Position(latitude_i = 0, longitude_i = 0)

    private class Harness(
        val monitor: TrackedNodeMonitor,
        val notifications: NotificationManager,
        val time: TestTimeSource,
        val tracked: MutableStateFlow<Set<Int>>,
        val enabled: MutableStateFlow<Boolean>,
        private val scope: TestScope,
    ) {
        /** Deliver one position for [sender] and let the serial worker fully evaluate it at the current time. */
        fun deliver(nodeNum: Int, position: Position) {
            monitor.onPositionReceived(nodeNum, MY_NODE_NUM, position)
            scope.advanceUntilIdle()
        }

        fun assertDispatched(times: Int) = verify(exactly(times)) { notifications.dispatch(any()) }

        fun stop() = scope.cancel() // stop the serial worker so runTest sees no leaked coroutine

        private companion object {
            const val MY_NODE_NUM = 1
        }
    }

    private fun TestScope.harness(tracked: Set<Int>, enabled: Boolean = true, timeoutMinutes: Int = 10): Harness {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val nodeManager: NodeManager = mock(MockMode.autofill)
        val notifications: NotificationManager = mock(MockMode.autofill)
        val prefs: TrackingPrefs = mock(MockMode.autofill)
        val trackedFlow = MutableStateFlow(tracked)
        val enabledFlow = MutableStateFlow(enabled)
        every { prefs.trackedNodeNums } returns trackedFlow
        every { prefs.reacquisitionAlertsEnabled } returns enabledFlow
        every { prefs.reacquisitionTimeoutMinutes } returns MutableStateFlow(timeoutMinutes)
        every { nodeManager.nodeDBbyNodeNum } returns mapOf(sender to Node(num = sender))
        val time = TestTimeSource()
        val monitor =
            TrackedNodeMonitor(nodeManager, notifications, prefs, scope).apply {
                reacquisitionTracker = ReacquisitionTracker(time)
                // Avoid the compose-resources getStringSuspend hang if the dispatch path is reached.
                resolveString = { _, _ -> "stub" }
            }
        return Harness(monitor, notifications, time, trackedFlow, enabledFlow, scope)
    }

    @Test
    fun firstSightingOnlyEstablishesBaseline() = runTest {
        val h = harness(tracked = setOf(sender))
        h.deliver(sender, fix)
        h.assertDispatched(0)
        h.stop()
    }

    @Test
    fun ownPositionIsNeverEvaluated() = runTest {
        val h = harness(tracked = setOf(myNodeNum))
        h.deliver(myNodeNum, fix)
        h.time += 20.minutes
        h.deliver(myNodeNum, fix)
        h.assertDispatched(0)
        h.stop()
    }

    @Test
    fun noFixPositionIsIgnored() = runTest {
        val h = harness(tracked = setOf(sender))
        h.deliver(sender, noFix)
        h.time += 20.minutes
        h.deliver(sender, noFix)
        h.assertDispatched(0)
        h.stop()
    }

    @Test
    fun untrackedNodeNeverAlerts() = runTest {
        val h = harness(tracked = emptySet())
        h.deliver(sender, fix) // would-be seed
        h.time += 20.minutes
        h.deliver(sender, fix) // long gap, but node is untracked
        h.assertDispatched(0)
        h.stop()
    }

    @Test
    fun gapUnderThresholdDoesNotAlert() = runTest {
        val h = harness(tracked = setOf(sender), timeoutMinutes = 10)
        h.deliver(sender, fix) // seed
        h.time += 2.minutes
        h.deliver(sender, fix) // gap 2 min < 10 min threshold
        h.assertDispatched(0)
        h.stop()
    }

    @Test
    fun longGapAfterSingleSeedDispatches() = runTest {
        val h = harness(tracked = setOf(sender), timeoutMinutes = 10)
        h.deliver(sender, fix) // the only fix before the loss
        h.time += 15.minutes
        h.deliver(sender, fix) // reacquisition after a long silence
        h.assertDispatched(1)
        h.stop()
    }

    @Test
    fun consecutiveLongGapsEachDispatch() = runTest {
        val h = harness(tracked = setOf(sender), timeoutMinutes = 10)
        h.deliver(sender, fix) // seed
        h.time += 15.minutes
        h.deliver(sender, fix) // first loss -> dispatch
        h.time += 15.minutes
        h.deliver(sender, fix) // second loss -> dispatch again (no arming between gaps)
        h.assertDispatched(2)
        h.stop()
    }

    @Test
    fun disabledSuppressesDispatchButStateStillAdvances() = runTest {
        val h = harness(tracked = setOf(sender), enabled = false, timeoutMinutes = 10)
        h.deliver(sender, fix) // seed (disabled)
        h.time += 15.minutes
        h.deliver(sender, fix) // tracker would alert, but alerts are disabled -> suppressed
        h.assertDispatched(0)

        // Re-enable. Normal operation resuming immediately proves the tracker kept folding sightings into per-node
        // state throughout the disabled window (the last-seen baseline was maintained).
        h.enabled.value = true
        h.time += 15.minutes
        h.deliver(sender, fix) // long gap while enabled -> dispatches
        h.assertDispatched(1)
        h.stop()
    }

    @Test
    fun untrackThenTrackBehavesLikeFreshSeed() = runTest {
        val h = harness(tracked = setOf(sender), timeoutMinutes = 10)
        h.deliver(sender, fix) // seed
        h.tracked.value = emptySet() // untrack -> drops state
        h.time += 1.minutes
        h.deliver(sender, fix) // untracked, resets
        h.tracked.value = setOf(sender) // retrack
        h.time += 15.minutes
        h.deliver(sender, fix) // first position after retracking is a fresh seed despite the long wall gap
        h.assertDispatched(0)
        h.stop()
    }
}
