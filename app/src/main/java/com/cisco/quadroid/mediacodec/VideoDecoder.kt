// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.mediacodec

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.cisco.quadroid.util.NALUParser
import com.cisco.quadroid.util.SPSParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class VideoDecoder(private val surface: Surface, private val onResolutionChanged: (Int, Int) -> Unit) {
    private val TAG = "VideoDecoder"
    private var decoder: MediaCodec? = null
    private var configured = false
    private val pendingFrames = mutableListOf<Pair<ByteArray, Long>>()

    // Called when you receive Annex-B format
    fun configureWithAnnexB(data: ByteArray) {
        val (sps, pps) = NALUParser.parseAnnexB(data)
        if (sps == null || pps == null) {
            // Log at verbose level to avoid spamming if most frames don't have SPS/PPS
            Log.v(TAG, "configureWithAnnexB: No SPS/PPS in this frame (size=${data.size})")
            return
        }
        Log.i(TAG, "configureWithAnnexB: Found SPS/PPS! Configuring decoder...")
        configureWithSPSPPS(sps, pps)
    }

    private fun configureWithSPSPPS(sps: ByteArray, pps: ByteArray) {
        // Parse actual dimensions from SPS
        val spsInfo = SPSParser.parse(sps)
        if (spsInfo == null) {
            Log.e(TAG, "configureWithSPSPPS: Failed to parse SPS for dimensions")
            return
        }

        Log.i(TAG, "Video resolution from SPS: ${spsInfo.width}x${spsInfo.height}")
        onResolutionChanged(spsInfo.width, spsInfo.height)

        // Create CSD buffers with Annex-B start code prefix
        val startCode = byteArrayOf(0, 0, 0, 1)
        val csd0 = ByteBuffer.wrap(startCode + sps)  // SPS
        val csd1 = ByteBuffer.wrap(startCode + pps)  // PPS

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
            spsInfo.width, spsInfo.height)
        format.setByteBuffer("csd-0", csd0)
        format.setByteBuffer("csd-1", csd1)

        try {
            decoder?.stop()
            decoder?.release()
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder?.configure(format, surface, null, 0)
            decoder?.start()
            configured = true
            Log.i(TAG, "Decoder started successfully")

            // Decode any frames that arrived before config
            synchronized(pendingFrames) {
                Log.i(TAG, "Decoding ${pendingFrames.size} pending frames")
                pendingFrames.forEach { decodeFrameInternal(it.first, it.second) }
                pendingFrames.clear()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start decoder", e)
        }
    }

    fun decodeAVCCFrame(avccData: ByteArray, presentationTimeUs: Long) {
        // Not implemented for now as MoQ seems to send Annex-B
        val annexB = avccToAnnexB(avccData)
        decodeAnnexBFrame(annexB, presentationTimeUs)
    }
    
    fun decodeAnnexBFrame(annexBData: ByteArray, presentationTimeUs: Long) {
        if (!configured) {
            // Try to find SPS/PPS in this Annex-B frame
            configureWithAnnexB(annexBData)
            
            if (!configured) {
                synchronized(pendingFrames) {
                    // Prevent pending frames from growing too large (keep last 30 frames)
                    if (pendingFrames.size > 30) pendingFrames.removeAt(0)
                    pendingFrames.add(Pair(annexBData, presentationTimeUs))
                }
                return
            }
        }
        decodeFrameInternal(annexBData, presentationTimeUs)
    }

    private fun avccToAnnexB(avcc: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val startCode = byteArrayOf(0, 0, 0, 1)
        var pos = 0

        while (pos + 4 <= avcc.size) {
            val naluLen = ((avcc[pos].toInt() and 0xFF) shl 24) or
                          ((avcc[pos+1].toInt() and 0xFF) shl 16) or
                          ((avcc[pos+2].toInt() and 0xFF) shl 8) or
                          (avcc[pos+3].toInt() and 0xFF)
            pos += 4

            if (pos + naluLen > avcc.size) break

            output.write(startCode)
            output.write(avcc, pos, naluLen)
            pos += naluLen
        }
        return output.toByteArray()
    }

    private fun decodeFrameInternal(annexBData: ByteArray, pts: Long) {
        val decoder = decoder ?: return

        try {
            val inputIndex = decoder.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(annexBData)
                decoder.queueInputBuffer(inputIndex, 0, annexBData.size, pts, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex >= 0) {
                decoder.releaseOutputBuffer(outputIndex, true)
                outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during decoding", e)
        }
    }

    fun release() {
        Log.i(TAG, "Releasing decoder")
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {}
        decoder = null
        configured = false
    }
}
