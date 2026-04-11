package com.cisco.quadroid.transport

/**
 * Callback interface for receiving batches of ready video frames from the jitter buffer.
 *
 * This callback is invoked from the C++ decode thread (via JNI) approximately every 16ms
 * with frames that are ready for decoding after being reordered and jitter-compensated.
 *
 * Implementation should be thread-safe as it may be called from a background thread.
 */
interface VideoJitterBufferCallback {
    /**
     * Called when frames are ready for decoding.
     *
     * @param trackName Full track name (e.g., "meeting/participant123/video")
     * @param frames Array of ready frames, ordered by (groupId, objectId)
     */
    fun onFramesReady(trackName: String, frames: Array<VideoFrame>)
}

/**
 * A video frame ready for decoding, delivered from the jitter buffer.
 *
 * @property data Frame payload in H.264 Annex-B format
 * @property keyframeByte Internal byte representation (1=keyframe, 0=not) - use isKeyframe property instead
 * @property groupId MoQ group ID (GOP number)
 * @property objectId MoQ object ID (frame number within group)
 * @property ptsUs Presentation timestamp in microseconds (monotonic, starts from 0)
 * @property shouldRender True if frame should be rendered; false for decode-only catch-up frames
 * @property isKeyframe True if this is an IDR keyframe
 */
data class VideoFrame(
    val data: ByteArray,
    private val keyframeByte: Byte,  // JNI doesn't support boolean in arrays
    val groupId: Long,
    val objectId: Long,
    val ptsUs: Long,
    val shouldRender: Boolean,
    val isKeyframe: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VideoFrame

        if (!data.contentEquals(other.data)) return false
        if (groupId != other.groupId) return false
        if (objectId != other.objectId) return false
        if (ptsUs != other.ptsUs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + groupId.hashCode()
        result = 31 * result + objectId.hashCode()
        result = 31 * result + ptsUs.hashCode()
        return result
    }
}

/**
 * Statistics from the video jitter buffer for monitoring and debugging.
 *
 * @property framesReceived Total frames added to buffer (from network)
 * @property framesOutput Total frames delivered to decoder
 * @property framesDropped Frames dropped due to late arrival or buffer overflow
 * @property groupsSkipped Groups skipped to maintain latency targets
 * @property avgLatencyUs Average end-to-end latency in microseconds
 * @property maxLatencyUs Maximum observed latency in microseconds
 */
data class VideoJitterBufferStats(
    val framesReceived: Long,
    val framesOutput: Long,
    val framesDropped: Long,
    val groupsSkipped: Long,
    val avgLatencyUs: Long,
    val maxLatencyUs: Long
) {
    /**
     * Frame drop rate as a percentage.
     */
    val dropRatePercent: Double
        get() = if (framesReceived > 0) {
            (framesDropped.toDouble() / framesReceived) * 100.0
        } else 0.0

    /**
     * Average latency in milliseconds.
     */
    val avgLatencyMs: Double
        get() = avgLatencyUs / 1000.0

    /**
     * Maximum latency in milliseconds.
     */
    val maxLatencyMs: Double
        get() = maxLatencyUs / 1000.0
}

/**
 * Statistics from the audio jitter buffer for monitoring and debugging.
 *
 * @property packetsReceived Total packets added to buffer (from network)
 * @property packetsOutput Total packets delivered to decoder
 * @property packetsDropped Packets dropped due to late arrival or buffer overflow
 * @property groupsSkipped Groups skipped to maintain latency targets
 * @property avgLatencyUs Average end-to-end latency in microseconds
 * @property maxLatencyUs Maximum observed latency in microseconds
 */
data class AudioJitterBufferStats(
    val packetsReceived: Long,
    val packetsOutput: Long,
    val packetsDropped: Long,
    val groupsSkipped: Long,
    val avgLatencyUs: Long,
    val maxLatencyUs: Long
) {
    /**
     * Packet drop rate as a percentage.
     */
    val dropRatePercent: Double
        get() = if (packetsReceived > 0) {
            (packetsDropped.toDouble() / packetsReceived) * 100.0
        } else 0.0

    /**
     * Average latency in milliseconds.
     */
    val avgLatencyMs: Double
        get() = avgLatencyUs / 1000.0

    /**
     * Maximum latency in milliseconds.
     */
    val maxLatencyMs: Double
        get() = maxLatencyUs / 1000.0
}
