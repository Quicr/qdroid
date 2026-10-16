// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#pragma once

#include <vector>
#include <cstdint>
#include <optional>
#include <string>

/**
 * Media type enumeration for LOC container
 */
enum class MediaType {
    Video,
    Audio
};

/**
 * LOC metadata attached to each frame/packet
 */
struct LocMetadata {
    MediaType mediaType;
    uint64_t groupId;
    uint32_t objectId;
    bool isKeyframe;  // For video only
    uint64_t captureTimestampUs;  // Microseconds since epoch

    // True when a LOC video_frame_marking property (id 4) was actually present and parsed on
    // unwrap. When false, isKeyframe is not authoritative and the caller should fall back to a
    // transport-level signal (e.g. MoQ object_id == 0 == group start). This is required for
    // interop with publishers that carry no LOC public properties in the object payload (they put
    // media directly in the payload and metadata in MoQ extensions) — see LocWrapper::unwrap.
    bool frameMarkingPresent;  // For video only; set by unwrap
    uint64_t timescale;        // LOC timescale (property id 8), 0 if absent
};

/**
 * Result of LOC unwrap operation
 */
struct LocUnwrapResult {
    std::vector<uint8_t> payload;  // Unwrapped codec data
    LocMetadata metadata;
    bool success;
    std::string errorMessage;
};

/**
 * Helper class for wrapping/unwrapping media frames with LOC container
 */
class LocWrapper {
public:
    /**
     * Wrap codec data with a LOC container (public properties concatenated ahead of the payload).
     *
     * ⚠️ UNUSED as of the Mac interop work — kept intentionally as documentation of the legacy
     * "LOC-in-payload" framing and as a utility. The publish path (moq_jni.cpp nativeSendObject) no
     * longer LOC-wraps outgoing objects: to interop with the reference Mac client it now sends a
     * clean media payload (video converted Annex-B→AVCC, audio verbatim) and carries metadata in MoQ
     * object extensions (id 2 = timestamp, id 12 = activity) instead of a payload prefix. Prepending
     * these LOC property bytes corrupted the bitstream the Mac fed to its decoder.
     *
     * The receive counterpart, unwrap(), is still used: it tolerantly strips a LOC property prefix
     * if a peer sends one, and passes clean payloads through untouched.
     *
     * If a future track type needs LOC-in-payload packaging again, re-wire this into nativeSendObject.
     *
     * @param codecData Raw H.264 or Opus data
     * @param dataLen Size of codec data
     * @param metadata LOC metadata to embed
     * @return LOC-wrapped data ready for transmission
     */
    static std::vector<uint8_t> wrap(
        const uint8_t* codecData,
        size_t dataLen,
        const LocMetadata& metadata
    );

    /**
     * Unwrap LOC container to extract codec data and metadata
     *
     * @param locData LOC-wrapped data from network
     * @param dataLen Size of LOC data
     * @return Unwrapped codec data and metadata, or error
     */
    static LocUnwrapResult unwrap(
        const uint8_t* locData,
        size_t dataLen
    );

private:
    // Disable instantiation - static utility class
    LocWrapper() = delete;
};
