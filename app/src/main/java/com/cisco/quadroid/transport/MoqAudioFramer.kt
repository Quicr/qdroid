package com.cisco.quadroid.transport

import android.media.MediaCodec
import android.util.Log
import java.nio.ByteBuffer

/**
 * Handles MoQ framing logic for audio:
 * - Group audio into 1-second intervals (approximate based on sample rate/frame size).
 * - Group ID starts as last 32 bits of current time + offset for audio.
 * - Sends codec config as object 0 at the start of each group for AAC decoder initialization.
 */
class MoqAudioFramer(
    private val transport: MoqTransport,
    private val trackName: String
) {
    private val tag = "MoqAudioFramer"
    private var currentGroupId: Long = (System.currentTimeMillis() and 0xFFFFFFFFL) + 1000000 // Offset to avoid collisions with video if same base
    private var currentObjectId: Long = 0
    private var framesInCurrentGroup = 0
    private val framesPerGroup = 50 // Approx 1 second for 20ms frames
    private var frameCount = 0
    private var codecConfig: ByteBuffer? = null

    fun processFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        // Store codec config for sending at the start of each group
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            Log.i(tag, "Received codec config frame, size=${info.size}")
            codecConfig = ByteBuffer.allocateDirect(info.size)
            buffer.position(0)
            codecConfig?.put(buffer)
            codecConfig?.flip()

            // Send codec config immediately as object 0 of the first group
            transport.sendObject(
                trackName = trackName,
                groupId = currentGroupId,
                objectId = 0,
                payload = codecConfig!!,
                priority = 0, // Highest priority for codec config
                deliveryTimeoutMs = 2000,
                useDatagram = false // Use reliable transport for codec config
            )
            currentObjectId = 1
            Log.i(tag, "Sent codec config as object 0 in group $currentGroupId")
            return
        }

        frameCount++
        if (frameCount % 10 == 0) {
            Log.d(tag, "Processing audio frame $frameCount, size=${info.size}, groupId=$currentGroupId, objectId=$currentObjectId")
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
            framesInCurrentGroup = 0

            // Send codec config as object 0 of the new group if available
            if (codecConfig != null) {
                codecConfig?.position(0)
                transport.sendObject(
                    trackName = trackName,
                    groupId = currentGroupId,
                    objectId = 0,
                    payload = codecConfig!!,
                    priority = 0,
                    deliveryTimeoutMs = 2000,
                    useDatagram = false
                )
                currentObjectId = 1
                Log.d(tag, "Starting new audio group $currentGroupId with codec config at object 0")
            } else {
                currentObjectId = 0
                Log.d(tag, "Starting new audio group $currentGroupId")
            }
        }
    }
}
