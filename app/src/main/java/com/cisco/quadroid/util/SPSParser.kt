// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.util

import android.util.Log

object SPSParser {
    data class SPSInfo(
        val width: Int,
        val height: Int,
        val profileIdc: Int,
        val levelIdc: Int
    )

    fun parse(sps: ByteArray): SPSInfo? {
        try {
            val reader = BitReader(sps)

            // Skip forbidden_zero_bit and nal_ref_idc
            reader.skipBits(8)  // NAL header

            val profileIdc = reader.readBits(8)
            reader.skipBits(8)  // constraint flags + reserved
            val levelIdc = reader.readBits(8)

            reader.readUE()  // seq_parameter_set_id

            if (profileIdc in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134)) {
                val chromaFormatIdc = reader.readUE()
                if (chromaFormatIdc == 3) reader.skipBits(1)  // separate_colour_plane_flag
                reader.readUE()  // bit_depth_luma_minus8
                reader.readUE()  // bit_depth_chroma_minus8
                reader.skipBits(1)  // qpprime_y_zero_transform_bypass_flag
                val seqScalingMatrixPresent = reader.readBit()
                if (seqScalingMatrixPresent) {
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until count) {
                        if (reader.readBit()) skipScalingList(reader, if (i < 6) 16 else 64)
                    }
                }
            }

            reader.readUE()  // log2_max_frame_num_minus4
            val picOrderCntType = reader.readUE()
            when (picOrderCntType) {
                0 -> reader.readUE()  // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    reader.skipBits(1)  // delta_pic_order_always_zero_flag
                    reader.readSE()  // offset_for_non_ref_pic
                    reader.readSE()  // offset_for_top_to_bottom_field
                    val numRefFrames = reader.readUE()
                    repeat(numRefFrames) { reader.readSE() }
                }
            }

            reader.readUE()  // max_num_ref_frames
            reader.skipBits(1)  // gaps_in_frame_num_allowed_flag

            val picWidthInMbsMinus1 = reader.readUE()
            val picHeightInMapUnitsMinus1 = reader.readUE()
            val frameMbsOnlyFlag = reader.readBit()

            if (!frameMbsOnlyFlag) reader.skipBits(1)  // mb_adaptive_frame_field_flag
            reader.skipBits(1)  // direct_8x8_inference_flag

            var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
            val frameCroppingFlag = reader.readBit()
            if (frameCroppingFlag) {
                cropLeft = reader.readUE()
                cropRight = reader.readUE()
                cropTop = reader.readUE()
                cropBottom = reader.readUE()
            }

            // Calculate dimensions
            val width = (picWidthInMbsMinus1 + 1) * 16 - (cropLeft + cropRight) * 2
            val height = (2 - if (frameMbsOnlyFlag) 1 else 0) *
                        (picHeightInMapUnitsMinus1 + 1) * 16 - (cropTop + cropBottom) * 2

            return SPSInfo(width, height, profileIdc, levelIdc)
        } catch (e: Exception) {
            Log.e("SPSParser", "Failed to parse SPS", e)
            return null
        }
    }

    private fun skipScalingList(reader: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (i in 0 until size) {
            if (nextScale != 0) {
                val delta = reader.readSE()
                nextScale = (lastScale + delta + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }
}
