// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid

import android.app.Activity
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cisco.quadroid.mediacodec.model.ParticipantStream
import com.cisco.quadroid.mediacodec.VideoSessionManager
import com.cisco.quadroid.transport.MoqConnectionStatus
import com.cisco.quadroid.wearable.WearableManager
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.types.DisplayState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CameraSource { PHONE, GLASSES }

@HiltViewModel
class MainViewModel @Inject constructor(
    private val videoSessionManager: VideoSessionManager
) : ViewModel() {

    private val wearableManager = WearableManager(viewModelScope)

    val registrationState: StateFlow<RegistrationState> = wearableManager.registrationState
    val displayState: StateFlow<DisplayState> = wearableManager.displayState

    private val _cameraSource = MutableStateFlow(CameraSource.PHONE)
    val cameraSource: StateFlow<CameraSource> = _cameraSource.asStateFlow()

    init {
        wearableManager.observeRegistration()
    }

    //Relay config - Managed via settings
    var relay_url: String = "moq://eng-3.us-west-2.m10x.org:33550"
    private var last_connected_url: String? = null


    private val _uiState = MutableStateFlow<CallUiState>(CallUiState.Lobby)
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private val _isMicEnabled = MutableStateFlow(true)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    private val _isVideoEnabled = MutableStateFlow(true)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled.asStateFlow()
    
    private val _videoToggleCount = MutableStateFlow(0)
    val videoToggleCount: StateFlow<Int> = _videoToggleCount.asStateFlow()

    val isFrontCamera: StateFlow<Boolean> = videoSessionManager.isFrontCamera

    val videoAspectRatio: StateFlow<Float> = videoSessionManager.videoAspectRatio

    val remoteParticipants: StateFlow<List<ParticipantStream>> = videoSessionManager.remoteParticipants

    val connectionStatus: StateFlow<MoqConnectionStatus> = videoSessionManager.connectionStatus

    val isCatalogReady: StateFlow<Boolean> = videoSessionManager.isCatalogReady

    fun connectToRelay() {
        if (relay_url != last_connected_url || videoSessionManager.connectionStatus.value == MoqConnectionStatus.DISCONNECTED || videoSessionManager.connectionStatus.value == MoqConnectionStatus.IDLE) {
            videoSessionManager.connectToRelay(relay_url)
            last_connected_url = relay_url
        }
    }

    fun disconnectFromRelay() {
        videoSessionManager.resetCatalog()
        videoSessionManager.disconnectFromRelay()
        last_connected_url = null
    }

    fun startCall(lifecycleOwner: LifecycleOwner, rotation: Int, source: CameraSource = CameraSource.PHONE) {
        _cameraSource.value = source
        viewModelScope.launch {
            videoSessionManager.startSession(lifecycleOwner, rotation, relay_url, glassesCamera = source == CameraSource.GLASSES)
            _uiState.value = CallUiState.InCall
            if (source == CameraSource.GLASSES) {
                startGlassesStream()
            }
        }
    }

    fun toggleVideo(lifecycleOwner: LifecycleOwner) {
        val newState = !_isVideoEnabled.value
        _isVideoEnabled.value = newState
        if(newState) {
             _videoToggleCount.value++
        }
        videoSessionManager.enableVideo(newState, lifecycleOwner)
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner) {
        videoSessionManager.switchCamera(lifecycleOwner)
    }

    fun toggleAudio() {
        val newState = !_isMicEnabled.value
        _isMicEnabled.value = newState
        videoSessionManager.enableAudio(newState)
    }

    fun addVideoFrameListener(trackKey: String, listener: (ByteArray, Long) -> Unit) {
        videoSessionManager.addVideoFrameListener(trackKey, listener)
    }

    fun removeVideoFrameListener(trackKey: String) {
        videoSessionManager.removeVideoFrameListener(trackKey)
    }

    fun onLocalPreviewSurfaceReady(surface: android.view.Surface) {
        videoSessionManager.setLocalPreviewSurface(surface)
    }

    fun setDatCameraPermissionLauncher(launcher: (Permission) -> Unit) {
        wearableManager.permissionLauncher = launcher
    }

    fun reinitializeWearables() {
        wearableManager.reinitialize()
    }

    fun launchGlassesRegistration(activity: Activity) {
        wearableManager.launchRegistration(activity)
    }

    fun startGlassesStream() {
        wearableManager.startGlassesStream(
            onFrame = { frame -> onGlassesFrame(frame) },
            onError = { err -> Log.e("MainViewModel", "glasses stream error: $err") },
        )
    }

    private fun onGlassesFrame(frame: VideoFrame) {
        if (frame.isCodecConfig) return  // SPS/PPS config — not a display frame
        val data = ByteArray(frame.buffer.remaining()).also { frame.buffer.get(it) }
        val isKeyframe = !frame.isCompressed  // raw = keyframe boundary; compressed = delta
        videoSessionManager.feedGlassesFrame(data, frame.presentationTimeUs, isKeyframe)
    }

    fun setGlassesPreviewListener(listener: (ByteArray, Long) -> Unit) {
        videoSessionManager.setGlassesPreviewListener(listener)
    }

    fun clearGlassesPreviewListener() {
        videoSessionManager.clearGlassesPreviewListener()
    }

    fun stopGlassesStream() {
        wearableManager.stopGlassesStream()
        videoSessionManager.clearGlassesPreviewListener()
    }

    fun startGlassesDisplay(label: String = "Qdroid") {
        wearableManager.startGlassesDisplay(label)
    }

    fun stopGlassesDisplay() {
        wearableManager.stopGlassesDisplay()
    }

    fun endCall() {
        if (_cameraSource.value == CameraSource.GLASSES) {
            stopGlassesStream()
        }
        videoSessionManager.stopSession()
        _uiState.value = CallUiState.Lobby
        _isMicEnabled.value = true
        _isVideoEnabled.value = true
        _cameraSource.value = CameraSource.PHONE
    }

    fun navigateToSettings() {
        _uiState.value = CallUiState.Settings
    }

    fun saveSettings() {
        _uiState.value = CallUiState.Lobby
        // Trigger reconnect if URL changed in settings
        connectToRelay()
    }

    fun setVadEnabled(enabled: Boolean) {
        videoSessionManager.setVadEnabled(enabled)
    }
}

sealed class CallUiState {
    object Lobby : CallUiState()
    object InCall : CallUiState()
    object Settings : CallUiState()
}
