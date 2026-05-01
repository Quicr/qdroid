// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#include "audio_jitter_buffer.h"
#include <kairos/kairos.hpp>
#include <android/log.h>
#include <chrono>
#include <thread>

#define TAG "AudioJitterBuffer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, TAG, __VA_ARGS__)

// Global JVM reference for JNI callbacks from decode thread
extern JavaVM* g_jvm;

//==============================================================================
// AudioTrackJitterBuffer Implementation
//==============================================================================

AudioTrackJitterBuffer::AudioTrackJitterBuffer(
    const std::string& trackName,
    const std::string& trackKey,
    int maxLatencyMs,
    int jitterDelayMs)
    : trackName_(trackName)
    , trackKey_(trackKey)
    , nativeAudioLibClass_(nullptr)
    , nativeAudioLibInstance_(nullptr)
    , feedDecoderMethod_(nullptr)
{
    using namespace kairos;
    using namespace std::chrono_literals;

    // Cache JNI references for NativeAudioLib (must be done in JVM thread)
    JNIEnv* env = nullptr;
    if (g_jvm && g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        // Find NativeAudioLib class
        jclass localClass = env->FindClass("com/cisco/nativeaudio/NativeAudioLib");
        if (localClass) {
            nativeAudioLibClass_ = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
            env->DeleteLocalRef(localClass);
            LOGI("Cached NativeAudioLib class reference");

            // Create instance of NativeAudioLib
            jmethodID constructor = env->GetMethodID(nativeAudioLibClass_, "<init>", "()V");
            if (constructor) {
                jobject localInstance = env->NewObject(nativeAudioLibClass_, constructor);
                if (localInstance) {
                    nativeAudioLibInstance_ = env->NewGlobalRef(localInstance);
                    env->DeleteLocalRef(localInstance);
                    LOGI("Created NativeAudioLib instance");

                    // CRITICAL: Call startPlayback() to initialize the decoder for this track
                    jmethodID startPlaybackMethod = env->GetMethodID(
                        nativeAudioLibClass_,
                        "startPlayback",
                        "(Ljava/lang/String;)Z"
                    );
                    if (startPlaybackMethod) {
                        jstring jtrackKey = env->NewStringUTF(trackKey_.c_str());
                        jboolean result = env->CallBooleanMethod(nativeAudioLibInstance_, startPlaybackMethod, jtrackKey);
                        env->DeleteLocalRef(jtrackKey);
                        LOGI("[%s] Called startPlayback() on NativeAudioLib for trackKey: %s, result=%d",
                             trackName_.c_str(), trackKey_.c_str(), result);
                    } else {
                        LOGE("Failed to find startPlayback method");
                    }

                    // Cache feedDecoder method ID for performance
                    feedDecoderMethod_ = env->GetMethodID(
                        nativeAudioLibClass_,
                        "feedDecoder",
                        "(Ljava/lang/String;Ljava/nio/ByteBuffer;I)V"
                    );
                    if (feedDecoderMethod_) {
                        LOGI("Cached feedDecoder method ID");
                    } else {
                        LOGE("Failed to find feedDecoder method");
                    }
                } else {
                    LOGE("Failed to create NativeAudioLib instance");
                }
            } else {
                LOGE("Failed to find NativeAudioLib constructor");
            }
        } else {
            LOGE("Failed to find NativeAudioLib class during jitter buffer creation!");
        }
    }

    // Configure Kairos Interactive profile with custom parameters
    auto config = TimingConfig::fromProfile(LatencyProfile::Interactive);
    config.maxLatency = std::chrono::milliseconds(maxLatencyMs);
    config.jitterDelay = std::chrono::milliseconds(jitterDelayMs);
    config.enableCatchUp = false;  // Audio doesn't need catch-up mode

    // Create Kairos GroupArbiter
    arbiter_ = std::make_unique<GroupArbiter<std::vector<uint8_t>>>(config);

    LOGI("Created jitter buffer for audio track '%s': maxLatency=%dms, jitterDelay=%dms",
         trackName_.c_str(), maxLatencyMs, jitterDelayMs);
}

AudioTrackJitterBuffer::~AudioTrackJitterBuffer()
{
    // Stop decode thread
    running_ = false;
    if (decodeThread_.joinable()) {
        decodeThread_.join();
    }

    // Clean up audio playback and cached JNI references
    JNIEnv* env = nullptr;
    if (g_jvm && g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        // CRITICAL: Stop playback before destroying the instance
        if (nativeAudioLibInstance_ && nativeAudioLibClass_) {
            jmethodID stopPlaybackMethod = env->GetMethodID(
                nativeAudioLibClass_,
                "stopPlayback",
                "(Ljava/lang/String;)V"
            );
            if (stopPlaybackMethod) {
                jstring jtrackKey = env->NewStringUTF(trackKey_.c_str());
                env->CallVoidMethod(nativeAudioLibInstance_, stopPlaybackMethod, jtrackKey);
                env->DeleteLocalRef(jtrackKey);
                LOGI("[%s] Called stopPlayback() for trackKey: %s", trackName_.c_str(), trackKey_.c_str());
            } else {
                LOGE("[%s] Failed to find stopPlayback method during cleanup", trackName_.c_str());
            }
        }

        if (nativeAudioLibClass_) {
            env->DeleteGlobalRef(nativeAudioLibClass_);
            nativeAudioLibClass_ = nullptr;
        }
        if (nativeAudioLibInstance_) {
            env->DeleteGlobalRef(nativeAudioLibInstance_);
            nativeAudioLibInstance_ = nullptr;
        }
    }

    LOGI("Destroyed jitter buffer for audio track '%s'", trackName_.c_str());
}

void AudioTrackJitterBuffer::start()
{
    if (running_) {
        LOGW("Decode thread already running for audio track '%s'", trackName_.c_str());
        return;
    }

    running_ = true;
    decodeThread_ = std::thread(&AudioTrackJitterBuffer::decodeThreadLoop, this);

    LOGI("Started decode thread for audio track '%s'", trackName_.c_str());
}

void AudioTrackJitterBuffer::addPacket(
    uint64_t groupId,
    uint32_t objectId,
    const uint8_t* data,
    size_t dataLen)
{
    std::lock_guard<std::mutex> lock(arbiterMutex_);

    // Copy packet data into vector
    std::vector<uint8_t> packetData(data, data + dataLen);

    // Submit to Kairos arbiter (audio packets are not keyframes)
    bool accepted = arbiter_->addFrame(
        groupId,
        objectId,
        std::move(packetData),
        false  // isKeyframe - not applicable for audio
    );

    if (!accepted) {
        // Packet was rejected (late arrival or buffer full)
        if (objectId % 50 == 0) {  // Log every 50th packet to avoid spam
            LOGW("[%s] Packet rejected: group=%llu, obj=%u, size=%zu",
                 trackName_.c_str(), groupId, objectId, dataLen);
        }
    } else {
        // Mark group complete immediately - audio uses 1 packet per group
        arbiter_->markGroupComplete(groupId);

        // Log periodically
        if (objectId % 200 == 0) {
            LOGV("[%s] Packet added and group marked complete: group=%llu, obj=%u, size=%zu",
                 trackName_.c_str(), groupId, objectId, dataLen);
        }
    }
}

void AudioTrackJitterBuffer::markGroupComplete(uint64_t groupId)
{
    std::lock_guard<std::mutex> lock(arbiterMutex_);
    arbiter_->markGroupComplete(groupId);
    LOGV("[%s] Group marked complete: %llu", trackName_.c_str(), groupId);
}

AudioJitterBufferStats AudioTrackJitterBuffer::getStats() const
{
    std::lock_guard<std::mutex> lock(arbiterMutex_);

    auto kairosStats = arbiter_->stats();

    return AudioJitterBufferStats{
        .packetsReceived = kairosStats.framesReceived,
        .packetsOutput = kairosStats.framesOutput,
        .packetsDropped = kairosStats.droppedLateFrames,
        .groupsSkipped = kairosStats.groupsSkipped,
        .avgLatencyUs = static_cast<int64_t>(kairosStats.avgOutputLatencyMs * 1000),
        .maxLatencyUs = static_cast<int64_t>(kairosStats.maxOutputLatencyMs * 1000)
    };
}

void AudioTrackJitterBuffer::decodeThreadLoop()
{
    LOGI("[%s] Audio decode thread started", trackName_.c_str());

    JNIEnv* env = nullptr;
    bool attached = false;

    // Attach thread to JVM for calls to native audio library
    int getEnvResult = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("[%s] Failed to attach audio decode thread to JVM", trackName_.c_str());
            return;
        }
        attached = true;
    }

    // Decode loop: poll for ready packets every 20ms (matching Opus frame duration)
    int pollCount = 0;
    while (running_) {
        std::vector<ReadyAudioPacket> readyPackets;

        {
            std::lock_guard<std::mutex> lock(arbiterMutex_);

            // Get up to 10 ready packets per poll
            auto packets = arbiter_->getReadyFrames(10);

            // Debug logging every 100 polls (~2 seconds)
            if (pollCount % 100 == 0) {
                auto stats = arbiter_->stats();
                LOGI("[%s] Audio poll #%d: received=%llu, output=%llu, dropped=%llu, ready=%zu",
                     trackName_.c_str(), pollCount,
                     stats.framesReceived, stats.framesOutput, stats.droppedLateFrames,
                     packets.size());
            }
            pollCount++;

            if (!packets.empty()) {
                readyPackets.reserve(packets.size());

                for (auto& packet : packets) {
                    readyPackets.push_back(ReadyAudioPacket{
                        .data = std::move(packet.data),
                        .groupId = 0,  // Not tracked
                        .objectId = packet.objectId
                    });
                }

                LOGV("[%s] Polled %zu audio packets, feeding to decoder",
                     trackName_.c_str(), readyPackets.size());
            }
        }

        // Feed packets to decoder outside the lock
        if (!readyPackets.empty() && env) {
            for (const auto& packet : readyPackets) {
                feedToDecoder(env, packet.data);
            }
        }

        // Sleep until next poll
        std::this_thread::sleep_for(std::chrono::milliseconds(POLL_INTERVAL_MS));
    }

    // Detach thread from JVM
    if (attached) {
        g_jvm->DetachCurrentThread();
    }

    LOGI("[%s] Audio decode thread stopped", trackName_.c_str());
}

void AudioTrackJitterBuffer::feedToDecoder(JNIEnv* env, const std::vector<uint8_t>& data)
{
    if (!nativeAudioLibInstance_ || !nativeAudioLibClass_ || !feedDecoderMethod_) {
        LOGE("[%s] NativeAudioLib not available for feeding decoder", trackName_.c_str());
        return;
    }

    try {
        // Create direct ByteBuffer from packet data
        jobject byteBuffer = env->NewDirectByteBuffer(
            const_cast<uint8_t*>(data.data()),
            data.size()
        );

        if (!byteBuffer) {
            LOGE("[%s] Failed to create ByteBuffer", trackName_.c_str());
            return;
        }

        // Create Java string for trackKey
        jstring jtrackKey = env->NewStringUTF(trackKey_.c_str());

        // Call the cached method
        env->CallVoidMethod(
            nativeAudioLibInstance_,
            feedDecoderMethod_,
            jtrackKey,
            byteBuffer,
            static_cast<jint>(data.size())
        );

        // Cleanup
        env->DeleteLocalRef(jtrackKey);
        env->DeleteLocalRef(byteBuffer);

        // Check for exceptions
        if (env->ExceptionCheck()) {
            LOGE("[%s] Exception in feedDecoder call", trackName_.c_str());
            env->ExceptionDescribe();
            env->ExceptionClear();
        }

    } catch (const std::exception& e) {
        LOGE("[%s] Exception in feedToDecoder: %s", trackName_.c_str(), e.what());
    } catch (...) {
        LOGE("[%s] Unknown exception in feedToDecoder", trackName_.c_str());
    }
}

//==============================================================================
// AudioJitterBufferManager Implementation
//==============================================================================

AudioJitterBufferManager& AudioJitterBufferManager::getInstance()
{
    static AudioJitterBufferManager instance;
    return instance;
}

void AudioJitterBufferManager::createBuffer(const std::string& trackName, const std::string& trackKey)
{
    std::lock_guard<std::mutex> lock(mutex_);

    // Check if buffer already exists
    if (buffers_.find(trackName) != buffers_.end()) {
        LOGW("Audio buffer already exists for track '%s'", trackName.c_str());
        return;
    }

    // Create new buffer
    auto buffer = std::make_unique<AudioTrackJitterBuffer>(trackName, trackKey);
    buffer->start();  // Start decode thread immediately

    buffers_[trackName] = std::move(buffer);

    LOGI("AudioJitterBufferManager: Created buffer for track '%s' (key: %s)",
         trackName.c_str(), trackKey.c_str());
}

void AudioJitterBufferManager::destroyBuffer(const std::string& trackName)
{
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = buffers_.find(trackName);
    if (it == buffers_.end()) {
        LOGW("No audio buffer found for track '%s'", trackName.c_str());
        return;
    }

    // Destructor will stop decode thread and clean up
    buffers_.erase(it);

    LOGI("AudioJitterBufferManager: Destroyed buffer for track '%s'", trackName.c_str());
}

AudioTrackJitterBuffer* AudioJitterBufferManager::getBuffer(const std::string& trackName)
{
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = buffers_.find(trackName);
    if (it == buffers_.end()) {
        return nullptr;
    }

    return it->second.get();
}
