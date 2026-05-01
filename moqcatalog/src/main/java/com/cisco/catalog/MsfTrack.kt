// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MsfTrack(
    // Required fields
    val name: String,
    val packaging: String,

    // Optional fields - Root Catalog
    val namespace: String? = null,

    // Optional fields - Track properties
    val eventType: String? = null,
    val isLive: Boolean? = null,
    val targetLatency: Int? = null,
    val role: String? = null,
    val label: String? = null,
    val renderGroup: Int? = null,
    val altGroup: Int? = null,
    val initData: String? = null,
    val depends: List<String>? = null,
    val template: JsonElement? = null,

    // Optional fields - Codec/Media properties
    val temporalId: Int? = null,
    val spatialId: Int? = null,
    val codec: String? = null,
    val mimeType: String? = null,
    val framerate: Double? = null,
    val timescale: Int? = null,
    val bitrate: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val samplerate: Int? = null,
    val channelConfig: String? = null,
    val displayWidth: Int? = null,
    val displayHeight: Int? = null,
    val lang: String? = null,
    val trackDuration: Long? = null,

    // Optional fields - Encryption
    val encryptionScheme: String? = null,
    val cipherSuite: String? = null,
    val keyId: String? = null,
    val trackBaseKey: String? = null,

    // Optional fields - Delta updates
    val parentName: String? = null
)
