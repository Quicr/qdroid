// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#pragma once

#include <memory>
#include <mutex>
#include <string>
#include <cstdint>

// Forward declarations
class AndroidPublishTrackHandler;

/**
 * Native video framer for GOP (Group of Pictures) management.
 *
 * Handles MoQ framing logic for video:
 * - One group per IDR interval (GOP)
 * - Group ID starts from current timestamp
 * - Object ID 0 is the IDR frame, subsequent P-frames increment Object ID
 * - Calls EndSubgroup when transitioning to new GOP
 * - LOC wraps frame data with metadata before publishing
 *
 * Thread-safe: Can be called from MediaCodec output callback thread
 */
class VideoFramer {
public:
    /**
     * Create video framer for a track.
     *
     * @param track_name Track identifier (e.g., "meeting/participant/video")
     * @param handler Publish track handler for sending objects to libquicr
     */
    VideoFramer(const std::string& track_name,
                std::shared_ptr<AndroidPublishTrackHandler> handler);

    ~VideoFramer();

    // Disable copy and move
    VideoFramer(const VideoFramer&) = delete;
    VideoFramer& operator=(const VideoFramer&) = delete;

    /**
     * Process a video frame from MediaCodec.
     * Handles GOP tracking, LOC wrapping, and publishing to libquicr.
     *
     * @param data Frame payload (H.264 NAL units)
     * @param size Size of frame payload in bytes
     * @param is_keyframe True if this is an IDR/keyframe
     * @param timestamp_us Presentation timestamp in microseconds
     */
    void processFrame(const uint8_t* data, size_t size,
                     bool is_keyframe, uint64_t timestamp_us);

    /**
     * Get current framer statistics.
     */
    struct Stats {
        uint64_t framesProcessed;
        uint64_t gopCount;
        uint64_t currentGroupId;
        uint64_t currentObjectId;
    };
    Stats getStats() const;

private:
    std::string trackName_;
    std::shared_ptr<AndroidPublishTrackHandler> publishHandler_;

    // GOP state
    uint64_t currentGroupId_;
    uint64_t currentObjectId_;
    bool firstFrame_;

    // Thread safety
    mutable std::mutex stateMutex_;

    // Stats
    uint64_t framesProcessed_;
    uint64_t gopCount_;
};
