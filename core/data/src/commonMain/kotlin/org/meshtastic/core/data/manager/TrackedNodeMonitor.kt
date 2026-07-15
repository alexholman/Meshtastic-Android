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

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import org.meshtastic.core.repository.NodeManager
import org.meshtastic.core.repository.Notification
import org.meshtastic.core.repository.NotificationManager
import org.meshtastic.core.repository.TrackingPrefs
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.getStringSuspend
import org.meshtastic.core.resources.tracking_reacquired_body
import org.meshtastic.core.resources.tracking_reacquired_title
import org.meshtastic.core.resources.unknown_username
import org.meshtastic.proto.Position
import kotlin.time.Duration.Companion.minutes

/**
 * Raises a reacquisition alert when a node selected on the tracking map reports a position after at least
 * [TrackingPrefs.reacquisitionTimeoutMinutes] minutes of position silence — the "runner came back into coverage" signal
 * for tracking in rugged terrain.
 *
 * Hooked from [MeshDataHandlerImpl.handlePosition] beside [GeofenceMonitor], and structured the same way: received
 * positions are funnelled through a single ordered worker so two positions for the same node can never be evaluated out
 * of order (which would corrupt the last-seen baseline and fire spurious alerts).
 *
 * The decision is delegated to a per-node state machine ([ReacquisitionTracker]): the first position seen for a node
 * only seeds its baseline, and every later position whose gap since the previous one is at least the timeout is a
 * reacquisition — even when that previous position was the only fix before the loss, which is exactly the
 * single-ping-then-dead-zone pattern this fork exists for. An untracked node's state is dropped, so tracking-start
 * seeds fresh instead of alerting instantly off a stale baseline (an app restart reseeds the same way, since all state
 * is in-memory). Accepted trade-off (see [ReacquisitionTracker]): a node whose routine cadence exceeds the timeout
 * alerts on every packet, mitigated by the per-node notification id replacing rather than stacking alerts.
 *
 * Timing is monotonic ([kotlin.time.TimeSource]), not wall-clock, so an NTP correction on a field device that syncs its
 * clock after boot can neither fabricate a spurious alert nor swallow a real one.
 */
@Single
class TrackedNodeMonitor(
    private val nodeManager: NodeManager,
    private val notificationManager: NotificationManager,
    private val trackingPrefs: TrackingPrefs,
    @Named("ServiceScope") private val scope: CoroutineScope,
) {

    // Unbounded so we never drop a sample (which would corrupt the last-seen baseline); positions arrive infrequently.
    private val samples = Channel<Int>(Channel.UNLIMITED)

    /**
     * Reacquisition state machine. `internal var` (not a constructor param) so `@Single` Koin resolution stays a plain
     * 4-arg graph, while tests can swap in a [ReacquisitionTracker] backed by a [kotlin.time.TestTimeSource].
     */
    internal var reacquisitionTracker: ReacquisitionTracker = ReacquisitionTracker()

    /**
     * String resolution seam. Defaults to compose-resources' [getStringSuspend], which never resolves in the plain-JVM
     * test runner; tests override this to assert the dispatch path.
     */
    internal var resolveString: suspend (StringResource, Array<out Any>) -> String = { resource, args ->
        getStringSuspend(resource, *args)
    }

    init {
        scope.launch {
            for (nodeNum in samples) {
                try {
                    evaluate(nodeNum)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // Isolate per-sample failures: an unexpected throw must not kill the sole consumer and silently
                    // stop reacquisition alerts for the rest of the session.
                    Logger.e(e) { "Reacquisition evaluation failed for node $nodeNum; skipping sample" }
                }
            }
        }
    }

    /** Record a received node position. [nodeNum] is the position's sender. */
    fun onPositionReceived(nodeNum: Int, myNodeNum: Int, position: Position) {
        val latI = position.latitude_i ?: 0
        val lonI = position.longitude_i ?: 0
        // Skip our own position and no-fix reports (0,0): a node with no GPS lock hasn't "reported a location."
        if (nodeNum == myNodeNum || (latI == 0 && lonI == 0)) return
        samples.trySend(nodeNum)
    }

    private suspend fun evaluate(nodeNum: Int) {
        val tracked = nodeNum in trackingPrefs.trackedNodeNums.value
        val timeout = trackingPrefs.reacquisitionTimeoutMinutes.value.minutes
        // The tracker always folds the sighting into per-node state, so disabling alerts still updates the baseline.
        val decision = reacquisitionTracker.evaluate(nodeNum, tracked, timeout)
        if (decision.alert && trackingPrefs.reacquisitionAlertsEnabled.value) {
            notifyReacquired(nodeNum, decision.gapMinutes)
        }
    }

    private suspend fun notifyReacquired(nodeNum: Int, gapMinutes: Long) {
        val node = nodeManager.nodeDBbyNodeNum[nodeNum]
        val nodeName =
            node?.user?.long_name?.takeIf { it.isNotBlank() }
                ?: node?.user?.short_name?.takeIf { it.isNotBlank() }
                ?: resolveString(Res.string.unknown_username, emptyArray())
        notificationManager.dispatch(
            Notification(
                title = resolveString(Res.string.tracking_reacquired_title, arrayOf(nodeName)),
                message = resolveString(Res.string.tracking_reacquired_body, arrayOf<Any>(nodeName, gapMinutes)),
                // Warning maps to a raised urgency on the Linux desktop sender (NotifyUrgency.NORMAL, up from the LOW
                // that the default Type.Info would yield); other platforms key off category, so this is a no-op there.
                type = Notification.Type.Warning,
                category = Notification.Category.Tracking,
                // Salted so a reacquisition alert never replaces another category's per-node notification.
                id = "tracking:$nodeNum".hashCode(),
                // Literal URI avoids a core:navigation module dep (see NodeManagerImpl).
                deepLinkUri = "meshtastic://meshtastic/tracking",
            ),
        )
    }
}
