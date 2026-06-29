// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.mediacodec.audio

import android.content.Context
import android.util.Log
import com.cisco.nativeaudio.NativeAudioLib
import com.cisco.quadroid.transport.MoqNative
import com.cisco.quadroid.transport.MoqTransport
import com.cisco.quadroid.util.PreferencesManager
import java.nio.ByteBuffer

class AudioManager(
    private val moqTransport: MoqTransport,
    private val context: Context
) {
    private val tag = "AudioManager"
    private val nativeAudioLib = NativeAudioLib()
    private var isMicEnabled = true
    private var vadEnabled: Boolean = true
    private var audioTrackName: String? = null

    fun startCapture(audioTrackName: String) {
        this.audioTrackName = audioTrackName

        // Load VAD preference from SharedPreferences
        vadEnabled = PreferencesManager.getVadEnabled(context)

        // Create native audio framer
        val nativeTransport = moqTransport as MoqNative
        nativeTransport.createAudioFramer(audioTrackName, vadEnabled)

        // Link native framer to audio capture
        val framerPtr = nativeTransport.linkAudioFramer(audioTrackName)
        val callbackPtr = nativeTransport.getAudioFramerCallback()

        // Start native audio capture with dummy callback (not used anymore)
        nativeAudioLib.startCapture(object : NativeAudioLib.NativeAudioCallback {
            override fun onAudioEncoded(payload: ByteBuffer, size: Int, presentationTimeUs: Long, flags: Int) {
                // This callback is no longer used - audio goes directly from native encoder to native framer
            }
        })

        // Link the native framer to the audio capture
        nativeAudioLib.nativeSetAudioFramer(framerPtr, callbackPtr)

        Log.i(tag, "Started native audio capture with native framer for track: $audioTrackName")
    }

    fun stopCapture() {
        nativeAudioLib.stopCapture()

        // Destroy native audio framer
        audioTrackName?.let { trackName ->
            val nativeTransport = moqTransport as? MoqNative
            nativeTransport?.destroyAudioFramer(trackName)
        }

        audioTrackName = null

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

        // Update VAD in native framer
        audioTrackName?.let { trackName ->
            val nativeTransport = moqTransport as? MoqNative
            nativeTransport?.setAudioVad(trackName, enabled)
        }

        Log.i(tag, "VAD ${if (enabled) "enabled" else "disabled"}")
    }
}
