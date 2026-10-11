// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoVADTransitionTest {

    private val end = AudioActivityValue.SPEECH_END.raw
    private val cont = AudioActivityValue.CONTINUOUS_SPEECH.raw
    private val start = AudioActivityValue.SPEECH_START.raw

    @Test
    fun `rise requests keyframe and no subgroup roll`() {
        val t = VideoVADTransition()
        val action = t.update(start) // 0 -> 2
        assertTrue(action.requestKeyframe)
        assertFalse(action.rollSubgroup)
        assertEquals(start, action.value)
    }

    @Test
    fun `equal value requests neither`() {
        val t = VideoVADTransition()
        t.update(start)
        val action = t.update(start) // 2 -> 2
        assertFalse(action.requestKeyframe)
        assertFalse(action.rollSubgroup)
    }

    @Test
    fun `fall does not request keyframe and rolls subgroup only when enabled`() {
        val withoutRoll = VideoVADTransition(rollSubgroupEnabled = false)
        withoutRoll.update(start)
        val a1 = withoutRoll.update(cont) // 2 -> 1
        assertFalse(a1.requestKeyframe)
        assertFalse(a1.rollSubgroup)

        val withRoll = VideoVADTransition(rollSubgroupEnabled = true)
        withRoll.update(start)
        val a2 = withRoll.update(cont) // 2 -> 1
        assertFalse(a2.requestKeyframe)
        assertTrue(a2.rollSubgroup)
    }

    @Test
    fun `full cycle end-start-continuous-end tracks committed value`() {
        val t = VideoVADTransition()
        assertEquals(end, t.committedValue())
        assertTrue(t.update(start).requestKeyframe) // rise
        assertFalse(t.update(cont).requestKeyframe)  // fall
        assertFalse(t.update(end).requestKeyframe)   // fall
        assertEquals(end, t.committedValue())
        assertTrue(t.update(start).requestKeyframe)  // rise again
    }

    @Test
    fun `reset returns to speech end`() {
        val t = VideoVADTransition()
        t.update(start)
        t.reset()
        assertEquals(end, t.committedValue())
        // After reset a rise is detected again from the idle baseline.
        assertTrue(t.update(start).requestKeyframe)
    }
}
