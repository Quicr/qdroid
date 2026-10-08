// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.mediacodec.audio

import android.content.Context
import com.cisco.nativeaudio.NativeAudioLib
import com.cisco.quadroid.transport.MoqAudioFramer
import com.cisco.quadroid.transport.MoqTransport
import com.cisco.quadroid.util.PreferencesManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Unit tests for the Kotlin side of the VAD/audio-capture pipeline ([AudioManager]).
 *
 * The real speech-detection logic runs natively (see vad_gate_test.cpp); these tests
 * cover the control wiring the app owns: how the VAD enable/disable toggle and the
 * initial VAD state flow to the native engine, and how microphone mute gates the
 * already-speech-filtered frames.
 */
class AudioManagerVadTest {

    private val transport: MoqTransport = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val engine: NativeAudioEngine = mockk(relaxed = true)
    private val framer: MoqAudioFramer = mockk(relaxed = true)

    private lateinit var audioManager: AudioManager

    @Before
    fun setup() {
        mockkObject(PreferencesManager)
        every { PreferencesManager.getVadEnabled(any()) } returns true
        audioManager = AudioManager(
            moqTransport = transport,
            context = context,
            audioEngine = engine,
            audioFramerFactory = { _, _ -> framer }
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---- VAD toggle forwarding ----------------------------------------------

    @Test
    fun `setVadEnabled true forwards to native engine`() {
        audioManager.setVadEnabled(true)
        verify(exactly = 1) { engine.setVadEnabled(true) }
    }

    @Test
    fun `setVadEnabled false forwards to native engine`() {
        audioManager.setVadEnabled(false)
        verify(exactly = 1) { engine.setVadEnabled(false) }
    }

    // ---- Initial VAD state is read from preferences -------------------------

    @Test
    fun `startCapture passes VAD-enabled pref to native capture`() {
        every { PreferencesManager.getVadEnabled(any()) } returns true
        audioManager.startCapture("track/audio")
        verify { engine.startCapture(any(), true) }
    }

    @Test
    fun `startCapture passes VAD-disabled pref to native capture`() {
        every { PreferencesManager.getVadEnabled(any()) } returns false
        audioManager.startCapture("track/audio")
        verify { engine.startCapture(any(), false) }
    }

    // ---- stopCapture --------------------------------------------------------

    @Test
    fun `stopCapture forwards to native engine`() {
        audioManager.startCapture("track/audio")
        audioManager.stopCapture()
        verify(exactly = 1) { engine.stopCapture() }
    }

    // ---- Microphone gating of speech-filtered frames ------------------------

    @Test
    fun `encoded frames are forwarded to framer when mic enabled`() {
        val callback = captureStartedCallback()

        callback.onAudioEncoded(ByteBuffer.allocate(8), 8, 0L, 0)

        verify(exactly = 1) { framer.processFrame(any(), any()) }
    }

    @Test
    fun `encoded frames are dropped when mic disabled`() {
        val callback = captureStartedCallback()
        audioManager.enableMicrophone(false)

        callback.onAudioEncoded(ByteBuffer.allocate(8), 8, 0L, 0)

        verify(exactly = 0) { framer.processFrame(any(), any()) }
    }

    @Test
    fun `re-enabling mic resumes frame forwarding`() {
        val callback = captureStartedCallback()

        audioManager.enableMicrophone(false)
        callback.onAudioEncoded(ByteBuffer.allocate(8), 8, 0L, 0)

        audioManager.enableMicrophone(true)
        callback.onAudioEncoded(ByteBuffer.allocate(8), 8, 1L, 0)

        verify(exactly = 1) { framer.processFrame(any(), any()) }
    }

    /** Starts capture and returns the native callback AudioManager registered. */
    private fun captureStartedCallback(): NativeAudioLib.NativeAudioCallback {
        val slot = slot<NativeAudioLib.NativeAudioCallback>()
        every { engine.startCapture(capture(slot), any()) } returns true
        audioManager.startCapture("track/audio")
        return slot.captured
    }
}
