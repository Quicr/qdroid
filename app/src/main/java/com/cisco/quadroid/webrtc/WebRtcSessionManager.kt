package com.cisco.quadroid.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcSessionManager @Inject constructor(
    private val context: Context
) {
    private val rootEglBase: EglBase = EglBase.create()
    
    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack

    private val _remoteVideoTracks = MutableStateFlow<List<VideoTrack>>(emptyList())
    val remoteVideoTracks: StateFlow<List<VideoTrack>> = _remoteVideoTracks

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoSource: VideoSource? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    init {
        initPeerConnectionFactory()
    }

    fun getEglBaseContext(): EglBase.Context = rootEglBase.eglBaseContext

    private fun initPeerConnectionFactory() {
        if (peerConnectionFactory != null) return

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    fun setupLocalStream() {
        if (localVideoTrack.value != null) return

        val factory = peerConnectionFactory ?: return

        val videoSource = factory.createVideoSource(false)
        localVideoSource = videoSource
        
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
        videoCapturer = createVideoCapturer()
        videoCapturer?.initialize(
            surfaceTextureHelper,
            context,
            videoSource.capturerObserver
        )
        videoCapturer?.startCapture(1280, 720, 30)

        val videoTrack = factory.createVideoTrack("VIDEO_TRACK_ID", videoSource)
        _localVideoTrack.value = videoTrack

        localAudioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("AUDIO_TRACK_ID", localAudioSource)
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return null
    }

    fun simulateRemoteParticipant() {
        localVideoTrack.value?.let { track ->
            if (_remoteVideoTracks.value.size < 3) {
                _remoteVideoTracks.value = _remoteVideoTracks.value + track
            }
        }
    }

    fun enableVideo(enabled: Boolean) {
        _localVideoTrack.value?.setEnabled(enabled)
    }

    fun enableAudio(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun startVideo() {
        videoCapturer?.startCapture(1280, 720, 30)
    }

    fun stopVideo() {
        videoCapturer?.stopCapture()
    }

    fun disconnect() {
        _localVideoTrack.value = null
        _remoteVideoTracks.value = emptyList()

        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            localVideoSource?.dispose()
            localVideoSource = null

            localAudioSource?.dispose()
            localAudioSource = null

            localAudioTrack?.dispose()
            localAudioTrack = null

            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
        } catch (e: Exception) {
            Log.e("WebRtcSessionManager", "Error during disconnect", e)
        }
    }

    fun destroy() {
        disconnect()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        rootEglBase.release()
    }
}
