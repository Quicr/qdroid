package com.cisco.catalog

import kotlinx.serialization.Serializable

@Serializable
data class MsfCatalog(
    // Required fields
    val version: Int,

    // Optional fields - Root catalog
    val generatedAt: Long? = null,
    val isComplete: Boolean? = null,
    val tracks: List<MsfTrack>? = null,

    // Optional fields - Delta updates
    val deltaUpdate: Boolean? = null,
    val addTracks: List<MsfTrack>? = null,
    val removeTracks: List<MsfTrack>? = null,
    val cloneTracks: List<MsfTrack>? = null
) {
    fun isDeltaUpdate(): Boolean = deltaUpdate == true

    fun isCompleteCatalog(): Boolean = isComplete == true

    fun getAllTracks(): List<MsfTrack> = tracks ?: emptyList()

    fun getTracksToAdd(): List<MsfTrack> = addTracks ?: emptyList()

    fun getTracksToRemove(): List<MsfTrack> = removeTracks ?: emptyList()

    fun getTracksToClone(): List<MsfTrack> = cloneTracks ?: emptyList()
}
