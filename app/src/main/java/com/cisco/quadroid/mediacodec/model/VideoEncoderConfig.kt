package com.cisco.quadroid.mediacodec.model

import com.cisco.catalog.MsfTrack

data class VideoEncoderConfig(
    val track: MsfTrack,
    val trackNameUrl: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val framerate: Int,
    val priority: Int // 1 = highest (1080p), 2 = medium (720p), 3 = lowest (360p)
)
