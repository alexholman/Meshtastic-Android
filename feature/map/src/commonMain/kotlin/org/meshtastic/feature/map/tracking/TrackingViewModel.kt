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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.BuildConfigProvider
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.model.MeshLog
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.MapPrefs
import org.meshtastic.core.repository.MeshLogRepository
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.TrackingPrefs
import org.meshtastic.core.ui.util.toPosition
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed
import org.meshtastic.feature.map.tracking.gpx.GpxParser
import org.meshtastic.feature.map.tracking.model.GpxFileEntry
import org.meshtastic.feature.map.tracking.model.GpxOverlayData
import org.meshtastic.feature.map.tracking.model.NodeTrack
import org.meshtastic.feature.map.tracking.model.TrackingMapState
import org.meshtastic.proto.PortNum

@KoinViewModel
class TrackingViewModel(
    private val nodeRepository: NodeRepository,
    private val meshLogRepository: MeshLogRepository,
    private val trackingPrefs: TrackingPrefs,
    private val gpxFileStore: GpxFileStore,
    mapPrefs: MapPrefs,
    buildConfigProvider: BuildConfigProvider,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val gpxEntrySerializer = ListSerializer(GpxFileEntry.serializer())
    private val gpxCache = mutableMapOf<String, GpxOverlayData?>()

    val applicationId = buildConfigProvider.applicationId

    /** All seen nodes, most recently heard first, for the tracked-node picker. */
    val allNodes: StateFlow<List<Node>> =
        nodeRepository.nodeDBbyNum
            .map { nodes -> nodes.values.sortedByDescending { it.lastHeard } }
            .stateInWhileSubscribed(initialValue = emptyList())

    val trackedNodeNums: StateFlow<Set<Int>> = trackingPrefs.trackedNodeNums

    fun toggleTracked(nodeNum: Int) {
        val current = trackingPrefs.trackedNodeNums.value
        trackingPrefs.setTrackedNodeNums(if (nodeNum in current) current - nodeNum else current + nodeNum)
    }

    val timeoutMinutes: StateFlow<Int> = trackingPrefs.reacquisitionTimeoutMinutes

    fun setTimeoutMinutes(minutes: Int) = trackingPrefs.setReacquisitionTimeoutMinutes(minutes)

    val alertsEnabled: StateFlow<Boolean> = trackingPrefs.reacquisitionAlertsEnabled

    fun setAlertsEnabled(enabled: Boolean) = trackingPrefs.setReacquisitionAlertsEnabled(enabled)

    private val ourNodeNumFlow = nodeRepository.myNodeInfo.map { it?.myNodeNum }.distinctUntilChanged()

    private val tracks: Flow<List<NodeTrack>> =
        combine(trackingPrefs.trackedNodeNums, ourNodeNumFlow) { nums, ourNum -> nums.sorted() to ourNum }
            .distinctUntilChanged()
            .flatMapLatest { (nums, ourNum) ->
                // combine() over an empty collection never emits, so guard the empty selection explicitly.
                if (nums.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(nums.map { num -> nodeTrackFlow(num, ourNum) }) { perNode -> perNode.filterNotNull() }
                }
            }

    private fun nodeTrackFlow(nodeNum: Int, ourNodeNum: Int?): Flow<NodeTrack?> {
        val logId = if (nodeNum == ourNodeNum) MeshLog.NODE_NUM_LOCAL else nodeNum
        val positionsFlow =
            meshLogRepository.getMeshPacketsFrom(logId, PortNum.POSITION_APP.value).map { packets ->
                packets
                    .mapNotNull { it.toPosition() }
                    .asFlow()
                    .distinctUntilChanged { old, new ->
                        old.time == new.time || (old.latitude_i == new.latitude_i && old.longitude_i == new.longitude_i)
                    }
                    .toList()
                    .sortedBy { it.time }
            }
        return combine(nodeRepository.nodeDBbyNum.map { it[nodeNum] }.distinctUntilChanged(), positionsFlow) {
                node,
                positions,
            ->
            node?.let {
                NodeTrack(
                    nodeNum = nodeNum,
                    shortName = it.user.short_name,
                    longName = it.user.long_name,
                    colors = it.colors,
                    positions = positions,
                )
            }
        }
    }

    val gpxFiles: StateFlow<List<GpxFileEntry>> =
        trackingPrefs.gpxFilesJson.map { decodeGpxEntries(it) }.stateInWhileSubscribed(initialValue = emptyList())

    private val _gpxImportErrors = MutableSharedFlow<Unit>()

    /** Emits when an added file cannot be imported or parsed as GPX. */
    val gpxImportErrors: SharedFlow<Unit> = _gpxImportErrors

    fun addGpxFile(uri: CommonUri, displayName: String) {
        viewModelScope.launch {
            val entry = gpxFileStore.import(uri, displayName)
            if (entry == null) {
                _gpxImportErrors.emit(Unit)
                return@launch
            }
            val parsed = gpxFileStore.readText(entry)?.let { GpxParser.parse(entry.id, entry.name, it).getOrNull() }
            if (parsed == null) {
                gpxFileStore.delete(entry)
                _gpxImportErrors.emit(Unit)
                return@launch
            }
            gpxCache[entry.id] = parsed
            saveGpxEntries(decodeGpxEntries(trackingPrefs.gpxFilesJson.value) + entry)
        }
    }

    fun removeGpxFile(entry: GpxFileEntry) {
        viewModelScope.launch {
            gpxFileStore.delete(entry)
            gpxCache.remove(entry.id)
            saveGpxEntries(decodeGpxEntries(trackingPrefs.gpxFilesJson.value).filterNot { it.id == entry.id })
        }
    }

    private val gpxOverlays: Flow<List<GpxOverlayData>> =
        gpxFiles.mapLatest { entries ->
            entries.mapNotNull { entry ->
                gpxCache.getOrPut(entry.id) {
                    gpxFileStore.readText(entry)?.let { GpxParser.parse(entry.id, entry.name, it).getOrNull() }
                }
            }
        }

    val mapState: StateFlow<TrackingMapState> =
        combine(tracks, gpxOverlays, mapPrefs.mapStyle) { nodeTracks, overlays, mapStyleId ->
            TrackingMapState(
                applicationId = applicationId,
                mapStyleId = mapStyleId,
                tracks = nodeTracks,
                gpxOverlays = overlays,
            )
        }
            .stateInWhileSubscribed(initialValue = TrackingMapState(applicationId = applicationId))

    private fun decodeGpxEntries(jsonString: String): List<GpxFileEntry> = try {
        json.decodeFromString(gpxEntrySerializer, jsonString)
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    private fun saveGpxEntries(entries: List<GpxFileEntry>) {
        trackingPrefs.setGpxFilesJson(json.encodeToString(gpxEntrySerializer, entries))
    }
}
