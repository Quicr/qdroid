// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.util

class BitReader(private val data: ByteArray) {
    private var bytePos = 0
    private var bitPos = 0

    fun readBit(): Boolean {
        if (bytePos >= data.size) return false
        val bit = (data[bytePos].toInt() shr (7 - bitPos)) and 1
        if (++bitPos == 8) {
            bitPos = 0
            bytePos++
        }
        return bit == 1
    }

    fun readBits(n: Int): Int {
        var value = 0
        repeat(n) {
            value = (value shl 1) or (if (readBit()) 1 else 0)
        }
        return value
    }

    fun skipBits(n: Int) {
        repeat(n) { readBit() }
    }

    // Unsigned Exp-Golomb
    fun readUE(): Int {
        var zeros = 0
        while (!readBit() && zeros < 32) zeros++
        return if (zeros == 0) 0 else ((1 shl zeros) - 1 + readBits(zeros))
    }

    // Signed Exp-Golomb
    fun readSE(): Int {
        val ue = readUE()
        return if (ue % 2 == 0) -(ue / 2) else (ue + 1) / 2
    }
}
