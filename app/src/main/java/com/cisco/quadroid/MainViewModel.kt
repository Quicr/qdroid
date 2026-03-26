package com.cisco.quadroid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cisco.quadroid.webrtc.WebRtcSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val webRtcSessionManager: WebRtcSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<CallUiState>(CallUiState.Lobby)
    val uiState: StateFlow<CallUiState> = _uiState

    val localVideoTrack: StateFlow<VideoTrack?> = webRtcSessionManager.localVideoTrack
    val remoteVideoTracks: StateFlow<List<VideoTrack>> = webRtcSessionManager.remoteVideoTracks

    fun getEglBaseContext() = webRtcSessionManager.getEglBaseContext()

    fun startCall() {
        viewModelScope.launch {
            webRtcSessionManager.setupLocalStream()
            _uiState.value = CallUiState.InCall
        }
    }

    fun simulateParticipant() {
        webRtcSessionManager.simulateRemoteParticipant()
    }

    fun endCall() {
        webRtcSessionManager.disconnect()
        _uiState.value = CallUiState.Lobby
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
