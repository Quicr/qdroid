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

        /**
         * Called whenever the native 3-value voice-activity state changes (for Top-N ranking).
         *
         * Fires for both voiced and silent frames (unlike [onAudioEncoded], which only fires for
         * transmitted/voiced frames), so the drop to `speechEnd` during silence is observable.
         *
         * @param value The [com.cisco.nativeaudio] raw activity level:
         *   `0` = speechEnd, `1` = continuousSpeech, `2` = speechStart.
         *
         * Default no-op so existing callbacks that don't care about Top-N keep working unchanged.
         */
        fun onVoiceActivity(value: Int) {}
    }

    companion object {
        // Used to load the 'nativeaudio' library on application startup.
        init {
            System.loadLibrary("nativeaudio")
        }
    }
}
