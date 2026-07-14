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
package org.meshtastic.feature.map.tracking.model

import kotlinx.serialization.Serializable
import org.meshtastic.proto.Position

/** The position history of one tracked node, ready for rendering. [positions] are ordered oldest → newest. */
data class NodeTrack(
    val nodeNum: Int,
    val shortName: String,
    val longName: String,
    /** Pair of (text color, background color) ARGB ints, as exposed by `Node.colors`. */
    val colors: Pair<Int, Int>,
    val positions: List<Position>,
) {
    val latestPosition: Position?
        get() = positions.lastOrNull()
}

/** A GPX file imported into app-internal storage. Persisted as JSON in `TrackingPrefs.gpxFilesJson`. */
@Serializable data class GpxFileEntry(val id: String, val name: String, val storedPath: String)

data class GpxPoint(val latitude: Double, val longitude: Double, val name: String? = null)

data class GpxTrack(val name: String?, val points: List<GpxPoint>)

/** Parsed contents of one GPX file. Routes are folded into [tracks]. */
data class GpxOverlayData(
    val fileId: String,
    val name: String,
    val waypoints: List<GpxPoint>,
    val tracks: List<GpxTrack>,
)

/** Everything the platform-specific tracking map needs to render one frame. */
data class TrackingMapState(
    val applicationId: String = "",
    val mapStyleId: Int = 0,
    val tracks: List<NodeTrack> = emptyList(),
    val gpxOverlays: List<GpxOverlayData> = emptyList(),
)
