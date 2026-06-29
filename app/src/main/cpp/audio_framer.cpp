// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#include "audio_framer.h"
#include "native_vad.h"
#include "loc_wrapper.h"
#include <android/log.h>
#include <quicr/client.h>
#include <chrono>

#define LOG_TAG "AudioFramer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward declare AndroidPublishTrackHandler (defined in moq_jni.cpp)
class AndroidPublishTrackHandler : public quicr::PublishTrackHandler {
public:
    using quicr::PublishTrackHandler::PublishTrackHandler;
    using quicr::PublishTrackHandler::PublishObject;
    using quicr::PublishTrackHandler::CanPublish;
};

AudioFramer::AudioFramer(const std::string& track_name,
                         std::shared_ptr<AndroidPublishTrackHandler> handler,
                         bool vad_enabled)
    : trackName_(track_name)
    , publishHandler_(handler)
    , currentGroupId_((std::chrono::system_clock::now().time_since_epoch().count() & 0xFFFFFFFFL) + 1000000)
    , vadEnabled_(vad_enabled)
    , packetsProcessed_(0)
    , packetsDroppedVad_(0)
{
    // Initialize VAD if enabled
    if (vad_enabled) {
        vad_ = std::make_unique<NativeVAD>();
        LOGI("AudioFramer created for track: %s with VAD enabled, initial groupId: %llu",
             trackName_.c_str(), static_cast<unsigned long long>(currentGroupId_.load()));
    } else {
        LOGI("AudioFramer created for track: %s with VAD disabled, initial groupId: %llu",
             trackName_.c_str(), static_cast<unsigned long long>(currentGroupId_.load()));
    }
}

AudioFramer::~AudioFramer() {
    LOGI("AudioFramer destroyed for track: %s, processed %llu packets (%llu dropped by VAD)",
         trackName_.c_str(),
         static_cast<unsigned long long>(packetsProcessed_.load()),
         static_cast<unsigned long long>(packetsDroppedVad_.load()));
}

void AudioFramer::processPacket(const uint8_t* data, size_t size, uint64_t timestamp_us) {
    if (!data || size == 0) {
        LOGE("AudioFramer::processPacket: Invalid data (null or zero size)");
        return;
    }

    if (!publishHandler_) {
        LOGE("AudioFramer::processPacket: No publish handler");
        return;
    }

    if (!publishHandler_->CanPublish()) {
        // Not ready yet - this is normal during startup
        return;
    }

    // Apply VAD filtering if enabled
    if (vadEnabled_.load() && vad_) {
        if (!vad_->isSpeech(data, size)) {
            packetsDroppedVad_++;

            // Log VAD stats periodically
            uint64_t totalPackets = packetsProcessed_.load();
            if (totalPackets % 100 == 0 && totalPackets > 0) {
                uint64_t dropped = packetsDroppedVad_.load();
                double dropRate = (dropped * 100.0) / totalPackets;
                LOGI("AudioFramer VAD stats: dropped=%llu/%llu (%.1f%%)",
                     static_cast<unsigned long long>(dropped),
                     static_cast<unsigned long long>(totalPackets),
                     dropRate);
            }

            return;  // Skip this packet - no speech detected
        }
    }

    // Get current group ID and increment for next packet (atomic)
    uint64_t groupId = currentGroupId_.fetch_add(1);

    // Prepare LOC metadata
    LocMetadata locMeta;
    locMeta.mediaType = MediaType::Audio;
    locMeta.groupId = groupId;
    locMeta.objectId = 0;  // Always 0 for audio - one packet per group
    locMeta.isKeyframe = false;  // Not applicable for audio
    locMeta.captureTimestampUs = timestamp_us;

    // Wrap packet with LOC container
    std::vector<uint8_t> locWrappedData;
    try {
        locWrappedData = LocWrapper::wrap(data, size, locMeta);
    } catch (const std::exception& e) {
        LOGE("AudioFramer::LOC wrap exception: %s", e.what());
        return;
    }

    // Prepare object headers for libquicr
    quicr::ObjectHeaders headers = {
        .group_id = groupId,
        .object_id = 0,  // Always 0 for audio
        .subgroup_id = 0,
        .payload_length = static_cast<uint64_t>(locWrappedData.size()),
        .status = quicr::ObjectStatus::kAvailable,
        .priority = 1,  // Audio often higher priority than video
        .ttl = std::optional<uint32_t>(1000),  // 1 second TTL
        .track_mode = std::nullopt,
        .extensions = std::nullopt,
        .immutable_extensions = std::nullopt
    };

    // Publish object to libquicr with datagram mode for lower latency
    try {
        quicr::BytesSpan data_span(locWrappedData.data(), locWrappedData.size());
        auto status = publishHandler_->PublishObject(headers, data_span);

        if (status != quicr::PublishTrackHandler::PublishObjectStatus::kOk) {
            LOGE("AudioFramer::PublishObject failed with status %d for group %llu",
                 static_cast<int>(status),
                 static_cast<unsigned long long>(groupId));
        } else {
            // Log only occasionally to avoid spam
            uint64_t packets = packetsProcessed_.load();
            if (packets % 100 == 0) {
                LOGI("AudioFramer: Published packet %llu (group %llu, %zu bytes)",
                     static_cast<unsigned long long>(packets),
                     static_cast<unsigned long long>(groupId),
                     size);
            }
        }
    } catch (const std::exception& e) {
        LOGE("AudioFramer::PublishObject exception: %s", e.what());
        return;
    }

    packetsProcessed_++;
}

void AudioFramer::setVadEnabled(bool enabled) {
    vadEnabled_.store(enabled);

    // Initialize VAD if enabling and not already initialized
    if (enabled && !vad_) {
        vad_ = std::make_unique<NativeVAD>();
        LOGI("AudioFramer: VAD enabled for track %s", trackName_.c_str());
    } else if (!enabled) {
        LOGI("AudioFramer: VAD disabled for track %s", trackName_.c_str());
    }
}

AudioFramer::Stats AudioFramer::getStats() const {
    return Stats{
        .packetsProcessed = packetsProcessed_.load(),
        .packetsDroppedVad = packetsDroppedVad_.load(),
        .currentGroupId = currentGroupId_.load()
    };
}
