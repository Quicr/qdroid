// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.mediacodec.audio

import android.content.Context
import android.media.MediaCodec
import android.util.Log
import com.cisco.nativeaudio.NativeAudioLib
import com.cisco.quadroid.transport.MoqAudioFramer
import com.cisco.quadroid.transport.MoqTransport
import com.cisco.quadroid.util.PreferencesManager
import java.nio.ByteBuffer

class AudioManager(
    private val moqTransport: MoqTransport,
    private val context: Context
) {
    private val tag = "AudioManager"
    private val nativeAudioLib = NativeAudioLib()
    private var audioFramer: MoqAudioFramer? = null
    private var isMicEnabled = true

    // Voice Activity Detection
    private var voiceDetector: VoiceActivityDetector? = null
    private var vadEnabled: Boolean = true
    private var speechFramesDropped: Long = 0
    private var totalFramesProcessed: Long = 0

    fun startCapture(audioTrackName: String) {
        // Create audio framer for this track
        audioFramer = MoqAudioFramer(moqTransport, audioTrackName)

        // Load VAD preference from SharedPreferences
        vadEnabled = PreferencesManager.getVadEnabled(context)

        // Initialize Voice Activity Detector (48kHz, 960 samples typical for Opus 20ms frames)
        voiceDetector = VoiceActivityDetector(sampleRate = 48000, frameSize = 960)
        speechFramesDropped = 0
        totalFramesProcessed = 0

        var callbackCount = 0
        nativeAudioLib.startCapture(object : NativeAudioLib.NativeAudioCallback {
            override fun onAudioEncoded(payload: ByteBuffer, size: Int, presentationTimeUs: Long, flags: Int) {
                callbackCount++
                totalFramesProcessed++

                if (callbackCount % 10 == 0) {
                    Log.d(tag, "onAudioEncoded callback #$callbackCount, size=$size, flags=$flags, micEnabled=$isMicEnabled, framerExists=${audioFramer != null}")
                }

                if (isMicEnabled) {
                    // Apply Voice Activity Detection
                    val hasSpeech = if (vadEnabled) {
                        // Convert ByteBuffer to ByteArray for VAD processing
                        val audioBytes = ByteArray(size)
                        payload.position(0)
                        payload.get(audioBytes)
                        payload.position(0) // Reset position for later use

                        voiceDetector?.isSpeech(audioBytes) ?: true
                    } else {
                        true // VAD disabled, assume all audio has speech
                    }

                    // Skip transmission if no speech detected
                    if (!hasSpeech && vadEnabled) {
                        speechFramesDropped++

                        // Log VAD stats periodically
                        if (totalFramesProcessed % 100 == 0L) {
                            val dropRate = (speechFramesDropped * 100.0) / totalFramesProcessed
                            Log.d(tag, "VAD stats: dropped=$speechFramesDropped/$totalFramesProcessed (${String.format("%.1f", dropRate)}%)")
                        }
                        return // Skip this frame
                    }

                    // Process and send frame
                    val info = MediaCodec.BufferInfo()
                    info.set(0, size, presentationTimeUs, flags)
                    audioFramer?.processFrame(payload, info)
                }
            }
        })

        Log.i(tag, "Started native audio capture with VAD enabled for track: $audioTrackName")
    }

    fun stopCapture() {
        nativeAudioLib.stopCapture()
        audioFramer = null

        // Close VAD and log final stats
        voiceDetector?.close()
        voiceDetector = null

        if (totalFramesProcessed > 0) {
            val dropRate = (speechFramesDropped * 100.0) / totalFramesProcessed
            Log.i(tag, "VAD session stats: dropped=$speechFramesDropped/$totalFramesProcessed (${String.format("%.1f", dropRate)}%)")
        }

        speechFramesDropped = 0
        totalFramesProcessed = 0

        Log.i(tag, "Stopped native audio capture")
    }

    fun enableMicrophone(enabled: Boolean) {
        isMicEnabled = enabled
        Log.d(tag, "Microphone enabled: $enabled")
    }

    /**
     * Enable or disable Voice Activity Detection
     * @param enabled true to enable VAD (drop silence frames), false to send all frames
     */
    fun setVadEnabled(enabled: Boolean) {
        vadEnabled = enabled
        Log.i(tag, "VAD ${if (enabled) "enabled" else "disabled"}")
    }
}
