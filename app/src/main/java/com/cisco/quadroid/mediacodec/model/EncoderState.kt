// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.mediacodec.model

import android.media.MediaCodec
import android.view.Surface

data class EncoderState(
    val encoder: MediaCodec,
    val inputSurface: Surface,
    val config: VideoEncoderConfig,
    var isActive: Boolean = false // Track if encoder is currently receiving camera input
)
