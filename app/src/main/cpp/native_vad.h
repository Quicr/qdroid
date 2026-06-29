// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#pragma once

#include <cstdint>
#include <cstddef>

/**
 * Native Voice Activity Detector using energy-based detection.
 *
 * Provides simple, fast VAD for filtering silence from audio streams.
 * Uses RMS (Root Mean Square) energy calculation with adaptive threshold.
 *
 * Note: This is a simplified VAD compared to WebRTC VAD used in Kotlin.
 * For production use, consider integrating WebRTC VAD native library.
 */
class NativeVAD {
public:
    /**
     * Create VAD with default parameters optimized for Opus audio.
     */
    NativeVAD();

    /**
     * Check if audio packet contains speech.
     *
     * @param audio_data Opus encoded audio packet (or raw PCM if needed)
     * @param size Size of audio data in bytes
     * @return true if speech detected, false if silence
     */
    bool isSpeech(const uint8_t* audio_data, size_t size);

private:
    /**
     * Calculate RMS energy of audio data.
     * For Opus packets, we use a simplified heuristic based on packet size and byte values.
     */
    double calculateEnergy(const uint8_t* audio_data, size_t size);

    // VAD parameters
    static constexpr double ENERGY_THRESHOLD = 15.0;  // Threshold for speech detection
    static constexpr size_t MIN_PACKET_SIZE = 10;     // Minimum packet size to consider

    // Adaptive threshold state (for future enhancement)
    double runningAvgEnergy_;
    uint64_t packetCount_;
};
