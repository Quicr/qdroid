// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.mediacodec.audio

import com.cisco.nativeaudio.NativeAudioLib

/**
 * Thin seam over [NativeAudioLib] so [AudioManager]'s VAD/capture wiring can be unit
 * tested on the JVM without triggering `System.loadLibrary("nativeaudio")` (which only
 * resolves on a device/emulator). Production uses [DefaultNativeAudioEngine]; tests
 * supply a fake/mock implementation.
 */
interface NativeAudioEngine {
    fun startCapture(callback: NativeAudioLib.NativeAudioCallback, vadEnabled: Boolean): Boolean
    fun stopCapture()
    fun setVadEnabled(enabled: Boolean)
}

/**
 * Production [NativeAudioEngine] backed by the real native library. The [NativeAudioLib]
 * instance is created lazily so merely constructing this object (e.g. as a default
 * constructor argument) does not force the native library to load.
 */
class DefaultNativeAudioEngine : NativeAudioEngine {
    private val lib by lazy { NativeAudioLib() }

    override fun startCapture(callback: NativeAudioLib.NativeAudioCallback, vadEnabled: Boolean): Boolean =
        lib.startCapture(callback, vadEnabled)

    override fun stopCapture() = lib.stopCapture()

    override fun setVadEnabled(enabled: Boolean) = lib.setVadEnabled(enabled)
}
