// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.catalog

import kotlinx.serialization.json.Json

object MsfCatalogParser {
    private val json = Json {
        // Ignore unknown keys to be lenient with missing or extra fields
        ignoreUnknownKeys = true

        // Allow for flexibility in JSON structure
        isLenient = true

        // Use default values for missing fields
        coerceInputValues = true

        // Encode null values by default (for completeness)
        encodeDefaults = false
    }

    fun parse(jsonString: String): Result<MsfCatalog> {
        return try {
            val catalog = json.decodeFromString<MsfCatalog>(jsonString)
            Result.success(catalog)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun serialize(catalog: MsfCatalog): Result<String> {
        return try {
            val jsonString = json.encodeToString(MsfCatalog.serializer(), catalog)
            Result.success(jsonString)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
