package com.cisco.quadroid.mediacodec.model

import android.media.MediaCodec
import android.view.Surface
import com.cisco.quadroid.transport.MoqMediaFramer

data class EncoderState(
    val encoder: MediaCodec,
    val inputSurface: Surface,
    val config: VideoEncoderConfig,
    val framer: MoqMediaFramer,
    var isActive: Boolean = false // Track if encoder is currently receiving camera input
)
