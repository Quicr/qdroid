// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.mediacodec.participant

import com.cisco.quadroid.mediacodec.model.ParticipantStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the pure Top-N display-cap logic in [RemoteParticipantManager.clampForDisplay].
 * (The stateful UI update path uses an Android Handler/Looper and is covered by instrumentation.)
 */
class RemoteParticipantManagerTest {

    private fun streams(vararg ids: String) = ids.map { ParticipantStream(it, 1.0f) }

    @Test
    fun `null cap returns participants unchanged`() {
        val list = streams("a", "b", "c")
        assertEquals(list, RemoteParticipantManager.clampForDisplay(list, listOf("a", "b", "c"), null))
    }

    @Test
    fun `under cap returns participants unchanged`() {
        val list = streams("a", "b")
        assertEquals(list, RemoteParticipantManager.clampForDisplay(list, listOf("a", "b"), 2))
    }

    @Test
    fun `over cap keeps the most recently active and preserves input order`() {
        val list = streams("a", "b", "c")
        // Most-recent-last recency: c then a are the two most recent; b is stale.
        val kept = RemoteParticipantManager.clampForDisplay(list, listOf("b", "c", "a"), 2)
        assertEquals(streams("a", "c"), kept) // input order preserved (a before c)
    }

    @Test
    fun `cap of one keeps only the single most recent`() {
        val list = streams("a", "b", "c")
        val kept = RemoteParticipantManager.clampForDisplay(list, listOf("a", "b", "c"), 1)
        assertEquals(streams("c"), kept)
    }

    @Test
    fun `keeps input order even when recency order differs`() {
        val list = streams("x", "y", "z", "w")
        // w and x are the two most recent; result must still be in input order [x, w].
        val kept = RemoteParticipantManager.clampForDisplay(list, listOf("y", "z", "x", "w"), 2)
        assertEquals(streams("x", "w"), kept)
    }
}
