// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.vad

/**
 * Action a video publisher should take when the shared voice-activity value changes,
 * compared against the value last committed to the wire.
 *
 * Mirrors the Mac client's `VideoVADTransition`:
 * - A **rising** transition (e.g. `speechEnd(0) -> speechStart(2)`) is the only keyframe-worthy
 *   one: forcing an IDR means a receiver the relay just swapped into its top-N can decode
 *   immediately with no fetch / key-frame-request round trip.
 * - A **falling** transition only affects ranking, so it needs no IDR; optionally the encoder
 *   rolls a subgroup so the relay sees the value change at a clean boundary (gated by
 *   [rollSubgroupEnabled]).
 */
class VideoVADTransition(
    /** Whether a falling transition should roll a subgroup boundary. */
    private val rollSubgroupEnabled: Boolean = false
) {
    data class Action(
        /** Request an IDR on the next encode (rising transition). */
        val requestKeyframe: Boolean,
        /** End the current subgroup at the next object (falling transition, if enabled). */
        val rollSubgroup: Boolean,
        /** The value to stamp on outgoing objects. */
        val value: Int
    )

    private var committed: Int = AudioActivityValue.SPEECH_END.raw

    /**
     * Compare [value] against the last committed value and return the action to take.
     * Updates the committed value to [value].
     */
    fun update(value: Int): Action {
        val previous = committed
        committed = value
        return when {
            value > previous -> Action(requestKeyframe = true, rollSubgroup = false, value = value)
            value < previous -> Action(
                requestKeyframe = false,
                rollSubgroup = rollSubgroupEnabled,
                value = value
            )
            else -> Action(requestKeyframe = false, rollSubgroup = false, value = value)
        }
    }

    /** Current committed value (for tests / diagnostics). */
    fun committedValue(): Int = committed

    /** Reset to the idle state (e.g. when a new encoder/track starts). */
    fun reset() {
        committed = AudioActivityValue.SPEECH_END.raw
    }
}
