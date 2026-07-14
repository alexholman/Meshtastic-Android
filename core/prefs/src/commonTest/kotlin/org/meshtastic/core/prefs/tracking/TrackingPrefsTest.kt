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
package org.meshtastic.core.prefs.tracking

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.repository.TrackingPrefs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class TrackingPrefsTest {
    private lateinit var tmpDir: Path

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var trackingPrefs: TrackingPrefs
    private lateinit var dispatchers: CoroutineDispatchers

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @BeforeTest
    fun setup() {
        tmpDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "trackingPrefsTest-${Uuid.random()}"
        FileSystem.SYSTEM.createDirectories(tmpDir)
        dataStore =
            PreferenceDataStoreFactory.createWithPath(
                scope = testScope,
                produceFile = { tmpDir / "test.preferences_pb" },
            )
        dispatchers = CoroutineDispatchers(testDispatcher, testDispatcher, testDispatcher)
        trackingPrefs = TrackingPrefsImpl(dataStore, dispatchers)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(tmpDir)
    }

    @Test
    fun `trackedNodeNums defaults to empty`() =
        testScope.runTest { assertTrue(trackingPrefs.trackedNodeNums.value.isEmpty()) }

    @Test
    fun `trackedNodeNums round-trips a set of ints`() = testScope.runTest {
        trackingPrefs.setTrackedNodeNums(setOf(1, 42, -100))
        assertEquals(setOf(1, 42, -100), trackingPrefs.trackedNodeNums.value)
    }

    @Test
    fun `reacquisitionTimeoutMinutes defaults to 10`() =
        testScope.runTest { assertEquals(10, trackingPrefs.reacquisitionTimeoutMinutes.value) }

    @Test
    fun `setting reacquisitionTimeoutMinutes updates preference`() = testScope.runTest {
        trackingPrefs.setReacquisitionTimeoutMinutes(25)
        assertEquals(25, trackingPrefs.reacquisitionTimeoutMinutes.value)
    }

    @Test
    fun `reacquisitionTimeoutMinutes is clamped to at least one minute`() = testScope.runTest {
        trackingPrefs.setReacquisitionTimeoutMinutes(0)
        assertEquals(1, trackingPrefs.reacquisitionTimeoutMinutes.value)
    }

    @Test
    fun `reacquisitionAlertsEnabled defaults to true`() =
        testScope.runTest { assertTrue(trackingPrefs.reacquisitionAlertsEnabled.value) }

    @Test
    fun `setting reacquisitionAlertsEnabled updates preference`() = testScope.runTest {
        trackingPrefs.setReacquisitionAlertsEnabled(false)
        assertFalse(trackingPrefs.reacquisitionAlertsEnabled.value)
    }

    @Test
    fun `gpxFilesJson defaults to empty list`() =
        testScope.runTest { assertEquals("[]", trackingPrefs.gpxFilesJson.value) }

    @Test
    fun `setting gpxFilesJson updates preference`() = testScope.runTest {
        val json = """[{"id":"a","name":"course.gpx","storedPath":"/tmp/a.gpx"}]"""
        trackingPrefs.setGpxFilesJson(json)
        assertEquals(json, trackingPrefs.gpxFilesJson.value)
    }
}
