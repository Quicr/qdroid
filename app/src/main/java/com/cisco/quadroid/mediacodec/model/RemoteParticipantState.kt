// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.mediacodec.model

data class RemoteParticipantState(
    val participantId: String,
    val videoTracks: MutableList<RemoteVideoTrack> = mutableListOf(), // sorted by priority
    var activeTrackKey: String? = null, // Currently displayed track
    var audioTrackKey: String? = null
)
