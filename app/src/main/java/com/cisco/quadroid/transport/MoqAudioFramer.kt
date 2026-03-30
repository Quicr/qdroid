package com.cisco.quadroid.transport

import android.media.MediaCodec
import java.nio.ByteBuffer

/**
 * Handles MoQ framing logic for audio:
 * - Group audio into 1-second intervals (approximate based on sample rate/frame size).
 * - Group ID starts as last 32 bits of current time + offset for audio.
 */
class MoqAudioFramer(
    private val transport: MoqTransport,
    private val trackName: String
) {
    private var currentGroupId: Long = (System.currentTimeMillis() and 0xFFFFFFFFL) + 1000000 // Offset to avoid collisions with video if same base
    private var currentObjectId: Long = 0
    private var framesInCurrentGroup = 0
    private val framesPerGroup = 50 // Approx 1 second for 20ms frames

    fun processFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            return
        }

        transport.sendObject(
            trackName = trackName,
            groupId = currentGroupId,
            objectId = currentObjectId,
            payload = buffer,
            priority = 1, // Audio often higher priority than video
            deliveryTimeoutMs = 1000,
            useDatagram = true // Audio can often use datagrams for lower latency
        )

        currentObjectId++
        framesInCurrentGroup++

        if (framesInCurrentGroup >= framesPerGroup) {
            currentGroupId++
            currentObjectId = 0
            framesInCurrentGroup = 0
        }
    }
}
