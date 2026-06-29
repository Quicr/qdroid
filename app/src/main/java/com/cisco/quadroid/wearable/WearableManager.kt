// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.wearable

import android.app.Activity
import android.util.Log
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayConfiguration
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.meta.wearable.dat.core.session.DeviceSessionState

private const val TAG = "WearableManager"

/**
 * Thin lifecycle wrapper around the DAT SDK.
 *
 * The SDK only allows one DeviceSession per device at a time. Stream and display
 * capabilities are both attached to [sharedSession]; it is created on first use
 * and torn down only when both capabilities have been stopped.
 *
 * Wearables.initialize() is called once in QuadroidApp.onCreate().
 */
class WearableManager(private val scope: CoroutineScope) {

    private val _registrationState = MutableStateFlow(RegistrationState.UNAVAILABLE)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _streamState = MutableStateFlow(StreamState.STOPPED)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    private val _displayState = MutableStateFlow(DisplayState.STOPPED)
    val displayState: StateFlow<DisplayState> = _displayState.asStateFlow()

    // Single session shared by both stream and display capabilities.
    private var sharedSession: DeviceSession? = null
    private var activeStream: Stream? = null
    private var hasActiveDisplay = false

    /** Set by the Activity after registering Wearables.RequestPermissionContract(). */
    var permissionLauncher: ((Permission) -> Unit)? = null

    fun observeRegistration() {
        scope.launch {
            Wearables.registrationState.collect { state ->
                Log.d(TAG, "registrationState -> $state")
                _registrationState.value = state
            }
        }
        scope.launch {
            Wearables.registrationErrorStream.collect { error ->
                Log.e(TAG, "registrationError -> ${error.description} ($error)")
            }
        }
        scope.launch {
            Wearables.devices.collect { devices ->
                Log.d(TAG, "paired devices (${devices.size}): $devices")
            }
        }
    }

    /**
     * Called after BLUETOOTH_CONNECT is granted post-init. The DAT SDK re-queries
     * Bluetooth-paired devices when its underlying service re-binds; restarting
     * observation causes the StateFlow to deliver a fresh value.
     */
    fun reinitialize() {
        Log.d(TAG, "reinitialize() — re-observing after BLUETOOTH_CONNECT granted")
        observeRegistration()
    }

    fun launchRegistration(activity: Activity) {
        Wearables.startRegistration(activity)
    }

    fun launchUnregistration(activity: Activity) {
        Wearables.startUnregistration(activity)
    }

    // Returns the existing session (if STARTED/PAUSED) or creates and starts a new one.
    // Suspends until the session reaches STARTED, or returns null on failure/timeout.
    private suspend fun getOrCreateSession(): DeviceSession? {
        sharedSession?.let { existing ->
            val s = existing.state.value
            if (s == DeviceSessionState.STARTED || s == DeviceSessionState.PAUSED) return existing
            // Session exists but is STOPPED — clear it and create a fresh one.
            sharedSession = null
            runCatching { existing.stop() }
        }
        var sessionError: String? = null
        val session: DeviceSession? = Wearables.createSession(AutoDeviceSelector())
            .fold(
                onSuccess = { it },
                onFailure = { err, ex ->
                    sessionError = err?.description ?: ex?.message ?: "unknown"
                    null
                }
            )
        if (session == null) {
            Log.e(TAG, "createSession failed: $sessionError")
            return null
        }
        sharedSession = session
        session.start()
        // Wait for the session to become active before attaching capabilities.
        val started = withTimeoutOrNull(10_000) {
            session.state.first { it == DeviceSessionState.STARTED }
        }
        if (started == null) {
            Log.e(TAG, "session did not reach STARTED within 10s (state=${session.state.value})")
            sharedSession = null
            runCatching { session.stop() }
            return null
        }
        Log.d(TAG, "session STARTED")
        return session
    }

    // Stops and clears the shared session only when no capability is still active.
    private fun releaseSessionIfIdle() {
        if (activeStream != null || hasActiveDisplay) return
        val session = sharedSession ?: return
        sharedSession = null
        runCatching { session.stop() }
            .onFailure { Log.w(TAG, "session.stop() threw", it) }
    }

    fun startGlassesStream(
        onFrame: (VideoFrame) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            val permResult = Wearables.checkPermissionStatus(Permission.CAMERA)
            val permStatus = permResult.getOrNull()
            Log.d(TAG, "DAT camera permission: $permStatus")
            if (permStatus !is PermissionStatus.Granted) {
                val launcher = permissionLauncher
                if (launcher != null) {
                    Log.d(TAG, "Launching DAT camera permission request via Meta AI")
                    launcher(Permission.CAMERA)
                } else {
                    val errMsg = permResult.errorOrNull()?.description ?: "status=$permStatus"
                    Log.e(TAG, "DAT camera permission not granted and no launcher set: $errMsg")
                    onError("Camera permission not granted in Meta AI app ($errMsg)")
                }
                return@launch
            }

            val session = getOrCreateSession() ?: run {
                onError("Failed to create glasses session")
                return@launch
            }

            val config = StreamConfiguration(
                videoQuality = VideoQuality.MEDIUM,
                frameRate = 24,
            )
            var streamError: String? = null
            val stream: Stream? = session.addStream(config)
                .fold(
                    onSuccess = { it },
                    onFailure = { err, ex ->
                        streamError = err?.description ?: ex?.message ?: "unknown"
                        null
                    }
                )
            if (stream == null) {
                Log.e(TAG, "addStream failed: $streamError")
                onError("Failed to open camera stream: $streamError")
                releaseSessionIfIdle()
                return@launch
            }

            activeStream = stream
            launch {
                stream.state.collect { state ->
                    Log.d(TAG, "streamState -> $state")
                    _streamState.value = state
                }
            }
            launch {
                stream.videoStream.collect { frame -> onFrame(frame) }
            }
            stream.start()
        }
    }

    fun stopGlassesStream() {
        val stream = activeStream ?: return
        activeStream = null
        runCatching { stream.stop() }
            .onFailure { Log.w(TAG, "stream.stop() threw", it) }
        _streamState.value = StreamState.STOPPED
        releaseSessionIfIdle()
    }

    fun startGlassesDisplay(label: String = "Qdroid") {
        scope.launch {
            val session = getOrCreateSession() ?: return@launch

            val display = session.addDisplay(DisplayConfiguration()).getOrNull()
            if (display == null) {
                Log.e(TAG, "startGlassesDisplay: addDisplay returned null")
                releaseSessionIfIdle()
                return@launch
            }
            hasActiveDisplay = true
            launch {
                display.state.collect { state ->
                    Log.d(TAG, "displayState -> $state")
                    _displayState.value = state
                }
            }

            display.sendContent {
                flexBox {
                    text(label, style = TextStyle.HEADING)
                }
            }
        }
    }

    fun stopGlassesDisplay() {
        hasActiveDisplay = false
        runCatching { sharedSession?.removeDisplay() }
            .onFailure { Log.w(TAG, "removeDisplay() threw", it) }
        _displayState.value = DisplayState.STOPPED
        releaseSessionIfIdle()
    }
}
