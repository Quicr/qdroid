// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.vad

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SharedVoiceActivityStateTest {

    @Before
    fun setup() {
        SharedVoiceActivityState.reset()
        SharedVoiceActivityState.setEnabled(false)
    }

    @After
    fun tearDown() {
        SharedVoiceActivityState.reset()
        SharedVoiceActivityState.setEnabled(false)
    }

    @Test
    fun `mic unmuted follows the live vad value`() {
        SharedVoiceActivityState.setEnabled(true)
        SharedVoiceActivityState.setMicEnabled(true)
        SharedVoiceActivityState.setVideoEnabled(true)
        SharedVoiceActivityState.post(AudioActivityValue.SPEECH_START)
        assertEquals(
            AudioActivityValue.SPEECH_START.raw,
            SharedVoiceActivityState.activityValueForSend()
        )
    }

    @Test
    fun `mic unmuted ignores mute fallback even when video is off`() {
        SharedVoiceActivityState.setEnabled(true)
        SharedVoiceActivityState.setMicEnabled(true)
        SharedVoiceActivityState.setVideoEnabled(false)
        SharedVoiceActivityState.post(AudioActivityValue.SPEECH_START)
        assertEquals(
            AudioActivityValue.SPEECH_START.raw,
            SharedVoiceActivityState.activityValueForSend()
        )
    }

    @Test
    fun `mic muted but video on sends continuous speech presence rank`() {
        SharedVoiceActivityState.setEnabled(true)
        SharedVoiceActivityState.setMicEnabled(false)
        SharedVoiceActivityState.setVideoEnabled(true)
        // A stale live value must not leak through while muted.
        SharedVoiceActivityState.post(AudioActivityValue.SPEECH_START)
        assertEquals(
            AudioActivityValue.CONTINUOUS_SPEECH.raw,
            SharedVoiceActivityState.activityValueForSend()
        )
    }

    @Test
    fun `mic and video both muted sends speech end`() {
        SharedVoiceActivityState.setEnabled(true)
        SharedVoiceActivityState.setMicEnabled(false)
        SharedVoiceActivityState.setVideoEnabled(false)
        SharedVoiceActivityState.post(AudioActivityValue.SPEECH_START)
        assertEquals(
            AudioActivityValue.SPEECH_END.raw,
            SharedVoiceActivityState.activityValueForSend()
        )
    }

    @Test
    fun `mute state is ignored when top-n disabled`() {
        SharedVoiceActivityState.setEnabled(false)
        SharedVoiceActivityState.setMicEnabled(false)
        SharedVoiceActivityState.setVideoEnabled(true)
        assertEquals(-1, SharedVoiceActivityState.activityValueForSend())
    }

    @Test
    fun `reset restores mic and video to enabled`() {
        SharedVoiceActivityState.setEnabled(true)
        SharedVoiceActivityState.setMicEnabled(false)
        SharedVoiceActivityState.setVideoEnabled(false)
        SharedVoiceActivityState.reset()
        // After reset, an unmuted mic path is assumed: value falls back to the live (idle) value.
        assertEquals(
            AudioActivityValue.SPEECH_END.raw,
            SharedVoiceActivityState.activityValueForSend()
        )
    }

    @Test
    fun `defaults to speech end and disabled`() {
        assertEquals(AudioActivityValue.SPEECH_END.raw, SharedVoiceActivityState.latest())
        assertEquals(-1, SharedVoiceActivityState.activityValueForSend())
    }

    @Test
    fun `activityValueForSend returns minus one when disabled regardless of latest`() {
        SharedVoiceActivityState.post(AudioActivityValue.SPEECH_START)
        SharedVoiceActivityState.setEnabled(false)
        assertEquals(AudioActivityValue.SPEECH_START.raw, SharedVoiceActivityState.latest())
        assertEquals(-1, SharedVoiceActivityState.activityValueForSend())
    }

    @Test
    fun `activityValueForSend returns latest when enabled`() {
        SharedVoiceActivityState.setEnabled(true)
        SharedVoiceActivityState.post(AudioActivityValue.CONTINUOUS_SPEECH)
        assertEquals(
            AudioActivityValue.CONTINUOUS_SPEECH.raw,
            SharedVoiceActivityState.activityValueForSend()
        )
    }

    @Test
    fun `post accepts raw ints`() {
        SharedVoiceActivityState.setEnabled(true)
        SharedVoiceActivityState.post(2)
        assertEquals(2, SharedVoiceActivityState.latest())
    }

    @Test
    fun `reset returns to speech end`() {
        SharedVoiceActivityState.post(AudioActivityValue.SPEECH_START)
        SharedVoiceActivityState.reset()
        assertEquals(AudioActivityValue.SPEECH_END.raw, SharedVoiceActivityState.latest())
    }

    @Test
    fun `fromRaw maps known values and falls back to speech end`() {
        assertEquals(AudioActivityValue.SPEECH_END, AudioActivityValue.fromRaw(0))
        assertEquals(AudioActivityValue.CONTINUOUS_SPEECH, AudioActivityValue.fromRaw(1))
        assertEquals(AudioActivityValue.SPEECH_START, AudioActivityValue.fromRaw(2))
        assertEquals(AudioActivityValue.SPEECH_END, AudioActivityValue.fromRaw(99))
    }
}
