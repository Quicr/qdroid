package com.cisco.quadroid

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cisco.quadroid.mediacodec.ParticipantStream
import com.cisco.quadroid.mediacodec.VideoSessionManager
import com.cisco.quadroid.transport.MoqConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val videoSessionManager: VideoSessionManager
) : ViewModel() {

    //Relay config - TODO - This should come in via catalog or settings
    var relay_url:String = "moq://eng-3.us-west-2.m10x.org:33550" //"moq://eng-3.us-west-2.m10x.org:33660-cloud flare relay"//"moq://eng-1.us-west-2.m10x.org:33440" //"moq://relay.us-west-2.m10x.org:33437" //"moq://eng-1.us-west-2.m10x.org:33440" //"moq://eng-3.us-west-2.m10x.org:33660"
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

    fun connectToRelay() {
        if (relay_url != last_connected_url || videoSessionManager.connectionStatus.value == MoqConnectionStatus.DISCONNECTED || videoSessionManager.connectionStatus.value == MoqConnectionStatus.IDLE) {
            videoSessionManager.connectToRelay(relay_url)
            last_connected_url = relay_url
        }
    }

    fun disconnectFromRelay() {
        videoSessionManager.disconnectFromRelay()
        last_connected_url = null
    }

    fun startCall(lifecycleOwner: LifecycleOwner, rotation: Int) {
        viewModelScope.launch {
            videoSessionManager.startSession(lifecycleOwner, rotation, relay_url)
            _uiState.value = CallUiState.InCall
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

    fun onLocalPreviewSurfaceReady(surface: android.view.Surface) {
        videoSessionManager.setLocalPreviewSurface(surface)
    }

    fun onRemoteSurfaceReady(trackKey: String, surface: android.view.Surface) {
        videoSessionManager.onRemoteSurfaceReady(trackKey, surface)
    }

    fun onRemoteSurfaceDestroyed(trackKey: String) {
        videoSessionManager.onRemoteSurfaceDestroyed(trackKey)
    }

    fun endCall() {
        videoSessionManager.stopSession()
        _uiState.value = CallUiState.Lobby
        _isMicEnabled.value = true
        _isVideoEnabled.value = true
    }

    fun navigateToSettings() {
        _uiState.value = CallUiState.Settings
    }

    fun saveSettings() {
        _uiState.value = CallUiState.Lobby
        // Trigger reconnect if URL changed in settings
        connectToRelay()
    }
}

sealed class CallUiState {
    object Lobby : CallUiState()
    object InCall : CallUiState()
    object Settings : CallUiState()
}
