// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#pragma once

#include <memory>
#include <atomic>
#include <string>
#include <cstdint>

// Forward declarations
class AndroidPublishTrackHandler;
class NativeVAD;

/**
 * Native audio framer for MoQ audio packets.
 *
 * Handles MoQ framing logic for audio using Opus codec:
 * - 1 audio packet per MoQ group (objectId always 0)
 * - Each packet marks end of group
 * - Group ID increments by 1 for each subsequent packet
 * - Optional Voice Activity Detection (VAD) filtering
 * - Uses datagrams for lower latency
 *
 * Thread-safe: Uses atomic operations for lock-free performance.
 * Called directly from native Opus encoder thread.
 */
class AudioFramer {
public:
    /**
     * Create audio framer for a track.
     *
     * @param track_name Track identifier (e.g., "meeting/participant/audio")
     * @param handler Publish track handler for sending objects to libquicr
     * @param vad_enabled Enable Voice Activity Detection filtering
     */
    AudioFramer(const std::string& track_name,
                std::shared_ptr<AndroidPublishTrackHandler> handler,
                bool vad_enabled);

    ~AudioFramer();

    // Disable copy and move
    AudioFramer(const AudioFramer&) = delete;
    AudioFramer& operator=(const AudioFramer&) = delete;

    /**
     * Process an audio packet from Opus encoder.
     * Handles VAD filtering, LOC wrapping, and publishing to libquicr.
     *
     * Called directly from native audio encoder thread.
     *
     * @param data Opus packet payload
     * @param size Size of packet payload in bytes
     * @param timestamp_us Capture timestamp in microseconds
     */
    void processPacket(const uint8_t* data, size_t size, uint64_t timestamp_us);

    /**
     * Enable or disable VAD filtering at runtime.
     *
     * @param enabled True to enable VAD, false to disable
     */
    void setVadEnabled(bool enabled);

    /**
     * Check if VAD is currently enabled.
     */
    bool isVadEnabled() const { return vadEnabled_.load(); }

    /**
     * Get current framer statistics.
     */
    struct Stats {
        uint64_t packetsProcessed;
        uint64_t packetsDroppedVad;
        uint64_t currentGroupId;
    };
    Stats getStats() const;

private:
    std::string trackName_;
    std::shared_ptr<AndroidPublishTrackHandler> publishHandler_;

    // Framing state (atomic for lock-free access)
    std::atomic<uint64_t> currentGroupId_;

    // VAD state
    std::atomic<bool> vadEnabled_;
    std::unique_ptr<NativeVAD> vad_;

    // Stats (atomic for lock-free reads)
    std::atomic<uint64_t> packetsProcessed_;
    std::atomic<uint64_t> packetsDroppedVad_;
};
