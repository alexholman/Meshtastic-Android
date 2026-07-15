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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.meshtastic.core.resources.toggle_my_position
import org.meshtastic.core.resources.tracking_fit_bounds
import org.meshtastic.core.ui.icon.LocationDisabled
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.MyLocation
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
    val initialBox = remember(allPoints) { boundingBoxOf(allPoints) }
    val mapView =
        rememberMapViewWithLifecycle(
            applicationId = state.applicationId,
            box = initialBox,
            tileSource = CustomTileSource.getTileSource(state.mapStyleId),
        )

    // Frame the data exactly once: the map is created with a world-view default (see [boundingBoxOf]) since
    // tracks/GPX overlays typically load a moment after first composition. As soon as we see the first non-empty
    // set of points -- whether that's on the very first composition or a later recomposition -- zoom to fit them
    // and never again, so the user's manual panning/zooming afterward is preserved. The fit-bounds [MapButton]
    // remains the manual re-frame affordance.
    var hasCentered by remember { mutableStateOf(false) }
    LaunchedEffect(allPoints) {
        if (hasCentered || allPoints.isEmpty()) return@LaunchedEffect
        mapView.zoomToBoundingBox(boundingBoxOf(allPoints), true, FIT_BOUNDS_PADDING_PX)
        hasCentered = true
    }

    val myLocation = rememberTrackingMyLocation(mapView)

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { mapView },
            update = { map ->
                val desiredTileSource = CustomTileSource.getTileSource(state.mapStyleId)
                if (map.tileProvider.tileSource.name() != desiredTileSource.name()) {
                    map.setTileSource(desiredTileSource)
                }
                map.overlays.clear()
                map.addCopyright()
                map.addScaleBarOverlay(density)
                state.gpxOverlays.forEach { overlay -> map.addGpxOverlay(density, overlay) }
                state.tracks.forEach { track -> map.addNodeTrack(density, track) }
                myLocation.reattach()
                map.invalidate()
            },
        )

        TrackingMapButtons(
            myLocation = myLocation,
            onFitBounds = {
                if (allPoints.isNotEmpty()) {
                    mapView.zoomToBoundingBox(boundingBoxOf(allPoints), true, FIT_BOUNDS_PADDING_PX)
                }
            },
        )
    }
}

@Composable
private fun BoxScope.TrackingMapButtons(myLocation: TrackingMyLocationController, onFitBounds: () -> Unit) {
    Column(
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapButton(
            icon = if (myLocation.enabled) MeshtasticIcons.LocationDisabled else MeshtasticIcons.MyLocation,
            contentDescription = stringResource(Res.string.toggle_my_position),
            // Read the var at click time: the permission flow reassigns it on recomposition.
            onClick = { myLocation.onButtonClick() },
        )
        MapButton(
            icon = MeshtasticIcons.SelectAll,
            contentDescription = stringResource(Res.string.tracking_fit_bounds),
            onClick = onFitBounds,
        )
    }
}

private fun boundingBoxOf(points: List<org.osmdroid.util.GeoPoint>): BoundingBox =
    if (points.isEmpty()) BoundingBox(85.0, 180.0, -85.0, -180.0) else BoundingBox.fromGeoPoints(points)
