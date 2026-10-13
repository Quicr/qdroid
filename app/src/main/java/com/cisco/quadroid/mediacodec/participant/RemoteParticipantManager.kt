// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.mediacodec.participant

import android.content.Context
import android.os.Handler
import android.os.SystemClock
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

    // Top-N display cap. When non-null, at most this many participant tiles are shown, keeping
    // the most-recently-active ones (the relay already forwards only the top-N, so this is a
    // client-side safeguard against transient over-delivery during speaker swaps). Accessed on
    // the main looper where the participant list is published.
    @Volatile
    private var maxDisplayCount: Int? = null

    // Native-sourced signals (set by VideoSessionManager), keyed by FULL track name:
    //  - activityProvider: latest Top-N activity value (0/1/2, -1 if none) seen on that track.
    //  - idleMsProvider: ms since the relay last forwarded an object for that track.
    // Both read the native per-track map populated in ObjectReceived, so they see EVERY forwarded
    // track and its activity — even one that has no jitter buffer / tile yet. This lets the client
    // display exactly what the relay forwards, ranked by activity, instead of a buffer-gated subset.
    var activityProvider: ((String) -> Int)? = null
    var idleMsProvider: ((String) -> Long)? = null

    // Last time (ms) each track was a speaker (activity > 0), for the recency tiebreak in ranking.
    private val lastActiveMs = ConcurrentHashMap<String, Long>()

    // Persistent display-slot assignment (trackKey per slot, null = vacant), so unchanged speakers
    // keep their on-screen position and only a vacated slot is reused (anti-flicker). Main looper.
    private var slotAssignments: List<String?> = emptyList()

    // A tile is hidden once its track has been idle (no forwarded object) for this long, freeing the
    // slot; the periodic sweep also re-ranks so a newly-forwarded speaker swaps in.
    private val stalenessHandler = Handler(context.mainLooper)
    private var stalenessScheduled = false
    // Must be LONGER than the relay's forwarding timeout (topNTimeoutMs = 2000ms): the relay keeps
    // forwarding a track for ~2s after it goes inactive, so hiding before then (and re-showing when
    // it resumes) causes the tile to remount — and with the lazy jitter buffer, each remount is
    // black until the next keyframe, i.e. a flickering black screen. 3s gives a safe hysteresis
    // margin so a tile is only dropped once the relay has genuinely stopped forwarding it.
    private val stalenessThresholdMs = 3000L
    private val stalenessCheckIntervalMs = 500L

    private val stalenessRunnable = object : Runnable {
        override fun run() {
            checkStaleParticipants()
            if (stalenessScheduled) stalenessHandler.postDelayed(this, stalenessCheckIntervalMs)
        }
    }

    /**
     * Sets the maximum number of participant tiles to display (Top-N), or null for no cap.
     * Re-clamps the currently displayed list on the main looper.
     */
    fun setMaxDisplayCount(count: Int?) {
        maxDisplayCount = count
        stalenessHandler.post {
            rebuildDisplay()
            // Staleness hiding only makes sense when the grid is capped (Top-N on).
            if (count != null) startStalenessChecks() else stopStalenessChecks()
        }
    }

    private fun startStalenessChecks() {
        if (stalenessScheduled) return
        stalenessScheduled = true
        stalenessHandler.postDelayed(stalenessRunnable, stalenessCheckIntervalMs)
    }

    private fun stopStalenessChecks() {
        stalenessScheduled = false
        stalenessHandler.removeCallbacks(stalenessRunnable)
    }

    /** Periodic staleness sweep: just rebuilds the display (staleness is handled in rebuild). */
    private fun checkStaleParticipants() {
        if (maxDisplayCount == null) return
        rebuildDisplay()
    }

    /**
     * Rebuilds the displayed tile list from authoritative state ([remoteParticipantStates]): one
     * candidate per participant (their active track), then — when Top-N caps the grid — hides stale
     * tiles (relay swapped the speaker out) and arranges the rest into stable slots ranked by voice
     * activity. Must run on the main looper.
     */
    private fun rebuildDisplay() {
        val now = SystemClock.elapsedRealtime()
        val activityByKey = HashMap<String, Int>()
        val lastSeenByKey = HashMap<String, Long>()
        val candidates = remoteParticipantStates.values.mapNotNull { state ->
            val key = state.activeTrackKey ?: return@mapNotNull null
            val track = state.videoTracks.find { it.trackKey == key }
            val aspect = if (track != null && track.displayHeight > 0)
                track.displayWidth.toFloat() / track.displayHeight.toFloat() else 1f
            val fullName = track?.fullTrackName
            val act = if (fullName != null) (activityProvider?.invoke(fullName) ?: -1) else -1
            val idle = if (fullName != null) (idleMsProvider?.invoke(fullName) ?: Long.MAX_VALUE) else Long.MAX_VALUE
            activityByKey[key] = act
            // Synthetic last-seen in the `now` domain so (now - lastSeen) == idle for freshKept.
            lastSeenByKey[key] = now - idle
            if (act > 0) lastActiveMs[key] = now
            ParticipantStream(key, aspect)
        }

        val cap = maxDisplayCount
        if (cap == null) {
            _remoteParticipants.value = candidates
            return
        }

        // Keep only tracks the relay is currently forwarding (recent objects); blank-tile guard in
        // freshKept keeps everything if fewer than cap are fresh so a hiccup can't empty the grid.
        val keepIds = freshKept(candidates.map { it.id }, lastSeenByKey, now, stalenessThresholdMs, cap).toSet()
        val visible = candidates.filter { it.id in keepIds }

        val (newSlots, ordered) = arrangeSlots(visible, activityByKey, lastActiveMs, slotAssignments, cap)
        slotAssignments = newSlots
        _remoteParticipants.value = ordered
        Log.d(tag, "rebuildDisplay cand=${candidates.size} order=${ordered.map { "${it.id.takeLast(6)}=act${activityByKey[it.id] ?: -1}" }}")
    }

    companion object {
        /**
         * Pure ranking + slot stabilization (design §4.2/§B.4). Ranks [candidates] by activity
         * (desc), then last-active (desc), then id; selects the top [cap]; keeps selected tiles in
         * their existing [currentSlots] positions and assigns newcomers to the first vacant slot, so
         * unchanged speakers don't move on screen. Returns (newSlots, orderedStreams).
         */
        fun arrangeSlots(
            candidates: List<ParticipantStream>,
            activity: Map<String, Int>,
            lastActive: Map<String, Long>,
            currentSlots: List<String?>,
            cap: Int
        ): Pair<List<String?>, List<ParticipantStream>> {
            val byId = candidates.associateBy { it.id }

            // Incumbent hysteresis (anti-flicker): a track currently in a slot KEEPS it as long as
            // it is still a candidate AND still speaking (activity >= continuousSpeech). This stops
            // a shown active speaker from being evicted by another participant's momentary
            // speechStart (2 > 1) blip — the cause of tile flapping at small N. An incumbent is only
            // released when it leaves (no longer forwarded) or goes silent (activity 0).
            val slots = MutableList<String?>(cap) { currentSlots.getOrNull(it) }
            for (i in slots.indices) {
                val id = slots[i] ?: continue
                val keep = byId.containsKey(id) && (activity[id] ?: 0) >= 1
                if (!keep) slots[i] = null
            }
            val held = slots.filterNotNull().toMutableSet()

            // Fill vacant slots with the best remaining candidates: activity desc, recency, id.
            val challengers = candidates
                .filter { it.id !in held }
                .sortedWith(
                    compareByDescending<ParticipantStream> { activity[it.id] ?: 0 }
                        .thenByDescending { lastActive[it.id] ?: 0L }
                        .thenBy { it.id }
                )
            var ci = 0
            for (i in slots.indices) {
                if (slots[i] == null && ci < challengers.size) {
                    slots[i] = challengers[ci].id
                    held.add(challengers[ci].id)
                    ci++
                }
            }
            val ordered = slots.mapNotNull { id -> id?.let { byId[it] } }
            return slots to ordered
        }

        /**
         * Pure staleness decision: returns the [ids] to keep. An id is "fresh" if its last frame
         * time (from [lastFrameMs]) is within [thresholdMs] of [now]. Keeps everything (returns
         * [ids] unchanged) unless at least [cap] ids are fresh — the blank-tile guard, so a
         * network hiccup can't empty the grid — in which case stale ids are dropped.
         */
        fun freshKept(
            ids: List<String>,
            lastFrameMs: Map<String, Long>,
            now: Long,
            thresholdMs: Long,
            cap: Int?
        ): List<String> {
            if (cap == null || ids.isEmpty()) return ids
            fun fresh(id: String): Boolean {
                val last = lastFrameMs[id] ?: return false
                return (now - last) <= thresholdMs
            }
            val freshCount = ids.count { fresh(it) }
            if (freshCount < cap) return ids
            return ids.filter { fresh(it) }
        }
    }

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
        Log.i(tag, "updateRemoteParticipantUI: participantId=$participantId, activeTrackKey=$activeTrackKey")
        // Display selection is derived from authoritative state + native forwarding signals in
        // rebuildDisplay; just trigger a rebuild.
        stalenessHandler.post { rebuildDisplay() }
    }

    fun clear() {
        stopStalenessChecks()
        _remoteParticipants.value = emptyList()
        remoteParticipantStates.clear()
        lastActiveMs.clear()
        slotAssignments = emptyList()
        Log.d(tag, "Cleared all remote participants")
    }
}
