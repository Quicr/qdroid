// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause
//
// Host unit tests for the three-value voice-activity state machine
// (AudioActivityStateMachine) used for Top-N active-speaker ranking.
//
// AudioActivityStateMachine is pure logic with no libfvad/Android/Oboe dependency, so
// these tests build and run on the host with any C++17 compiler — no device, NDK, or
// emulator required:
//
//   c++ -std=c++17 -I nativeaudio/src/main/cpp \
//       nativeaudio/src/test/cpp/audio_activity_state_machine_test.cpp \
//       -o /tmp/aasm_test && /tmp/aasm_test
//
// (See nativeaudio/src/test/cpp/CMakeLists.txt for a CMake/CTest target.)

#include "audio_activity_state_machine.h"
#include "vad_gate.h"

#include <cstdio>

// ---- Tiny assertion harness (keeps the test dependency-free) ----------------
static int g_failures = 0;
static int g_checks = 0;

#define EXPECT_EQ(actual, expected)                                            \
    do {                                                                       \
        ++g_checks;                                                            \
        auto _a = (actual);                                                    \
        auto _e = (expected);                                                  \
        if (!(_a == _e)) {                                                     \
            ++g_failures;                                                      \
            std::printf("  FAIL %s:%d: %s == %s (got %ld, want %ld)\n",        \
                        __FILE__, __LINE__, #actual, #expected,                \
                        (long)_a, (long)_e);                                   \
        }                                                                      \
    } while (0)

using Value = AudioActivityStateMachine::Value;

// Thresholds (ms) matching the production defaults (AudioActivityStateMachine's constructor).
static constexpr int64_t T_START = 200;
static constexpr int64_t T_CONTINUOUS = 1000;
static constexpr int64_t T_DROP_START = 300;
static constexpr int64_t T_DROP_CONTINUOUS = 2000;

// Feed frames of a constant decision from startMs to endMs (exclusive) at `stepMs`,
// returning the value on the last fed frame.
static Value feed(AudioActivityStateMachine& m, bool voice,
                  int64_t startMs, int64_t endMs, int64_t stepMs) {
    Value last = m.value();
    for (int64_t t = startMs; t < endMs; t += stepMs) {
        last = m.update(voice, t);
    }
    return last;
}

// ---- Test cases -------------------------------------------------------------

static void test_starts_in_speech_end() {
    AudioActivityStateMachine m;
    EXPECT_EQ(m.rawValue(), (int)Value::SpeechEnd);
}

static void test_raw_ordering_is_2_1_0() {
    // The relay ranks numerically; a just-started speaker must out-rank an established one.
    EXPECT_EQ((int)Value::SpeechStart, 2);
    EXPECT_EQ((int)Value::ContinuousSpeech, 1);
    EXPECT_EQ((int)Value::SpeechEnd, 0);
}

static void test_enters_speech_start_after_trigger() {
    AudioActivityStateMachine m(T_START, T_CONTINUOUS, T_DROP_START, T_DROP_CONTINUOUS);
    // Voice from t=0; at t=200 (>= timeToSpeechStart) we promote to SpeechStart.
    EXPECT_EQ(m.update(true, 0), Value::SpeechEnd);    // onset armed at 0
    EXPECT_EQ(m.update(true, 100), Value::SpeechEnd);  // 100 ms < 200 ms
    EXPECT_EQ(m.update(true, 200), Value::SpeechStart); // 200 ms >= 200 ms
}

static void test_speech_start_promotes_to_continuous_from_rearmed_onset() {
    AudioActivityStateMachine m(T_START, T_CONTINUOUS, T_DROP_START, T_DROP_CONTINUOUS);
    feed(m, true, 0, 201, 20);                 // -> SpeechStart around t=200, onset re-armed
    EXPECT_EQ(m.value(), Value::SpeechStart);
    // timeToContinuous (1000 ms) counts from the re-arm (~t=200), so we reach continuous
    // around t=1200, NOT t=1000.
    EXPECT_EQ(m.update(true, 1100), Value::SpeechStart);    // 900 ms since re-arm
    EXPECT_EQ(m.update(true, 1200), Value::ContinuousSpeech); // 1000 ms since re-arm
}

static void test_no_continuous_to_speech_start_edge() {
    AudioActivityStateMachine m(T_START, T_CONTINUOUS, T_DROP_START, T_DROP_CONTINUOUS);
    feed(m, true, 0, 1800, 20); // drive all the way into ContinuousSpeech
    EXPECT_EQ(m.value(), Value::ContinuousSpeech);
    // Continuous speech stays continuous no matter how long voice continues; there is no
    // edge back up to SpeechStart without first dropping to SpeechEnd.
    feed(m, true, 1800, 5000, 20);
    EXPECT_EQ(m.value(), Value::ContinuousSpeech);
}

static void test_drop_from_continuous_uses_longer_threshold() {
    AudioActivityStateMachine m(T_START, T_CONTINUOUS, T_DROP_START, T_DROP_CONTINUOUS);
    feed(m, true, 0, 1800, 20); // ContinuousSpeech, silence onset cleared
    EXPECT_EQ(m.value(), Value::ContinuousSpeech);
    // Silence begins at t=1800. timeToDropContinuous = 2000 ms -> drop at t=3800.
    EXPECT_EQ(m.update(false, 1800), Value::ContinuousSpeech); // silence onset = 1800
    EXPECT_EQ(m.update(false, 3700), Value::ContinuousSpeech); // 1900 ms < 2000 ms
    EXPECT_EQ(m.update(false, 3800), Value::SpeechEnd);        // 2000 ms -> drop
}

static void test_drop_from_speech_start_uses_shorter_threshold() {
    AudioActivityStateMachine m(T_START, T_CONTINUOUS, T_DROP_START, T_DROP_CONTINUOUS);
    feed(m, true, 0, 201, 20); // SpeechStart at ~t=200
    EXPECT_EQ(m.value(), Value::SpeechStart);
    // Silence from t=200. A barely-started speaker drops after only timeToDropStart = 300 ms.
    EXPECT_EQ(m.update(false, 200), Value::SpeechStart); // silence onset = 200
    EXPECT_EQ(m.update(false, 400), Value::SpeechStart); // 200 ms < 300 ms
    EXPECT_EQ(m.update(false, 500), Value::SpeechEnd);   // 300 ms -> drop
}

static void test_single_silent_frame_resets_voice_onset() {
    AudioActivityStateMachine m(T_START, T_CONTINUOUS, T_DROP_START, T_DROP_CONTINUOUS);
    // Almost at the trigger, then one silent frame resets the voiced run.
    m.update(true, 0);
    m.update(true, 180); // 180 ms voiced so far (< 200)
    m.update(false, 190); // one silent frame -> voiceOnset reset
    // A fresh voiced run must accumulate the full 200 ms again from here.
    EXPECT_EQ(m.update(true, 200), Value::SpeechEnd);  // onset armed at 200
    EXPECT_EQ(m.update(true, 390), Value::SpeechEnd);  // 190 ms < 200 ms
    EXPECT_EQ(m.update(true, 400), Value::SpeechStart); // 200 ms -> promote
}

static void test_worked_timeline_from_doc() {
    // Reproduces the worked timeline: end -> start -> cont -> end -> start.
    // Voice onset is re-armed at the SpeechStart transition (~t=200), so continuous is
    // reached at ~t=1200 (200 + timeToContinuous), not t=1000.
    AudioActivityStateMachine m(T_START, T_CONTINUOUS, T_DROP_START, T_DROP_CONTINUOUS);
    // Voice from t=0; SpeechStart at t=200.
    feed(m, true, 0, 220, 20);
    EXPECT_EQ(m.value(), Value::SpeechStart);
    // Continuous at t=1200 (re-armed onset at 200 + 1000).
    feed(m, true, 220, 1720, 20);
    EXPECT_EQ(m.value(), Value::ContinuousSpeech);
    feed(m, true, 1720, 3500, 20);
    EXPECT_EQ(m.value(), Value::ContinuousSpeech);
    // Silence from t=3500; timeToDropContinuous=2000 -> SpeechEnd at t=5500.
    feed(m, false, 3500, 5520, 20);
    EXPECT_EQ(m.value(), Value::SpeechEnd);
    // Voice returns at t=5600; SpeechStart again at t=5800.
    EXPECT_EQ(m.update(true, 5600), Value::SpeechEnd);
    feed(m, true, 5620, 5820, 20);
    EXPECT_EQ(m.value(), Value::SpeechStart);
}

static void test_gated_input_reaches_continuous_despite_sparse_unvoiced() {
    // An aggressive raw VAD chops continuous speech with brief unvoiced blips. Fed raw, the
    // continuous-voice onset clock keeps resetting and the machine never reaches
    // ContinuousSpeech; fed the VadGate-smoothed decision (as production now does), it does.
    // Voiced, but one unvoiced frame every 15 frames (~300 ms) — mimics mode-3 fvad chop.
    auto rawAt = [](int f) { return (f % 15) != 14; };
    const int kFrames = 200; // 4 s at 20 ms/frame

    // Case A: raw decision straight into the state machine -> stuck at SpeechStart.
    {
        AudioActivityStateMachine m(T_START, T_CONTINUOUS, T_DROP_START, T_DROP_CONTINUOUS);
        int64_t t = 0;
        for (int f = 0; f < kFrames; ++f, t += 20) m.update(rawAt(f), t);
        EXPECT_EQ(m.value(), Value::SpeechStart);
    }

    // Case B: same raw decision smoothed by the VadGate first -> reaches ContinuousSpeech.
    {
        AudioActivityStateMachine m(T_START, T_CONTINUOUS, T_DROP_START, T_DROP_CONTINUOUS);
        VadGate gate(20, 50, 300); // frameMs, speechTriggerMs, silenceHangoverMs (production)
        int64_t t = 0;
        for (int f = 0; f < kFrames; ++f, t += 20) m.update(gate.update(rawAt(f)), t);
        EXPECT_EQ(m.value(), Value::ContinuousSpeech);
    }
}

static void test_reset_returns_to_speech_end() {
    AudioActivityStateMachine m(T_START, T_CONTINUOUS, T_DROP_START, T_DROP_CONTINUOUS);
    feed(m, true, 0, 1800, 20);
    EXPECT_EQ(m.value(), Value::ContinuousSpeech);
    m.reset();
    EXPECT_EQ(m.value(), Value::SpeechEnd);
    // After reset the trigger run is required again from scratch.
    EXPECT_EQ(m.update(true, 5000), Value::SpeechEnd);
    EXPECT_EQ(m.update(true, 5200), Value::SpeechStart);
}

// ---- Runner -----------------------------------------------------------------

int main() {
    struct { const char* name; void (*fn)(); } tests[] = {
        {"starts_in_speech_end", test_starts_in_speech_end},
        {"raw_ordering_is_2_1_0", test_raw_ordering_is_2_1_0},
        {"enters_speech_start_after_trigger", test_enters_speech_start_after_trigger},
        {"speech_start_promotes_to_continuous_from_rearmed_onset",
         test_speech_start_promotes_to_continuous_from_rearmed_onset},
        {"no_continuous_to_speech_start_edge", test_no_continuous_to_speech_start_edge},
        {"drop_from_continuous_uses_longer_threshold",
         test_drop_from_continuous_uses_longer_threshold},
        {"drop_from_speech_start_uses_shorter_threshold",
         test_drop_from_speech_start_uses_shorter_threshold},
        {"single_silent_frame_resets_voice_onset",
         test_single_silent_frame_resets_voice_onset},
        {"worked_timeline_from_doc", test_worked_timeline_from_doc},
        {"gated_input_reaches_continuous_despite_sparse_unvoiced",
         test_gated_input_reaches_continuous_despite_sparse_unvoiced},
        {"reset_returns_to_speech_end", test_reset_returns_to_speech_end},
    };

    int failed_tests = 0;
    for (auto& t : tests) {
        int before = g_failures;
        t.fn();
        bool ok = (g_failures == before);
        std::printf("[%s] %s\n", ok ? "PASS" : "FAIL", t.name);
        if (!ok) ++failed_tests;
    }

    std::printf("\n%d/%d checks passed, %d/%zu test cases failed\n",
                g_checks - g_failures, g_checks, failed_tests,
                sizeof(tests) / sizeof(tests[0]));
    return (g_failures == 0) ? 0 : 1;
}
