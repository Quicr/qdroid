// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.mediacodec.participant

import com.cisco.quadroid.mediacodec.model.ParticipantStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the pure Top-N display logic in [RemoteParticipantManager]: activity-ranked slot
 * stabilization ([RemoteParticipantManager.arrangeSlots]) and the staleness decision
 * ([RemoteParticipantManager.freshKept]). The stateful UI path uses an Android Handler/Looper and
 * is covered by instrumentation / on-device validation.
 */
class RemoteParticipantManagerTest {

    private fun streams(vararg ids: String) = ids.map { ParticipantStream(it, 1.0f) }
    private fun ids(list: List<ParticipantStream>) = list.map { it.id }

    // ---- arrangeSlots (ranking + slot stabilization) ----

    @Test
    fun `ranks by activity descending into fresh slots`() {
        val (slots, ordered) = RemoteParticipantManager.arrangeSlots(
            candidates = streams("a", "b", "c"),
            activity = mapOf("a" to 0, "b" to 2, "c" to 1),
            lastActive = emptyMap(),
            currentSlots = emptyList(),
            cap = 3
        )
        assertEquals(listOf("b", "c", "a"), ids(ordered)) // 2 > 1 > 0
        assertEquals(listOf("b", "c", "a"), slots)
    }

    @Test
    fun `caps to N selecting highest activity`() {
        val (_, ordered) = RemoteParticipantManager.arrangeSlots(
            candidates = streams("a", "b", "c"),
            activity = mapOf("a" to 1, "b" to 2, "c" to 0),
            lastActive = emptyMap(),
            currentSlots = emptyList(),
            cap = 2
        )
        assertEquals(listOf("b", "a"), ids(ordered)) // c (0) dropped
    }

    @Test
    fun `keeps unchanged speakers in their existing slots, reusing only the vacated one`() {
        // Slots currently [a, b]. New ranking: c(2) > a(1) > b(0); cap=2 selects c,a.
        // b leaves slot1; a stays in slot0; c takes the vacated slot1.
        val (slots, ordered) = RemoteParticipantManager.arrangeSlots(
            candidates = streams("a", "b", "c"),
            activity = mapOf("a" to 1, "b" to 0, "c" to 2),
            lastActive = emptyMap(),
            currentSlots = listOf("a", "b"),
            cap = 2
        )
        assertEquals(listOf("a", "c"), slots)       // a did NOT move; c reused b's slot
        assertEquals(listOf("a", "c"), ids(ordered))
    }

    @Test
    fun `assigns a newcomer to the first vacant slot`() {
        // Slot0 empty, slot1 holds b (still selected). a is new.
        val (slots, _) = RemoteParticipantManager.arrangeSlots(
            candidates = streams("a", "b"),
            activity = mapOf("a" to 2, "b" to 2),
            lastActive = mapOf("a" to 100L, "b" to 50L),
            currentSlots = listOf(null, "b"),
            cap = 2
        )
        assertEquals(listOf("a", "b"), slots) // a fills slot0; b stays in slot1
    }

    @Test
    fun `incumbent speaker is not evicted by a higher-activity challenger (anti-flap)`() {
        // A holds the only slot and is speaking (continuousSpeech=1). B hits speechStart (2).
        // A must KEEP the slot — no flicker.
        val (slots, ordered) = RemoteParticipantManager.arrangeSlots(
            candidates = streams("a", "b"),
            activity = mapOf("a" to 1, "b" to 2),
            lastActive = emptyMap(),
            currentSlots = listOf("a"),
            cap = 1
        )
        assertEquals(listOf("a"), slots)
        assertEquals(listOf("a"), ids(ordered))
    }

    @Test
    fun `incumbent that goes silent yields to an active challenger`() {
        // A holds the slot but went silent (0); B is speaking (2) -> hand off to B.
        val (slots, _) = RemoteParticipantManager.arrangeSlots(
            candidates = streams("a", "b"),
            activity = mapOf("a" to 0, "b" to 2),
            lastActive = emptyMap(),
            currentSlots = listOf("a"),
            cap = 1
        )
        assertEquals(listOf("b"), slots)
    }

    @Test
    fun `incumbent that left the forwarded set is replaced`() {
        // A no longer a candidate (relay stopped forwarding it); B takes the slot.
        val (slots, _) = RemoteParticipantManager.arrangeSlots(
            candidates = streams("b"),
            activity = mapOf("b" to 1),
            lastActive = emptyMap(),
            currentSlots = listOf("a"),
            cap = 1
        )
        assertEquals(listOf("b"), slots)
    }

    @Test
    fun `equal activity broken by recency then id`() {
        val (_, ordered) = RemoteParticipantManager.arrangeSlots(
            candidates = streams("a", "b", "c"),
            activity = mapOf("a" to 1, "b" to 1, "c" to 1),
            lastActive = mapOf("a" to 10L, "b" to 30L, "c" to 30L), // b,c newer than a; b,c tie -> id
            currentSlots = emptyList(),
            cap = 3
        )
        assertEquals(listOf("b", "c", "a"), ids(ordered)) // recency: b/c (30) before a (10); tie b<c by id
    }

    // ---- freshKept (staleness) ----

    @Test
    fun `freshKept with null cap keeps everything`() {
        val kept = RemoteParticipantManager.freshKept(
            listOf("a", "b"), mapOf("a" to 0L), now = 10_000L, thresholdMs = 1500L, cap = null
        )
        assertEquals(listOf("a", "b"), kept)
    }

    @Test
    fun `freshKept drops stale tiles when enough fresh exist`() {
        val lastFrame = mapOf("a" to 9_500L, "b" to 9_000L, "c" to 1_000L)
        val kept = RemoteParticipantManager.freshKept(
            listOf("a", "b", "c"), lastFrame, now = 10_000L, thresholdMs = 1500L, cap = 2
        )
        assertEquals(listOf("a", "b"), kept) // c stale, a,b fresh (>=cap) -> drop c
    }

    @Test
    fun `freshKept keeps all when fewer than cap are fresh (blank-tile guard)`() {
        val lastFrame = mapOf("a" to 9_500L, "b" to 1_000L, "c" to 1_000L)
        val kept = RemoteParticipantManager.freshKept(
            listOf("a", "b", "c"), lastFrame, now = 10_000L, thresholdMs = 1500L, cap = 2
        )
        assertEquals(listOf("a", "b", "c"), kept)
    }

    @Test
    fun `freshKept treats unknown track (no frame time) as stale`() {
        val lastFrame = mapOf("a" to 9_500L, "c" to 9_000L)
        val kept = RemoteParticipantManager.freshKept(
            listOf("a", "b", "c"), lastFrame, now = 10_000L, thresholdMs = 1500L, cap = 2
        )
        assertEquals(listOf("a", "c"), kept)
    }

    @Test
    fun `freshKept keeps a tile exactly at the threshold boundary`() {
        val lastFrame = mapOf("a" to 8_500L, "b" to 9_000L)
        val kept = RemoteParticipantManager.freshKept(
            listOf("a", "b"), lastFrame, now = 10_000L, thresholdMs = 1500L, cap = 2
        )
        assertEquals(listOf("a", "b"), kept)
    }
}
