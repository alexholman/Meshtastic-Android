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
package org.meshtastic.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NavigationParityTest {

    @Test
    fun `all top level destinations are defined`() {
        assertEquals(6, TopLevelDestination.entries.size)
    }

    @Test
    fun `fromNavKey matches all top level routes`() {
        TopLevelDestination.entries.forEach { destination ->
            val result = TopLevelDestination.fromNavKey(destination.route)
            assertNotNull(result, "Should match destination for route ${destination.route}")
            assertEquals(destination, result)
        }
    }

    @Test
    fun `visibleEntries hides Tracking when the flag is disabled`() {
        val visible = TopLevelDestination.visibleEntries(trackingTabEnabled = false)
        assertFalse(
            visible.contains(TopLevelDestination.Tracking),
            "Tracking tab must be hidden when the flavor does not opt in",
        )
        assertEquals(5, visible.size)
        // Every non-Tracking destination stays visible.
        assertEquals(TopLevelDestination.entries.filter { it != TopLevelDestination.Tracking }, visible)
    }

    @Test
    fun `visibleEntries shows Tracking when the flag is enabled`() {
        val visible = TopLevelDestination.visibleEntries(trackingTabEnabled = true)
        assertTrue(visible.contains(TopLevelDestination.Tracking), "Tracking tab must be shown when the flavor opts in")
        assertEquals(TopLevelDestination.entries.toList(), visible)
    }
}
