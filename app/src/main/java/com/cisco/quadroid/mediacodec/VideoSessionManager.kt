// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.mediacodec

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.lifecycle.LifecycleOwner
import com.cisco.quadroid.mediacodec.audio.AudioManager
import com.cisco.quadroid.mediacodec.camera.CameraManager
import com.cisco.quadroid.mediacodec.catalog.CatalogManager
import com.cisco.quadroid.mediacodec.catalog.VideoEncoderConfigParser
import com.cisco.quadroid.mediacodec.encoder.VideoEncoderManager
import com.cisco.quadroid.mediacodec.jitter.JitterBufferMonitor
import com.cisco.quadroid.mediacodec.model.*
import com.cisco.quadroid.mediacodec.participant.RemoteParticipantManager
import com.cisco.quadroid.transport.AppHeaders
import com.cisco.quadroid.transport.MoqConnectionStatus
import com.cisco.quadroid.transport.MoqNative
import com.cisco.quadroid.transport.MoqObjectCallback
import com.cisco.quadroid.transport.MoqTransport
import com.cisco.quadroid.transport.NamespaceFilter
import com.cisco.quadroid.transport.NamespaceSubscriptionCallback
import com.cisco.quadroid.transport.PublishOptions
import com.cisco.quadroid.util.DeviceIdentifier
import com.cisco.quadroid.util.PreferencesManager
import com.cisco.quadroid.util.TrackUtil
import com.cisco.quadroid.vad.SharedVoiceActivityState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates media streaming sessions using Media over QUIC (MoQ) transport.
 *
 * This class serves as the main coordinator for multi-quality video conferencing sessions,
 * managing the lifecycle and interactions between specialized manager components:
 *
 * **Architecture:**
 * - [CatalogManager]: Handles catalog subscription and parsing for dynamic track configuration
 * - [VideoEncoderManager]: Manages multiple video encoders for adaptive quality streaming
 * - [CameraManager]: Controls CameraX integration for local video capture
 * - [AudioManager]: Manages native audio capture and encoding
 * - [RemoteParticipantManager]: Tracks remote participants and handles adaptive quality switching
 * - [JitterBufferMonitor]: Monitors jitter buffer statistics for audio/video tracks
 *
 * **Key Features:**
 * - Multi-quality video encoding (1080p, 720p, 360p) with adaptive quality switching
 * - Catalog-based dynamic track configuration
 * - Namespace-based participant discovery
 * - Jitter buffer management for smooth playback
 * - Front/back camera switching
 * - Audio/video mute controls
 *
 * **Session Lifecycle:**
 * 1. Connect to relay server via [connectToRelay]
 * 2. Subscribe to catalog track (automatic when connected)
 * 3. Start session via [startSession] (publishes local tracks, discovers remote participants)
 * 4. Handle video/audio via manager delegates
 * 5. End session via [stopSession]
 *
 * **Thread Safety:**
 * This class is a Hilt @Singleton and uses coroutines for async operations.
 * StateFlows are exposed for UI observation on the main thread.
 *
 * @property context Application context for Android resources
 * @property moqTransport MoQ transport layer for media streaming
 */
@Singleton
class VideoSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moqTransport: MoqTransport
) {
    private val tag = "VideoSessionManager"

    // Coroutine scope for observing connection status
    // Scope for deferred native publish/subscribe and catalog subscription. These create
    // QUIC streams in libquicr, which is thread-sensitive and must run on the main thread
    // (same thread as connect) — running it elsewhere breaks stream-based video publishing.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Multi-quality video encoder configuration
    private val videoEncoderConfigs = mutableListOf<VideoEncoderConfig>()

    private var lifecycleOwner: LifecycleOwner? = null
    private var rotation: Int = 0

    private val _videoAspectRatio = MutableStateFlow(9f / 16f) // Default portrait aspect ratio

    /**
     * Current video aspect ratio for local video (height / width).
     * Updated automatically when encoder quality changes.
     * Default: 9/16 (portrait mode).
     */
    val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

    // Meeting Configuration
    private val meetingId = "meeting123"

    // Top-N active-speaker configuration. Read from preferences at session start; the setters
    // let the Settings UI change them live. demoTimeout mirrors the Mac's relay idle window.
    private var topNEnabled = false
    private var topNCount = PreferencesManager.TOPN_DEFAULT_COUNT
    private val topNTimeoutMs = 2000L
    // Namespace prefixes used for the video (filtered) and audio (unfiltered) subscriptions, so
    // a live Top-N change can re-subscribe the video namespace with the new count.
    private var videoNamespacePrefix: String? = null
    private var audioNamespacePrefix: String? = null
    private var namespaceCallback: NamespaceSubscriptionCallback? = null

    // Each device publishes under its own unique device ID
    private val deviceId = DeviceIdentifier.get(context)

    // Catalog state management
    private val catalogManager = CatalogManager(moqTransport, deviceId, meetingId).apply {
        onCatalogReady = {
            _isCatalogReady.value = true
            parseVideoEncoderConfigs()
        }
    }

    private val _isCatalogReady = MutableStateFlow(false)

    /**
     * Indicates whether the catalog has been successfully loaded.
     * When true, video encoder configurations are available and encoders are setup.
     */
    val isCatalogReady: StateFlow<Boolean> = _isCatalogReady.asStateFlow()

    // Jitter buffer monitoring
    private val jitterBufferMonitor = JitterBufferMonitor(moqTransport)

    // Audio management
    private val audioManager = AudioManager(moqTransport, context)

    // Camera management
    private val cameraManager = CameraManager(context)

    // Video encoder management
    private val videoEncoderManager = VideoEncoderManager(moqTransport, context).apply {
        onQualityChanged = { config ->
            _videoAspectRatio.value = config.height.toFloat() / config.width.toFloat()

            // Rebind camera to new encoder surface when quality switches
            lifecycleOwner?.let { owner ->
                activeInputSurface?.let { surface ->
                    cameraManager.updateEncoderSurface(surface, config.width, config.height)
                    Log.i(tag, "Camera rebound to new encoder: ${config.width}x${config.height}")
                }
            }
        }
    }

    // Remote participant management
    private val participantManager = RemoteParticipantManager(context, catalogManager)

    /**
     * Indicates whether the front camera is currently active.
     * Use [switchCamera] to toggle between front and back cameras.
     */
    val isFrontCamera: StateFlow<Boolean> = cameraManager.isFrontCamera

    /**
     * List of active remote participants in the session.
     * Each participant has their highest available quality track selected automatically.
     * Updated dynamically as participants join/leave or quality changes.
     */
    val remoteParticipants: StateFlow<List<ParticipantStream>> = participantManager.remoteParticipants

    /**
     * Current connection status to the MoQ relay server.
     * Catalog subscription begins automatically when status becomes CONNECTED.
     */
    val connectionStatus: StateFlow<MoqConnectionStatus> = moqTransport.connectionStatus

    init {
        Log.e("QUADROID_DEBUG", "VideoSessionManager INIT - deviceId=$deviceId")
        Log.e(tag, "⭐ VideoSessionManager initialized with deviceId=$deviceId, meetingId=$meetingId")

        // Observe connection status and subscribe to catalog when connected
        scope.launch {
            connectionStatus.collect { status ->
                Log.e("QUADROID_DEBUG", "Connection status: $status")
                Log.d(tag, "Connection status changed to: $status")
                if (status == MoqConnectionStatus.CONNECTED) {
                    Log.i(tag, "Connection established, subscribing to catalog track")
                    catalogManager.subscribeToCatalogTrack()
                }
            }
        }
    }

    /**
     * Establishes connection to the MoQ relay server.
     *
     * When connection succeeds, the catalog track is automatically subscribed.
     * Monitor [connectionStatus] to observe connection state changes.
     *
     * @param url WebSocket URL of the relay server (e.g., "wss://relay.example.com")
     */
    fun connectToRelay(url: String) {
        moqTransport.connect(url, deviceId)
    }

    /**
     * Disconnects from the MoQ relay server.
     *
     * This terminates the transport connection but does not clean up session state.
     * Call [stopSession] before disconnecting to properly end the session.
     */
    fun disconnectFromRelay() {
        moqTransport.disconnect()
    }

    private fun parseVideoEncoderConfigs() {
        videoEncoderConfigs.clear()
        videoEncoderConfigs.addAll(
            VideoEncoderConfigParser.parse(
                catalogManager.catalogTracks,
                catalogManager.catalogTrackNamesUrl
            )
        )

        // Setup encoders with new configs
        if (videoEncoderConfigs.isNotEmpty()) {
            videoEncoderManager.setupEncoders(videoEncoderConfigs)
        }
    }

    /**
     * Registers a listener to receive decoded video frames for a remote participant.
     *
     * Creates a native jitter buffer for the specified track and routes decoded frames
     * to the provided listener. The listener is called on the jitter buffer's native thread.
     *
     * @param trackKey Unique identifier for the remote participant's video track
     * @param listener Callback receiving (frameData: ByteArray, timestampUs: Long)
     */
    fun addVideoFrameListener(trackKey: String, listener: (ByteArray, Long) -> Unit) {
        jitterBufferMonitor.addVideoFrameListener(trackKey, listener)
    }

    /**
     * Removes the video frame listener for a remote participant.
     *
     * Destroys the associated native jitter buffer and stops frame delivery.
     *
     * @param trackKey Unique identifier for the remote participant's video track
     */
    fun removeVideoFrameListener(trackKey: String) {
        jitterBufferMonitor.removeVideoFrameListener(trackKey)
    }

    private fun startJitterBufferMonitoring() {
        jitterBufferMonitor.startMonitoring()
    }

    private fun stopJitterBufferMonitoring() {
        jitterBufferMonitor.stopMonitoring()
    }

    /**
     * Starts a new media streaming session.
     *
     * This method orchestrates the complete session setup:
     * 1. Connects to relay if not already connected
     * 2. Subscribes to meeting namespace for participant discovery
     * 3. Publishes local video tracks (multi-quality) and audio track
     * 4. Starts camera capture and audio encoding
     * 5. Begins jitter buffer monitoring
     *
     * Remote participants discovered via namespace subscription are automatically
     * added to [remoteParticipants] with adaptive quality selection.
     *
     * **Prerequisites:**
     * - Catalog must be loaded ([isCatalogReady] == true)
     * - Connection must be established or will be initiated automatically
     *
     * @param lifecycleOwner Android lifecycle owner for camera binding
     * @param rotation Screen rotation in degrees (0, 90, 180, 270) for camera orientation
     * @param relayUrl WebSocket URL of the relay server
     */
    suspend fun startSession(lifecycleOwner: LifecycleOwner, rotation: Int, relayUrl: String) {
        Log.e("QUADROID_DEBUG", "╔════════════════════════════════════════╗")
        Log.e("QUADROID_DEBUG", "║    START SESSION CALLED               ║")
        Log.e("QUADROID_DEBUG", "╚════════════════════════════════════════╝")
        Log.e(tag, "⭐ startSession: relayUrl=$relayUrl, deviceId=$deviceId")

        this.lifecycleOwner = lifecycleOwner
        this.rotation = rotation

        // Heavy, thread-agnostic setup (MediaCodec release + create x3, audio teardown) is
        // the main-thread jank/ANR source, so run it off the main thread. The native MoQ
        // publish/subscribe below must NOT move off-main: libquicr's stream-based publish is
        // thread-sensitive (see PicoQuicTransport::CreateStreamOnPqThread) and running it on
        // a worker thread silently breaks stream video — only datagram audio survives.
        withContext(Dispatchers.Default) {
            cleanup()

            Log.i(tag, "Starting session with deviceId: $deviceId")
            Log.i(tag, "Local tracks from catalog: ${catalogManager.catalogTrackNamesUrl.joinToString(", ")}")

            // Recreate encoders if they were destroyed during cleanup
            if (videoEncoderConfigs.isNotEmpty() && videoEncoderManager.activeInputSurface == null) {
                Log.i(tag, "Recreating encoders after cleanup")
                videoEncoderManager.setupEncoders(videoEncoderConfigs)
            }
        }

        // Setup camera with active encoder surface
        videoEncoderManager.activeInputSurface?.let { surface ->
            videoEncoderManager.activeConfig?.let { config ->
                cameraManager.setupCamera(lifecycleOwner, surface, config.width, config.height, rotation)
                Log.i(tag, "Camera setup initiated with ${config.width}x${config.height}")
            }
        } ?: Log.e(tag, "Cannot setup camera - no active encoder surface available")

        // Publishing tracks and subscribing to the meeting namespace both create QUIC
        // streams in the native transport, which requires an established connection.
        // Calling them before the connection is CONNECTED makes libmoq_jni throw across
        // the JNI boundary and abort the process (SIGABRT in
        // PicoQuicTransport::CreateStreamOnPqThread). Capture the work in a lambda and
        // run it only once the connection (and catalog) are ready.
        val beginPublishAndSubscribe = {
        // Subscribe to meeting namespace to discover all participants
        // Extract common parent namespace to cover all quality levels
        // From cisco.webex.com/nab/v1/avc1/1080 -> cisco.webex.com/nab/v1
        val subscriptionNamespace = computeCombinedNamespace()

        // Top-N: read current settings. The relay-side filter is applied to video only (audio
        // is always delivered), and the grid is clamped to N when enabled.
        topNEnabled = PreferencesManager.getTopNEnabled(context)
        topNCount = PreferencesManager.getTopNCount(context)
        SharedVoiceActivityState.setEnabled(topNEnabled)
        participantManager.setMaxDisplayCount(if (topNEnabled) topNCount else null)

        val nsCallback = object : NamespaceSubscriptionCallback {
            override fun onMatch(trackName: String): Boolean {
                Log.e("QUADROID_DEBUG", "🔍 onMatch called for track: $trackName")

                if (catalogManager.catalogTrackNamesUrl.contains(trackName)) {
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
                jitterBufferMonitor.registerTrack(trackKey, trackName)

                // Extract participant ID from namespace (last tuple before track name)
                val participantId = participantManager.extractParticipantId(trackName)
                Log.i(tag, "Extracted participant ID: $participantId from track: $trackName")

                // Find matching track in catalog to get media properties
                val catalogTrack = participantManager.findCatalogTrackByName(trackName)
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
                    participantManager.setAudioTrack(participantId, trackKey)
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

                    // Add track to participant manager
                    participantManager.addVideoTrack(participantId, remoteTrack)

                    // Jitter buffer will be created when UI adds a video frame listener

                    // Trigger initial UI update immediately (don't wait for objects)
                    // Objects go directly to jitter buffer in C++, bypassing Kotlin callback
                    Log.e("QUADROID_DEBUG", "🎬 Triggering initial track selection for $participantId")
                    participantManager.handleRemoteVideoObject(participantId, trackKey, 0)
                }

                // Note: onObject callback is bypassed when jitter buffers exist
                // Objects go directly from C++ to jitter buffer
                moqTransport.trackCallbacks[trackKey] = object : MoqObjectCallback {
                    override fun onObject(trackName: String, groupId: Long, objectId: Long, payload: ByteBuffer) {
                        // This callback is NOT called when jitter buffers exist
                        // Keeping it for compatibility but it won't be invoked for video
                        if (!catalogManager.catalogTrackNamesUrl.contains(trackName) && trackName.contains("video")) {
                            Log.d(tag, "onObject called for $trackName (unexpected with jitter buffer)")
                        }
                    }
                }

                return true
            }
        }
        namespaceCallback = nsCallback
        subscribeMeetingNamespaces(subscriptionNamespace, nsCallback)


        // Publish our tracks
        for (catalogTrackName in catalogManager.catalogTrackNamesUrl) {
            Log.i(tag, "Publishing tracks: $catalogTrackName")
            if (catalogTrackName.contains("video")) {
                moqTransport.publish(catalogTrackName, PublishOptions(TrackUtil.getTrackPriority(catalogTrackName), false))
            } else if (catalogTrackName.contains("audio")) {
                moqTransport.publish(catalogTrackName, PublishOptions(1, true))
            }
        }

        // Encoders are already setup via parseVideoEncoderConfigs()
        // Camera will be setup via onEncoderReady callback

        // Start audio capture for audio tracks
        catalogManager.catalogTrackNamesUrl.forEach { trackName ->
            if (trackName.contains("audio")) {
                audioManager.startCapture(trackName)
            }
        }

        // Start periodic jitter buffer monitoring
        startJitterBufferMonitoring()
        }

        if (moqTransport.connectionStatus.value == MoqConnectionStatus.CONNECTED) {
            beginPublishAndSubscribe()
        } else {
            // Not connected yet (e.g. starting a call before the relay handshake has
            // completed, or after a drop). Connect, then run once CONNECTED and the
            // catalog is loaded — this avoids the native stream-creation crash and the
            // empty-catalog race (no namespaces/tracks to subscribe or publish).
            Log.i(tag, "Transport not connected; deferring publish/subscribe until CONNECTED")
            connectToRelay(relayUrl)
            scope.launch {
                moqTransport.connectionStatus.first { it == MoqConnectionStatus.CONNECTED }
                _isCatalogReady.first { it }
                Log.i(tag, "Connection and catalog ready; starting publish/subscribe")
                beginPublishAndSubscribe()
            }
        }
    }

    /**
     * Toggles between front and back camera.
     *
     * Camera switch is applied immediately. Monitor [isFrontCamera] to observe the current state.
     *
     * @param owner Lifecycle owner for camera rebinding
     */
    fun switchCamera(owner: LifecycleOwner) {
        cameraManager.switchCamera()
    }

    /**
     * Sets the surface for local video preview.
     *
     * The local preview allows the user to see their own camera feed.
     * This should be called before or after [startSession] to display local video.
     *
     * @param surface Surface from SurfaceView or TextureView for preview rendering
     */
    fun setLocalPreviewSurface(surface: Surface) {
        cameraManager.setLocalPreviewSurface(surface)
    }

    /**
     * Called when the local-preview UI surface goes away. Unbinds only the preview use
     * case; the encoder keeps streaming so the outgoing video does not freeze.
     */
    fun removeLocalPreviewSurface() {
        cameraManager.removeLocalPreviewSurface()
    }

    /**
     * Enables or disables local video transmission.
     *
     * When enabled, camera capture resumes and video frames are published.
     * When disabled, camera is unbound and no video is sent.
     *
     * @param enabled True to enable video, false to disable
     * @param owner Lifecycle owner for camera binding
     */
    fun enableVideo(enabled: Boolean, owner: LifecycleOwner) {
        // Keep the Top-N ranking aware of camera mute: a muted-mic/camera-on publisher ranks as
        // baseline presence, while both-muted ranks lowest.
        SharedVoiceActivityState.setVideoEnabled(enabled)
        if (enabled) {
            videoEncoderManager.activeInputSurface?.let { surface ->
                videoEncoderManager.activeConfig?.let { config ->
                    cameraManager.setupCamera(
                        owner,
                        surface,
                        config.width,
                        config.height,
                        rotation
                    )
                }
            }
        } else {
            cameraManager.unbindAll()
        }
    }

    /**
     * Enables or disables microphone for audio transmission.
     *
     * When disabled, audio frames are still captured but not published to the network.
     * This provides instant mute/unmute without stopping the audio encoder.
     *
     * @param enabled True to enable microphone, false to mute
     */
    fun enableAudio(enabled: Boolean) {
        // Keep the Top-N ranking aware of mic mute: when muted the live VAD value is ignored in
        // favor of a mute-based presence rank (see SharedVoiceActivityState.activityValueForSend).
        SharedVoiceActivityState.setMicEnabled(enabled)
        audioManager.enableMicrophone(enabled)
    }

    /**
     * Enables or disables Voice Activity Detection (VAD).
     *
     * When enabled, audio frames without speech are automatically dropped to reduce bandwidth.
     * When disabled, all audio frames are transmitted regardless of speech presence.
     *
     * @param enabled True to enable VAD, false to disable
     */
    fun setVadEnabled(enabled: Boolean) {
        audioManager.setVadEnabled(enabled)
    }

    /**
     * Enables or disables Top-N active-speaker filtering at runtime (from the Settings toggle).
     * Updates header stamping, the grid display cap, and re-subscribes the meeting namespaces so
     * the relay-side filter is (re)applied.
     */
    fun setTopNEnabled(enabled: Boolean) {
        topNEnabled = enabled
        SharedVoiceActivityState.setEnabled(enabled)
        participantManager.setMaxDisplayCount(if (enabled) topNCount else null)
        Log.i(tag, "Top-N ${if (enabled) "enabled (N=$topNCount)" else "disabled"}")
        resubscribeMeetingNamespaces()
    }

    /**
     * Sets the number of active speakers (N) for Top-N at runtime (from the Settings stepper).
     * Updates the grid cap and, because libquicr has no SetFilter, re-subscribes the video
     * namespace with the new maxTracks.
     */
    fun setTopNCount(count: Int) {
        topNCount = count.coerceIn(PreferencesManager.TOPN_MIN_COUNT, PreferencesManager.TOPN_MAX_COUNT)
        Log.i(tag, "Top-N count set to $topNCount")
        if (topNEnabled) {
            participantManager.setMaxDisplayCount(topNCount)
            resubscribeMeetingNamespaces()
        }
    }

    /**
     * Common parent namespace covering all quality levels, e.g.
     * cisco.webex.com/nab/v1/avc1/1080 -> cisco.webex.com/nab/v1.
     */
    private fun computeCombinedNamespace(): String {
        if (catalogManager.catalogNamespacesUrl.isEmpty()) {
            return "webex.com/$meetingId" // Fallback if catalog not available
        }
        val firstNamespace = catalogManager.catalogNamespacesUrl[0]
        // Remove the last two levels (codec type and quality) to get common parent
        val parts = firstNamespace.split("/")
        return if (parts.size >= 2) parts.dropLast(2).joinToString("/") else firstNamespace
    }

    /**
     * Subscribes to the meeting namespace(s). When Top-N is enabled AND a clean video/audio
     * split can be derived from the catalog, the relay-side [NamespaceFilter] is attached to the
     * video namespace only (audio is always delivered). Otherwise a single combined, unfiltered
     * subscription is used — identical to the pre-Top-N behavior.
     */
    private fun subscribeMeetingNamespaces(
        combinedNamespace: String,
        callback: NamespaceSubscriptionCallback
    ) {
        val (videoPrefix, audioPrefix) = computeMediaNamespacePrefixes()
        if (topNEnabled && videoPrefix != null && audioPrefix != null && videoPrefix != audioPrefix) {
            // Clean split: video gets the relay-side Top-N filter; audio is unfiltered.
            videoNamespacePrefix = videoPrefix
            audioNamespacePrefix = audioPrefix
            val filter = NamespaceFilter(
                propertyType = AppHeaders.AUDIO_ACTIVITY_INDICATOR,
                maxTracks = topNCount,
                timeoutMs = topNTimeoutMs
            )
            Log.i(tag, "Top-N ON: video=$videoPrefix (maxTracks=$topNCount), audio=$audioPrefix (unfiltered)")
            moqTransport.subscribeNamespace(videoPrefix, callback, filter)
            moqTransport.subscribeNamespace(audioPrefix, callback, null)
        } else {
            // Top-N off or no clean split: single combined subscription, no filter.
            videoNamespacePrefix = combinedNamespace
            audioNamespacePrefix = null
            if (topNEnabled) {
                Log.w(tag, "Top-N ON but no clean video/audio namespace split; using combined (unfiltered)")
            }
            Log.i(tag, "Subscribing to namespace: $combinedNamespace (covers all quality levels)")
            moqTransport.subscribeNamespace(combinedNamespace, callback, null)
        }
    }

    /**
     * Tears down and re-establishes the meeting namespace subscriptions to (re)apply the
     * relay-side filter after a Top-N setting change. No-op until connected.
     */
    private fun resubscribeMeetingNamespaces() {
        val callback = namespaceCallback ?: return
        if (moqTransport.connectionStatus.value != MoqConnectionStatus.CONNECTED) return
        videoNamespacePrefix?.let { moqTransport.unsubscribeNamespace(it) }
        audioNamespacePrefix?.let { if (it != videoNamespacePrefix) moqTransport.unsubscribeNamespace(it) }
        subscribeMeetingNamespaces(computeCombinedNamespace(), callback)
    }

    /**
     * Partitions the catalog namespaces into video and audio and returns the longest common
     * path prefix of each (e.g. [.../avc1/1080, .../avc1/720] -> .../avc1). Returns null for a
     * media type with no matching namespaces.
     */
    private fun computeMediaNamespacePrefixes(): Pair<String?, String?> {
        val namespaces = catalogManager.catalogNamespacesUrl
        if (namespaces.isEmpty()) return null to null
        val videoPrefix = longestCommonPathPrefix(namespaces.filter { isVideoNamespace(it) })
        val audioPrefix = longestCommonPathPrefix(namespaces.filter { isAudioNamespace(it) })
        return videoPrefix to audioPrefix
    }

    private fun isVideoNamespace(ns: String): Boolean {
        val l = ns.lowercase()
        return l.contains("avc1") || l.contains("h264") || l.contains("hevc") ||
            l.contains("h265") || l.contains("av01") || l.contains("vp8") ||
            l.contains("vp9") || l.contains("/video")
    }

    private fun isAudioNamespace(ns: String): Boolean {
        val l = ns.lowercase()
        return l.contains("opus") || l.contains("mp4a") || l.contains("aac") || l.contains("/audio")
    }

    /** Longest common '/'-delimited path prefix across the given namespaces (null if empty). */
    private fun longestCommonPathPrefix(namespaces: List<String>): String? {
        if (namespaces.isEmpty()) return null
        return namespaces.map { it.split("/") }.reduce { acc, parts ->
            acc.zip(parts).takeWhile { it.first == it.second }.map { it.first }
        }.joinToString("/").ifEmpty { null }
    }

    /**
     * Stops the current media streaming session.
     *
     * This method performs complete cleanup:
     * 1. Unpublishes all local tracks (video + audio)
     * 2. Unsubscribes from meeting namespace
     * 3. Stops camera, encoders, and audio capture
     * 4. Clears jitter buffers and remote participants
     * 5. Stops jitter buffer monitoring
     *
     * The connection to the relay remains active. Call [disconnectFromRelay] to close it.
     *
     * **Thread Safety:** This method is synchronized to prevent concurrent cleanup.
     */
    @Synchronized
    fun stopSession() {
        // Unpublish all self tracks (video tracks + audio track)
        catalogManager.catalogTrackNamesUrl.forEach { trackName ->
            Log.i(tag, "Unpublishing track: $trackName")
            moqTransport.unpublishTrack(trackName)
        }

        // Note: Remote tracks are unsubscribed via namespace unsubscribe below

        // Unsubscribe from the namespace(s) we actually subscribed to (combined, or the
        // video/audio split when Top-N was active). Fall back to the catalog namespace.
        val subscribed = listOfNotNull(videoNamespacePrefix, audioNamespacePrefix).distinct()
        if (subscribed.isNotEmpty()) {
            subscribed.forEach { moqTransport.unsubscribeNamespace(it) }
        } else if (catalogManager.catalogNamespacesUrl.isNotEmpty()) {
            moqTransport.unsubscribeNamespace(catalogManager.catalogNamespacesUrl[0])
        }

        cleanup()
    }

    private fun cleanup() {
        // Reset Top-N publish state so a new session starts clean.
        SharedVoiceActivityState.reset()
        videoNamespacePrefix = null
        audioNamespacePrefix = null
        namespaceCallback = null

        // Stop jitter buffer monitoring first
        stopJitterBufferMonitoring()

        cameraManager.cleanup()

        videoEncoderManager.cleanup()

        audioManager.stopCapture()

        // Clear jitter buffers
        jitterBufferMonitor.clear()

        // Clear remote participants
        participantManager.clear()
    }

    /**
     * Resets catalog state for re-subscription.
     *
     * This clears the current catalog data and marks [isCatalogReady] as false.
     * Use this when the catalog needs to be reloaded (e.g., after connection loss).
     * Catalog will be automatically resubscribed when connection is re-established.
     */
    fun resetCatalog() {
        catalogManager.reset()
        _isCatalogReady.value = false
    }
}
