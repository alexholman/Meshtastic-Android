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

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.tracking_alerts_enabled
import org.meshtastic.core.resources.tracking_timeout_minutes
import org.meshtastic.core.ui.component.EditTextPreference
import org.meshtastic.core.ui.component.SwitchListItem
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Notifications

/** Configures reacquisition alerts: the on/off switch and the global silence threshold in minutes. */
@Composable
internal fun TrackingAlertsSheet(viewModel: TrackingViewModel, onDismiss: () -> Unit) {
    val alertsEnabled by viewModel.alertsEnabled.collectAsStateWithLifecycle()
    val timeoutMinutes by viewModel.timeoutMinutes.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        SwitchListItem(
            checked = alertsEnabled,
            text = stringResource(Res.string.tracking_alerts_enabled),
            leadingIcon = MeshtasticIcons.Notifications,
            onClick = { viewModel.setAlertsEnabled(!alertsEnabled) },
        )
        EditTextPreference(
            title = stringResource(Res.string.tracking_timeout_minutes),
            value = timeoutMinutes,
            enabled = alertsEnabled,
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            onValueChanged = { if (it > 0) viewModel.setTimeoutMinutes(it) },
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}
