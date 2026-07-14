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
@file:OptIn(ExperimentalMaterial3Api::class)

package org.meshtastic.feature.map.tracking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.model.Node
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.remove
import org.meshtastic.core.resources.tracking
import org.meshtastic.core.resources.tracking_add_gpx
import org.meshtastic.core.resources.tracking_alerts
import org.meshtastic.core.resources.tracking_gpx_layers
import org.meshtastic.core.resources.tracking_gpx_parse_error
import org.meshtastic.core.resources.tracking_no_nodes
import org.meshtastic.core.resources.tracking_select_nodes
import org.meshtastic.core.ui.component.MainAppBar
import org.meshtastic.core.ui.icon.Delete
import org.meshtastic.core.ui.icon.Groups
import org.meshtastic.core.ui.icon.Layers
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Notifications
import org.meshtastic.core.ui.util.rememberOpenFileLauncher
import org.meshtastic.core.ui.util.rememberShowToastResource
import org.meshtastic.feature.map.component.MapButton
import org.meshtastic.feature.map.tracking.model.GpxFileEntry

/**
 * Map view dedicated to tracking a user-selected subset of nodes: their historical positions render as dot-trails, GPX
 * overlays can be added/removed, and reacquisition alerts are configured here.
 */
@Suppress("ViewModelForwarding") // The sheets are private pieces of this screen sharing its single ViewModel.
@Composable
fun TrackingScreen(modifier: Modifier = Modifier, viewModel: TrackingViewModel = koinViewModel()) {
    val mapState by viewModel.mapState.collectAsStateWithLifecycle()

    var showNodesSheet by remember { mutableStateOf(false) }
    var showGpxSheet by remember { mutableStateOf(false) }
    var showAlertsSheet by remember { mutableStateOf(false) }

    val showToast = rememberShowToastResource()
    LaunchedEffect(viewModel) { viewModel.gpxImportErrors.collect { showToast(Res.string.tracking_gpx_parse_error) } }

    Scaffold(
        modifier = modifier,
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.tracking),
                ourNode = null,
                showNodeChip = false,
                canNavigateUp = false,
                onNavigateUp = {},
                actions = {},
                onClickChip = {},
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            LocalTrackingMapProvider.current(mapState, Modifier.fillMaxSize())

            Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MapButton(
                    icon = MeshtasticIcons.Groups,
                    contentDescription = stringResource(Res.string.tracking_select_nodes),
                    onClick = { showNodesSheet = true },
                )
                MapButton(
                    icon = MeshtasticIcons.Layers,
                    contentDescription = stringResource(Res.string.tracking_gpx_layers),
                    onClick = { showGpxSheet = true },
                )
                MapButton(
                    icon = MeshtasticIcons.Notifications,
                    contentDescription = stringResource(Res.string.tracking_alerts),
                    onClick = { showAlertsSheet = true },
                )
            }
        }
    }

    if (showNodesSheet) {
        TrackedNodesSheet(viewModel = viewModel, onDismiss = { showNodesSheet = false })
    }
    if (showGpxSheet) {
        GpxLayersSheet(viewModel = viewModel, onDismiss = { showGpxSheet = false })
    }
    if (showAlertsSheet) {
        TrackingAlertsSheet(viewModel = viewModel, onDismiss = { showAlertsSheet = false })
    }
}

@Composable
private fun TrackedNodesSheet(viewModel: TrackingViewModel, onDismiss: () -> Unit) {
    val allNodes by viewModel.allNodes.collectAsStateWithLifecycle()
    val trackedNodeNums by viewModel.trackedNodeNums.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle(stringResource(Res.string.tracking_select_nodes))
        if (allNodes.isEmpty()) {
            Text(
                text = stringResource(Res.string.tracking_no_nodes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn {
                items(allNodes, key = { it.num }) { node ->
                    TrackedNodeRow(
                        node = node,
                        isTracked = node.num in trackedNodeNums,
                        onToggle = { viewModel.toggleTracked(node.num) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackedNodeRow(node: Node, isTracked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = isTracked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = node.user.short_name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(text = node.user.long_name, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun GpxLayersSheet(viewModel: TrackingViewModel, onDismiss: () -> Unit) {
    val gpxFiles by viewModel.gpxFiles.collectAsStateWithLifecycle()
    val openFileLauncher = rememberOpenFileLauncher { uri ->
        if (uri != null) {
            viewModel.addGpxFile(uri, uri.toString().substringAfterLast('/').ifBlank { "track.gpx" })
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle(stringResource(Res.string.tracking_gpx_layers))
        LazyColumn {
            items(gpxFiles, key = { it.id }) { entry ->
                GpxFileRow(entry = entry, onRemove = { viewModel.removeGpxFile(entry) })
            }
        }
        HorizontalDivider()
        TextButton(onClick = { openFileLauncher("*/*") }, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(Res.string.tracking_add_gpx))
        }
    }
}

@Composable
private fun GpxFileRow(entry: GpxFileEntry, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = entry.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove) {
            Icon(imageVector = MeshtasticIcons.Delete, contentDescription = stringResource(Res.string.remove))
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
