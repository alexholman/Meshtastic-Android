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
package org.meshtastic.app.map.tracking

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.app.map.addCopyright
import org.meshtastic.app.map.addScaleBarOverlay
import org.meshtastic.app.map.model.CustomTileSource
import org.meshtastic.app.map.rememberMapViewWithLifecycle
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.tracking_fit_bounds
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.SelectAll
import org.meshtastic.feature.map.component.MapButton
import org.meshtastic.feature.map.tracking.model.TrackingMapState
import org.osmdroid.util.BoundingBox

private const val FIT_BOUNDS_PADDING_PX = 96

/**
 * OSMDroid tracking map: renders only the selected nodes' position histories (dot trails + latest arrow marker) and any
 * imported GPX overlays. See [addNodeTrack] and [addGpxOverlay] for the drawing details.
 */
@Composable
fun TrackingMap(state: TrackingMapState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current

    val allPoints = remember(state) { allTrackingGeoPoints(state.tracks, state.gpxOverlays) }
    val initialBox = remember { boundingBoxOf(allPoints) }
    val mapView =
        rememberMapViewWithLifecycle(
            applicationId = state.applicationId,
            box = initialBox,
            tileSource = CustomTileSource.getTileSource(state.mapStyleId),
        )

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { mapView },
            update = { map ->
                map.overlays.clear()
                map.addCopyright()
                map.addScaleBarOverlay(density)
                state.gpxOverlays.forEach { overlay -> map.addGpxOverlay(density, overlay) }
                state.tracks.forEach { track -> map.addNodeTrack(density, track) }
                map.invalidate()
            },
        )

        MapButton(
            icon = MeshtasticIcons.SelectAll,
            contentDescription = stringResource(Res.string.tracking_fit_bounds),
            onClick = {
                if (allPoints.isNotEmpty()) {
                    mapView.zoomToBoundingBox(boundingBoxOf(allPoints), true, FIT_BOUNDS_PADDING_PX)
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

private fun boundingBoxOf(points: List<org.osmdroid.util.GeoPoint>): BoundingBox =
    if (points.isEmpty()) BoundingBox(85.0, 180.0, -85.0, -180.0) else BoundingBox.fromGeoPoints(points)
