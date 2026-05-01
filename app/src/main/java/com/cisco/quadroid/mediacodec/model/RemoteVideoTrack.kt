// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.mediacodec.model

data class RemoteVideoTrack(
    val trackKey: String,
    val fullTrackName: String,
    val priority: Int, // 1=highest, 2=medium, 3=lowest
    val displayWidth: Int,
    val displayHeight: Int,
    var isReceivingObjects: Boolean = false,
    var consecutiveFramesReceived: Int = 0
)
