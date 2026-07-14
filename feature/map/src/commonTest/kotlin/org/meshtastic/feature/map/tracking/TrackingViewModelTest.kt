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
package org.meshtastic.feature.map.tracking

import app.cash.turbine.test
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.common.BuildConfigProvider
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.MapPrefs
import org.meshtastic.core.repository.MeshLogRepository
import org.meshtastic.core.repository.TrackingPrefs
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.feature.map.tracking.model.GpxFileEntry
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var nodeRepository: FakeNodeRepository
    private lateinit var trackingPrefs: FakeTrackingPrefs
    private lateinit var gpxFileStore: FakeGpxFileStore
    private val meshLogRepository: MeshLogRepository = mock()
    private val mapPrefs: MapPrefs = mock()
    private val buildConfigProvider: BuildConfigProvider = mock()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        nodeRepository = FakeNodeRepository()
        trackingPrefs = FakeTrackingPrefs()
        gpxFileStore = FakeGpxFileStore()
        every { mapPrefs.mapStyle } returns MutableStateFlow(0)
        every { buildConfigProvider.applicationId } returns "test.app"
        every { meshLogRepository.getMeshPacketsFrom(any(), any()) } returns flowOf(emptyList())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = TrackingViewModel(
        nodeRepository = nodeRepository,
        meshLogRepository = meshLogRepository,
        trackingPrefs = trackingPrefs,
        gpxFileStore = gpxFileStore,
        mapPrefs = mapPrefs,
        buildConfigProvider = buildConfigProvider,
    )

    @Test
    fun `empty selection produces empty tracks`() = runTest {
        nodeRepository.setNodes(listOf(node(NODE_A)))
        viewModel().mapState.test {
            val state = awaitItem()
            assertTrue(state.tracks.isEmpty())
        }
    }

    @Test
    fun `selected node produces a deduped time-sorted track`() = runTest {
        nodeRepository.setNodes(listOf(node(NODE_A), node(NODE_B)))
        trackingPrefs.setTrackedNodeNums(setOf(NODE_A))
        every { meshLogRepository.getMeshPacketsFrom(NODE_A, PortNum.POSITION_APP.value) } returns
            flowOf(
                listOf(
                    positionPacket(NODE_A, latI = 100, lonI = 200, time = 100),
                    // Same coordinates as the previous fix → deduped.
                    positionPacket(NODE_A, latI = 100, lonI = 200, time = 200),
                    // Out-of-order arrival → sorted by time.
                    positionPacket(NODE_A, latI = 300, lonI = 400, time = 50),
                ),
            )

        viewModel().mapState.test {
            var state = awaitItem()
            while (state.tracks.isEmpty()) state = awaitItem()
            assertEquals(1, state.tracks.size)
            val track = state.tracks.single()
            assertEquals(NODE_A, track.nodeNum)
            assertEquals("A", track.shortName)
            assertEquals(listOf(50, 100), track.positions.map { it.time })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleTracked adds and removes a node`() {
        val vm = viewModel()
        vm.toggleTracked(NODE_A)
        assertEquals(setOf(NODE_A), trackingPrefs.trackedNodeNums.value)
        vm.toggleTracked(NODE_A)
        assertTrue(trackingPrefs.trackedNodeNums.value.isEmpty())
    }

    @Test
    fun `addGpxFile persists an entry and removeGpxFile deletes it`() = runTest {
        val vm = viewModel()
        vm.addGpxFile(CommonUri.parse("content://docs/course.gpx"), "course.gpx")
        vm.gpxFiles.test {
            var entries = awaitItem()
            while (entries.isEmpty()) entries = awaitItem()
            assertEquals("course.gpx", entries.single().name)

            vm.removeGpxFile(entries.single())
            while (entries.isNotEmpty()) entries = awaitItem()
            assertTrue(gpxFileStore.files.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unparseable gpx is rejected and not persisted`() = runTest {
        gpxFileStore.contentOverride = "not xml at all"
        val vm = viewModel()
        vm.gpxImportErrors.test {
            vm.addGpxFile(CommonUri.parse("content://docs/bad.gpx"), "bad.gpx")
            awaitItem()
        }
        assertEquals("[]", trackingPrefs.gpxFilesJson.value)
        assertTrue(gpxFileStore.files.isEmpty())
    }

    private fun node(num: Int) =
        Node(num = num, user = User(short_name = if (num == NODE_A) "A" else "B", long_name = "Node $num"))

    private fun positionPacket(from: Int, latI: Int, lonI: Int, time: Int): MeshPacket {
        val payload =
            Position.ADAPTER.encode(Position(latitude_i = latI, longitude_i = lonI, time = time)).toByteString()
        return MeshPacket(from = from, decoded = Data(portnum = PortNum.POSITION_APP, payload = payload))
    }

    private class FakeTrackingPrefs : TrackingPrefs {
        override val trackedNodeNums = MutableStateFlow<Set<Int>>(emptySet())

        override fun setTrackedNodeNums(nums: Set<Int>) {
            trackedNodeNums.value = nums
        }

        override val reacquisitionTimeoutMinutes = MutableStateFlow(10)

        override fun setReacquisitionTimeoutMinutes(minutes: Int) {
            reacquisitionTimeoutMinutes.value = minutes
        }

        override val reacquisitionAlertsEnabled = MutableStateFlow(true)

        override fun setReacquisitionAlertsEnabled(enabled: Boolean) {
            reacquisitionAlertsEnabled.value = enabled
        }

        override val gpxFilesJson = MutableStateFlow("[]")

        override fun setGpxFilesJson(json: String) {
            gpxFilesJson.value = json
        }
    }

    private class FakeGpxFileStore : GpxFileStore {
        val files = mutableMapOf<String, String>()
        var contentOverride: String? = null
        private var nextId = 0

        override suspend fun import(sourceUri: CommonUri, displayName: String): GpxFileEntry? {
            val id = "gpx-${nextId++}"
            files[id] = contentOverride ?: VALID_GPX
            return GpxFileEntry(id = id, name = displayName, storedPath = "/fake/$id.gpx")
        }

        override suspend fun readText(entry: GpxFileEntry): String? = files[entry.id]

        override suspend fun delete(entry: GpxFileEntry) {
            files.remove(entry.id)
        }
    }

    private companion object {
        const val NODE_A = 10
        const val NODE_B = 11
        const val VALID_GPX =
            """<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="t">""" +
                """<wpt lat="1.0" lon="2.0"><name>W</name></wpt></gpx>"""
    }
}
