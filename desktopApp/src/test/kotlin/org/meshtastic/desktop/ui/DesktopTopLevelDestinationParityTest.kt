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
package org.meshtastic.desktop.ui

import org.meshtastic.core.navigation.ConnectionsRoute
import org.meshtastic.core.navigation.ContactsRoute
import org.meshtastic.core.navigation.FirmwareRoute
import org.meshtastic.core.navigation.MapRoute
import org.meshtastic.core.navigation.NodesRoute
import org.meshtastic.core.navigation.Route
import org.meshtastic.core.navigation.SettingsRoute
import org.meshtastic.core.navigation.TopLevelDestination
import org.meshtastic.core.navigation.TrackingRoute
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keeps Desktop top-level destinations aligned with Android top-level navigation (Messages, Nodes, Map, Settings,
 * Connect). Desktop never opts the fork-specific Tracking tab in (no native map), so it renders the tracking-disabled
 * set — see [TopLevelDestination.visibleEntries].
 */
class DesktopTopLevelDestinationParityTest {

    // Desktop provides no override for LocalTrackingTabEnabled, so it renders the default (disabled) set.
    private val desktopRoutes: Set<KClass<out Route>> =
        TopLevelDestination.visibleEntries(trackingTabEnabled = false).map { it.route::class }.toSet()

    @Test
    fun `desktop top-level routes match android parity set`() {
        val androidParityRoutes: Set<KClass<out Route>> =
            setOf(
                ContactsRoute.Contacts::class,
                NodesRoute.Nodes::class,
                MapRoute.Map::class,
                SettingsRoute.Settings::class,
                ConnectionsRoute.Connections::class,
            )

        assertEquals(
            expected = androidParityRoutes,
            actual = desktopRoutes,
            message = "Desktop top-level destinations must stay aligned with Android parity set",
        )
    }

    @Test
    fun `tracking is not a desktop top-level destination`() {
        assertFalse(
            actual = desktopRoutes.contains(TrackingRoute.Tracking::class),
            message = "Tracking is fdroid-only and must not appear in the desktop top-level rail",
        )
        // The route itself stays registered so the tracking deep link never crashes on desktop.
        assertTrue(
            actual = TopLevelDestination.entries.any { it.route is TrackingRoute },
            message = "TrackingRoute must remain a registered top-level route for deep-link parity",
        )
    }

    @Test
    fun `firmware is not a desktop top-level destination`() {
        assertFalse(
            actual = desktopRoutes.contains(FirmwareRoute.FirmwareGraph::class),
            message = "Firmware must stay in-flow and not appear in the desktop top-level rail",
        )
    }
}
