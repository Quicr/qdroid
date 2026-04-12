package com.cisco.quadroid.transport

import android.media.MediaCodec
import android.util.Log
import java.nio.ByteBuffer

/**
 * Handles MoQ framing logic for audio using Opus codec:
 * - 1 audio packet per MoQ group (objectId always 0).
 * - Each packet marks end of group.
 * - Group ID increments by 1 for each subsequent packet.
 * - No codec config needed for Opus - packets are self-contained.
 */
class MoqAudioFramer(
    private val transport: MoqTransport,
    private val trackName: String
) {
    private val tag = "MoqAudioFramer"
    private var currentGroupId: Long = (System.currentTimeMillis() and 0xFFFFFFFFL) + 1000000 // Offset to avoid collisions with video
    private var frameCount = 0

    fun processFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        frameCount++
        if (frameCount % 50 == 0) {
            Log.d(tag, "Processing Opus frame $frameCount, size=${info.size}, groupId=$currentGroupId")
        }

        // Each audio packet is its own group with objectId=0
        transport.sendObject(
            trackName = trackName,
            groupId = currentGroupId,
            objectId = 0, // Always 0 - one packet per group
            payload = buffer,
            priority = 1, // Audio often higher priority than video
            deliveryTimeoutMs = 1000,
            useDatagram = true // Audio can use datagrams for lower latency
        )

        // Increment group ID for next packet
        currentGroupId++
    }
}
