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

/**
 * Covers the reacquisition gating deterministically. The positive notification *dispatch* is NOT asserted here: it is
 * gated behind `getStringSuspend` (compose-resources), which does not resolve in the plain-JVM test runner (same
 * limitation as [GeofenceMonitorTest]). Coverage is structured around negatives that drive the full decision path up to
 * the dispatch entry point. A `timeoutMinutes = 0` config makes any gap qualify, so those negatives are stopped only by
 * the specific gate under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackedNodeMonitorTest {

    private val sender = 9
    private val myNodeNum = 1
    private val fix = Position(latitude_i = 100_000_000, longitude_i = 200_000_000)
    private val noFix = Position(latitude_i = 0, longitude_i = 0)

    private fun expectNoDispatch(
        tracked: Set<Int>,
        enabled: Boolean = true,
        timeoutMinutes: Int = 0,
        drive: (TrackedNodeMonitor) -> Unit,
    ) = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val nodeManager: NodeManager = mock(MockMode.autofill)
        val notifications: NotificationManager = mock(MockMode.autofill)
        val prefs: TrackingPrefs = mock(MockMode.autofill)
        every { prefs.trackedNodeNums } returns MutableStateFlow(tracked)
        every { prefs.reacquisitionAlertsEnabled } returns MutableStateFlow(enabled)
        every { prefs.reacquisitionTimeoutMinutes } returns MutableStateFlow(timeoutMinutes)
        every { nodeManager.nodeDBbyNodeNum } returns mapOf(sender to Node(num = sender))
        val monitor = TrackedNodeMonitor(nodeManager, notifications, prefs, scope)

        drive(monitor)
        scope.advanceUntilIdle()

        verify(exactly(0)) { notifications.dispatch(any()) }
        scope.cancel() // stop the serial worker so runTest sees no leaked coroutine
    }

    @Test
    fun firstSightingOnlyEstablishesBaseline() =
        expectNoDispatch(tracked = setOf(sender)) { m -> m.onPositionReceived(sender, myNodeNum, fix) }

    @Test
    fun untrackedNodeNeverAlerts() = expectNoDispatch(tracked = emptySet()) { m ->
        m.onPositionReceived(sender, myNodeNum, fix) // baseline
        m.onPositionReceived(sender, myNodeNum, fix) // gap qualifies (timeout 0), but node is untracked
    }

    @Test
    fun disabledAlertsSuppressDispatch() = expectNoDispatch(tracked = setOf(sender), enabled = false) { m ->
        m.onPositionReceived(sender, myNodeNum, fix) // baseline
        m.onPositionReceived(sender, myNodeNum, fix) // gap qualifies (timeout 0), but alerts are disabled
    }

    @Test
    fun gapUnderThresholdDoesNotAlert() = expectNoDispatch(tracked = setOf(sender), timeoutMinutes = 10) { m ->
        m.onPositionReceived(sender, myNodeNum, fix) // baseline
        m.onPositionReceived(sender, myNodeNum, fix) // near-zero real gap, threshold is 10 minutes
    }

    @Test
    fun ownPositionIsNeverEvaluated() = expectNoDispatch(tracked = setOf(myNodeNum)) { m ->
        m.onPositionReceived(myNodeNum, myNodeNum, fix)
        m.onPositionReceived(myNodeNum, myNodeNum, fix)
    }

    @Test
    fun noFixPositionIsIgnored() = expectNoDispatch(tracked = setOf(sender)) { m ->
        m.onPositionReceived(sender, myNodeNum, noFix)
        m.onPositionReceived(sender, myNodeNum, noFix)
    }
}
