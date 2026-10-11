// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#ifndef QUADROID_AUDIO_ACTIVITY_STATE_MACHINE_H
#define QUADROID_AUDIO_ACTIVITY_STATE_MACHINE_H

#include <cstdint>

// Three-value voice-activity state machine for Top-N active-speaker ranking.
//
// Port of the Mac client's AudioActivityStateMachine. It consumes a raw per-frame
// speech/no-speech decision (e.g. from libfvad's fvad_process) plus a wall-clock
// timestamp, and produces one of three activity levels. The raw ordinals matter: the
// relay ranks publishers numerically by this value, and the ordering
//   speechStart (2) > continuousSpeech (1) > speechEnd (0)
// is deliberate so that someone who JUST started talking out-ranks someone who has been
// talking for a while ("pick up the new voice" quickly).
//
// Unlike the gate in vad_gate.h (a moving boolean), this keeps two continuous-run onset
// timers so it can distinguish "just started" from "established" speech and apply
// asymmetric drop hysteresis. It is deliberately free of any libfvad/Android/Oboe
// dependency so it can be unit-tested on the host. All timing is in milliseconds; the
// caller supplies a monotonic `nowMs` per frame.
class AudioActivityStateMachine {
public:
    enum class Value : int {
        SpeechEnd = 0,         // not speaking (or just stopped)
        ContinuousSpeech = 1,  // has been speaking for a while
        SpeechStart = 2,       // just transitioned from silence into speech
    };

    // Defaults match the Mac client's AppSettings defaults.
    AudioActivityStateMachine(int64_t timeToSpeechStartMs = 200,
                              int64_t timeToContinuousMs = 1500,
                              int64_t timeToDropStartMs = 300,
                              int64_t timeToDropContinuousMs = 800)
        : timeToSpeechStartMs_(timeToSpeechStartMs),
          timeToContinuousMs_(timeToContinuousMs),
          timeToDropStartMs_(timeToDropStartMs),
          timeToDropContinuousMs_(timeToDropContinuousMs) {}

    // Feed one frame's raw decision (true == speech) and the current timestamp (ms).
    // Returns the (possibly updated) activity value.
    Value update(bool voiceActive, int64_t nowMs) {
        // Maintain continuous-run onset timers. A single opposite frame resets the
        // relevant onset, so one silent frame mid-speech restarts the promotion clock.
        if (voiceActive) {
            silenceOnsetMs_ = kNoOnset;
            if (voiceOnsetMs_ == kNoOnset) {
                voiceOnsetMs_ = nowMs;
            }
        } else {
            voiceOnsetMs_ = kNoOnset;
            if (silenceOnsetMs_ == kNoOnset) {
                silenceOnsetMs_ = nowMs;
            }
        }

        switch (state_) {
            case Value::SpeechEnd:
                if (voiceActive && voicedForAtLeast(nowMs, timeToSpeechStartMs_)) {
                    state_ = Value::SpeechStart;
                    // Re-arm the voice onset: timeToContinuous counts from THIS transition,
                    // not from when voice actually began.
                    voiceOnsetMs_ = nowMs;
                }
                break;

            case Value::SpeechStart:
                if (!voiceActive && silentForAtLeast(nowMs, timeToDropStartMs_)) {
                    state_ = Value::SpeechEnd;
                } else if (voiceActive && voicedForAtLeast(nowMs, timeToContinuousMs_)) {
                    state_ = Value::ContinuousSpeech;
                }
                break;

            case Value::ContinuousSpeech:
                // Note: there is no ContinuousSpeech -> SpeechStart edge. An established
                // speaker only becomes SpeechStart again by first dropping to SpeechEnd.
                if (!voiceActive && silentForAtLeast(nowMs, timeToDropContinuousMs_)) {
                    state_ = Value::SpeechEnd;
                }
                break;
        }

        return state_;
    }

    Value value() const { return state_; }
    int rawValue() const { return static_cast<int>(state_); }

    // Clear all state so a freshly re-enabled machine starts at SpeechEnd.
    void reset() {
        state_ = Value::SpeechEnd;
        voiceOnsetMs_ = kNoOnset;
        silenceOnsetMs_ = kNoOnset;
    }

private:
    static constexpr int64_t kNoOnset = -1;

    bool voicedForAtLeast(int64_t nowMs, int64_t thresholdMs) const {
        return voiceOnsetMs_ != kNoOnset && (nowMs - voiceOnsetMs_) >= thresholdMs;
    }

    bool silentForAtLeast(int64_t nowMs, int64_t thresholdMs) const {
        return silenceOnsetMs_ != kNoOnset && (nowMs - silenceOnsetMs_) >= thresholdMs;
    }

    const int64_t timeToSpeechStartMs_;
    const int64_t timeToContinuousMs_;
    const int64_t timeToDropStartMs_;
    const int64_t timeToDropContinuousMs_;

    Value state_ = Value::SpeechEnd;
    int64_t voiceOnsetMs_ = kNoOnset;
    int64_t silenceOnsetMs_ = kNoOnset;
};

#endif // QUADROID_AUDIO_ACTIVITY_STATE_MACHINE_H
