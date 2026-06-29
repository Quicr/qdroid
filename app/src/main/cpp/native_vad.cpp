// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#include "native_vad.h"
#include <android/log.h>
#include <cmath>
#include <algorithm>

#define LOG_TAG "NativeVAD"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

NativeVAD::NativeVAD()
    : runningAvgEnergy_(0.0)
    , packetCount_(0)
{
    LOGI("NativeVAD initialized with energy threshold: %.1f", ENERGY_THRESHOLD);
}

bool NativeVAD::isSpeech(const uint8_t* audio_data, size_t size) {
    if (!audio_data || size < MIN_PACKET_SIZE) {
        // Invalid or too small packet - assume silence
        return false;
    }

    // Calculate energy of the packet
    double energy = calculateEnergy(audio_data, size);

    packetCount_++;

    // Update running average (exponential moving average with alpha=0.1)
    if (packetCount_ == 1) {
        runningAvgEnergy_ = energy;
    } else {
        runningAvgEnergy_ = 0.9 * runningAvgEnergy_ + 0.1 * energy;
    }

    // Decision: Speech if energy exceeds threshold
    bool hasSpeech = energy > ENERGY_THRESHOLD;

    // Log periodically for debugging
    if (packetCount_ % 100 == 0) {
        LOGI("NativeVAD: packet %llu, energy=%.2f, avgEnergy=%.2f, speech=%d",
             static_cast<unsigned long long>(packetCount_),
             energy, runningAvgEnergy_, hasSpeech);
    }

    return hasSpeech;
}

double NativeVAD::calculateEnergy(const uint8_t* audio_data, size_t size) {
    // For Opus encoded packets, we can't directly calculate RMS like PCM audio.
    // Instead, we use a heuristic based on packet characteristics:
    // 1. Larger packets typically mean more audio content
    // 2. Higher byte values indicate more audio activity
    //
    // This is a simplified approach. For better accuracy, consider:
    // - Integrating WebRTC VAD
    // - Decoding Opus to PCM and calculating RMS
    // - Using Opus packet metadata (DTX detection)

    // Heuristic: Calculate average byte magnitude
    uint64_t sum = 0;
    for (size_t i = 0; i < size; ++i) {
        sum += audio_data[i];
    }

    double avgValue = static_cast<double>(sum) / size;

    // Normalize by packet size (larger packets = more speech typically)
    // Typical Opus packet sizes:
    // - Silence/DTX: 2-20 bytes
    // - Active speech: 40-200 bytes (at 48kHz, 20ms frames)
    double sizeFactor = std::min(static_cast<double>(size) / 50.0, 2.0);

    double energy = avgValue * sizeFactor;

    return energy;
}
