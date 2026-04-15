package com.cisco.quadroid.transport

import android.media.MediaCodec
import java.nio.ByteBuffer

/**
 * Handles MoQ framing logic:
 * - One group per IDR interval.
 * - Group ID starts as last 32 bits of current time.
 * - Object ID 0 is the IDR, subsequent P-frames increment Object ID.
 */
class MoqMediaFramer(
    private val transport: MoqTransport,
    private val trackName: String
) {
    private var currentGroupId: Long = System.currentTimeMillis() and 0xFFFFFFFFL
    private var currentObjectId: Long = 0
    private var firstFrame = true

    fun processFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        // Skip codec config frames (SPS/PPS), they are usually handled out-of-band or prefixed
        /*
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            return
        }
         */

        val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

        if (isKeyFrame) {
            if (!firstFrame) {
                transport.endSubgroup(trackName, currentGroupId, 0 , true)
                currentGroupId++
            }
            currentObjectId = 0
            firstFrame = false
        }

        transport.sendObject(
            trackName = trackName,
            groupId = currentGroupId,
            objectId = currentObjectId,
            payload = buffer,
            priority = 0,
            deliveryTimeoutMs = 3000L,
            useDatagram = false // Default to stream for video frames
        )

        currentObjectId++
    }
}
