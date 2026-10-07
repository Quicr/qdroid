// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.nativeaudio

import java.nio.ByteBuffer

class NativeAudioLib {

    /**
     * A native method that is implemented by the 'nativeaudio' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    // Native Audio Capture
    // VAD runs natively on raw PCM before Opus encoding; silent frames are
    // dropped in-place and never reach onAudioEncoded.
    external fun startCapture(callback: NativeAudioCallback, vadEnabled: Boolean): Boolean
    external fun stopCapture()

    // Toggle native Voice Activity Detection while capture is running.
    external fun setVadEnabled(enabled: Boolean)

    // Native Audio Playback
    external fun startPlayback(trackKey: String): Boolean
    external fun stopPlayback(trackKey: String)
    external fun feedDecoder(trackKey: String, payload: ByteBuffer, size: Int)

    interface NativeAudioCallback {
        fun onAudioEncoded(payload: ByteBuffer, size: Int, presentationTimeUs: Long, flags: Int)
    }

    companion object {
        // Used to load the 'nativeaudio' library on application startup.
        init {
            System.loadLibrary("nativeaudio")
        }
    }
}
