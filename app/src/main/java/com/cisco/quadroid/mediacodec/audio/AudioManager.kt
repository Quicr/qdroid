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
    private val context: Context,
    // Injectable seams for unit testing; production uses the real native engine and
    // the real Opus/MoQ audio framer.
    private val audioEngine: NativeAudioEngine = DefaultNativeAudioEngine(),
    private val audioFramerFactory: (MoqTransport, String) -> MoqAudioFramer =
        { transport, trackName -> MoqAudioFramer(transport, trackName) }
) {
    private val tag = "AudioManager"
    private var audioFramer: MoqAudioFramer? = null
    private var isMicEnabled = true

    fun startCapture(audioTrackName: String) {
        // Create audio framer for this track
        audioFramer = audioFramerFactory(moqTransport, audioTrackName)

        // Voice Activity Detection now runs natively on raw PCM before Opus
        // encoding (libfvad), so silent frames are dropped in the native capture
        // path and never cross JNI. We only pass the initial enabled state here.
        val vadEnabled = PreferencesManager.getVadEnabled(context)

        var callbackCount = 0
        audioEngine.startCapture(object : NativeAudioLib.NativeAudioCallback {
            override fun onAudioEncoded(payload: ByteBuffer, size: Int, presentationTimeUs: Long, flags: Int) {
                callbackCount++

                if (callbackCount % 10 == 0) {
                    Log.d(tag, "onAudioEncoded callback #$callbackCount, size=$size, flags=$flags, micEnabled=$isMicEnabled, framerExists=${audioFramer != null}")
                }

                if (isMicEnabled) {
                    // Speech-gating already happened natively; just forward the frame.
                    val info = MediaCodec.BufferInfo()
                    info.set(0, size, presentationTimeUs, flags)
                    audioFramer?.processFrame(payload, info)
                }
            }
        }, vadEnabled)

        Log.i(tag, "Started native audio capture (native VAD enabled=$vadEnabled) for track: $audioTrackName")
    }

    fun stopCapture() {
        audioEngine.stopCapture()
        audioFramer = null
        Log.i(tag, "Stopped native audio capture")
    }

    fun enableMicrophone(enabled: Boolean) {
        isMicEnabled = enabled
        Log.d(tag, "Microphone enabled: $enabled")
    }

    /**
     * Enable or disable Voice Activity Detection.
     * Forwards to the native capture pipeline, which drops silent frames before
     * Opus encoding when enabled, or transmits every frame when disabled.
     * @param enabled true to enable VAD (drop silence frames), false to send all frames
     */
    fun setVadEnabled(enabled: Boolean) {
        audioEngine.setVadEnabled(enabled)
        Log.i(tag, "VAD ${if (enabled) "enabled" else "disabled"}")
    }
}
