// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause
//
// Host unit tests for the VAD speech/silence hysteresis (VadGate).
//
// VadGate is the pure gating logic extracted from NativeVad (nativeaudio.cpp). It
// has no libfvad/Android/Oboe dependency, so these tests build and run on the host
// with any C++17 compiler — no device, NDK, or emulator required:
//
//   c++ -std=c++17 -I nativeaudio/src/main/cpp \
//       nativeaudio/src/test/cpp/vad_gate_test.cpp -o /tmp/vad_gate_test && /tmp/vad_gate_test
//
// (See nativeaudio/src/test/cpp/CMakeLists.txt for a CMake/CTest target.)

#include "vad_gate.h"

#include <cstdio>
#include <string>

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
            std::printf("  FAIL %s:%d: %s == %s (got %ld, want %ld)\n",       \
                        __FILE__, __LINE__, #actual, #expected,               \
                        (long)_a, (long)_e);                                   \
        }                                                                      \
    } while (0)

#define EXPECT_TRUE(cond)  EXPECT_EQ((cond), true)
#define EXPECT_FALSE(cond) EXPECT_EQ((cond), false)

// Production VAD timing (mirrors the constants in nativeaudio.cpp:
// 20 ms frames, 50 ms speech trigger, 300 ms silence hangover).
static constexpr int FRAME_MS = 20;
static constexpr int TRIGGER_MS = 50;
static constexpr int HANGOVER_MS = 300;

// Feed `n` frames of the same decision; return the gate result on the LAST frame.
static bool feed(VadGate& gate, bool speech, int n) {
    bool last = false;
    for (int i = 0; i < n; ++i) last = gate.update(speech);
    return last;
}

// Drive the gate fully open (continuous speech past the trigger).
static void open(VadGate& gate) {
    feed(gate, /*speech=*/true, 10);
}

// ---- Test cases -------------------------------------------------------------

static void test_starts_closed() {
    VadGate gate(FRAME_MS, TRIGGER_MS, HANGOVER_MS);
    EXPECT_FALSE(gate.active());
    EXPECT_FALSE(gate.update(false)); // a silence frame while closed stays closed
}

static void test_opens_only_after_trigger() {
    // 50 ms trigger at 20 ms/frame => speechMs hits 20, 40, 60; opens on the 3rd frame.
    VadGate gate(FRAME_MS, TRIGGER_MS, HANGOVER_MS);
    EXPECT_FALSE(gate.update(true)); // 20 ms
    EXPECT_FALSE(gate.update(true)); // 40 ms
    EXPECT_TRUE(gate.update(true));  // 60 ms >= 50 ms -> open
    EXPECT_TRUE(gate.active());
}

static void test_subthreshold_speech_does_not_open() {
    // Two speech frames (40 ms < 50 ms), then silence must reset the speech run.
    VadGate gate(FRAME_MS, TRIGGER_MS, HANGOVER_MS);
    EXPECT_FALSE(gate.update(true)); // 20 ms
    EXPECT_FALSE(gate.update(true)); // 40 ms
    EXPECT_FALSE(gate.update(false)); // silence resets speechMs -> still closed
    // Now a single speech frame is only 20 ms again; needs the full run once more.
    EXPECT_FALSE(gate.update(true)); // 20 ms
    EXPECT_FALSE(gate.update(true)); // 40 ms
    EXPECT_TRUE(gate.update(true));  // 60 ms -> open
}

static void test_stays_open_during_speech() {
    VadGate gate(FRAME_MS, TRIGGER_MS, HANGOVER_MS);
    open(gate);
    for (int i = 0; i < 100; ++i) EXPECT_TRUE(gate.update(true));
}

static void test_hangover_keeps_gate_open_then_closes() {
    // 300 ms hangover at 20 ms/frame => 15 silence frames to close.
    VadGate gate(FRAME_MS, TRIGGER_MS, HANGOVER_MS);
    open(gate);
    // Frames 1..14 of silence (20..280 ms) still transmit (gate open during hangover).
    for (int i = 1; i <= 14; ++i) {
        EXPECT_TRUE(gate.update(false));
    }
    // Frame 15 reaches 300 ms -> gate closes, this frame is dropped.
    EXPECT_FALSE(gate.update(false));
    EXPECT_FALSE(gate.active());
    // Subsequent silence stays closed.
    EXPECT_FALSE(gate.update(false));
}

static void test_speech_resets_hangover() {
    // A short pause (< 300 ms) followed by speech must NOT close the gate, and must
    // reset the silence counter so the full hangover is required again afterwards.
    VadGate gate(FRAME_MS, TRIGGER_MS, HANGOVER_MS);
    open(gate);
    feed(gate, /*speech=*/false, 10); // 200 ms of silence (< 300 ms) -> still open
    EXPECT_TRUE(gate.active());
    EXPECT_TRUE(gate.update(true)); // speech resets silenceMs
    // Now it should again take the full 15 silence frames to close.
    for (int i = 1; i <= 14; ++i) EXPECT_TRUE(gate.update(false));
    EXPECT_FALSE(gate.update(false)); // 15th -> closes
}

static void test_reset_reclose_the_gate() {
    VadGate gate(FRAME_MS, TRIGGER_MS, HANGOVER_MS);
    open(gate);
    EXPECT_TRUE(gate.active());
    gate.reset();
    EXPECT_FALSE(gate.active());
    // After reset the trigger run is required again from scratch.
    EXPECT_FALSE(gate.update(true));
    EXPECT_FALSE(gate.update(true));
    EXPECT_TRUE(gate.update(true));
}

static void test_full_cycle_reopens() {
    VadGate gate(FRAME_MS, TRIGGER_MS, HANGOVER_MS);
    open(gate);
    feed(gate, /*speech=*/false, 15); // close via full hangover
    EXPECT_FALSE(gate.active());
    // Reopen on a fresh speech run.
    EXPECT_FALSE(gate.update(true));
    EXPECT_FALSE(gate.update(true));
    EXPECT_TRUE(gate.update(true));
    EXPECT_TRUE(gate.active());
}

static void test_custom_timing_parameters() {
    // 10 ms frames, 30 ms trigger (3 frames), 50 ms hangover (5 frames).
    VadGate gate(/*frameMs=*/10, /*trigger=*/30, /*hangover=*/50);
    EXPECT_FALSE(gate.update(true)); // 10
    EXPECT_FALSE(gate.update(true)); // 20
    EXPECT_TRUE(gate.update(true));  // 30 -> open
    for (int i = 1; i <= 4; ++i) EXPECT_TRUE(gate.update(false)); // 10..40 ms
    EXPECT_FALSE(gate.update(false)); // 50 ms -> close
}

// ---- Runner -----------------------------------------------------------------

int main() {
    struct { const char* name; void (*fn)(); } tests[] = {
        {"starts_closed", test_starts_closed},
        {"opens_only_after_trigger", test_opens_only_after_trigger},
        {"subthreshold_speech_does_not_open", test_subthreshold_speech_does_not_open},
        {"stays_open_during_speech", test_stays_open_during_speech},
        {"hangover_keeps_gate_open_then_closes", test_hangover_keeps_gate_open_then_closes},
        {"speech_resets_hangover", test_speech_resets_hangover},
        {"reset_recloses_the_gate", test_reset_reclose_the_gate},
        {"full_cycle_reopens", test_full_cycle_reopens},
        {"custom_timing_parameters", test_custom_timing_parameters},
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
