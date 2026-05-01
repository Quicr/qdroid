// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.catalog

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Utility methods for converting MOQ namespace and track names to/from safe forms
 * as defined in https://datatracker.ietf.org/doc/html/draft-ietf-moq-transport-16#name-representing-namespace-and-
 *
 * The safe form format:
 * - Namespace tuples are separated by hyphens (-)
 * - Track name is separated from the last namespace by double hyphen (--)
 * - Characters a-z, A-Z, 0-9, and _ (0x5f) are output as-is
 * - All other bytes are encoded as a period (.) followed by exactly two lowercase hex digits
 *
 * Example: example.2enet-team2-project_x--report
 *   Namespace tuples: (example.net, team2, project_x)
 *   Track name: report
 */
object MoqNameUtils {

    /**
     * Characters that are allowed in safe form without encoding.
     * Only a-z, A-Z, 0-9, and _ (0x5f) are allowed.
     */
    private val ALLOWED_CHARS = (('a'..'z') + ('A'..'Z') + ('0'..'9') + '_' + '-').toSet()

    /**
     * Converts namespace tuples and track name to safe form string.
     *
     * Example:
     *   Input: namespaceTuples = ["example.net", "team2", "project_x"], trackName = "report"
     *   Output: "example.2enet-team2-project_x--report"
     *
     * @param namespaceTuples List of ByteArray representing namespace tuple elements
     * @param trackName ByteArray representing the track name
     * @return Safe form string representation
     */
    fun toSafeForm(namespaceTuples: List<ByteArray>, trackName: ByteArray): String {
        val encodedNamespace = namespaceTuples.joinToString("-") { tuple ->
            encodeSafeForm(tuple)
        }
        val encodedTrackName = encodeSafeForm(trackName)

        return if (namespaceTuples.isEmpty()) {
            encodedTrackName
        } else {
            "$encodedNamespace--$encodedTrackName"
        }
    }

    /**
     * Parses a safe form string back to namespace tuples and track name.
     *
     * Example:
     *   Input: "example.2enet-team2-project_x--report"
     *   Output: namespaceTuples = ["example.net", "team2", "project_x"], trackName = "report"
     *
     * If no double hyphen is found, the entire string is treated as a namespace with empty track name.
     *
     * @param safeForm The safe form string representation
     * @return Pair of (namespace tuples, track name) as ByteArrays
     * @throws IllegalArgumentException if the safe form is invalid
     */
    fun fromSafeForm(safeForm: String): Pair<List<ByteArray>, ByteArray> {
        if (safeForm.isEmpty()) {
            throw IllegalArgumentException("Safe form cannot be empty")
        }

        // Find the double hyphen separator
        val doubleDashIndex = safeForm.indexOf("--")

        return if (doubleDashIndex == -1) {
            // No double hyphen found, treat entire string as namespace with empty track name
            val namespaceTuples = parseNamespaceTuples(safeForm)
            Pair(namespaceTuples, ByteArray(0)) // Empty track name
        } else {
            // Split namespace and track name
            val namespaceStr = safeForm.substring(0, doubleDashIndex)
            val trackNameStr = safeForm.substring(doubleDashIndex + 2)

            if (trackNameStr.isEmpty()) {
                throw IllegalArgumentException("Track name cannot be empty")
            }

            val namespaceTuples = if (namespaceStr.isEmpty()) {
                emptyList()
            } else {
                // Split namespace by single hyphens, but need to be careful not to split
                // on hyphens that are part of encoded sequences
                parseNamespaceTuples(namespaceStr)
            }

            Pair(namespaceTuples, decodeSafeForm(trackNameStr))
        }
    }

    /**
     * Converts a safe form string to URL format.
     *
     * In URL format:
     * - Namespace tuples are separated by forward slashes (/)
     * - Track name comes at the end after the last forward slash
     * - No special encoding is applied (plain text format)
     *
     * Example:
     * - Safe form: cisco.2ewebex.2ecom-nab-v1-publisher_211919113--catalog
     * - URL format: cisco.webex.com/nab/v1/publisher_211919113/catalog
     *
     * If the safe form has no track name (no "--"), returns just the namespace path.
     *
     * @param safeForm The safe form string representation
     * @return URL format string
     */
    fun safeFormToUrl(safeForm: String): String {
        // Parse the safe form to get the binary data
        val (namespaceTuples, trackName) = fromSafeForm(safeForm)

        // Convert each part to plain string
        val namespaceParts = namespaceTuples.map { tuple ->
            String(tuple, StandardCharsets.UTF_8)
        }
        val trackNameStr = String(trackName, StandardCharsets.UTF_8)

        return when {
            namespaceParts.isEmpty() && trackNameStr.isEmpty() -> ""
            namespaceParts.isEmpty() -> trackNameStr
            trackNameStr.isEmpty() -> namespaceParts.joinToString("/") // No track name, just namespace
            else -> "${namespaceParts.joinToString("/")}/$trackNameStr"
        }
    }

    /**
     * Converts a URL format string back to safe form.
     *
     * In URL format:
     * - Namespace tuples are separated by forward slashes (/)
     * - Track name is the last part after the final forward slash
     *
     * Example:
     * - URL format: cisco.webex.com/nab/v1/publisher_211919113/catalog
     * - Safe form: cisco.2ewebex.2ecom-nab-v1-publisher_211919113--catalog
     *
     * @param urlForm The URL format string
     * @return Safe form string representation
     * @throws IllegalArgumentException if the URL form is invalid
     */
    fun urlToSafeForm(urlForm: String): String {
        if (urlForm.isEmpty()) {
            throw IllegalArgumentException("URL form cannot be empty")
        }

        // Split by forward slashes
        val parts = urlForm.split("/")

        return if (parts.size == 1) {
            // No slashes found, entire string is the track name with no namespace
            val trackName = parts[0].toByteArray(StandardCharsets.UTF_8)
            toSafeForm(emptyList(), trackName)
        } else {
            // Last part is track name, everything before is namespace tuples
            val namespaceTuples = parts.dropLast(1).map { part ->
                part.toByteArray(StandardCharsets.UTF_8)
            }
            val trackName = parts.last().toByteArray(StandardCharsets.UTF_8)

            toSafeForm(namespaceTuples, trackName)
        }
    }

    /**
     * Convenience method to create a safe form from string elements.
     *
     * @param namespaceTuples Variable number of namespace tuple strings
     * @param trackName The track name string
     * @return Safe form string representation
     */
    fun createSafeForm(vararg namespaceTuples: String, trackName: String): String {
        val tuples = namespaceTuples.map { it.toByteArray(StandardCharsets.UTF_8) }
        return toSafeForm(tuples, trackName.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Convenience method to parse a safe form into string elements.
     *
     * @param safeForm The safe form string representation
     * @return Pair of (namespace tuple strings, track name string)
     */
    fun parseSafeForm(safeForm: String): Pair<List<String>, String> {
        val (tuples, trackName) = fromSafeForm(safeForm)
        return Pair(
            tuples.map { String(it, StandardCharsets.UTF_8) },
            String(trackName, StandardCharsets.UTF_8)
        )
    }

    /**
     * Validates a safe form string.
     *
     * @param safeForm The safe form string to validate
     * @return true if valid, false otherwise
     */
    fun isValidSafeForm(safeForm: String): Boolean {
        return try {
            fromSafeForm(safeForm)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Encodes a byte array to safe form format.
     * Characters a-z, A-Z, 0-9, and _ are output as-is.
     * All other bytes are encoded as period (.) followed by two lowercase hex digits.
     */
    private fun encodeSafeForm(bytes: ByteArray): String {
        val result = StringBuilder()

        for (byte in bytes) {
            val char = byte.toInt().toChar()

            // Check if this is an ASCII character in the allowed set
            if (byte >= 0 && char in ALLOWED_CHARS) {
                result.append(char)
            } else {
                // Encode as .XX where XX is lowercase hex
                result.append('.')
                result.append(String.format("%02x", byte.toInt() and 0xFF))
            }
        }

        return result.toString()
    }

    /**
     * Decodes a safe form encoded string back to bytes.
     * Converts .XX sequences back to their original byte values.
     */
    private fun decodeSafeForm(encoded: String): ByteArray {
        val bytes = mutableListOf<Byte>()
        var i = 0

        while (i < encoded.length) {
            when {
                encoded[i] == '.' && i + 2 < encoded.length -> {
                    // Check if this is a hex sequence
                    val hex = encoded.substring(i + 1, i + 3)
                    try {
                        val byte = hex.toInt(16).toByte()
                        bytes.add(byte)
                        i += 3
                    } catch (e: NumberFormatException) {
                        throw IllegalArgumentException("Invalid hex sequence: .$hex at position $i", e)
                    }
                }
                encoded[i] == '.' -> {
                    // Period at end or not enough characters for hex sequence
                    throw IllegalArgumentException("Invalid encoding: period without hex sequence at position $i")
                }
                else -> {
                    // Regular allowed character
                    val char = encoded[i]
                    if (char !in ALLOWED_CHARS) {
                        throw IllegalArgumentException("Invalid character '$char' at position $i (must be a-z, A-Z, 0-9, or _)")
                    }
                    bytes.add(char.code.toByte())
                    i++
                }
            }
        }

        return bytes.toByteArray()
    }

    /**
     * Parses namespace tuples from a safe form namespace string.
     * Splits by single hyphens, being careful not to split on hyphens in encoded sequences.
     */
    private fun parseNamespaceTuples(namespaceStr: String): List<ByteArray> {
        val tuples = mutableListOf<ByteArray>()
        val currentTuple = StringBuilder()
        var i = 0

        while (i < namespaceStr.length) {
            when {
                namespaceStr[i] == '.' && i + 2 < namespaceStr.length -> {
                    // Encoded sequence - add it as-is
                    currentTuple.append(namespaceStr.substring(i, i + 3))
                    i += 3
                }
                namespaceStr[i] == '-' -> {
                    // Separator - save current tuple and start new one
                    if (currentTuple.isEmpty()) {
                        throw IllegalArgumentException("Empty namespace tuple at position $i")
                    }
                    tuples.add(decodeSafeForm(currentTuple.toString()))
                    currentTuple.clear()
                    i++
                }
                else -> {
                    // Regular character
                    currentTuple.append(namespaceStr[i])
                    i++
                }
            }
        }

        // Add the last tuple
        if (currentTuple.isEmpty()) {
            throw IllegalArgumentException("Empty namespace tuple at end")
        }
        tuples.add(decodeSafeForm(currentTuple.toString()))

        return tuples
    }
}
