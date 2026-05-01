// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.util

import android.util.Log
import java.nio.ByteBuffer

object NALUParser {
    private const val TAG = "NALUParser"

    fun isAnnexB(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return (data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 0.toByte() && data[3] == 1.toByte()) ||
               (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte())
    }

    fun parseAnnexB(data: ByteArray): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null

        val nalus = splitAnnexBNALUs(data)
        Log.d(TAG, "parseAnnexB: Found ${nalus.size} NALUs in buffer of size ${data.size}")
        
        for (nalu in nalus) {
            if (nalu.isEmpty()) continue
            val naluType = nalu[0].toInt() and 0x1F
            Log.v(TAG, "  NALU Type: $naluType, Size: ${nalu.size}")
            when (naluType) {
                7 -> sps = nalu  // SPS
                8 -> pps = nalu  // PPS
            }
        }
        return Pair(sps, pps)
    }

    private fun splitAnnexBNALUs(data: ByteArray): List<ByteArray> {
        val nalus = mutableListOf<ByteArray>()
        var i = 0
        var naluStart = -1

        while (i <= data.size - 3) {
            if (data[i] == 0.toByte() && data[i+1] == 0.toByte()) {
                val is4Byte = i + 3 < data.size && data[i+2] == 0.toByte() && data[i+3] == 1.toByte()
                val is3Byte = data[i+2] == 1.toByte()

                if (is4Byte || is3Byte) {
                    if (naluStart >= 0) {
                        val naluEnd = i
                        if (naluEnd > naluStart) {
                            nalus.add(data.copyOfRange(naluStart, naluEnd))
                        }
                    }
                    naluStart = i + (if (is4Byte) 4 else 3)
                    i = naluStart
                    continue
                }
            }
            i++
        }
        if (naluStart >= 0 && naluStart < data.size) {
            nalus.add(data.copyOfRange(naluStart, data.size))
        }
        return nalus
    }
}
