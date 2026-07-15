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

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.meshtastic.app.R
import org.meshtastic.app.map.model.MarkerWithLabel
import org.meshtastic.core.model.util.GeoConstants.DEG_D
import org.meshtastic.feature.map.tracking.model.GpxOverlayData
import org.meshtastic.feature.map.tracking.model.NodeTrack
import org.meshtastic.proto.Position
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.advancedpolyline.MonochromaticPaintList

private const val TRACK_LINE_WIDTH_DP = 3f
private const val HISTORY_DOT_SIZE_DP = 8f
private const val GPX_LINE_WIDTH_DP = 3f
private const val GPX_LINE_COLOR = 0xB3555555.toInt()

/** Minimum distance between consecutive rendered history dots; closer fixes are decimated away. */
private const val MIN_DOT_SPACING_METERS = 10.0

/** Hard cap on rendered history dots per node, keeping osmdroid responsive on long tracks. */
private const val MAX_DOTS_PER_TRACK = 500

fun Position.toGeoPoint(): GeoPoint = GeoPoint((latitude_i ?: 0) * DEG_D, (longitude_i ?: 0) * DEG_D)

/**
 * Renders one node's track: a solid polyline in the node's color, decimated small dots for historical positions, and
 * the app's navigation-arrow marker (rotated to the last known heading) with a short-name label at the most recent
 * position.
 */
fun MapView.addNodeTrack(density: Density, track: NodeTrack) {
    val positions = track.positions
    if (positions.isEmpty()) return
    val nodeColor = track.colors.second

    val geoPoints = positions.map { it.toGeoPoint() }
    if (geoPoints.size > 1) {
        addSolidPolyline(density, geoPoints, nodeColor, TRACK_LINE_WIDTH_DP)
    }

    val dotDrawable =
        ShapeDrawable(OvalShape()).apply {
            with(density) {
                intrinsicWidth = HISTORY_DOT_SIZE_DP.dp.toPx().toInt()
                intrinsicHeight = HISTORY_DOT_SIZE_DP.dp.toPx().toInt()
            }
            paint.color = nodeColor
            paint.isAntiAlias = true
        }
    val historyDots = decimate(geoPoints.dropLast(1))
    overlays.addAll(
        historyDots.map { point ->
            Marker(this).apply {
                icon = dotDrawable
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                position = point
                setInfoWindow(null)
            }
        },
    )

    val latest = positions.last()
    val latestMarker =
        MarkerWithLabel(this, track.shortName).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_map_navigation)
            rotation = ((latest.ground_track ?: 0) * ROTATION_SCALE).toFloat()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            position = latest.toGeoPoint()
            setNodeColors(track.colors)
            title = track.longName
        }
    overlays.add(latestMarker)
}

/** Renders a parsed GPX file: dashed neutral polylines for tracks/routes and labeled pin markers for waypoints. */
fun MapView.addGpxOverlay(density: Density, overlay: GpxOverlayData) {
    overlay.tracks.forEach { track ->
        val geoPoints = track.points.map { GeoPoint(it.latitude, it.longitude) }
        if (geoPoints.size > 1) {
            addSolidPolyline(density, geoPoints, GPX_LINE_COLOR, GPX_LINE_WIDTH_DP, dashed = true)
        }
    }
    overlay.waypoints.forEach { waypoint ->
        val marker =
            MarkerWithLabel(this, waypoint.name ?: overlay.name).apply {
                icon = ContextCompat.getDrawable(context, R.drawable.ic_location_on)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                position = GeoPoint(waypoint.latitude, waypoint.longitude)
            }
        overlays.add(marker)
    }
}

/** All points rendered on the tracking map, used to compute the camera bounding box. */
fun allTrackingGeoPoints(tracks: List<NodeTrack>, gpxOverlays: List<GpxOverlayData>): List<GeoPoint> =
    tracks.flatMap { track -> track.positions.map { it.toGeoPoint() } } +
        gpxOverlays.flatMap { overlay ->
            overlay.waypoints.map { GeoPoint(it.latitude, it.longitude) } +
                overlay.tracks.flatMap { track -> track.points.map { GeoPoint(it.latitude, it.longitude) } }
        }

private fun MapView.addSolidPolyline(
    density: Density,
    geoPoints: List<GeoPoint>,
    color: Int,
    widthDp: Float,
    dashed: Boolean = false,
) {
    val polyline =
        Polyline(this).apply {
            val paint =
                Paint().apply {
                    this.color = color
                    isAntiAlias = true
                    strokeWidth = with(density) { widthDp.dp.toPx() }
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                    if (dashed) pathEffect = DashPathEffect(floatArrayOf(40f, 30f), 0f)
                }
            outlinePaintLists.add(MonochromaticPaintList(paint))
            setPoints(geoPoints)
            setInfoWindow(null)
        }
    overlays.add(polyline)
}

private fun decimate(points: List<GeoPoint>): List<GeoPoint> {
    if (points.isEmpty()) return points
    val kept = mutableListOf(points.first())
    points.drop(1).forEach { point ->
        if (kept.last().distanceToAsDouble(point) >= MIN_DOT_SPACING_METERS) kept += point
    }
    return if (kept.size <= MAX_DOTS_PER_TRACK) kept else kept.takeLast(MAX_DOTS_PER_TRACK)
}

private const val ROTATION_SCALE = 1e-5
