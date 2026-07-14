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
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.nowSeconds
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

/**
 * Raises a reacquisition alert when a node selected on the tracking map reports a position after at least
 * [TrackingPrefs.reacquisitionTimeoutMinutes] minutes of position silence — the "runner came back into coverage" signal
 * for tracking in rugged terrain.
 *
 * Hooked from [MeshDataHandlerImpl.handlePosition] beside [GeofenceMonitor], and structured the same way: received
 * positions are funnelled through a single ordered worker so two positions for the same node can never be evaluated out
 * of order (which would corrupt the last-seen baseline and fire spurious alerts).
 *
 * Gap math uses local receipt time, not the device-reported position timestamp, since tracked nodes in the field may
 * have skewed clocks or no valid time fix. The first position seen for a node after app start only establishes the
 * baseline; alerts fire from the second sighting onward.
 */
@Single
class TrackedNodeMonitor(
    private val nodeManager: NodeManager,
    private val notificationManager: NotificationManager,
    private val trackingPrefs: TrackingPrefs,
    @Named("ServiceScope") private val scope: CoroutineScope,
) {

    private data class PositionSample(val nodeNum: Int, val receivedAtSeconds: Long)

    // Unbounded so we never drop a sample (which would corrupt the last-seen baseline); positions arrive infrequently.
    private val samples = Channel<PositionSample>(Channel.UNLIMITED)

    /** Last position receipt time per node. Only touched by the single serial consumer, so no locking is needed. */
    private val lastSeenSeconds = mutableMapOf<Int, Long>()

    init {
        scope.launch {
            for (sample in samples) {
                try {
                    evaluate(sample)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // Isolate per-sample failures: an unexpected throw must not kill the sole consumer and silently
                    // stop reacquisition alerts for the rest of the session.
                    Logger.e(e) { "Reacquisition evaluation failed for node ${sample.nodeNum}; skipping sample" }
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
        samples.trySend(PositionSample(nodeNum, nowSeconds))
    }

    private suspend fun evaluate(sample: PositionSample) {
        // put() returns the previous last-seen time; null means this is the baseline sighting.
        val previousSeconds = lastSeenSeconds.put(sample.nodeNum, sample.receivedAtSeconds)
        val gapSeconds = if (previousSeconds == null) -1L else sample.receivedAtSeconds - previousSeconds
        val timeoutSeconds = trackingPrefs.reacquisitionTimeoutMinutes.value.toLong() * SECONDS_PER_MINUTE
        val shouldNotify =
            gapSeconds >= 0 &&
                gapSeconds >= timeoutSeconds &&
                sample.nodeNum in trackingPrefs.trackedNodeNums.value &&
                trackingPrefs.reacquisitionAlertsEnabled.value
        if (shouldNotify) {
            notifyReacquired(sample.nodeNum, gapSeconds / SECONDS_PER_MINUTE)
        }
    }

    private suspend fun notifyReacquired(nodeNum: Int, gapMinutes: Long) {
        val node = nodeManager.nodeDBbyNodeNum[nodeNum]
        val nodeName =
            node?.user?.long_name?.takeIf { it.isNotBlank() }
                ?: node?.user?.short_name?.takeIf { it.isNotBlank() }
                ?: getStringSuspend(Res.string.unknown_username)
        notificationManager.dispatch(
            Notification(
                title = getStringSuspend(Res.string.tracking_reacquired_title, nodeName),
                message = getStringSuspend(Res.string.tracking_reacquired_body, nodeName, gapMinutes),
                category = Notification.Category.Tracking,
                // Salted so a reacquisition alert never replaces another category's per-node notification.
                id = "tracking:$nodeNum".hashCode(),
                // Literal URI avoids a core:navigation module dep (see NodeManagerImpl).
                deepLinkUri = "meshtastic://meshtastic/tracking",
            ),
        )
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
    }
}
