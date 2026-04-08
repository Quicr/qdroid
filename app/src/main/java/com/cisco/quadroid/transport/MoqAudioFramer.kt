package com.cisco.quadroid.transport

import android.media.MediaCodec
import android.util.Log
import java.nio.ByteBuffer

/**
 * Handles MoQ framing logic for audio using Opus codec:
 * - Group audio into 1-second intervals (50 frames at 20ms each).
 * - Group ID starts as last 32 bits of current time + offset for audio.
 * - No codec config needed for Opus - packets are self-contained.
 */
class MoqAudioFramer(
    private val transport: MoqTransport,
    private val trackName: String
) {
    private val tag = "MoqAudioFramer"
    private var currentGroupId: Long = (System.currentTimeMillis() and 0xFFFFFFFFL) + 1000000 // Offset to avoid collisions with video
    private var currentObjectId: Long = 0
    private var framesInCurrentGroup = 0
    private val framesPerGroup = 50 // 1 second for 20ms frames
    private var frameCount = 0

    fun processFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        frameCount++
        if (frameCount % 50 == 0) {
            Log.d(tag, "Processing Opus frame $frameCount, size=${info.size}, groupId=$currentGroupId, objectId=$currentObjectId")
        }

        transport.sendObject(
            trackName = trackName,
            groupId = currentGroupId,
            objectId = currentObjectId,
            payload = buffer,
            priority = 1, // Audio often higher priority than video
            deliveryTimeoutMs = 1000,
            useDatagram = true // Audio can use datagrams for lower latency
        )

        currentObjectId++
        framesInCurrentGroup++

        if (framesInCurrentGroup >= framesPerGroup) {
            currentGroupId++
            currentObjectId = 0
            framesInCurrentGroup = 0
            Log.d(tag, "Starting new audio group $currentGroupId")
        }
    }
}
