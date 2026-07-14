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

import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Pure, side-effect-free arming state machine deciding when a tracked node's position counts as a "signal reacquired"
 * event. Extracted from [TrackedNodeMonitor] so the decision logic is directly unit-testable with a
 * [kotlin.time.TestTimeSource]; the monitor keeps the coroutine/notification plumbing.
 *
 * Timing uses a monotonic [TimeSource] (NTP-immune, KMP-friendly): a clock correction on a field device that syncs time
 * after boot can neither fabricate nor swallow a gap.
 *
 * Per-node state is `lastSeen` + `armed`. A node must first demonstrate one *healthy* interval (a gap shorter than the
 * timeout) before it is allowed to raise an alert; the alert then fires exactly once when the next gap is at least the
 * timeout, and the node re-arms only after another healthy interval. This is why a beacon whose routine cadence already
 * exceeds the timeout never spams — it never demonstrates a healthy interval, so it stays disarmed. The documented
 * trade-off (see [TrackedNodeMonitor]) is that an alternating long-gap / single-fix pattern only alerts on the first
 * loss until a healthy interval occurs.
 */
internal class ReacquisitionTracker(private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic) {

    private data class NodeState(var lastSeen: ComparableTimeMark, var armed: Boolean)

    /** Per-node arming state. Owned by [TrackedNodeMonitor]'s single serial consumer, so no locking is needed. */
    private val states = mutableMapOf<Int, NodeState>()

    /** Outcome of evaluating one received position. [gapMinutes] is only meaningful when [alert] is true. */
    data class Decision(val alert: Boolean, val gapMinutes: Long)

    /**
     * Fold one received position into the state machine and report whether it is a reacquisition.
     *
     * @param tracked whether the node is currently selected for tracking. An untracked node has its state dropped and
     *   never alerts, so track → untrack → track behaves like a fresh seed.
     * @param timeout the silence threshold; a gap of at least this long on an armed node is a reacquisition.
     */
    fun evaluate(nodeNum: Int, tracked: Boolean, timeout: Duration): Decision {
        if (!tracked) {
            states.remove(nodeNum)
            return Decision(alert = false, gapMinutes = 0L)
        }
        val now = timeSource.markNow()
        val state = states[nodeNum]
        val decision =
            if (state == null) {
                // First sighting only seeds the baseline — never alerts.
                states[nodeNum] = NodeState(lastSeen = now, armed = false)
                Decision(alert = false, gapMinutes = 0L)
            } else {
                val gap = now - state.lastSeen
                val alert = state.armed && gap >= timeout
                state.lastSeen = now
                // Re-arm only after a healthy interval; a second consecutive long gap must not re-alert.
                state.armed = gap < timeout
                Decision(alert = alert, gapMinutes = gap.inWholeMinutes)
            }
        return decision
    }
}
