// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.transport

import android.media.MediaCodec
import com.cisco.quadroid.vad.SharedVoiceActivityState
import com.cisco.quadroid.vad.VideoVADTransition
import java.nio.ByteBuffer

/**
 * Handles MoQ framing logic:
 * - One group per IDR interval.
 * - Group ID starts as last 32 bits of current time.
 * - Object ID 0 is the IDR, subsequent P-frames increment Object ID.
 *
 * For Top-N active-speaker filtering, every outgoing video object mirrors the publisher's
 * current voice-activity value (read from [SharedVoiceActivityState]) so the relay can rank it.
 * A rising transition requests an IDR via [onRequestKeyframe] so a just-swapped-in subscriber can
 * decode immediately. When Top-N is disabled, [SharedVoiceActivityState.activityValueForSend]
 * returns `-1` and no header is attached (identical to pre-Top-N behavior).
 */
class MoqMediaFramer(
    private val transport: MoqTransport,
    private val trackName: String,
    private val onRequestKeyframe: (() -> Unit)? = null
) {
    private var currentGroupId: Long = System.currentTimeMillis() and 0xFFFFFFFFL
    private var currentObjectId: Long = 0
    private var firstFrame = true
    private val vadTransition = VideoVADTransition()

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

        // Mirror the publisher's current voice-activity value onto this object so the relay
        // can rank it for Top-N. Returns -1 (no header) when Top-N is disabled.
        val activityValue = SharedVoiceActivityState.activityValueForSend()
        if (activityValue >= 0) {
            // Track the value actually stamped on the wire (which already accounts for mute state)
            // so the transition stays consistent with how the relay ranks us.
            val action = vadTransition.update(activityValue)
            // A rise (e.g. speechEnd -> speechStart) is the only keyframe-worthy transition.
            if (action.requestKeyframe) {
                onRequestKeyframe?.invoke()
            }
        }

        transport.sendObject(
            trackName = trackName,
            groupId = currentGroupId,
            objectId = currentObjectId,
            payload = buffer,
            priority = 0,
            deliveryTimeoutMs = 3000L,
            useDatagram = false, // Default to stream for video frames
            activityValue = activityValue
        )

        currentObjectId++
    }
}
