// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#include "video_jitter_buffer.h"
#include <kairos/kairos.hpp>
#include <android/log.h>
#include <chrono>
#include <thread>

#define TAG "VideoJitterBuffer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, TAG, __VA_ARGS__)

// Global JVM reference for JNI callbacks from decode thread
extern JavaVM* g_jvm;

//==============================================================================
// VideoTrackJitterBuffer Implementation
//==============================================================================

VideoTrackJitterBuffer::VideoTrackJitterBuffer(
    const std::string& trackName,
    jobject callback_ref,
    int maxLatencyMs,
    int jitterDelayMs)
    : trackName_(trackName)
    , kotlinCallback_(callback_ref)
    , videoFrameClass_(nullptr)
{
    using namespace kairos;
    using namespace std::chrono_literals;

    // Cache JNI class references (must be done in JVM thread)
    JNIEnv* env = nullptr;
    if (g_jvm && g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        jclass localClass = env->FindClass("com/cisco/quadroid/transport/VideoFrame");
        if (localClass) {
            videoFrameClass_ = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
            env->DeleteLocalRef(localClass);
            LOGI("Cached VideoFrame class reference");
        } else {
            LOGE("Failed to find VideoFrame class during jitter buffer creation!");
        }
    }

    // Configure Kairos Interactive profile with custom parameters
    // Interactive profile is optimized for low-latency video conferencing
    auto config = TimingConfig::fromProfile(LatencyProfile::Interactive);
    config.maxLatency = std::chrono::milliseconds(maxLatencyMs);
    config.jitterDelay = std::chrono::milliseconds(jitterDelayMs);
    config.enableCatchUp = true;  // Enable catch-up mode (decode-only intermediate frames)
    config.catchUpThreshold = 5;  // Trigger catch-up when 5+ frames queued

    // Create Kairos GroupArbiter
    arbiter_ = std::make_unique<GroupArbiter<std::vector<uint8_t>>>(config);

    LOGI("Created jitter buffer for track '%s': maxLatency=%dms, jitterDelay=%dms",
         trackName_.c_str(), maxLatencyMs, jitterDelayMs);
}

VideoTrackJitterBuffer::~VideoTrackJitterBuffer()
{
    // Stop decode thread
    running_ = false;
    if (decodeThread_.joinable()) {
        decodeThread_.join();
    }

    // Clean up cached JNI references
    JNIEnv* env = nullptr;
    if (g_jvm && g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (videoFrameClass_) {
            env->DeleteGlobalRef(videoFrameClass_);
            videoFrameClass_ = nullptr;
        }
    }

    LOGI("Destroyed jitter buffer for track '%s'", trackName_.c_str());

    // Note: kotlinCallback_ is a global ref that will be deleted by the caller
    // (VideoJitterBufferManager or JNI destroy method)
}

void VideoTrackJitterBuffer::start()
{
    if (running_) {
        LOGW("Decode thread already running for track '%s'", trackName_.c_str());
        return;
    }

    running_ = true;
    decodeThread_ = std::thread(&VideoTrackJitterBuffer::decodeThreadLoop, this);

    LOGI("Started decode thread for track '%s'", trackName_.c_str());
}

void VideoTrackJitterBuffer::addFrame(
    uint64_t groupId,
    uint32_t objectId,
    const uint8_t* data,
    size_t dataLen,
    bool isKeyframe)
{
    std::lock_guard<std::mutex> lock(arbiterMutex_);

    // Copy frame data into vector
    std::vector<uint8_t> frameData(data, data + dataLen);

    // Submit to Kairos arbiter
    bool accepted = arbiter_->addFrame(
        groupId,
        objectId,
        std::move(frameData),
        isKeyframe
    );

    if (!accepted) {
        // Frame was rejected (late arrival or buffer full)
        if (objectId % 30 == 0) {  // Log every 30th frame to avoid spam
            LOGW("[%s] Frame rejected: group=%llu, obj=%u, size=%zu",
                 trackName_.c_str(), groupId, objectId, dataLen);
        }
    } else {
        // Success - only log keyframes and periodically
        if (isKeyframe || objectId % 100 == 0) {
            LOGV("[%s] Frame added: group=%llu, obj=%u, size=%zu, keyframe=%d",
                 trackName_.c_str(), groupId, objectId, dataLen, isKeyframe);
        }
    }
}

void VideoTrackJitterBuffer::markGroupComplete(uint64_t groupId)
{
    std::lock_guard<std::mutex> lock(arbiterMutex_);
    arbiter_->markGroupComplete(groupId);
    LOGV("[%s] Group marked complete: %llu", trackName_.c_str(), groupId);
}

VideoJitterBufferStats VideoTrackJitterBuffer::getStats() const
{
    std::lock_guard<std::mutex> lock(arbiterMutex_);

    auto kairosStats = arbiter_->stats();

    return VideoJitterBufferStats{
        .framesReceived = kairosStats.framesReceived,
        .framesOutput = kairosStats.framesOutput,
        .framesDropped = kairosStats.droppedLateFrames,
        .groupsSkipped = kairosStats.groupsSkipped,
        .avgLatencyUs = static_cast<int64_t>(kairosStats.avgOutputLatencyMs * 1000),
        .maxLatencyUs = static_cast<int64_t>(kairosStats.maxOutputLatencyMs * 1000)
    };
}

void VideoTrackJitterBuffer::decodeThreadLoop()
{
    LOGI("[%s] Decode thread started", trackName_.c_str());

    JNIEnv* env = nullptr;
    bool attached = false;

    // Attach thread to JVM for callbacks
    int getEnvResult = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("[%s] Failed to attach decode thread to JVM", trackName_.c_str());
            return;
        }
        attached = true;
    }

    // Decode loop: poll for ready frames every 16ms (~60fps)
    int pollCount = 0;
    while (running_) {
        std::vector<ReadyFrame> readyFrames;

        {
            std::lock_guard<std::mutex> lock(arbiterMutex_);

            // Get up to 5 ready frames per poll
            auto frames = arbiter_->getReadyFrames(5);

            // Debug logging every 60 polls (~1 second)
            if (pollCount % 60 == 0) {
                auto stats = arbiter_->stats();
                LOGI("[%s] Poll #%d: received=%llu, output=%llu, dropped=%llu, ready=%zu",
                     trackName_.c_str(), pollCount,
                     stats.framesReceived, stats.framesOutput, stats.droppedLateFrames,
                     frames.size());
            }
            pollCount++;

            if (!frames.empty()) {
                readyFrames.reserve(frames.size());

                for (auto& frame : frames) {
                    // Initialize PTS on first frame
                    if (!ptsInitialized_) {
                        basePtsUs_ = 0;
                        ptsInitialized_ = true;
                        LOGI("[%s] Initialized PTS base", trackName_.c_str());
                    }

                    // Generate simple monotonic PTS using frame counter
                    // Each frame advances PTS by FRAME_DURATION_US (~33ms for 30fps)
                    int64_t ptsUs = basePtsUs_;
                    basePtsUs_ += FRAME_DURATION_US;

                    readyFrames.push_back(ReadyFrame{
                        .data = std::move(frame.data),
                        .groupId = 0,  // Not tracked (would need separate state)
                        .objectId = frame.objectId,
                        .shouldRender = frame.shouldRender,
                        .ptsUs = ptsUs,
                        .isKeyframe = frame.isKeyframe
                    });
                }

                LOGI("[%s] Polled %zu frames, delivering to Kotlin", trackName_.c_str(), readyFrames.size());
            }
        }

        // Invoke Kotlin callback outside the lock
        if (!readyFrames.empty() && env) {
            invokeKotlinCallback(env, readyFrames);
        }

        // Sleep until next poll
        std::this_thread::sleep_for(std::chrono::milliseconds(POLL_INTERVAL_MS));
    }

    // Detach thread from JVM
    if (attached) {
        g_jvm->DetachCurrentThread();
    }

    LOGI("[%s] Decode thread stopped", trackName_.c_str());
}

void VideoTrackJitterBuffer::invokeKotlinCallback(
    JNIEnv* env,
    const std::vector<ReadyFrame>& frames)
{
    if (!kotlinCallback_ || frames.empty() || !videoFrameClass_) {
        return;
    }

    try {
        // Use cached VideoFrame class reference
        // Constructor: (ByteArray, Byte, Long, Long, Long, Boolean, Boolean) -> VideoFrame
        jmethodID frameCtor = env->GetMethodID(videoFrameClass_, "<init>", "([BBJJJZZ)V");
        if (!frameCtor) {
            LOGE("[%s] Failed to find VideoFrame constructor", trackName_.c_str());
            env->ExceptionDescribe();
            env->ExceptionClear();
            return;
        }

        // Create Java VideoFrame array
        jobjectArray frameArray = env->NewObjectArray(frames.size(), videoFrameClass_, nullptr);
        if (!frameArray) {
            LOGE("[%s] Failed to create frame array", trackName_.c_str());
            return;
        }

        // Populate array
        for (size_t i = 0; i < frames.size(); ++i) {
            const auto& frame = frames[i];

            // Create ByteArray for frame data
            jbyteArray jdata = env->NewByteArray(frame.data.size());
            if (!jdata) {
                LOGE("[%s] Failed to create byte array for frame %zu", trackName_.c_str(), i);
                continue;
            }
            env->SetByteArrayRegion(jdata, 0, frame.data.size(),
                                   reinterpret_cast<const jbyte*>(frame.data.data()));

            // Create VideoFrame object
            jobject frameObj = env->NewObject(videoFrameClass_, frameCtor,
                jdata,                                          // data: ByteArray
                static_cast<jbyte>(frame.isKeyframe ? 1 : 0),  // keyframeByte: Byte
                static_cast<jlong>(frame.groupId),             // groupId: Long
                static_cast<jlong>(frame.objectId),            // objectId: Long
                static_cast<jlong>(frame.ptsUs),               // ptsUs: Long
                static_cast<jboolean>(frame.shouldRender),     // shouldRender: Boolean
                static_cast<jboolean>(frame.isKeyframe)        // isKeyframe: Boolean
            );

            if (!frameObj) {
                LOGE("[%s] Failed to create VideoFrame object %zu", trackName_.c_str(), i);
                env->DeleteLocalRef(jdata);
                continue;
            }

            env->SetObjectArrayElement(frameArray, i, frameObj);
            env->DeleteLocalRef(frameObj);
            env->DeleteLocalRef(jdata);
        }

        // Invoke callback: onFramesReady(trackName: String, frames: Array<VideoFrame>)
        jclass callbackClass = env->GetObjectClass(kotlinCallback_);
        if (!callbackClass) {
            LOGE("[%s] Failed to get callback class", trackName_.c_str());
            env->DeleteLocalRef(frameArray);
            return;
        }

        jmethodID onFramesReady = env->GetMethodID(callbackClass, "onFramesReady",
            "(Ljava/lang/String;[Lcom/cisco/quadroid/transport/VideoFrame;)V");
        if (!onFramesReady) {
            LOGE("[%s] Failed to find onFramesReady method", trackName_.c_str());
            env->DeleteLocalRef(callbackClass);
            env->DeleteLocalRef(frameArray);
            return;
        }

        jstring jtrackName = env->NewStringUTF(trackName_.c_str());
        env->CallVoidMethod(kotlinCallback_, onFramesReady, jtrackName, frameArray);
        env->DeleteLocalRef(jtrackName);

        // Check for exceptions
        if (env->ExceptionCheck()) {
            LOGE("[%s] Exception in onFramesReady callback", trackName_.c_str());
            env->ExceptionDescribe();
            env->ExceptionClear();
        }

        // Cleanup (videoFrameClass_ is a global ref, don't delete it here)
        env->DeleteLocalRef(callbackClass);
        env->DeleteLocalRef(frameArray);

        LOGV("[%s] Delivered %zu frames to Kotlin", trackName_.c_str(), frames.size());

    } catch (const std::exception& e) {
        LOGE("[%s] Exception in invokeKotlinCallback: %s", trackName_.c_str(), e.what());
    } catch (...) {
        LOGE("[%s] Unknown exception in invokeKotlinCallback", trackName_.c_str());
    }
}

//==============================================================================
// VideoJitterBufferManager Implementation
//==============================================================================

VideoJitterBufferManager& VideoJitterBufferManager::getInstance()
{
    static VideoJitterBufferManager instance;
    return instance;
}

void VideoJitterBufferManager::createBuffer(const std::string& trackName, jobject callback_ref)
{
    std::lock_guard<std::mutex> lock(mutex_);

    // Check if buffer already exists
    if (buffers_.find(trackName) != buffers_.end()) {
        LOGW("Buffer already exists for track '%s'", trackName.c_str());
        return;
    }

    // Create new buffer (callback_ref must be a global ref from JNI caller)
    auto buffer = std::make_unique<VideoTrackJitterBuffer>(trackName, callback_ref);
    buffer->start();  // Start decode thread immediately

    buffers_[trackName] = std::move(buffer);

    LOGI("VideoJitterBufferManager: Created buffer for track '%s'", trackName.c_str());
}

void VideoJitterBufferManager::destroyBuffer(const std::string& trackName)
{
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = buffers_.find(trackName);
    if (it == buffers_.end()) {
        LOGW("No buffer found for track '%s'", trackName.c_str());
        return;
    }

    // Destructor will stop decode thread and clean up
    buffers_.erase(it);

    LOGI("VideoJitterBufferManager: Destroyed buffer for track '%s'", trackName.c_str());
}

VideoTrackJitterBuffer* VideoJitterBufferManager::getBuffer(const std::string& trackName)
{
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = buffers_.find(trackName);
    if (it == buffers_.end()) {
        return nullptr;
    }

    return it->second.get();
}
