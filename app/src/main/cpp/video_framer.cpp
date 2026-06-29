// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#include "video_framer.h"
#include "loc_wrapper.h"
#include <android/log.h>
#include <quicr/client.h>
#include <chrono>

#define LOG_TAG "VideoFramer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward declare AndroidPublishTrackHandler (defined in moq_jni.cpp)
class AndroidPublishTrackHandler : public quicr::PublishTrackHandler {
public:
    using quicr::PublishTrackHandler::PublishTrackHandler;
    using quicr::PublishTrackHandler::PublishObject;
    using quicr::PublishTrackHandler::EndSubgroup;
    using quicr::PublishTrackHandler::CanPublish;
};

VideoFramer::VideoFramer(const std::string& track_name,
                         std::shared_ptr<AndroidPublishTrackHandler> handler)
    : trackName_(track_name)
    , publishHandler_(handler)
    , currentGroupId_(std::chrono::system_clock::now().time_since_epoch().count() & 0xFFFFFFFFL)
    , currentObjectId_(0)
    , firstFrame_(true)
    , framesProcessed_(0)
    , gopCount_(0)
{
    LOGI("VideoFramer created for track: %s, initial groupId: %llu",
         trackName_.c_str(), static_cast<unsigned long long>(currentGroupId_));
}

VideoFramer::~VideoFramer() {
    LOGI("VideoFramer destroyed for track: %s, processed %llu frames in %llu GOPs",
         trackName_.c_str(),
         static_cast<unsigned long long>(framesProcessed_),
         static_cast<unsigned long long>(gopCount_));
}

void VideoFramer::processFrame(const uint8_t* data, size_t size,
                               bool is_keyframe, uint64_t timestamp_us) {
    if (!data || size == 0) {
        LOGE("VideoFramer::processFrame: Invalid data (null or zero size)");
        return;
    }

    if (!publishHandler_) {
        LOGE("VideoFramer::processFrame: No publish handler");
        return;
    }

    if (!publishHandler_->CanPublish()) {
        LOGW("VideoFramer::processFrame: Handler not ready to publish");
        return;
    }

    std::lock_guard<std::mutex> lock(stateMutex_);

    // Handle keyframe - start new GOP
    if (is_keyframe) {
        if (!firstFrame_) {
            // End previous subgroup before starting new GOP
            try {
                publishHandler_->EndSubgroup(currentGroupId_, 0, true);
                LOGI("VideoFramer: Ended subgroup for group %llu",
                     static_cast<unsigned long long>(currentGroupId_));
            } catch (const std::exception& e) {
                LOGE("VideoFramer::EndSubgroup exception: %s", e.what());
            }

            currentGroupId_++;
            gopCount_++;
        }
        currentObjectId_ = 0;
        firstFrame_ = false;

        LOGI("VideoFramer: New GOP started - groupId: %llu, objectId: %llu",
             static_cast<unsigned long long>(currentGroupId_),
             static_cast<unsigned long long>(currentObjectId_));
    }

    // Prepare LOC metadata
    LocMetadata locMeta;
    locMeta.mediaType = MediaType::Video;
    locMeta.groupId = currentGroupId_;
    locMeta.objectId = static_cast<uint32_t>(currentObjectId_);
    locMeta.isKeyframe = is_keyframe;
    locMeta.captureTimestampUs = timestamp_us;

    // Wrap frame with LOC container
    std::vector<uint8_t> locWrappedData;
    try {
        locWrappedData = LocWrapper::wrap(data, size, locMeta);
    } catch (const std::exception& e) {
        LOGE("VideoFramer::LOC wrap exception: %s", e.what());
        return;
    }

    // Prepare object headers for libquicr
    quicr::ObjectHeaders headers = {
        .group_id = currentGroupId_,
        .object_id = currentObjectId_,
        .subgroup_id = 0,
        .payload_length = static_cast<uint64_t>(locWrappedData.size()),
        .status = quicr::ObjectStatus::kAvailable,
        .priority = 0,
        .ttl = std::optional<uint32_t>(3000),  // 3 second TTL
        .track_mode = std::nullopt,
        .extensions = std::nullopt,
        .immutable_extensions = std::nullopt
    };

    // Publish object to libquicr
    try {
        quicr::BytesSpan data_span(locWrappedData.data(), locWrappedData.size());
        auto status = publishHandler_->PublishObject(headers, data_span);

        if (status != quicr::PublishTrackHandler::PublishObjectStatus::kOk) {
            LOGE("VideoFramer::PublishObject failed with status %d for group %llu object %llu",
                 static_cast<int>(status),
                 static_cast<unsigned long long>(currentGroupId_),
                 static_cast<unsigned long long>(currentObjectId_));
        } else {
            // Log only occasionally to avoid spam
            if (framesProcessed_ % 100 == 0) {
                LOGI("VideoFramer: Published frame %llu (group %llu, object %llu, %zu bytes)",
                     static_cast<unsigned long long>(framesProcessed_),
                     static_cast<unsigned long long>(currentGroupId_),
                     static_cast<unsigned long long>(currentObjectId_),
                     size);
            }
        }
    } catch (const std::exception& e) {
        LOGE("VideoFramer::PublishObject exception: %s", e.what());
        return;
    }

    currentObjectId_++;
    framesProcessed_++;
}

VideoFramer::Stats VideoFramer::getStats() const {
    std::lock_guard<std::mutex> lock(stateMutex_);
    return Stats{
        .framesProcessed = framesProcessed_,
        .gopCount = gopCount_,
        .currentGroupId = currentGroupId_,
        .currentObjectId = currentObjectId_
    };
}
