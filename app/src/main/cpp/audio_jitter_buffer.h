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

// Forward declare Kairos types
namespace kairos {
    template<typename T> class GroupArbiter;
    struct ArbiterStats;
}

/**
 * Statistics for monitoring audio jitter buffer performance
 */
struct AudioJitterBufferStats {
    uint64_t packetsReceived{0};
    uint64_t packetsOutput{0};
    uint64_t packetsDropped{0};
    uint64_t groupsSkipped{0};
    int64_t avgLatencyUs{0};
    int64_t maxLatencyUs{0};
};

/**
 * Per-track audio jitter buffer with dedicated decode thread.
 *
 * This class wraps the Kairos GroupArbiter and provides:
 * - Packet ingress from network thread (addPacket)
 * - Packet egress via dedicated decode thread polling
 * - Direct feeding to native audio decoder (via JNI)
 * - Thread-safe operations
 *
 * Thread model:
 * - Network thread calls addPacket() (thread-safe)
 * - Decode thread polls every 20ms (matching 20ms Opus frame duration)
 * - Kairos GroupArbiter protected by mutex
 */
class AudioTrackJitterBuffer {
public:
    /**
     * Create jitter buffer for an audio track.
     *
     * @param trackName Track identifier (e.g., "meeting/participant/audio")
     * @param trackKey Hash key for the track (for native audio lib calls)
     * @param maxLatencyMs Maximum end-to-end latency before skipping packets (default: 100ms)
     * @param jitterDelayMs Per-packet buffer delay to absorb jitter (default: 30ms)
     */
    AudioTrackJitterBuffer(
        const std::string& trackName,
        const std::string& trackKey,
        int maxLatencyMs = 100,
        int jitterDelayMs = 30
    );

    /**
     * Destructor stops decode thread and cleans up resources.
     */
    ~AudioTrackJitterBuffer();

    // Disable copy and move (contains thread and JNI references)
    AudioTrackJitterBuffer(const AudioTrackJitterBuffer&) = delete;
    AudioTrackJitterBuffer& operator=(const AudioTrackJitterBuffer&) = delete;

    /**
     * Start the decode thread.
     * Must be called after construction before packets will be output.
     */
    void start();

    /**
     * Add a packet to the jitter buffer.
     * Thread-safe. Called from network thread (ObjectReceived).
     *
     * @param groupId MoQ group ID
     * @param objectId MoQ object ID
     * @param data Packet payload (Opus encoded audio)
     * @param dataLen Size of packet payload in bytes
     */
    void addPacket(
        uint64_t groupId,
        uint32_t objectId,
        const uint8_t* data,
        size_t dataLen
    );

    /**
     * Signal that a group is complete (no more objects will arrive).
     * Optional optimization.
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
    AudioJitterBufferStats getStats() const;

private:
    /**
     * Decode thread loop.
     * Polls Kairos buffer every 20ms and feeds ready packets to native audio decoder.
     */
    void decodeThreadLoop();

    /**
     * Feed a packet to the native audio decoder via JNI.
     *
     * @param env JNI environment
     * @param data Packet data
     */
    void feedToDecoder(JNIEnv* env, const std::vector<uint8_t>& data);

    // Track name for logging
    std::string trackName_;

    // Track key (hash) for native audio library calls
    std::string trackKey_;

    // Kairos GroupArbiter (header-only, template-based)
    std::unique_ptr<kairos::GroupArbiter<std::vector<uint8_t>>> arbiter_;

    // Mutex protects arbiter_ (Kairos is not thread-safe)
    mutable std::mutex arbiterMutex_;

    // Decode thread
    std::thread decodeThread_;
    std::atomic<bool> running_{false};

    // Cached JNI references for native audio library calls
    jclass nativeAudioLibClass_;
    jobject nativeAudioLibInstance_;
    jmethodID feedDecoderMethod_;  // Cached method ID for performance

    static constexpr int POLL_INTERVAL_MS = 20;  // 20ms matches Opus frame duration
};

/**
 * Output packet with metadata ready for decoding.
 */
struct ReadyAudioPacket {
    std::vector<uint8_t> data;
    uint64_t groupId;
    uint32_t objectId;
};

/**
 * Global singleton manager for all audio track jitter buffers.
 *
 * Provides lifecycle management for per-track buffers:
 * - Create buffer when audio track listener is registered
 * - Destroy buffer when listener is removed
 * - Route incoming packets to appropriate buffer
 */
class AudioJitterBufferManager {
public:
    /**
     * Get singleton instance.
     * Thread-safe initialization.
     */
    static AudioJitterBufferManager& getInstance();

    /**
     * Create jitter buffer for an audio track.
     *
     * @param trackName Track identifier
     * @param trackKey Track hash key
     */
    void createBuffer(const std::string& trackName, const std::string& trackKey);

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
    AudioTrackJitterBuffer* getBuffer(const std::string& trackName);

private:
    AudioJitterBufferManager() = default;
    ~AudioJitterBufferManager() = default;

    // Disable copy and move
    AudioJitterBufferManager(const AudioJitterBufferManager&) = delete;
    AudioJitterBufferManager& operator=(const AudioJitterBufferManager&) = delete;

    // Mutex protects buffers_ map
    std::mutex mutex_;

    // Map from track name to jitter buffer
    std::unordered_map<std::string, std::unique_ptr<AudioTrackJitterBuffer>> buffers_;
};
