package com.cisco.quadroid.mediacodec.catalog

import android.util.Log
import com.cisco.catalog.MsfTrack
import com.cisco.quadroid.mediacodec.model.VideoEncoderConfig

object VideoEncoderConfigParser {
    private const val TAG = "VideoEncoderConfigParser"

    fun parse(
        catalogTracks: List<MsfTrack>,
        catalogTrackNamesUrl: List<String>
    ): List<VideoEncoderConfig> {
        val configs = mutableListOf<VideoEncoderConfig>()

        // Find all video tracks for the local user from catalog
        catalogTracks.forEachIndexed { index, track ->
            val trackNameUrl = catalogTrackNamesUrl.getOrNull(index)
            if (trackNameUrl != null && track.role == "video" && track.codec != null) {
                val width = track.width ?: 1280
                val height = track.height ?: 720
                val bitrate = track.bitrate ?: 2000000
                val framerate = track.framerate?.toInt() ?: 30

                // Determine priority based on height
                val priority = when {
                    height >= 1080 -> 1 // 1080p - highest priority
                    height >= 720 -> 2  // 720p - medium priority
                    else -> 3            // 360p or lower - lowest priority
                }

                val config = VideoEncoderConfig(
                    track = track,
                    trackNameUrl = trackNameUrl,
                    width = width,
                    height = height,
                    bitrate = bitrate,
                    framerate = framerate,
                    priority = priority
                )

                configs.add(config)
                Log.i(
                    TAG,
                    "Parsed video encoder config: ${track.name}, ${width}x${height}, ${bitrate}bps, ${framerate}fps, priority=$priority"
                )
            }
        }

        // Sort by priority (highest first)
        configs.sortBy { it.priority }

        Log.i(TAG, "Configured ${configs.size} video encoders")
        return configs
    }
}
