// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.vad

/**
 * Action a video publisher should take when the shared voice-activity value changes, compared
 * against the value last committed to the wire.
 *
 * A **rising** transition (e.g. `speechEnd(0) -> speechStart(2)`) is the only keyframe-worthy one:
 * forcing an IDR means a receiver the relay just swapped into its top-N can decode immediately with
 * no fetch / key-frame-request round trip.
 *
 * The reference (Mac) design additionally rolls a *subgroup* on a falling transition to give the
 * relay a clean boundary to observe the value drop. QDroid does not need that: it stamps the
 * activity value on **every** published object (see [com.cisco.quadroid.transport.MoqMediaFramer]),
 * so the relay sees any change on the very next object, and the framer uses a single subgroup per
 * group. A falling transition therefore requires no action here.
 */
class VideoVADTransition {
    data class Action(
        /** Request an IDR on the next encode (rising transition). */
        val requestKeyframe: Boolean,
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
        return Action(requestKeyframe = value > previous, value = value)
    }

    /** Current committed value (for tests / diagnostics). */
    fun committedValue(): Int = committed

    /** Reset to the idle state (e.g. when a new encoder/track starts). */
    fun reset() {
        committed = AudioActivityValue.SPEECH_END.raw
    }
}
