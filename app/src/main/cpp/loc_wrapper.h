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
     * Wrap codec data with LOC container
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
