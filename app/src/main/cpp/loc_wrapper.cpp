// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#include "loc_wrapper.h"
#include <android/log.h>
#include <cstring>
#include <chrono>
#include <loc/loc.hpp>

#define TAG "LocWrapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

std::vector<uint8_t> LocWrapper::wrap(
    const uint8_t* codecData,
    size_t dataLen,
    const LocMetadata& metadata)
{
    try {
        // Create LOC object
        loc::loc_object obj;

        // Add timestamp (microseconds)
        obj.timestamp(metadata.captureTimestampUs);

        // Add frame marking for video
        if (metadata.mediaType == MediaType::Video) {
            loc::video_frame_marking marking;
            marking.independent = metadata.isKeyframe;
            marking.discardable = false;
            marking.base_layer_sync = metadata.isKeyframe;
            marking.temporal_id = 0;
            marking.spatial_id = 0;
            obj.frame_marking(marking);
        }

        // Set payload - convert uint8_t* to byte_span
        auto byte_payload = loc::byte_span{
            reinterpret_cast<const std::byte*>(codecData),
            dataLen
        };
        obj.set_payload(byte_payload);

        // Encode to wire format
        auto encoded = obj.encode();

        // Combine public properties and payload
        std::vector<uint8_t> locData;
        locData.reserve(encoded.public_properties.size() + encoded.payload.size());

        // Copy public properties
        for (auto b : encoded.public_properties) {
            locData.push_back(static_cast<uint8_t>(b));
        }

        // Copy payload (which includes private properties + codec data)
        for (auto b : encoded.payload) {
            locData.push_back(static_cast<uint8_t>(b));
        }

        LOGI("Wrapped %s frame: size=%zu->%zu, group=%llu, obj=%u, keyframe=%d",
             metadata.mediaType == MediaType::Video ? "video" : "audio",
             dataLen, locData.size(),
             metadata.groupId, metadata.objectId,
             metadata.isKeyframe);

        return locData;

    } catch (const std::exception& e) {
        LOGE("LOC wrap failed: %s", e.what());
        // Fallback: return raw data if LOC fails
        return std::vector<uint8_t>(codecData, codecData + dataLen);
    }
}

LocUnwrapResult LocWrapper::unwrap(
    const uint8_t* locData,
    size_t dataLen)
{
    LocUnwrapResult result;
    result.success = false;

    // Initialize metadata with defaults
    result.metadata.groupId = 0;
    result.metadata.objectId = 0;
    result.metadata.isKeyframe = false;
    result.metadata.captureTimestampUs = 0;
    result.metadata.mediaType = MediaType::Video;

    try {
        // Convert to byte_span
        auto data_span = loc::byte_span{
            reinterpret_cast<const std::byte*>(locData),
            dataLen
        };

        // Parse properties to extract metadata
        // We need to separate public properties from payload
        // The data format is: public_props + (private_props + codec_data)

        loc::cursor c(data_span);
        size_t public_props_size = 0;

        // Parse public properties to extract metadata
        while (!c.empty()) {
            auto start_pos = data_span.size() - c.size();

            // Try to read property ID
            auto id_result = c.read_varint();
            if (!id_result) {
                // No more properties, rest is payload
                break;
            }
            auto id = *id_result;

            // Check if this is a valid property ID
            if (id == loc::property_id::timestamp ||
                id == loc::property_id::video_frame_marking ||
                id == loc::property_id::audio_level ||
                id == loc::property_id::timescale) {

                // Read the property value
                if (loc::is_varint_property(id)) {
                    auto val_result = c.read_varint();
                    if (!val_result) break;

                    // Extract metadata based on property type
                    if (id == loc::property_id::timestamp) {
                        result.metadata.captureTimestampUs = *val_result;
                    } else if (id == loc::property_id::video_frame_marking) {
                        auto marking = loc::video_frame_marking::decode(*val_result);
                        result.metadata.isKeyframe = marking.independent;
                        result.metadata.mediaType = MediaType::Video;
                    } else if (id == loc::property_id::audio_level) {
                        result.metadata.mediaType = MediaType::Audio;
                    }
                } else {
                    // Bytes property: skip length and data
                    auto len_result = c.read_varint();
                    if (!len_result) break;
                    auto bytes_result = c.read_bytes(*len_result);
                    if (!bytes_result) break;
                }

                public_props_size = data_span.size() - c.size();
            } else {
                // Unknown property or start of payload
                // Rewind to start of this property
                c = loc::cursor(data_span.subspan(start_pos));
                break;
            }
        }

        // Remaining data is payload (may include private properties + codec data)
        auto payload_span = c.remaining();

        // For simplicity, treat all remaining data as codec payload
        // (In a full implementation, we'd parse private properties too)
        result.payload.reserve(payload_span.size());
        for (auto b : payload_span) {
            result.payload.push_back(static_cast<uint8_t>(b));
        }

        result.success = true;

        LOGI("Unwrapped %s frame: size=%zu->%zu, keyframe=%d, ts=%llu",
             result.metadata.mediaType == MediaType::Video ? "video" : "audio",
             dataLen, result.payload.size(),
             result.metadata.isKeyframe,
             result.metadata.captureTimestampUs);

        return result;

    } catch (const std::exception& e) {
        result.errorMessage = e.what();
        LOGE("LOC unwrap failed: %s", e.what());

        // Fallback: treat as raw data
        result.payload.assign(locData, locData + dataLen);
        result.success = false;

        return result;
    }
}
