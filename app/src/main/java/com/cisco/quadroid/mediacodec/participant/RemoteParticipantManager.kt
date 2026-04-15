package com.cisco.quadroid.mediacodec.participant

import android.content.Context
import android.os.Handler
import android.util.Log
import com.cisco.catalog.MsfTrack
import com.cisco.quadroid.mediacodec.catalog.CatalogManager
import com.cisco.quadroid.mediacodec.model.ParticipantStream
import com.cisco.quadroid.mediacodec.model.RemoteParticipantState
import com.cisco.quadroid.mediacodec.model.RemoteVideoTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class RemoteParticipantManager(
    private val context: Context,
    private val catalogManager: CatalogManager
) {
    private val tag = "RemoteParticipantManager"

    private val remoteParticipantStates = ConcurrentHashMap<String, RemoteParticipantState>()

    private val _remoteParticipants = MutableStateFlow<List<ParticipantStream>>(emptyList())
    val remoteParticipants: StateFlow<List<ParticipantStream>> = _remoteParticipants.asStateFlow()

    /**
     * Extract participant ID from track name namespace.
     * Uses the last tuple of the namespace before the track name.
     * E.g., "webex.com/meeting123/bob/video" -> "bob"
     */
    fun extractParticipantId(trackName: String): String {
        val parts = trackName.split("/")
        // Get the second to last part (last part is track name like "video" or "audio")
        return if (parts.size >= 2) {
            parts[parts.size - 2]
        } else {
            trackName // Fallback to full track name if parsing fails
        }
    }

    /**
     * Find a catalog track by matching the track name.
     * Handles both exact matches and suffix-based matching.
     */
    fun findCatalogTrackByName(trackName: String): MsfTrack? {
        Log.v(tag, "findCatalogTrackByName: searching for $trackName")

        val catalogTracks = catalogManager.catalogTracks
        val catalogTrackNamesUrl = catalogManager.catalogTrackNamesUrl

        // Try exact match first (unlikely for remote tracks, but possible for local)
        catalogTracks.forEachIndexed { index, track ->
            val trackUrl = catalogTrackNamesUrl.getOrNull(index)
            if (trackUrl == trackName) {
                Log.d(tag, "Found exact match: ${track.name}")
                return track
            }
        }

        // For video tracks, prioritize dimension matching to distinguish between different resolutions
        // Track names contain resolution in path: .../avc1/1080/... or .../avc1/720/...
        if (trackName.contains("video")) {
            val inferredHeight = when {
                trackName.contains("/1080/") || trackName.contains("1080p") -> 1080
                trackName.contains("/720/") || trackName.contains("720p") -> 720
                trackName.contains("/360/") || trackName.contains("360p") -> 360
                else -> null
            }

            if (inferredHeight != null) {
                val dimensionMatch = catalogTracks.find { track ->
                    track.role == "video" && track.height == inferredHeight
                }
                if (dimensionMatch != null) {
                    Log.d(tag, "Found dimension match: ${dimensionMatch.name} with height=$inferredHeight for trackName=$trackName")
                    return dimensionMatch
                }
            }
        }

        // Fallback: Match by track name suffix
        // Remote: "cisco.webex.com/nab/v1/publisher_remoteId/video_1080p"
        // Catalog: track.name = "video_1080p" or "video"
        val trackNameSuffix = trackName.substringAfterLast("/")
        Log.v(tag, "Trying suffix match with: $trackNameSuffix")

        // First try: exact suffix match
        val exactMatch = catalogTracks.find { track ->
            track.name == trackNameSuffix
        }
        if (exactMatch != null) {
            Log.d(tag, "Found suffix match: ${exactMatch.name}")
            return exactMatch
        }

        // Second try: check if track name ends with catalog track name
        // This handles cases like track="video" matching trackName="...publisher_123/video"
        val endsWithMatch = catalogTracks.find { track ->
            trackName.endsWith("/${track.name}")
        }
        if (endsWithMatch != null) {
            Log.d(tag, "Found endsWith match: ${endsWithMatch.name}")
            return endsWithMatch
        }

        Log.w(tag, "No catalog track match found for: $trackName")
        return null
    }

    /**
     * Add a video track for a participant
     */
    fun addVideoTrack(participantId: String, track: RemoteVideoTrack) {
        val participantState = remoteParticipantStates.getOrPut(participantId) {
            RemoteParticipantState(participantId = participantId)
        }
        participantState.videoTracks.add(track)
        participantState.videoTracks.sortBy { it.priority }

        Log.i(
            tag,
            "Added video track for $participantId: priority=${track.priority}, ${track.displayWidth}x${track.displayHeight}"
        )
    }

    /**
     * Set audio track for a participant
     */
    fun setAudioTrack(participantId: String, trackKey: String) {
        val participantState = remoteParticipantStates.getOrPut(participantId) {
            RemoteParticipantState(participantId = participantId)
        }
        participantState.audioTrackKey = trackKey
        Log.i(tag, "Set audio track for $participantId: $trackKey")
    }

    /**
     * Handle incoming video object for adaptive quality switching.
     * Updates track state and switches to higher/lower quality as needed.
     */
    fun handleRemoteVideoObject(participantId: String, trackKey: String, objectId: Long) {
        Log.v(tag, "handleRemoteVideoObject: participantId=$participantId, trackKey=$trackKey, objectId=$objectId")

        val participantState = remoteParticipantStates[participantId]
        if (participantState == null) {
            Log.e(tag, "No participant state found for participantId=$participantId")
            Log.e(tag, "Available participants: ${remoteParticipantStates.keys.joinToString()}")
            return
        }

        // Find the track that received the object
        val receivingTrack = participantState.videoTracks.find { it.trackKey == trackKey }
        if (receivingTrack == null) {
            Log.e(tag, "Track not found: trackKey=$trackKey for participantId=$participantId")
            Log.e(tag, "Available tracks: ${participantState.videoTracks.map { it.trackKey }.joinToString()}")
            return
        }

        // Update track state
        receivingTrack.isReceivingObjects = true
        receivingTrack.consecutiveFramesReceived++

        Log.d(tag, "Track $trackKey receiving: consecutiveFrames=${receivingTrack.consecutiveFramesReceived}, priority=${receivingTrack.priority}")

        // Adaptive quality switching logic
        val currentActiveKey = participantState.activeTrackKey

        if (currentActiveKey == null) {
            // No active track yet - select the highest priority (lowest priority number) track
            // videoTracks is already sorted by priority, so first one is best
            val bestTrack = participantState.videoTracks.firstOrNull()
            if (bestTrack != null) {
                Log.e("QUADROID_DEBUG", "📺 Selecting BEST track: ${bestTrack.trackKey}, priority=${bestTrack.priority}")
                Log.i(tag, "No active track, selecting best: trackKey=${bestTrack.trackKey}, priority=${bestTrack.priority}")
                switchToTrack(participantState, bestTrack.trackKey)
                Log.i(
                    tag,
                    "Initial track selection for $participantId: priority=${bestTrack.priority}, ${bestTrack.displayWidth}x${bestTrack.displayHeight}"
                )
            } else {
                Log.w(tag, "No tracks available for participant $participantId")
            }
        } else {
            val currentTrack = participantState.videoTracks.find { it.trackKey == currentActiveKey }

            // Special case for initial discovery (objectId==0): if a better track is discovered, switch immediately
            if (objectId == 0L && receivingTrack.priority < (currentTrack?.priority ?: Int.MAX_VALUE)) {
                Log.e("QUADROID_DEBUG", "📺 NEW BEST track discovered: ${receivingTrack.trackKey}, priority=${receivingTrack.priority} (better than current priority=${currentTrack?.priority})")
                Log.i(tag, "Switching to better track discovered during initial setup")
                switchToTrack(participantState, receivingTrack.trackKey)
                Log.i(
                    tag,
                    "Switched to better track for $participantId: ${currentTrack?.priority} -> ${receivingTrack.priority} (${receivingTrack.displayWidth}x${receivingTrack.displayHeight})"
                )
            }
            // Check if we should upgrade to higher quality (lower priority number) for real objects
            else if (objectId > 0L && receivingTrack.priority < (currentTrack?.priority ?: Int.MAX_VALUE)) {
                // Higher priority track is receiving - check if we have 5 consecutive frames
                if (receivingTrack.consecutiveFramesReceived >= 5) {
                    switchToTrack(participantState, receivingTrack.trackKey)
                    Log.i(
                        tag,
                        "Upgraded quality for $participantId: ${currentTrack?.priority} -> ${receivingTrack.priority} (${receivingTrack.displayWidth}x${receivingTrack.displayHeight})"
                    )
                }
            } else if (objectId > 0L && currentTrack != null && !currentTrack.isReceivingObjects) {
                // Current track stopped receiving - downgrade to next available quality
                val fallbackTrack = participantState.videoTracks
                    .filter { it.isReceivingObjects && it.priority > currentTrack.priority }
                    .minByOrNull { it.priority } // Get highest priority available fallback

                if (fallbackTrack != null) {
                    switchToTrack(participantState, fallbackTrack.trackKey)
                    Log.w(
                        tag,
                        "Downgraded quality for $participantId: ${currentTrack.priority} -> ${fallbackTrack.priority} (${fallbackTrack.displayWidth}x${fallbackTrack.displayHeight})"
                    )
                }
            }
        }

        // Reset consecutive frame count for tracks not receiving
        // Skip this logic when objectId==0 (initial selection trigger, not real objects)
        if (objectId > 0) {
            participantState.videoTracks.forEach { track ->
                if (track.trackKey != trackKey) {
                    // Mark as not receiving if we haven't seen objects recently
                    // This is simplified - a production implementation might use timestamps
                    if (track.consecutiveFramesReceived > 0) {
                        track.consecutiveFramesReceived--
                    }
                    if (track.consecutiveFramesReceived == 0) {
                        track.isReceivingObjects = false
                    }
                }
            }
        }
    }

    /**
     * Switch the active track for a participant and update UI.
     */
    private fun switchToTrack(participantState: RemoteParticipantState, newTrackKey: String) {
        val oldTrackKey = participantState.activeTrackKey
        participantState.activeTrackKey = newTrackKey

        val newTrack = participantState.videoTracks.find { it.trackKey == newTrackKey }
        if (newTrack != null) {
            // Update UI with new participant stream
            updateRemoteParticipantUI(
                participantState.participantId,
                newTrackKey,
                newTrack.displayWidth.toFloat() / newTrack.displayHeight.toFloat()
            )
        }

        // Reset consecutive frames for the new track
        newTrack?.consecutiveFramesReceived = 0
    }

    /**
     * Update the UI with the participant's active track.
     * Removes old tracks for this participant and adds the new active track.
     */
    private fun updateRemoteParticipantUI(participantId: String, activeTrackKey: String, aspectRatio: Float) {
        Log.i(tag, "updateRemoteParticipantUI: participantId=$participantId, activeTrackKey=$activeTrackKey, aspectRatio=$aspectRatio")

        // Capture participant track keys before posting to handler (thread safety)
        val participantState = remoteParticipantStates[participantId]
        val participantTrackKeys = participantState?.videoTracks?.map { it.trackKey } ?: emptyList()
        Log.d(tag, "Participant $participantId has ${participantTrackKeys.size} tracks: ${participantTrackKeys.joinToString()}")

        Handler(context.mainLooper).post {
            val currentParticipants = _remoteParticipants.value
            Log.d(tag, "Current participants before update: count=${currentParticipants.size}, ids=[${currentParticipants.map { it.id }.joinToString()}]")

            // Remove any existing streams for this participant's other tracks
            val filteredParticipants = currentParticipants.filterNot {
                participantTrackKeys.contains(it.id) && it.id != activeTrackKey
            }
            Log.d(tag, "After filtering: count=${filteredParticipants.size}, ids=[${filteredParticipants.map { it.id }.joinToString()}]")

            // Check if the active track is already in the list
            val existingParticipant = filteredParticipants.find { it.id == activeTrackKey }

            if (existingParticipant == null) {
                // Add new participant stream with the active track
                val stream = ParticipantStream(activeTrackKey, aspectRatio)
                val newParticipants = filteredParticipants + stream
                _remoteParticipants.value = newParticipants
                Log.e("QUADROID_DEBUG", "🎉 REMOTE PARTICIPANT ADDED TO UI!")
                Log.e("QUADROID_DEBUG", "   participantId: $participantId")
                Log.e("QUADROID_DEBUG", "   trackKey: $activeTrackKey")
                Log.e("QUADROID_DEBUG", "   Total count: ${_remoteParticipants.value.size}")
                Log.i(tag, "✅ Added remote participant UI: $participantId with trackKey: $activeTrackKey, aspectRatio: $aspectRatio")
                Log.i(tag, "✅ Total remote participants: ${_remoteParticipants.value.size}, ids=[${_remoteParticipants.value.map { it.id }.joinToString()}]")
            } else {
                // Track is already showing, just update the filtered list
                _remoteParticipants.value = filteredParticipants
                Log.d(tag, "Track $activeTrackKey already showing for $participantId")
            }
        }
    }

    fun clear() {
        _remoteParticipants.value = emptyList()
        remoteParticipantStates.clear()
        Log.d(tag, "Cleared all remote participants")
    }
}
