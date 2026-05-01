// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.util

/**
 * Utility for MoQ track identification and key generation using MurmurHash3
 * to ensure consistent numeric keys across Kotlin and C++.
 */
object TrackUtil {

    private const val C1 = -0x783c8466e0660475L // 0x87c37b91114253d5L
    private const val C2 = 0x4cf5ad432745937fL

    /**
     * MurmurHash3_x64_128 implementation (returning the first 64 bits).
     * This is a stable, non-cryptographic hash function.
     */
    fun murmurHash64(input: String, seed: Int = 0): Long {
        val data = input.toByteArray(Charsets.UTF_8)
        var h1 = seed.toLong()
        var h2 = seed.toLong()

        val length = data.size
        val nblocks = length / 16

        for (i in 0 until nblocks) {
            var k1 = getLong(data, i * 16)
            var k2 = getLong(data, i * 16 + 8)

            k1 *= C1
            k1 = java.lang.Long.rotateLeft(k1, 31)
            k1 *= C2
            h1 = h1 xor k1

            h1 = java.lang.Long.rotateLeft(h1, 27)
            h1 += h2
            h1 = h1 * 5 + 0x52dce729

            k2 *= C2
            k2 = java.lang.Long.rotateLeft(k2, 33)
            k2 *= C1
            h2 = h2 xor k2

            h2 = java.lang.Long.rotateLeft(h2, 31)
            h2 += h1
            h2 = h2 * 5 + 0x38495ab5
        }

        // Tail
        var k1 = 0L
        var k2 = 0L
        val tailStart = nblocks * 16
        val remaining = length - tailStart

        if (remaining > 8) {
            if (remaining >= 15) k2 = k2 xor ((data[tailStart + 14].toLong() and 0xffL) shl 48)
            if (remaining >= 14) k2 = k2 xor ((data[tailStart + 13].toLong() and 0xffL) shl 40)
            if (remaining >= 13) k2 = k2 xor ((data[tailStart + 12].toLong() and 0xffL) shl 32)
            if (remaining >= 12) k2 = k2 xor ((data[tailStart + 11].toLong() and 0xffL) shl 24)
            if (remaining >= 11) k2 = k2 xor ((data[tailStart + 10].toLong() and 0xffL) shl 16)
            if (remaining >= 10) k2 = k2 xor ((data[tailStart + 9].toLong() and 0xffL) shl 8)
            if (remaining >= 9) k2 = k2 xor (data[tailStart + 8].toLong() and 0xffL)
            
            k2 *= C2
            k2 = java.lang.Long.rotateLeft(k2, 33)
            k2 *= C1
            h2 = h2 xor k2
        }

        if (remaining > 0) {
            if (remaining >= 8) k1 = k1 xor ((data[tailStart + 7].toLong() and 0xffL) shl 56)
            if (remaining >= 7) k1 = k1 xor ((data[tailStart + 6].toLong() and 0xffL) shl 48)
            if (remaining >= 6) k1 = k1 xor ((data[tailStart + 5].toLong() and 0xffL) shl 40)
            if (remaining >= 5) k1 = k1 xor ((data[tailStart + 4].toLong() and 0xffL) shl 32)
            if (remaining >= 4) k1 = k1 xor ((data[tailStart + 3].toLong() and 0xffL) shl 24)
            if (remaining >= 3) k1 = k1 xor ((data[tailStart + 2].toLong() and 0xffL) shl 16)
            if (remaining >= 2) k1 = k1 xor ((data[tailStart + 1].toLong() and 0xffL) shl 8)
            if (remaining >= 1) k1 = k1 xor (data[tailStart].toLong() and 0xffL)
            
            k1 *= C1
            k1 = java.lang.Long.rotateLeft(k1, 31)
            k1 *= C2
            h1 = h1 xor k1
        }

        // Finalization
        h1 = h1 xor length.toLong()
        h2 = h2 xor length.toLong()

        h1 += h2
        h2 += h1

        h1 = fmix64(h1)
        h2 = fmix64(h2)

        h1 += h2
        
        return h1
    }

    private fun getLong(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xffL) or
               ((data[offset + 1].toLong() and 0xffL) shl 8) or
               ((data[offset + 2].toLong() and 0xffL) shl 16) or
               ((data[offset + 3].toLong() and 0xffL) shl 24) or
               ((data[offset + 4].toLong() and 0xffL) shl 32) or
               ((data[offset + 5].toLong() and 0xffL) shl 40) or
               ((data[offset + 6].toLong() and 0xffL) shl 48) or
               ((data[offset + 7].toLong() and 0xffL) shl 56)
    }

    private fun fmix64(h: Long): Long {
        var k = h
        k = k xor (k ushr 33)
        k *= -0xae502812aa7333L // 0xff51afd7ed558ccdL
        k = k xor (k ushr 33)
        k *= -0x3b3146010f6d7dL // 0xc4ceb9fe1a85ec53L
        k = k xor (k ushr 33)
        return k
    }

    /**
     * Generates a unique track key from a track namespace and name.
     * Logic: (track_namespace_hash ^ (track_name_hash << 1)) << 1 >> 2
     *
     * @param trackNamespace The track's namespace prefix.
     * @param trackName The track's specific name.
     * @return A 64-bit track key that is stable across architectures and languages.
     */
    fun generateTrackKey(trackNamespace: String, trackName: String): Long {
        val nsHash = murmurHash64(trackNamespace)
        val nameHash = murmurHash64(trackName)

        val combinedHash = nsHash xor (nameHash shl 1)
        return (combinedHash shl 1) ushr 2
    }
    
    /**
     * Generates a unique track key from a full track name string.
     * Parses the full track name (e.g., "namespace/name") and generates the key.
     *
     * @param fullTrackName The full track name string with namespace and name separated by "/"
     * @return A string representation of the 64-bit track key
     */
    fun generateTrackKeyFromFullName(fullTrackName: String): String {
        val lastSlashIndex = fullTrackName.lastIndexOf('/')
        if (lastSlashIndex == -1) {
            // No slash, treat entire string as trackName with empty namespace
            return generateTrackKey("", fullTrackName).toString()
        }
        val namespace = fullTrackName.substring(0, lastSlashIndex)
        val trackName = fullTrackName.substring(lastSlashIndex + 1)
        return generateTrackKey(namespace, trackName).toString()
    }

    /**
     * Helper to reconstruct a full track name string from namespace and name.
     */
    fun getFullTrackName(trackNamespace: String, trackName: String): String {
        return if (trackName.isEmpty()) {
            trackNamespace
        } else {
            "$trackNamespace/$trackName"
        }
    }

    /*
    * Helper to map trackNames to priority for publish
     */
    fun getTrackPriority(trackName: String): Int {
        return when {
            trackName.contains("1080p") -> 8
            trackName.contains("720p") -> 6
            trackName.contains("360p") -> 4
            else -> 10
        }
    }
}
