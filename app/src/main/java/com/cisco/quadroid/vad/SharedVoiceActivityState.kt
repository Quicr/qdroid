// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.vad

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
 * Mute state also feeds the ranking (see [activityValueForSend]): a muted mic can't produce a
 * meaningful VAD value, so the publisher falls back to a presence rank based on whether the
 * camera is still on.
 */
object SharedVoiceActivityState {
    private val value = AtomicInteger(AudioActivityValue.SPEECH_END.raw)

    @Volatile
    private var enabled = false

    // Mic/camera mute state, mirrored from the call controls. Both default to on, matching the
    // app's initial state (mic + camera enabled at session start).
    @Volatile
    private var micEnabled = true

    @Volatile
    private var videoEnabled = true

    /** Enables/disables header stamping. Driven by the Top-N settings toggle. */
    fun setEnabled(isEnabled: Boolean) {
        enabled = isEnabled
    }

    fun isEnabled(): Boolean = enabled

    /** Mirrors the microphone mute state (true == unmuted). Driven by the mic toggle. */
    fun setMicEnabled(isEnabled: Boolean) {
        micEnabled = isEnabled
    }

    /** Mirrors the camera mute state (true == on). Driven by the video toggle. */
    fun setVideoEnabled(isEnabled: Boolean) {
        videoEnabled = isEnabled
    }

    /** Records the latest voice-activity value (called from the native state-machine callback). */
    fun post(rawValue: Int) {
        value.set(rawValue)
    }

    fun post(activity: AudioActivityValue) = post(activity.raw)

    /** The latest voice-activity value, regardless of enabled state. */
    fun latest(): Int = value.get()

    /**
     * The value to stamp on an outgoing object. When Top-N is disabled, returns `-1` ("no header").
     * When enabled, the value depends on mute state:
     * - mic unmuted → the live 3-value VAD state ([latest]), as before;
     * - mic muted but camera on → [AudioActivityValue.CONTINUOUS_SPEECH] (`1`), a baseline presence
     *   rank so a silent-but-visible participant keeps a reasonable spot in the top-N;
     * - mic and camera both muted → [AudioActivityValue.SPEECH_END] (`0`), the lowest rank so the
     *   relay lets this publisher fall out of the top-N.
     */
    fun activityValueForSend(): Int {
        if (!enabled) return -1
        return when {
            micEnabled -> value.get()
            videoEnabled -> AudioActivityValue.CONTINUOUS_SPEECH.raw
            else -> AudioActivityValue.SPEECH_END.raw
        }
    }

    /** Resets to the idle state (e.g. between sessions). */
    fun reset() {
        value.set(AudioActivityValue.SPEECH_END.raw)
        micEnabled = true
        videoEnabled = true
    }
}
