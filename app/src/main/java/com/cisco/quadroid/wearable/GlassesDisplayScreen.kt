// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.wearable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.display.types.DisplayState

@Composable
fun GlassesDisplayScreen(
    displayState: DisplayState,
    onStartDisplay: () -> Unit,
    onStopDisplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Glasses Display",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = when (displayState) {
                DisplayState.STOPPED  -> "Stopped"
                DisplayState.STARTING -> "Starting…"
                DisplayState.STARTED  -> "Active"
                DisplayState.STOPPING -> "Stopping…"
                DisplayState.CLOSED   -> "Closed"
                else                  -> displayState.name
            },
            style = MaterialTheme.typography.bodySmall,
            color = when (displayState) {
                DisplayState.STARTED -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
                else                 -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onStartDisplay,
                enabled = displayState == DisplayState.STOPPED || displayState == DisplayState.CLOSED,
                modifier = Modifier.fillMaxWidth(0.5f),
            ) {
                Text("Show on Glasses")
            }
            Button(
                onClick = onStopDisplay,
                enabled = displayState == DisplayState.STARTED,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Stop")
            }
        }
    }
}
