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

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.meshtastic.app.R
import org.meshtastic.core.common.gpsDisabled
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.location_disabled
import org.meshtastic.core.ui.util.PermissionStatus
import org.meshtastic.core.ui.util.rememberLocationPermissionState
import org.meshtastic.core.ui.util.showToast
import org.osmdroid.bonuspack.utils.BonusPackHelper.getBitmapFromVectorDrawable
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * Phone-position overlay for the tracking map, mirroring the main fdroid map's my-location behavior: osmdroid's
 * [MyLocationNewOverlay] with the app's location-dot person icon and directional navigation arrow, toggled by a map
 * button behind the runtime location permission. The phone was chosen over the connected node's own reported position
 * because it is fresher, carries orientation, and the two are physically together anyway.
 */
@Stable
class TrackingMyLocationController
internal constructor(
    private val mapView: MapView,
    private val context: Context,
    private val scope: CoroutineScope,
) {

    var overlay: MyLocationNewOverlay? by mutableStateOf(null)
        private set

    val enabled: Boolean
        get() = overlay != null

    /** Set by [rememberTrackingMyLocation] so the button click routes through the permission flow. */
    internal var onButtonClick: () -> Unit = {}

    internal fun toggle() {
        if (context.gpsDisabled()) {
            scope.launch { context.showToast(Res.string.location_disabled) }
            return
        }
        val current = overlay
        if (current == null) {
            overlay =
                MyLocationNewOverlay(mapView)
                    .apply {
                        enableMyLocation()
                        getBitmapFromVectorDrawable(context, R.drawable.ic_map_location_dot)?.let {
                            setPersonIcon(it)
                            setPersonAnchor(CENTER_ANCHOR, CENTER_ANCHOR)
                        }
                        getBitmapFromVectorDrawable(context, R.drawable.ic_map_navigation)?.let {
                            setDirectionIcon(it)
                            setDirectionAnchor(CENTER_ANCHOR, CENTER_ANCHOR)
                        }
                    }
                    .also { mapView.overlays.add(it) }
        } else {
            current.disableMyLocation()
            mapView.overlays.remove(current)
            overlay = null
        }
        mapView.invalidate()
    }

    /**
     * Re-adds the overlay after the tracking map's update block rebuilds its overlay list from scratch
     * (`overlays.clear()` would otherwise silently drop the location dot on the next data emission).
     */
    fun reattach() {
        val current = overlay ?: return
        if (mapView.overlays.none { it is MyLocationNewOverlay }) {
            mapView.overlays.add(current)
        }
    }

    private companion object {
        const val CENTER_ANCHOR = 0.5f
    }
}

/**
 * Remembers a [TrackingMyLocationController] for [mapView] and wires its button click through the runtime location
 * permission: granted → toggle, permanently denied → app settings, otherwise → request and toggle on grant (same flow
 * as the main fdroid map).
 */
@Composable
fun rememberTrackingMyLocation(mapView: MapView): TrackingMyLocationController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(mapView) { TrackingMyLocationController(mapView, context, scope) }
    val locationPermission = rememberLocationPermissionState()
    var toggleAfterPermission by remember { mutableStateOf(false) }

    LaunchedEffect(locationPermission.isGranted) {
        if (locationPermission.isGranted && toggleAfterPermission) {
            controller.toggle()
            toggleAfterPermission = false
        }
    }

    controller.onButtonClick = {
        when {
            locationPermission.isGranted -> controller.toggle()

            // Permanently denied: the system won't prompt again, so send the user to settings.
            locationPermission.status == PermissionStatus.PERMANENTLY_DENIED -> locationPermission.openAppSettings()

            else -> {
                toggleAfterPermission = true
                locationPermission.request()
            }
        }
    }
    return controller
}
