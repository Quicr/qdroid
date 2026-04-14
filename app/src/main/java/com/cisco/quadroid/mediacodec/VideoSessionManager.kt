package com.cisco.quadroid.mediacodec

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.cisco.catalog.CatalogUpdateResult
import com.cisco.catalog.MoqCatalog
import com.cisco.catalog.MoqNameUtils
import com.cisco.catalog.MsfTrack
import com.cisco.nativeaudio.NativeAudioLib
import com.cisco.quadroid.transport.MoqAudioFramer
import com.cisco.quadroid.transport.MoqConnectionStatus
import com.cisco.quadroid.transport.MoqMediaFramer
import com.cisco.quadroid.transport.MoqNative
import com.cisco.quadroid.transport.MoqObjectCallback
import com.cisco.quadroid.transport.MoqTransport
import com.cisco.quadroid.transport.NamespaceSubscriptionCallback
import com.cisco.quadroid.transport.PublishOptions
import com.cisco.quadroid.transport.VideoFrame
import com.cisco.quadroid.transport.VideoJitterBufferCallback
import com.cisco.quadroid.util.DeviceIdentifier
import com.cisco.quadroid.util.TrackUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moqTransport: MoqTransport
) {
    private val tag = "VideoSessionManager"

    // Coroutine scope for observing connection status
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Video Config - Will be populated from catalog
    private val videoMimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    private val iFrameInterval = 2

    // Multi-quality video encoder configuration
    data class VideoEncoderConfig(
        val track: MsfTrack,
        val trackNameUrl: String,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val framerate: Int,
        val priority: Int // 1 = highest (1080p), 2 = medium (720p), 3 = lowest (360p)
    )

    private val videoEncoderConfigs = mutableListOf<VideoEncoderConfig>()

    private val nativeAudioLib = NativeAudioLib()
    private var isMicEnabled = true

    private var cameraProvider: ProcessCameraProvider? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var rotation: Int = 0
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    
    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    // Multi-encoder support - one encoder per quality level
    data class EncoderState(
        val encoder: MediaCodec,
        val inputSurface: Surface,
        val config: VideoEncoderConfig,
        val framer: MoqMediaFramer,
        var isActive: Boolean = false // Track if encoder is currently receiving camera input
    )

    private val encoders = mutableListOf<EncoderState>()
    private var activeEncoderIndex = 0 // Index of currently active encoder (for camera binding)
    private var consecutiveGoodFrames = 0 // Track consecutive successful encodes for quality upgrade
    private var lastEncoderError = 0L // Timestamp of last encoder error
    private val encoderThread = HandlerThread("VideoSessionManager_Encoder").apply { start() }
    private val encoderHandler = Handler(encoderThread.looper)

    // Monitoring for jitter buffer statistics
    private val monitoringHandler = Handler(android.os.Looper.getMainLooper())
    private var isMonitoring = false

    @Volatile
    private var encoderOutputFormat: MediaFormat? = null
    private var formatLatch = CountDownLatch(1) // No longer used with multi-encoder

    // Listeners for raw video objects
    private val videoFrameListeners = ConcurrentHashMap<String, (ByteArray, Long) -> Unit>()

    // Remote participant tracking with multi-quality support
    data class RemoteVideoTrack(
        val trackKey: String,
        val fullTrackName: String,
        val priority: Int, // 1=highest, 2=medium, 3=lowest
        val displayWidth: Int,
        val displayHeight: Int,
        var isReceivingObjects: Boolean = false,
        var consecutiveFramesReceived: Int = 0
    )

    data class RemoteParticipantState(
        val participantId: String,
        val videoTracks: MutableList<RemoteVideoTrack> = mutableListOf(), // sorted by priority
        var activeTrackKey: String? = null, // Currently displayed track
        var audioTrackKey: String? = null
    )

    private val remoteParticipantStates = ConcurrentHashMap<String, RemoteParticipantState>()

    private val _remoteParticipants = MutableStateFlow<List<ParticipantStream>>(emptyList())
    val remoteParticipants: StateFlow<List<ParticipantStream>> = _remoteParticipants.asStateFlow()

    private val _videoAspectRatio = MutableStateFlow(9f / 16f) // Default portrait aspect ratio
    val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

    private var localPreviewSurface: Surface? = null

    private var audioFramer: MoqAudioFramer? = null

    // Catalog state management
    private val moqCatalog = MoqCatalog()
    private val _isCatalogReady = MutableStateFlow(false)
    val isCatalogReady: StateFlow<Boolean> = _isCatalogReady.asStateFlow()

    private var catalogVersion: Int = 0
    private val catalogTracks = mutableListOf<MsfTrack>()
    private val catalogTrackNamesUrl = mutableListOf<String>()
    private val catalogNamespacesUrl = mutableListOf<String>()
    private var catalogSubscribed = false

    // Meeting Configuration
    private val meetingId = "meeting123"
    private val meetingNamespace = "webex.com/$meetingId"

    // Each device publishes under its own unique device ID
    private val deviceId = DeviceIdentifier.get(context)
    private val userName = "alice"
    private val userId = "carlos"

    // Note: Track names are now defined in the catalog, not hardcoded here

    val connectionStatus: StateFlow<MoqConnectionStatus> = moqTransport.connectionStatus

    init {
        Log.e("QUADROID_DEBUG", "VideoSessionManager INIT - deviceId=$deviceId")
        Log.e(tag, "⭐ VideoSessionManager initialized with deviceId=$deviceId, meetingId=$meetingId")

        // Observe connection status and subscribe to catalog when connected
        scope.launch {
            connectionStatus.collect { status ->
                Log.e("QUADROID_DEBUG", "Connection status: $status")
                Log.d(tag, "Connection status changed to: $status")
                if (status == MoqConnectionStatus.CONNECTED && !catalogSubscribed) {
                    Log.i(tag, "Connection established, subscribing to catalog track")
                    subscribeToCatalogTrack()
                    catalogSubscribed = true
                } else if (status == MoqConnectionStatus.DISCONNECTED || status == MoqConnectionStatus.ERROR) {
                    // Reset catalog subscription flag on disconnect
                    catalogSubscribed = false
                }
            }
        }
    }

    fun connectToRelay(url: String) {
        moqTransport.connect(url, deviceId)
    }

    fun disconnectFromRelay() {
        moqTransport.disconnect()
    }

    // Store mapping from trackKey to full track name for jitter buffer lookup
    private val trackKeyToFullName = mutableMapOf<String, String>()

    // Monitoring runnable for periodic jitter buffer statistics
    private val monitoringRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring) return

            val moqNative = moqTransport as? MoqNative
            if (moqNative != null) {
                // Monitor video jitter buffers
                videoFrameListeners.keys.forEach { trackKey ->
                    val fullTrackName = trackKeyToFullName[trackKey]
                    if (fullTrackName != null) {
                        val stats = moqNative.nativeGetVideoJitterBufferStats(fullTrackName)
                        if (stats != null) {
                            // Log warning if frame drop rate is significant
                            if (stats.framesDropped > 0) {
                                Log.w(tag, "Video Buffer [$trackKey]: recv=${stats.framesReceived}, " +
                                    "out=${stats.framesOutput}, drop=${stats.framesDropped} " +
                                    "(${String.format("%.1f", stats.dropRatePercent)}%), " +
                                    "avgLat=${String.format("%.1f", stats.avgLatencyMs)}ms, " +
                                    "maxLat=${String.format("%.1f", stats.maxLatencyMs)}ms")
                            }
                            // Log info periodically even if no drops
                            else if (stats.framesOutput > 0) {
                                Log.i(tag, "Video Buffer [$trackKey]: recv=${stats.framesReceived}, " +
                                    "out=${stats.framesOutput}, " +
                                    "avgLat=${String.format("%.1f", stats.avgLatencyMs)}ms")
                            }
                        }
                    }
                }

                // Monitor audio jitter buffers
                trackKeyToFullName.forEach { (trackKey, fullTrackName) ->
                    if (fullTrackName.contains("audio")) {
                        val stats = moqNative.nativeGetAudioJitterBufferStats(fullTrackName)
                        if (stats != null) {
                            // Log warning if packet drop rate is significant
                            if (stats.packetsDropped > 0) {
                                Log.w(tag, "Audio Buffer [$trackKey]: recv=${stats.packetsReceived}, " +
                                    "out=${stats.packetsOutput}, drop=${stats.packetsDropped} " +
                                    "(${String.format("%.1f", stats.dropRatePercent)}%), " +
                                    "avgLat=${String.format("%.1f", stats.avgLatencyMs)}ms, " +
                                    "maxLat=${String.format("%.1f", stats.maxLatencyMs)}ms")
                            }
                            // Log info periodically even if no drops
                            else if (stats.packetsOutput > 0) {
                                Log.i(tag, "Audio Buffer [$trackKey]: recv=${stats.packetsReceived}, " +
                                    "out=${stats.packetsOutput}, " +
                                    "avgLat=${String.format("%.1f", stats.avgLatencyMs)}ms")
                            }
                        }
                    }
                }
            }

            // Schedule next check in 5 seconds
            if (isMonitoring) {
                monitoringHandler.postDelayed(this, 5000)
            }
        }
    }

    private fun subscribeToCatalogTrack() {


        Log.i(
            tag,
            "Subscribing to catalog track: $moqCatalog.catalogTrackUrl (safe form: $moqCatalog.catalogTrackSafeForm)"
        )

        // Generate track key for the catalog track
        val catalogTrackKey = TrackUtil.generateTrackKeyFromFullName(moqCatalog.catalogTrackUrl)

        moqTransport.trackCallbacks[catalogTrackKey] = object : MoqObjectCallback {
            override fun onObject(
                trackName: String,
                groupId: Long,
                objectId: Long,
                payload: ByteBuffer
            ) {
                Log.d(
                    tag,
                    "Received catalog object: trackName=$trackName, groupId=$groupId, objectId=$objectId, size=${payload.remaining()}"
                )

                // Parse the JSON payload
                val bytes = ByteArray(payload.remaining())
                payload.get(bytes)
                val jsonString = String(bytes, StandardCharsets.UTF_8)

                Log.d(tag, "Catalog JSON: $jsonString")

                // Update catalog with the JSON
                val updateResult = moqCatalog.updateCatalog(jsonString)

                if (updateResult.isSuccess) {
                    val result = updateResult.getOrNull()!!
                    handleCatalogUpdate(result)
                } else {
                    Log.e(
                        tag,
                        "Failed to parse catalog: ${updateResult.exceptionOrNull()?.message}"
                    )
                }
            }
        }

        // Subscribe to the catalog track
        moqTransport.subscribe(moqCatalog.catalogTrackUrl, callback = object : MoqObjectCallback {
            override fun onObject(
                trackName: String,
                groupId: Long,
                objectId: Long,
                payload: ByteBuffer
            ) {
                Log.d(
                    tag,
                    "Received catalog object: trackName=$trackName, groupId=$groupId, objectId=$objectId, size=${payload.remaining()}"
                )

                // Parse the JSON payload
                val bytes = ByteArray(payload.remaining())
                payload.get(bytes)
                val jsonString = String(bytes, StandardCharsets.UTF_8)

                Log.d(tag, "Catalog JSON: $jsonString")

                // Update catalog with the JSON
                val updateResult = moqCatalog.updateCatalog(jsonString)

                if (updateResult.isSuccess) {
                    val result = updateResult.getOrNull()!!
                    handleCatalogUpdate(result)
                } else {
                    Log.e(
                        tag,
                        "Failed to parse catalog: ${updateResult.exceptionOrNull()?.message}"
                    )
                }
            }
        })
    }

    private fun handleCatalogUpdate(result: CatalogUpdateResult) {
        Log.i(
            tag, "Catalog updated: version=${result.version}, tracks=${result.totalTracks}, " +
                    "added=${result.tracksAdded}, removed=${result.tracksRemoved}, " +
                    "refreshed=${result.refreshed}, isDelta=${result.isDelta}"
        )

        // Check if version changed
        val versionChanged = catalogVersion != result.version
        catalogVersion = result.version

        if (versionChanged || result.refreshed) {
            // Update catalog tracks and namespaces
            updateCatalogTracksAndNamespaces()
        }

        // Mark catalog as ready
        if (!_isCatalogReady.value) {
            _isCatalogReady.value = true
            Log.i(tag, "Catalog is now ready")
        }
    }

    private fun updateCatalogTracksAndNamespaces() {
        val tracks = moqCatalog.getTracks()

        // Clear existing lists
        catalogTracks.clear()
        catalogTrackNamesUrl.clear()
        catalogNamespacesUrl.clear()

        // Collect unique namespaces
        val namespacesSet = mutableSetOf<String>()

        // Process tracks
        tracks.forEach { track ->
            catalogTracks.add(track)

            // Build track name in URL format
            val namespace = track.namespace
            val trackName = track.name

            if (namespace != null) {
                var namespaceUrl = MoqNameUtils.safeFormToUrl(namespace)

                // Replace publisher_{id} with publisher_{device_id} for local tracks (not catalog track)
                // Catalog track is identified by having "catalog" as the track name
                if (trackName != "catalog") {
                    // Replace publisher_<any_id> with publisher_<device_id>
                    namespaceUrl = namespaceUrl.replace(
                        Regex("publisher_[^/]+"),
                        "publisher_$deviceId"
                    )
                }

                val trackFullNameUrl = "$namespaceUrl/$trackName"
                catalogTrackNamesUrl.add(trackFullNameUrl)
                val subscribeNamespace = namespaceUrl.substringBeforeLast("/")
                namespacesSet.add(subscribeNamespace)
            } else {
                catalogTrackNamesUrl.add(trackName)
            }

            Log.d(
                tag,
                "Catalog track: $trackName, namespace: $namespace, codec: ${track.codec}, role: ${track.role}, url: ${catalogTrackNamesUrl.lastOrNull()}"
            )
        }

        // Store namespaces
        catalogNamespacesUrl.addAll(namespacesSet)

        Log.i(
            tag,
            "Updated catalog: ${catalogTracks.size} tracks, ${catalogNamespacesUrl.size} namespaces"
        )
        Log.i(tag, "Device ID: $deviceId")
        Log.i(tag, "Local track URLs: ${catalogTrackNamesUrl.filter { !it.contains("catalog") }.joinToString(", ")}")

        // Log all catalog tracks for debugging
        catalogTracks.forEachIndexed { index, track ->
            Log.d(tag, "Catalog[$index]: name=${track.name}, role=${track.role}, codec=${track.codec}, ${track.width}x${track.height}, namespace=${track.namespace}")
        }

        // Parse video tracks and setup encoder configs
        parseVideoEncoderConfigs()
    }

    private fun parseVideoEncoderConfigs() {
        videoEncoderConfigs.clear()

        // Find all video tracks for the local user from catalog
        catalogTracks.forEachIndexed { index, track ->
            val trackNameUrl = catalogTrackNamesUrl.getOrNull(index)
            if (trackNameUrl != null && track.role == "video" && track.codec != null) {
                val width = track.width ?: 1280
                val height = track.height ?: 720
                val bitrate = track.bitrate ?: 2000000
                val framerate = track.framerate?.toInt() ?: 30

                // Determine priority based on height
                val priority = when {
                    height >= 1080 -> 1 // 1080p - highest priority
                    height >= 720 -> 2  // 720p - medium priority
                    else -> 3            // 360p or lower - lowest priority
                }

                val config = VideoEncoderConfig(
                    track = track,
                    trackNameUrl = trackNameUrl,
                    width = width,
                    height = height,
                    bitrate = bitrate,
                    framerate = framerate,
                    priority = priority
                )

                videoEncoderConfigs.add(config)
                Log.i(
                    tag,
                    "Parsed video encoder config: ${track.name}, ${width}x${height}, ${bitrate}bps, ${framerate}fps, priority=$priority"
                )
            }
        }

        // Sort by priority (highest first)
        videoEncoderConfigs.sortBy { it.priority }

        Log.i(tag, "Configured ${videoEncoderConfigs.size} video encoders")
    }

    /**
     * Extract participant ID from track name namespace.
     * Uses the last tuple of the namespace before the track name.
     * E.g., "webex.com/meeting123/bob/video" -> "bob"
     */
    private fun extractParticipantId(trackName: String): String {
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
    private fun findCatalogTrackByName(trackName: String): MsfTrack? {
        Log.v(tag, "findCatalogTrackByName: searching for $trackName")

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
     * Handle incoming video object for adaptive quality switching.
     * Updates track state and switches to higher/lower quality as needed.
     */
    private fun handleRemoteVideoObject(participantId: String, trackKey: String, objectId: Long) {
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

    fun addVideoFrameListener(trackKey: String, listener: (ByteArray, Long) -> Unit) {
        Log.d(tag, "addVideoFrameListener for $trackKey")
        videoFrameListeners[trackKey] = listener

        // Create native jitter buffer for this track using full track name
        val moqNative = moqTransport as? MoqNative
        if (moqNative != null) {
            // Get full track name for jitter buffer lookup in C++
            val fullTrackName = trackKeyToFullName[trackKey]
            if (fullTrackName == null) {
                Log.w(tag, "No full track name found for trackKey $trackKey, jitter buffer not created")
                return
            }

            val callback = object : VideoJitterBufferCallback {
                override fun onFramesReady(trackName: String, frames: Array<VideoFrame>) {
                    val frameListener = videoFrameListeners[trackKey] ?: return

                    for (frame in frames) {
                        // Pass frames to decoder
                        // shouldRender flag is informational - we pass all frames to maintain decoder state
                        frameListener(frame.data, frame.ptsUs)
                    }

                    // Log periodically for monitoring
                    if (frames.isNotEmpty() && frames[0].objectId % 100 == 0L) {
                        Log.v(tag, "[$trackKey] Delivered ${frames.size} frames from jitter buffer")
                    }
                }
            }

            // Use full track name (not hashed key) for C++ jitter buffer lookup
            moqNative.nativeCreateVideoJitterBuffer(fullTrackName, callback)
            Log.i(tag, "Created jitter buffer for track $trackKey (fullName: $fullTrackName)")
        } else {
            Log.w(tag, "MoqTransport is not MoqNative, jitter buffer not available")
        }
    }

    fun removeVideoFrameListener(trackKey: String) {
        Log.d(tag, "removeVideoFrameListener for $trackKey")

        // Destroy native jitter buffer using full track name
        val moqNative = moqTransport as? MoqNative
        val fullTrackName = trackKeyToFullName[trackKey]
        if (fullTrackName != null) {
            moqNative?.nativeDestroyVideoJitterBuffer(fullTrackName)
            // Don't remove mapping - keep it for when renderer is recreated
            // The mapping will be cleared when the session ends in cleanup()
        }

        videoFrameListeners.remove(trackKey)
    }

    private fun startJitterBufferMonitoring() {
        if (!isMonitoring) {
            isMonitoring = true
            monitoringHandler.postDelayed(monitoringRunnable, 5000) // Start after 5 seconds
            Log.d(tag, "Started jitter buffer monitoring")
        }
    }

    private fun stopJitterBufferMonitoring() {
        if (isMonitoring) {
            isMonitoring = false
            monitoringHandler.removeCallbacks(monitoringRunnable)
            Log.d(tag, "Stopped jitter buffer monitoring")
        }
    }

    fun startSession(lifecycleOwner: LifecycleOwner, rotation: Int, relayUrl: String) {
        Log.e("QUADROID_DEBUG", "╔════════════════════════════════════════╗")
        Log.e("QUADROID_DEBUG", "║    START SESSION CALLED               ║")
        Log.e("QUADROID_DEBUG", "╚════════════════════════════════════════╝")
        Log.e(tag, "⭐ startSession: relayUrl=$relayUrl, deviceId=$deviceId")

        // Save existing track mappings before cleanup (for rejoin scenario)
        val savedTrackMappings = trackKeyToFullName.toMap()

        cleanup()

        // Restore track mappings after cleanup (allows rejoin to work when remote tracks
        // were already published - relay doesn't replay PUBLISH messages on resubscribe)
        trackKeyToFullName.putAll(savedTrackMappings)

        Log.i(tag, "Starting session with deviceId: $deviceId")
        Log.i(tag, "Local tracks from catalog: ${catalogTrackNamesUrl.joinToString(", ")}")
        /*
        if (savedTrackMappings.isNotEmpty()) {
            Log.i(tag, "Restored ${savedTrackMappings.size} track mappings from previous session (rejoin)")

            // Resubscribe to tracks that were in the previous session
            // This is needed because we unsubscribed from them in stopSession()
            savedTrackMappings.forEach { (trackKey, fullTrackName) ->
                val callback = moqTransport.trackCallbacks[trackKey]
                if (callback != null) {
                    Log.i(tag, "Resubscribing to track on rejoin: $fullTrackName")
                    moqTransport.subscribe(fullTrackName, callback)

                    // Recreate audio jitter buffer if this is an audio track
                    if (fullTrackName.contains("audio")) {
                        val moqNative = moqTransport as? MoqNative
                        moqNative?.nativeCreateAudioJitterBuffer(fullTrackName, trackKey)
                        Log.i(tag, "Recreated audio jitter buffer for track $trackKey (fullName: $fullTrackName)")
                    }

                    // Recreate video jitter buffer if this is a video track
                    // Must create BEFORE packets arrive to avoid "No jitter buffer found" error
                    if (fullTrackName.contains("video")) {
                        val moqNative = moqTransport as? MoqNative
                        if (moqNative != null) {
                            // Create jitter buffer with callback (will be used when UI listener registers)
                            val jitterBufferCallback = object : VideoJitterBufferCallback {
                                override fun onFramesReady(trackName: String, frames: Array<VideoFrame>) {
                                    val frameListener = videoFrameListeners[trackKey]
                                    if (frameListener != null) {
                                        for (frame in frames) {
                                            frameListener(frame.data, frame.ptsUs)
                                        }
                                    } else {
                                        // UI hasn't registered listener yet - buffer the frames
                                        Log.v(tag, "[$trackKey] Dropping ${frames.size} frames - no UI listener yet")
                                    }
                                }
                            }
                            moqNative.nativeCreateVideoJitterBuffer(fullTrackName, jitterBufferCallback)
                            Log.i(tag, "Recreated video jitter buffer for track $trackKey (fullName: $fullTrackName)")
                        }

                        // Restore remote participant to trigger UI rendering
                        addRemoteParticipant(trackKey)
                    }
                } else {
                    Log.w(tag, "No callback found for track $trackKey, cannot resubscribe")
                }
            }
        }*/

        this.lifecycleOwner = lifecycleOwner
        this.rotation = rotation
        formatLatch = CountDownLatch(1)
        cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        _isFrontCamera.value = true

        if (moqTransport.connectionStatus.value != MoqConnectionStatus.CONNECTED) {
            connectToRelay(relayUrl)
        }

        // Subscribe to meeting namespace to discover all participants
        // Extract common parent namespace to cover all quality levels
        // From cisco.webex.com/nab/v1/avc1/1080 -> cisco.webex.com/nab/v1
        val subscriptionNamespace = if (catalogNamespacesUrl.isNotEmpty()) {
            val firstNamespace = catalogNamespacesUrl[0]
            // Remove the last two levels (codec type and quality) to get common parent
            val parts = firstNamespace.split("/")
            if (parts.size >= 2) {
                parts.dropLast(2).joinToString("/")
            } else {
                firstNamespace
            }
        } else {
            meetingNamespace
        }

        Log.e("QUADROID_DEBUG", "⭐ Subscribing to namespace: $subscriptionNamespace")
        Log.i(tag, "Subscribing to namespace: $subscriptionNamespace (covers all quality levels)")
        moqTransport.subscribeNamespace(subscriptionNamespace, object : NamespaceSubscriptionCallback {
            override fun onMatch(trackName: String): Boolean {
                Log.e("QUADROID_DEBUG", "🔍 onMatch called for track: $trackName")

                if (catalogTrackNamesUrl.contains(trackName)) {
                    Log.e("QUADROID_DEBUG", "❌ Ignoring own track: $trackName")
                    Log.d(tag, "Ignoring own track: $trackName")
                    return false
                }

                Log.e("QUADROID_DEBUG", "✅ REMOTE TRACK DISCOVERED: $trackName")
                Log.i(tag, "========== Track Discovered: $trackName ==========")
                val trackKey = TrackUtil.generateTrackKeyFromFullName(trackName)
                Log.e("QUADROID_DEBUG", "🔑 Generated trackKey: $trackKey")
                Log.i(tag, "Generated trackKey: $trackKey")

                // Store mapping for jitter buffer lookup in C++
                trackKeyToFullName[trackKey] = trackName

                // Extract participant ID from namespace (last tuple before track name)
                val participantId = extractParticipantId(trackName)
                Log.i(tag, "Extracted participant ID: $participantId from track: $trackName")

                // Find matching track in catalog to get media properties
                val catalogTrack = findCatalogTrackByName(trackName)
                if (catalogTrack != null) {
                    Log.i(tag, "Found catalog track: name=${catalogTrack.name}, role=${catalogTrack.role}, codec=${catalogTrack.codec}, ${catalogTrack.width}x${catalogTrack.height}")
                } else {
                    Log.w(tag, "No catalog track found for: $trackName")
                }

                if (trackName.contains("audio")) {
                    Log.i(tag, "Discovered audio track: $trackName for participant: $participantId")

                    // Create native audio jitter buffer
                    val moqNative = moqTransport as? MoqNative
                    moqNative?.nativeCreateAudioJitterBuffer(trackName, trackKey)
                    Log.i(tag, "Created audio jitter buffer for track $trackKey")

                    // Associate audio track with participant
                    val participantState = remoteParticipantStates.getOrPut(participantId) {
                        RemoteParticipantState(participantId = participantId)
                    }
                    participantState.audioTrackKey = trackKey
                } else if (trackName.contains("video")) {
                    Log.i(tag, "Discovered video track: $trackName for participant: $participantId")

                    // Get display dimensions and priority from catalog track (or use defaults)
                    val displayWidth: Int
                    val displayHeight: Int
                    val priority: Int

                    if (catalogTrack != null) {
                        displayWidth = catalogTrack.displayWidth ?: catalogTrack.width ?: 1280
                        displayHeight = catalogTrack.displayHeight ?: catalogTrack.height ?: 720
                        priority = when {
                            (catalogTrack.height ?: 720) >= 1080 -> 1
                            (catalogTrack.height ?: 720) >= 720 -> 2
                            else -> 3
                        }
                        Log.i(tag, "Using catalog dimensions: ${displayWidth}x${displayHeight}")
                    } else {
                        // Fallback: Try to infer quality from track name
                        Log.w(tag, "No catalog track found, inferring from track name")
                        when {
                            trackName.contains("1080") -> {
                                displayWidth = 1920
                                displayHeight = 1080
                                priority = 1
                            }
                            trackName.contains("720") -> {
                                displayWidth = 1280
                                displayHeight = 720
                                priority = 2
                            }
                            trackName.contains("360") -> {
                                displayWidth = 640
                                displayHeight = 360
                                priority = 3
                            }
                            else -> {
                                // Default to 720p if we can't determine
                                displayWidth = 1280
                                displayHeight = 720
                                priority = 2
                                Log.w(tag, "Could not determine quality, using 720p default")
                            }
                        }
                    }

                    val remoteTrack = RemoteVideoTrack(
                        trackKey = trackKey,
                        fullTrackName = trackName,
                        priority = priority,
                        displayWidth = displayWidth,
                        displayHeight = displayHeight
                    )

                    // Add track to participant state
                    val participantState = remoteParticipantStates.getOrPut(participantId) {
                        RemoteParticipantState(participantId = participantId)
                    }
                    participantState.videoTracks.add(remoteTrack)
                    participantState.videoTracks.sortBy { it.priority }

                    // Create jitter buffer for this video track (decode all qualities)
                    val moqNative = moqTransport as? MoqNative
                    if (moqNative != null) {
                        val jitterBufferCallback = object : VideoJitterBufferCallback {
                            override fun onFramesReady(trackName: String, frames: Array<VideoFrame>) {
                                val frameListener = videoFrameListeners[trackKey]
                                if (frameListener != null) {
                                    for (frame in frames) {
                                        frameListener(frame.data, frame.ptsUs)
                                    }
                                } else {
                                    // Decoder not ready yet - this is OK, frames will be buffered
                                    if (frames.isNotEmpty() && frames[0].objectId % 100 == 0L) {
                                        Log.v(tag, "[$trackKey] No listener yet for ${frames.size} frames")
                                    }
                                }
                            }
                        }
                        moqNative.nativeCreateVideoJitterBuffer(trackName, jitterBufferCallback)
                        Log.i(tag, "Created video jitter buffer for track $trackKey (priority=$priority)")
                    }

                    Log.i(
                        tag,
                        "Added video track for $participantId: priority=$priority, ${displayWidth}x${displayHeight}"
                    )

                    // Trigger initial UI update immediately (don't wait for objects)
                    // Objects go directly to jitter buffer in C++, bypassing Kotlin callback
                    Log.e("QUADROID_DEBUG", "🎬 Triggering initial track selection for $participantId")
                    handleRemoteVideoObject(participantId, trackKey, 0)
                }

                // Note: onObject callback is bypassed when jitter buffers exist
                // Objects go directly from C++ to jitter buffer
                moqTransport.trackCallbacks[trackKey] = object : MoqObjectCallback {
                    override fun onObject(trackName: String, groupId: Long, objectId: Long, payload: ByteBuffer) {
                        // This callback is NOT called when jitter buffers exist
                        // Keeping it for compatibility but it won't be invoked for video
                        if (!catalogTrackNamesUrl.contains(trackName) && trackName.contains("video")) {
                            Log.d(tag, "onObject called for $trackName (unexpected with jitter buffer)")
                        }
                    }
                }

                return true
            }
        })


        // Publish our tracks
        for (catalogTrackName in catalogTrackNamesUrl) {
            Log.i(tag, "Publishing tracks: $catalogTrackName")
            if (catalogTrackName.contains("video")) {
                moqTransport.publish(catalogTrackName, PublishOptions(TrackUtil.getTrackPriority(catalogTrackName), false))
            } else if (catalogTrackName.contains("audio")) {
                audioFramer = MoqAudioFramer(moqTransport, catalogTrackName)
                moqTransport.publish(catalogTrackName, PublishOptions(1, true))
            }
        }

        setupEncoders()
        setupCamera(lifecycleOwner)
        startNativeAudio()

        // Start periodic jitter buffer monitoring
        startJitterBufferMonitoring()
    }

    private fun startNativeAudio() {
        var callbackCount = 0
        nativeAudioLib.startCapture(object : NativeAudioLib.NativeAudioCallback {
            override fun onAudioEncoded(payload: ByteBuffer, size: Int, presentationTimeUs: Long, flags: Int) {
                callbackCount++
                if (callbackCount % 10 == 0) {
                    Log.d(tag, "onAudioEncoded callback #$callbackCount, size=$size, flags=$flags, micEnabled=$isMicEnabled, framerExists=${audioFramer != null}")
                }
                if (isMicEnabled) {
                    val info = MediaCodec.BufferInfo()
                    info.set(0, size, presentationTimeUs, flags)
                    audioFramer?.processFrame(payload, info)
                }
            }
        })
    }

    private fun addRemoteParticipant(trackKey: String) {
        if (_remoteParticipants.value.any { it.id == trackKey }) {
            return
        }
        
        Handler(context.mainLooper).post {
            if (!_remoteParticipants.value.any { it.id == trackKey }) {
                val stream = ParticipantStream(trackKey)
                _remoteParticipants.value = _remoteParticipants.value + stream
                Log.i(tag, "Added remote participant: $trackKey. List size: ${_remoteParticipants.value.size}")
            }
        }
    }

    private fun setupCamera(owner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(owner)
            } catch (e: Exception) {
                Log.e(tag, "Failed to get camera provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(owner: LifecycleOwner) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        if (encoders.isEmpty()) {
            Log.w(tag, "No encoders configured, skipping camera bind")
            return
        }

        // Validate active encoder index
        if (activeEncoderIndex < 0 || activeEncoderIndex >= encoders.size) {
            activeEncoderIndex = 0
        }

        val activeEncoder = encoders[activeEncoderIndex]
        val activeConfig = activeEncoder.config

        // Mark encoder as active
        encoders.forEach { it.isActive = false }
        activeEncoder.isActive = true

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(activeConfig.width, activeConfig.height),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        // Create preview use case ONLY for the active encoder
        val encoderPreview = Preview.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(resolutionSelector)
            .build().apply {
                setSurfaceProvider { request ->
                    request.provideSurface(
                        activeEncoder.inputSurface,
                        ContextCompat.getMainExecutor(context)
                    ) {}
                }
            }

        // Create local UI preview
        val localUiPreview = Preview.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(resolutionSelector)
            .build()

        localPreviewSurface?.let { surface ->
            localUiPreview.setSurfaceProvider { request ->
                request.provideSurface(surface, ContextCompat.getMainExecutor(context)) {}
            }
        }

        try {
            // Bind only active encoder + UI preview (max 2 use cases)
            val useCases = mutableListOf(encoderPreview)
            if (localPreviewSurface != null) {
                useCases.add(localUiPreview)
            }

            provider.bindToLifecycle(owner, cameraSelector, *useCases.toTypedArray())
            Log.i(
                tag,
                "Bound camera with active encoder ${activeEncoderIndex} (${activeConfig.width}x${activeConfig.height}, priority=${activeConfig.priority})"
            )
        } catch (exc: Exception) {
            Log.e(tag, "Use case binding failed", exc)
        }
    }

    fun switchCamera(owner: LifecycleOwner) {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            _isFrontCamera.value = false
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            _isFrontCamera.value = true
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        bindCameraUseCases(owner)
    }

    fun setLocalPreviewSurface(surface: Surface) {
        localPreviewSurface = surface
        lifecycleOwner?.let { bindCameraUseCases(it) }
    }

    /**
     * Switch to a different encoder quality for local preview.
     * Follows the same upgrade/downgrade rules as remote participants.
     */
    private fun switchLocalEncoderQuality(newIndex: Int) {
        if (newIndex < 0 || newIndex >= encoders.size) {
            Log.w(tag, "Invalid encoder index: $newIndex")
            return
        }

        if (newIndex == activeEncoderIndex) {
            return // Already at this quality
        }

        val oldIndex = activeEncoderIndex
        val oldConfig = encoders[oldIndex].config
        val newConfig = encoders[newIndex].config

        activeEncoderIndex = newIndex
        consecutiveGoodFrames = 0

        Log.i(
            tag,
            "Local encoder quality switch: ${oldConfig.priority} (${oldConfig.width}x${oldConfig.height}) -> " +
                    "${newConfig.priority} (${newConfig.width}x${newConfig.height})"
        )

        // Rebind camera with new encoder
        lifecycleOwner?.let { bindCameraUseCases(it) }

        // Update aspect ratio for UI
        val newAspectRatio = newConfig.height.toFloat() / newConfig.width.toFloat()
        _videoAspectRatio.value = newAspectRatio
    }

    /**
     * Attempt to upgrade to higher quality encoder.
     * Upgrades to the next higher priority (lower number) encoder.
     */
    private fun tryUpgradeLocalQuality() {
        // Find next higher quality (lower priority number)
        val currentPriority = encoders[activeEncoderIndex].config.priority
        val higherQualityIndex = encoders.indexOfFirst { it.config.priority < currentPriority }

        if (higherQualityIndex >= 0) {
            Log.d(tag, "Upgrading local quality after $consecutiveGoodFrames consecutive good frames")
            switchLocalEncoderQuality(higherQualityIndex)
        }
    }

    /**
     * Downgrade to lower quality encoder immediately.
     * Downgrades to the next lower priority (higher number) encoder.
     */
    private fun downgradeLocalQuality() {
        // Find next lower quality (higher priority number)
        val currentPriority = encoders[activeEncoderIndex].config.priority
        val lowerQualityIndex = encoders.indexOfFirst { it.config.priority > currentPriority }

        if (lowerQualityIndex >= 0) {
            Log.w(tag, "Downgrading local quality due to encoding issues")
            switchLocalEncoderQuality(lowerQualityIndex)
        } else {
            Log.w(tag, "Already at lowest quality, cannot downgrade further")
        }
    }

    private fun setupEncoders() {
        // Create one encoder per video quality level
        videoEncoderConfigs.forEach { config ->
            try {
                val format = MediaFormat.createVideoFormat(
                    videoMimeType,
                    config.width,
                    config.height
                ).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, config.framerate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
                    // Prepend SPS/PPS to keyframes for easier decoding by late-joiners
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                    }
                }

                // Create framer for this track
                val framer = MoqMediaFramer(moqTransport, config.trackNameUrl)

                val encoder = MediaCodec.createEncoderByType(videoMimeType).apply {
                    setCallback(object : MediaCodec.Callback() {
                        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                        override fun onOutputBufferAvailable(
                            codec: MediaCodec,
                            index: Int,
                            info: MediaCodec.BufferInfo
                        ) {
                            try {
                                getOutputBuffer(index)?.let { buffer ->
                                    if (info.size > 0) {
                                        framer.processFrame(buffer, info)

                                        // Track encoding success for adaptive quality (only for active encoder)
                                        val encoderIndex = encoders.indexOfFirst {
                                            it.config.trackNameUrl == config.trackNameUrl
                                        }
                                        if (encoderIndex == activeEncoderIndex) {
                                            consecutiveGoodFrames++

                                            // Upgrade after 5 consecutive good frames (same rule as remote)
                                            if (consecutiveGoodFrames >= 5) {
                                                tryUpgradeLocalQuality()
                                            }
                                        }
                                    }
                                }
                                releaseOutputBuffer(index, false)
                            } catch (e: IllegalStateException) {
                                Log.e(tag, "Encoder output error for ${config.trackNameUrl}", e)

                                // Track error and trigger downgrade if this is the active encoder
                                val encoderIndex = encoders.indexOfFirst {
                                    it.config.trackNameUrl == config.trackNameUrl
                                }
                                if (encoderIndex == activeEncoderIndex) {
                                    lastEncoderError = System.currentTimeMillis()
                                    consecutiveGoodFrames = 0
                                    downgradeLocalQuality()
                                }
                            }
                        }

                        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                            Log.e(tag, "Encoder Error for ${config.trackNameUrl}", e)

                            // Downgrade on encoder error if this is the active encoder
                            val encoderIndex = encoders.indexOfFirst {
                                it.config.trackNameUrl == config.trackNameUrl
                            }
                            if (encoderIndex == activeEncoderIndex) {
                                lastEncoderError = System.currentTimeMillis()
                                consecutiveGoodFrames = 0
                                downgradeLocalQuality()
                            }
                        }

                        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                            Log.d(tag, "Encoder format changed for ${config.trackNameUrl}: $format")
                        }
                    }, encoderHandler)
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }

                val inputSurface = encoder.createInputSurface()
                encoder.start()

                val encoderState = EncoderState(
                    encoder = encoder,
                    inputSurface = inputSurface,
                    config = config,
                    framer = framer
                )

                encoders.add(encoderState)
                Log.i(
                    tag,
                    "Created encoder for ${config.trackNameUrl}: ${config.width}x${config.height}, ${config.bitrate}bps"
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to setup video encoder for ${config.trackNameUrl}", e)
            }
        }

        Log.i(tag, "Setup ${encoders.size} video encoders")

        // Initialize with highest quality encoder (index 0)
        if (encoders.isNotEmpty()) {
            activeEncoderIndex = 0
            encoders[0].isActive = true
            val highestQualityConfig = encoders[0].config
            val aspectRatio = highestQualityConfig.height.toFloat() / highestQualityConfig.width.toFloat()
            _videoAspectRatio.value = aspectRatio
            Log.i(
                tag,
                "Initialized with highest quality: ${highestQualityConfig.width}x${highestQualityConfig.height}, priority=${highestQualityConfig.priority}"
            )
        }
    }

    fun enableVideo(enabled: Boolean, owner: LifecycleOwner) {
        if (enabled) bindCameraUseCases(owner) else cameraProvider?.unbindAll()
    }
    
    fun enableAudio(enabled: Boolean) { isMicEnabled = enabled }

    @Synchronized
    fun stopSession() {
        // Unpublish all self tracks (video tracks + audio track)
        catalogTrackNamesUrl.forEach { trackName ->
            Log.i(tag, "Unpublishing track: $trackName")
            moqTransport.unpublishTrack(trackName)
        }

        // Unsubscribe from all remote tracks
        trackKeyToFullName.values.forEach { fullTrackName ->
            Log.i(tag, "Unsubscribing from track: $fullTrackName")
            moqTransport.unsubscribeTrack(fullTrackName)
        }

        // Unsubscribe from namespace
        if (catalogNamespacesUrl.isNotEmpty()) {
            moqTransport.unsubscribeNamespace(catalogNamespacesUrl[0])
        }

        cleanup()
    }

    private fun cleanup() {
        // Stop jitter buffer monitoring first
        stopJitterBufferMonitoring()

        cameraProvider?.unbindAll()

        encoderHandler.removeCallbacksAndMessages(null)

        // Stop and release all encoders
        encoders.forEach { encoderState ->
            try {
                encoderState.encoder.stop()
                encoderState.encoder.release()
                encoderState.inputSurface.release()
                Log.d(tag, "Released encoder for ${encoderState.config.trackNameUrl}")
            } catch (e: Exception) {
                Log.e(tag, "Error releasing encoder for ${encoderState.config.trackNameUrl}", e)
            }
        }
        encoders.clear()

        // Reset encoder quality tracking
        activeEncoderIndex = 0
        consecutiveGoodFrames = 0
        lastEncoderError = 0L

        nativeAudioLib.stopCapture()

        // Destroy all jitter buffers (video and audio) before clearing listeners
        val moqNative = moqTransport as? MoqNative
        if (moqNative != null) {
            // Destroy video jitter buffers
            videoFrameListeners.keys.forEach { trackKey ->
                val fullTrackName = trackKeyToFullName[trackKey]
                if (fullTrackName != null) {
                    moqNative.nativeDestroyVideoJitterBuffer(fullTrackName)
                    Log.d(tag, "Destroyed video jitter buffer for track $trackKey (fullName: $fullTrackName)")
                }
            }

            // Destroy audio jitter buffers
            trackKeyToFullName.forEach { (trackKey, fullTrackName) ->
                if (fullTrackName.contains("audio")) {
                    moqNative.nativeDestroyAudioJitterBuffer(fullTrackName)
                    Log.d(tag, "Destroyed audio jitter buffer for track $trackKey (fullName: $fullTrackName)")
                }
            }
        }

        localPreviewSurface?.release()
        localPreviewSurface = null
        _remoteParticipants.value = emptyList()
        remoteParticipantStates.clear()
        encoderOutputFormat = null

        formatLatch.countDown()
        formatLatch = CountDownLatch(1)
        audioFramer = null
        videoFrameListeners.clear()

        trackKeyToFullName.clear()

    }

    fun resetCatalog() {
        // Clear catalog state
        moqTransport.unsubscribeTrack(moqCatalog.catalogTrackUrl)
        _isCatalogReady.value = false
        catalogVersion = 0
        catalogTracks.clear()
        catalogTrackNamesUrl.clear()
        catalogNamespacesUrl.clear()
        moqCatalog.clear()
        // Note: catalogSubscribed is managed by connection status observer
    }
}

data class ParticipantStream(val id: String, val aspectRatio: Float = 16f / 9f)
