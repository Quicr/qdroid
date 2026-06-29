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
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "WearableManager"

/**
 * Thin lifecycle wrapper around the DAT SDK.
 *
 * Call [observeRegistration] once (e.g. from a ViewModel init block) and
 * [launchRegistration] / [launchUnregistration] from UI. The [startGlassesStream]
 * / [stopGlassesStream] pair manages the camera session.
 *
 * Wearables.initialize() is called once in QuadroidApp.onCreate().
 */
class WearableManager(private val scope: CoroutineScope) {

    private val _registrationState = MutableStateFlow(RegistrationState.UNAVAILABLE)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _streamState = MutableStateFlow(StreamState.STOPPED)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    private var activeSession: DeviceSession? = null

    fun observeRegistration() {
        scope.launch {
            Wearables.registrationState.collect { state ->
                Log.d(TAG, "registrationState -> $state")
                _registrationState.value = state
            }
        }
        scope.launch {
            Wearables.devices.collect { devices ->
                Log.d(TAG, "paired devices: $devices")
            }
        }
    }

    fun launchRegistration(activity: Activity) {
        Wearables.startRegistration(activity)
    }

    fun launchUnregistration(activity: Activity) {
        Wearables.startUnregistration(activity)
    }

    /**
     * Opens a DAT camera session on whichever glasses are currently paired.
     * [onFrame] is called on each decoded video frame.
     */
    fun startGlassesStream(
        onFrame: (VideoFrame) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            val session: DeviceSession? = Wearables.createSession(AutoDeviceSelector()).getOrNull()
            if (session == null) {
                Log.e(TAG, "createSession returned null")
                onError("Failed to create glasses session")
                return@launch
            }
            activeSession = session
            session.start()

            val config = StreamConfiguration(
                videoQuality = VideoQuality.MEDIUM,
                frameRate = 24,
            )
            val stream: Stream? = session.addStream(config).getOrNull()
            if (stream == null) {
                Log.e(TAG, "addStream returned null")
                onError("Failed to open camera stream")
                return@launch
            }

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
        val session = activeSession ?: return
        activeSession = null
        runCatching { session.stop() }
            .onFailure { Log.w(TAG, "session.stop() threw", it) }
        _streamState.value = StreamState.STOPPED
    }
}
