// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.vad

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Three-value voice-activity state used for Top-N active-speaker ranking.
 *
 * The raw ordinals matter: the relay ranks publishers numerically by the
 * [com.cisco.quadroid.transport.AppHeaders.AUDIO_ACTIVITY_INDICATOR] header, and the ordering
 * `speechStart (2) > continuousSpeech (1) > speechEnd (0)` is deliberate so that someone who
 * just started talking out-ranks someone who has been talking for a while.
 *
 * Mirrors the Mac client's `AudioActivityValue`.
 */
enum class AudioActivityValue(val raw: Int) {
    /** Not speaking (or just stopped). */
    SPEECH_END(0),

    /** Has been speaking for a while. */
    CONTINUOUS_SPEECH(1),

    /** Just transitioned from silence into speech. */
    SPEECH_START(2);

    companion object {
        fun fromRaw(raw: Int): AudioActivityValue =
            entries.firstOrNull { it.raw == raw } ?: SPEECH_END
    }
}

/**
 * Process-wide latch bridging the audio publisher (which owns the voice-activity state machine,
 * running natively) and the video publisher (which mirrors the latest value onto every frame).
 *
 * Analogous to the Mac client's `SharedVoiceActivityState`.
 *
 * The audio path calls [post] whenever the native state machine's value changes; the audio and
 * video publish paths call [activityValueForSend] per object to stamp the header.
 *
 * When Top-N is disabled ([setEnabled]`(false)`, the default), [activityValueForSend] returns
 * `-1`, which the transport treats as "no header" — so published objects are byte-for-byte
 * identical to the pre-Top-N behavior and there is no regression.
 *
 * Mute state also feeds the ranking (see [activityValueForSend]): a muted mic ranks the publisher
 * lowest (speechEnd) so the relay lets it fall out of the top-N.
 */
object SharedVoiceActivityState {
    private val value = AtomicInteger(AudioActivityValue.SPEECH_END.raw)

    @Volatile
    private var enabled = false

    // Microphone mute state, mirrored from the call controls. Defaults to on (mic enabled at
    // session start). A muted mic makes [activityValueForSend] return speechEnd (lowest rank).
    @Volatile
    private var micEnabled = true

    // One-shot request for the video publisher to emit an IDR. Set on a mute->unmute transition so
    // that when the relay swaps us back into the top-N (we now rank as speaking) the subscriber
    // receives a decodable keyframe instead of a mid-group P-frame. Consumed by the video framer.
    private val pendingVideoKeyframe = AtomicBoolean(false)

    /** Enables/disables header stamping. Driven by the Top-N settings toggle. */
    fun setEnabled(isEnabled: Boolean) {
        enabled = isEnabled
    }

    fun isEnabled(): Boolean = enabled

    /** Mirrors the microphone mute state (true == unmuted). Driven by the mic toggle. */
    fun setMicEnabled(isEnabled: Boolean) {
        val wasEnabled = micEnabled
        micEnabled = isEnabled
        // Mute -> unmute while Top-N is active: force a keyframe so speaking-after-unmute swaps us
        // back in with a clean IDR. Guarded on `enabled` so Top-N-off behavior is unchanged.
        if (enabled && !wasEnabled && isEnabled) {
            pendingVideoKeyframe.set(true)
        }
    }

    /** Returns and clears the one-shot keyframe request (video framer calls this per frame). */
    fun consumePendingVideoKeyframe(): Boolean = pendingVideoKeyframe.getAndSet(false)

    /** Records the latest voice-activity value (called from the native state-machine callback). */
    fun post(rawValue: Int) {
        value.set(rawValue)
    }

    fun post(activity: AudioActivityValue) = post(activity.raw)

    /** The latest voice-activity value, regardless of enabled state. */
    fun latest(): Int = value.get()

    /**
     * The value to stamp on an outgoing object. When Top-N is disabled, returns `-1` ("no header").
     * When enabled:
     * - mic unmuted → the live 3-value VAD state ([latest]);
     * - mic muted (regardless of camera) → [AudioActivityValue.SPEECH_END] (`0`), the lowest rank
     *   so the relay lets this publisher fall out of the top-N.
     */
    fun activityValueForSend(): Int {
        if (!enabled) return -1
        return if (micEnabled) value.get() else AudioActivityValue.SPEECH_END.raw
    }

    /** Resets to the idle state (e.g. between sessions). */
    fun reset() {
        value.set(AudioActivityValue.SPEECH_END.raw)
        micEnabled = true
        pendingVideoKeyframe.set(false)
    }
}
