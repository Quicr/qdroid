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

namespace {

// The complete set of LOC public-property IDs defined by loc.hpp (property_id namespace). The
// unwrap parser walks public properties only while the next property ID is one of these; the first
// ID outside this set marks the start of the media payload. Keeping the full set (rather than the
// 4-ID subset the previous parser recognised) is what makes unwrap tolerant of publishers that
// stamp group/sequence/config properties — without it the parser bailed on the first unknown ID,
// extracted no metadata, and left the property bytes glued to the codec data.
[[nodiscard]] bool isKnownLocPropertyId(uint64_t id) noexcept {
    switch (id) {
        case loc::property_id::video_frame_marking:  // 4
        case loc::property_id::timestamp:            // 6
        case loc::property_id::timescale:            // 8
        case loc::property_id::audio_level:          // 10
        case loc::property_id::sequence_number:      // 12
        case loc::property_id::video_config:         // 13
        case loc::property_id::group_id:             // 14
        case loc::property_id::subgroup_id:          // 16
        case loc::property_id::priority:             // 18
        case loc::property_id::delivery_timeout:     // 20
            return true;
        default:
            return false;
    }
}

}  // namespace

// NOTE: UNUSED as of the Mac interop work. The publish path no longer LOC-wraps outgoing objects
// (it sends a clean media payload + MoQ extensions to match the reference Mac client). Kept as
// documentation of the legacy LOC-in-payload framing and as a utility — see the header for details.
std::vector<uint8_t> LocWrapper::wrap(
    const uint8_t* codecData,
    size_t dataLen,
    const LocMetadata& metadata)
{
    try {
        // Build the LOC object with its public properties: timestamp for all media, and
        // video_frame_marking for video. (This is the legacy framing; see the UNUSED note above.)
        loc::loc_object obj;

        obj.timestamp(metadata.captureTimestampUs);

        if (metadata.mediaType == MediaType::Video) {
            loc::video_frame_marking marking;
            marking.independent = metadata.isKeyframe;
            marking.discardable = false;
            marking.base_layer_sync = metadata.isKeyframe;
            marking.temporal_id = 0;
            marking.spatial_id = 0;
            obj.frame_marking(marking);
        }

        auto byte_payload = loc::byte_span{
            reinterpret_cast<const std::byte*>(codecData),
            dataLen
        };
        obj.set_payload(byte_payload);

        // Encode to wire format. loc.hpp keeps public_properties and payload (private_properties +
        // codec data) as separate buffers; QDroid's transport carries both in a single MoQ object
        // payload, so we concatenate public_properties ++ payload. unwrap() reverses this.
        auto encoded = obj.encode();

        std::vector<uint8_t> locData;
        locData.reserve(encoded.public_properties.size() + encoded.payload.size());
        for (auto b : encoded.public_properties) {
            locData.push_back(static_cast<uint8_t>(b));
        }
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

    // Defaults: assume video with no metadata until proven otherwise. These are what the caller
    // sees for a payload that carries no LOC public properties at all (e.g. a native MoQ publisher
    // that sends raw AVCC in the payload and keeps metadata in MoQ extensions).
    result.metadata.groupId = 0;
    result.metadata.objectId = 0;
    result.metadata.isKeyframe = false;
    result.metadata.frameMarkingPresent = false;
    result.metadata.captureTimestampUs = 0;
    result.metadata.timescale = 0;
    result.metadata.mediaType = MediaType::Video;

    try {
        auto data_span = loc::byte_span{
            reinterpret_cast<const std::byte*>(locData),
            dataLen
        };

        // Walk the public properties. We advance only while the next property ID is a known LOC
        // property; the first unknown ID (or a truncated/invalid read) is treated as the start of
        // the media payload. This isolates the codec bytes cleanly whether the publisher stamped a
        // full LOC property set, a minimal one, or none at all.
        loc::cursor c(data_span);
        size_t payload_offset = 0;  // bytes consumed as public properties

        while (!c.empty()) {
            // Peek the property ID without committing, so an unknown ID (= payload start) leaves
            // the cursor untouched.
            auto probe = c;
            auto id_result = probe.read_varint();
            if (!id_result) break;  // not a readable varint -> treat remainder as payload
            auto id = *id_result;

            if (!isKnownLocPropertyId(id)) {
                break;  // start of media payload
            }

            // Commit the ID read, then consume the value according to its wire type.
            c = probe;
            if (loc::is_varint_property(id)) {
                auto val_result = c.read_varint();
                if (!val_result) break;
                uint64_t value = *val_result;

                if (id == loc::property_id::timestamp) {
                    result.metadata.captureTimestampUs = value;
                } else if (id == loc::property_id::timescale) {
                    result.metadata.timescale = value;
                } else if (id == loc::property_id::video_frame_marking) {
                    if (auto marking = loc::video_frame_marking::decode(value)) {
                        result.metadata.isKeyframe = marking->independent;
                        result.metadata.frameMarkingPresent = true;
                    }
                    result.metadata.mediaType = MediaType::Video;
                } else if (id == loc::property_id::audio_level) {
                    result.metadata.mediaType = MediaType::Audio;
                }
                // sequence_number / group_id / subgroup_id / priority / delivery_timeout are valid
                // LOC properties we don't need here; they are skipped (consumed) so the payload
                // boundary is correct.
            } else {
                // Bytes property (e.g. video_config): length-prefixed value.
                auto len_result = c.read_varint();
                if (!len_result) break;
                auto bytes_result = c.read_bytes(static_cast<size_t>(*len_result));
                if (!bytes_result) break;
            }

            payload_offset = dataLen - c.size();
        }

        // Everything the cursor has not consumed is the media payload.
        auto payload_span = data_span.subspan(payload_offset);
        result.payload.reserve(payload_span.size());
        for (auto b : payload_span) {
            result.payload.push_back(static_cast<uint8_t>(b));
        }

        result.success = true;

        LOGI("Unwrapped %s frame: size=%zu->%zu, keyframe=%d (marked=%d), ts=%llu",
             result.metadata.mediaType == MediaType::Video ? "video" : "audio",
             dataLen, result.payload.size(),
             result.metadata.isKeyframe,
             result.metadata.frameMarkingPresent,
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
