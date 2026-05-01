// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#pragma once

#include <jni.h>
#include <string>
#include <memory>
#include <thread>
#include <atomic>
#include <mutex>
#include <unordered_map>
#include <vector>
#include <cstdint>

// Forward declare Kairos types to avoid exposing internal implementation
namespace kairos {
    template<typename T> class GroupArbiter;
    struct ArbiterStats;
}

/**
 * Statistics for monitoring jitter buffer performance
 */
struct VideoJitterBufferStats {
    uint64_t framesReceived{0};
    uint64_t framesOutput{0};
    uint64_t framesDropped{0};
    uint64_t groupsSkipped{0};
    int64_t avgLatencyUs{0};
    int64_t maxLatencyUs{0};
};

/**
 * Per-track video jitter buffer with dedicated decode thread.
 *
 * This class wraps the Kairos GroupArbiter and provides:
 * - Frame ingress from network thread (addFrame)
 * - Frame egress via dedicated decode thread polling
 * - JNI callbacks to Kotlin when frames are ready
 * - Thread-safe operations (external mutex protection for Kairos)
 *
 * Thread model:
 * - Network thread calls addFrame() (thread-safe)
 * - Decode thread polls every 16ms and calls back to Kotlin
 * - Kairos GroupArbiter protected by mutex (not thread-safe internally)
 */
class VideoTrackJitterBuffer {
public:
    /**
     * Create jitter buffer for a video track.
     *
     * @param trackName Track identifier (e.g., "meeting/participant/video")
     * @param callback_ref Global JNI reference to Kotlin callback object
     * @param maxLatencyMs Maximum end-to-end latency before skipping frames (default: 100ms Interactive profile)
     * @param jitterDelayMs Per-frame buffer delay to absorb jitter (default: 30ms)
     */
    VideoTrackJitterBuffer(
        const std::string& trackName,
        jobject callback_ref,
        int maxLatencyMs = 100,
        int jitterDelayMs = 30
    );

    /**
     * Destructor stops decode thread and cleans up resources.
     */
    ~VideoTrackJitterBuffer();

    // Disable copy and move (contains thread and JNI global ref)
    VideoTrackJitterBuffer(const VideoTrackJitterBuffer&) = delete;
    VideoTrackJitterBuffer& operator=(const VideoTrackJitterBuffer&) = delete;

    /**
     * Start the decode thread.
     * Must be called after construction before frames will be output.
     */
    void start();

    /**
     * Add a frame to the jitter buffer.
     * Thread-safe. Called from network thread (ObjectReceived).
     *
     * @param groupId MoQ group ID (GOP number)
     * @param objectId MoQ object ID (frame number within group)
     * @param data Frame payload (H.264 Annex-B format)
     * @param dataLen Size of frame payload in bytes
     * @param isKeyframe True if this is an IDR frame (objectId == 0 typically)
     */
    void addFrame(
        uint64_t groupId,
        uint32_t objectId,
        const uint8_t* data,
        size_t dataLen,
        bool isKeyframe
    );

    /**
     * Signal that a group is complete (no more objects will arrive).
     * Optional optimization - Kairos will auto-detect completion eventually.
     *
     * @param groupId MoQ group ID to mark complete
     */
    void markGroupComplete(uint64_t groupId);

    /**
     * Get current buffer statistics.
     * Thread-safe.
     *
     * @return Statistics snapshot
     */
    VideoJitterBufferStats getStats() const;

    /**
     * Get the Kotlin callback global reference.
     * Used for cleanup - caller must delete this global ref.
     *
     * @return Global JNI reference to callback object
     */
    jobject getCallbackRef() const { return kotlinCallback_; }

private:
    /**
     * Decode thread loop.
     * Polls Kairos buffer every 16ms (~60fps) and calls back to Kotlin with ready frames.
     */
    void decodeThreadLoop();

    /**
     * Call back to Kotlin with batch of ready frames.
     * Creates Java VideoFrame array and invokes onFramesReady() callback.
     *
     * @param env JNI environment
     * @param frames Vector of ready frames to deliver
     */
    void invokeKotlinCallback(JNIEnv* env, const std::vector<struct ReadyFrame>& frames);

    // Track name for logging and callback
    std::string trackName_;

    // Kairos GroupArbiter (header-only, template-based)
    std::unique_ptr<kairos::GroupArbiter<std::vector<uint8_t>>> arbiter_;

    // Mutex protects arbiter_ (Kairos is not thread-safe)
    mutable std::mutex arbiterMutex_;

    // Decode thread
    std::thread decodeThread_;
    std::atomic<bool> running_{false};

    // Kotlin callback (global JNI reference)
    jobject kotlinCallback_;

    // Cached JNI class references (global refs, must be created in JVM thread)
    jclass videoFrameClass_;

    // PTS generation state
    int64_t basePtsUs_{0};
    bool ptsInitialized_{false};

    static constexpr int64_t FRAME_DURATION_US = 33333;  // 30fps = 33.333ms
    static constexpr int POLL_INTERVAL_MS = 16;          // ~60fps decode thread
};

/**
 * Output frame with metadata ready for decoding.
 */
struct ReadyFrame {
    std::vector<uint8_t> data;
    uint64_t groupId;
    uint32_t objectId;
    bool shouldRender;  // False for decode-only frames during catch-up
    int64_t ptsUs;      // Presentation timestamp in microseconds
    bool isKeyframe;
};

/**
 * Global singleton manager for all video track jitter buffers.
 *
 * Provides lifecycle management for per-track buffers:
 * - Create buffer when video track listener is registered
 * - Destroy buffer when listener is removed
 * - Route incoming frames to appropriate buffer
 */
class VideoJitterBufferManager {
public:
    /**
     * Get singleton instance.
     * Thread-safe initialization.
     */
    static VideoJitterBufferManager& getInstance();

    /**
     * Create jitter buffer for a video track.
     *
     * @param trackName Track identifier
     * @param callback_ref Global JNI reference to Kotlin callback
     */
    void createBuffer(const std::string& trackName, jobject callback_ref);

    /**
     * Destroy jitter buffer for a track.
     * Stops decode thread and cleans up resources.
     *
     * @param trackName Track identifier
     */
    void destroyBuffer(const std::string& trackName);

    /**
     * Get jitter buffer for a track.
     *
     * @param trackName Track identifier
     * @return Pointer to buffer, or nullptr if not found
     */
    VideoTrackJitterBuffer* getBuffer(const std::string& trackName);

private:
    VideoJitterBufferManager() = default;
    ~VideoJitterBufferManager() = default;

    // Disable copy and move
    VideoJitterBufferManager(const VideoJitterBufferManager&) = delete;
    VideoJitterBufferManager& operator=(const VideoJitterBufferManager&) = delete;

    // Mutex protects buffers_ map
    std::mutex mutex_;

    // Map from track name to jitter buffer
    std::unordered_map<std::string, std::unique_ptr<VideoTrackJitterBuffer>> buffers_;
};
