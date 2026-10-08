// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#ifndef QUADROID_VAD_GATE_H
#define QUADROID_VAD_GATE_H

// Pure speech/silence hysteresis state machine for Voice Activity Detection.
//
// This is the gating logic that used to live inline in NativeVad: it takes a raw
// per-frame speech/no-speech decision (e.g. from libfvad's fvad_process) and
// applies "continuous speech" hysteresis so that:
//   * the gate only OPENS after a minimum run of continuous speech
//     (speechTriggerMs), avoiding chatter from isolated speech-like frames, and
//   * the gate only CLOSES after a minimum run of continuous silence
//     (silenceHangoverMs), so short pauses mid-sentence don't clip audio.
//
// It is deliberately free of any libfvad/Android/Oboe dependency so it can be
// unit-tested on the host. All timing is expressed in whole frames of frameMs.
class VadGate {
public:
    VadGate(int frameMs, int speechTriggerMs, int silenceHangoverMs)
        : frameMs_(frameMs),
          speechTriggerMs_(speechTriggerMs),
          silenceHangoverMs_(silenceHangoverMs) {}

    // Feed one frame's raw decision (true == speech). Returns whether the gate is
    // currently open, i.e. whether this frame should be transmitted. The trailing
    // hangover frames after speech ends still return true until the gate closes.
    bool update(bool rawSpeech) {
        if (rawSpeech) {
            silenceMs_ = 0;
            speechMs_ += frameMs_;
            if (!active_ && speechMs_ >= speechTriggerMs_) {
                active_ = true;
            }
        } else {
            speechMs_ = 0;
            silenceMs_ += frameMs_;
            if (active_ && silenceMs_ >= silenceHangoverMs_) {
                active_ = false;
            }
        }
        return active_;
    }

    // Clear all hysteresis state so a freshly re-enabled gate starts closed.
    void reset() {
        active_ = false;
        speechMs_ = 0;
        silenceMs_ = 0;
    }

    bool active() const { return active_; }

private:
    const int frameMs_;
    const int speechTriggerMs_;
    const int silenceHangoverMs_;

    bool active_ = false; // latched speaking state (with hangover)
    int speechMs_ = 0;
    int silenceMs_ = 0;
};

#endif // QUADROID_VAD_GATE_H
