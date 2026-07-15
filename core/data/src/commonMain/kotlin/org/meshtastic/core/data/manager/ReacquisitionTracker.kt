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
 * Pure, side-effect-free state machine deciding when a tracked node's position counts as a "signal reacquired" event.
 * Extracted from [TrackedNodeMonitor] so the decision logic is directly unit-testable with a
 * [kotlin.time.TestTimeSource]; the monitor keeps the coroutine/notification plumbing.
 *
 * Timing uses a monotonic [TimeSource] (NTP-immune, KMP-friendly): a clock correction on a field device that syncs time
 * after boot can neither fabricate nor swallow a gap.
 *
 * Per-node state is just the monotonic mark of the last received position. The first sighting of a node only seeds the
 * baseline; after that, *any* received position whose gap since the previous one is at least the timeout is a
 * reacquisition. There is deliberately no arming/hysteresis: a runner whose tracker gets exactly one fix out before
 * dropping into a dead zone must still alert when it comes back, even though it never demonstrated a healthy reporting
 * interval. The accepted trade-off is that a node whose routine broadcast cadence exceeds the timeout alerts on every
 * packet — that is a configuration mismatch (timeout set below the node's cadence), and it degrades gracefully because
 * the per-node notification id makes each alert replace the previous one rather than stack.
 */
internal class ReacquisitionTracker(private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic) {

    /**
     * Monotonic mark of each node's last received position. Owned by [TrackedNodeMonitor]'s single serial consumer, so
     * no locking is needed.
     */
    private val lastSeen = mutableMapOf<Int, ComparableTimeMark>()

    /** Outcome of evaluating one received position. [gapMinutes] is only meaningful when [alert] is true. */
    data class Decision(val alert: Boolean, val gapMinutes: Long)

    /**
     * Fold one received position into the state machine and report whether it is a reacquisition.
     *
     * @param tracked whether the node is currently selected for tracking. An untracked node has its state dropped and
     *   never alerts, so track → untrack → track behaves like a fresh seed.
     * @param timeout the silence threshold; a gap of at least this long between two received positions is a
     *   reacquisition.
     */
    fun evaluate(nodeNum: Int, tracked: Boolean, timeout: Duration): Decision {
        if (!tracked) {
            lastSeen.remove(nodeNum)
            return Decision(alert = false, gapMinutes = 0L)
        }
        val now = timeSource.markNow()
        val previous = lastSeen.put(nodeNum, now)
        // A null gap is the seeding first sighting, which never alerts.
        val gap = previous?.let { now - it }
        return Decision(alert = gap != null && gap >= timeout, gapMinutes = gap?.inWholeMinutes ?: 0L)
    }
}
