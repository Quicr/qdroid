package com.cisco.quadroid.mediacodec.audio

import android.media.MediaCodec
import android.util.Log
import com.cisco.nativeaudio.NativeAudioLib
import com.cisco.quadroid.transport.MoqAudioFramer
import com.cisco.quadroid.transport.MoqTransport
import java.nio.ByteBuffer

class AudioManager(
    private val moqTransport: MoqTransport
) {
    private val tag = "AudioManager"
    private val nativeAudioLib = NativeAudioLib()
    private var audioFramer: MoqAudioFramer? = null
    private var isMicEnabled = true

    fun startCapture(audioTrackName: String) {
        // Create audio framer for this track
        audioFramer = MoqAudioFramer(moqTransport, audioTrackName)

        var callbackCount = 0
        nativeAudioLib.startCapture(object : NativeAudioLib.NativeAudioCallback {
            override fun onAudioEncoded(payload: ByteBuffer, size: Int, presentationTimeUs: Long, flags: Int) {
                callbackCount++
                if (callbackCount % 10 == 0) {
                    Log.d(tag, "onAudioEncoded callback #$callbackCount, size=$size, flags=$flags, micEnabled=$isMicEnabled, framerExists=${audioFramer != null}")
                }
                if (isMicEnabled) {
                    val info = MediaCodec.BufferInfo()
                    info.set(0, size, presentationTimeUs, flags)
                    audioFramer?.processFrame(payload, info)
                }
            }
        })

        Log.i(tag, "Started native audio capture for track: $audioTrackName")
    }

    fun stopCapture() {
        nativeAudioLib.stopCapture()
        audioFramer = null
        Log.i(tag, "Stopped native audio capture")
    }

    fun enableMicrophone(enabled: Boolean) {
        isMicEnabled = enabled
        Log.d(tag, "Microphone enabled: $enabled")
    }
}
