package com.cisco.quadroid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cisco.quadroid.webrtc.WebRtcSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val webRtcSessionManager: WebRtcSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<CallUiState>(CallUiState.Lobby)
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private val _isMicEnabled = MutableStateFlow(true)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    private val _isVideoEnabled = MutableStateFlow(true)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled.asStateFlow()

    val localVideoTrack: StateFlow<VideoTrack?> = webRtcSessionManager.localVideoTrack
    val remoteVideoTracks: StateFlow<List<VideoTrack>> = webRtcSessionManager.remoteVideoTracks

    fun getEglBaseContext() = webRtcSessionManager.getEglBaseContext()

    fun startCall() {
        viewModelScope.launch {
            webRtcSessionManager.setupLocalStream()
            _uiState.value = CallUiState.InCall
        }
    }

    fun toggleVideo() {
        val newState = !_isVideoEnabled.value
        _isVideoEnabled.value = newState
        webRtcSessionManager.enableVideo(newState)
    }

    fun toggleAudio() {
        val newState = !_isMicEnabled.value
        _isMicEnabled.value = newState
        webRtcSessionManager.enableAudio(newState)
    }

    fun simulateParticipant() {
        webRtcSessionManager.simulateRemoteParticipant()
    }

    fun endCall() {
        webRtcSessionManager.disconnect()
        _uiState.value = CallUiState.Lobby
        _isMicEnabled.value = true
        _isVideoEnabled.value = true
    }

    fun navigateToSettings() {
        _uiState.value = CallUiState.Settings
    }

    fun saveSettings() {
        _uiState.value = CallUiState.Lobby
    }

    fun onStart() {
        if (_uiState.value is CallUiState.InCall) {
            webRtcSessionManager.startVideo()
        }
    }

    fun onStop() {
        if (_uiState.value is CallUiState.InCall) {
            webRtcSessionManager.stopVideo()
        }
    }

    override fun onCleared() {
        super.onCleared()
        webRtcSessionManager.disconnect()
    }
}

sealed class CallUiState {
    object Lobby : CallUiState()
    object InCall : CallUiState()
    object Settings : CallUiState()
}
