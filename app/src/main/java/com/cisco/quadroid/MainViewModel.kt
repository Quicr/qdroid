package com.cisco.quadroid

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cisco.quadroid.mediacodec.ParticipantStream
import com.cisco.quadroid.mediacodec.VideoSessionManager
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

    private val _uiState = MutableStateFlow<CallUiState>(CallUiState.Lobby)
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private val _isMicEnabled = MutableStateFlow(true)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    private val _isVideoEnabled = MutableStateFlow(true)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled.asStateFlow()
    
    private val _videoToggleCount = MutableStateFlow(0)
    val videoToggleCount: StateFlow<Int> = _videoToggleCount.asStateFlow()

    val remoteParticipants: StateFlow<List<ParticipantStream>> = videoSessionManager.remoteParticipants

    fun startCall(lifecycleOwner: LifecycleOwner) {
        viewModelScope.launch {
            videoSessionManager.startSession(lifecycleOwner)
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

    fun toggleAudio() {
        val newState = !_isMicEnabled.value
        _isMicEnabled.value = newState
        videoSessionManager.enableAudio(newState)
    }

    fun simulateParticipant() {
        videoSessionManager.addSimulatedParticipant()
    }

    fun onLocalPreviewSurfaceReady(surface: android.view.Surface) {
        videoSessionManager.setLocalPreviewSurface(surface)
    }

    fun onRemoteSurfaceReady(participantId: String, surface: android.view.Surface) {
        videoSessionManager.onRemoteSurfaceReady(participantId, surface)
    }
    
    fun onRemoteSurfaceDestroyed(participantId: String) {
        videoSessionManager.onRemoteSurfaceDestroyed(participantId)
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
    }
}

sealed class CallUiState {
    object Lobby : CallUiState()
    object InCall : CallUiState()
    object Settings : CallUiState()
}
