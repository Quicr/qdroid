package com.cisco.quadroid.mediacodec

import android.annotation.SuppressLint
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
import com.cisco.nativeaudio.NativeAudioLib
import com.cisco.quadroid.transport.MoqAudioFramer
import com.cisco.quadroid.transport.MoqConnectionStatus
import com.cisco.quadroid.transport.MoqMediaFramer
import com.cisco.quadroid.transport.MoqObjectCallback
import com.cisco.quadroid.transport.MoqTransport
import com.cisco.quadroid.transport.NamespaceSubscriptionCallback
import com.cisco.quadroid.util.DeviceIdentifier
import com.cisco.quadroid.util.TrackUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
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

    // Video Config
    private val videoMimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    private val width = 1280
    private val height = 720
    private val bitRate = 4000000
    private val frameRate = 30
    private val iFrameInterval = 2

    private val nativeAudioLib = NativeAudioLib()
    private var isMicEnabled = true

    private var cameraProvider: ProcessCameraProvider? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var rotation: Int = 0
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    
    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val encoderThread = HandlerThread("VideoSessionManager_Encoder").apply { start() }
    private val encoderHandler = Handler(encoderThread.looper)

    @Volatile
    private var encoderOutputFormat: MediaFormat? = null
    private var formatLatch = CountDownLatch(1)

    // Listeners for raw video objects
    private val videoFrameListeners = ConcurrentHashMap<String, (ByteArray, Long) -> Unit>()

    private val _remoteParticipants = MutableStateFlow<List<ParticipantStream>>(emptyList())
    val remoteParticipants: StateFlow<List<ParticipantStream>> = _remoteParticipants.asStateFlow()
    
    private val _videoAspectRatio = MutableStateFlow(height.toFloat() / width.toFloat())
    val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

    private var localPreviewSurface: Surface? = null
    
    private var videoFramer: MoqMediaFramer? = null
    private var audioFramer: MoqAudioFramer? = null
    
    // Meeting Configuration
    private val meetingId = "meeting123"
    private val userName = "alice"
    private val localPrefix = "webex.com/$meetingId/$userName"
    private val meetingNamespace = "webex.com/$meetingId"

    private val localVideoTrackName = "$localPrefix/video"
    private val localAudioTrackName = "$localPrefix/audio"
    private val remoteNamespace = "$meetingNamespace/bob"
    private val remoteVideoTrackName = "$remoteNamespace/video"
    private val remoteAudioTrackName = "$remoteNamespace/audio"
    
    val connectionStatus: StateFlow<MoqConnectionStatus> = moqTransport.connectionStatus

    fun connectToRelay(url: String) {
        val deviceId = DeviceIdentifier.get(context)
        moqTransport.connect(url, deviceId)
    }

    fun disconnectFromRelay() {
        moqTransport.disconnect()
    }

    fun addVideoFrameListener(trackKey: String, listener: (ByteArray, Long) -> Unit) {
        Log.d(tag, "addVideoFrameListener for $trackKey")
        videoFrameListeners[trackKey] = listener
    }

    fun removeVideoFrameListener(trackKey: String) {
        Log.d(tag, "removeVideoFrameListener for $trackKey")
        videoFrameListeners.remove(trackKey)
    }

    fun startSession(lifecycleOwner: LifecycleOwner, rotation: Int, relayUrl: String) {
        stopSession()

        this.lifecycleOwner = lifecycleOwner
        this.rotation = rotation
        formatLatch = CountDownLatch(1)
        cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        _isFrontCamera.value = true
        
        if (moqTransport.connectionStatus.value != MoqConnectionStatus.CONNECTED) {
            connectToRelay(relayUrl)
        }

        // Subscribe to meeting namespace to discover all participants
        Log.i(tag, "Subscribing to namespace: $remoteNamespace")
        moqTransport.subscribeNamespace(remoteNamespace, object : NamespaceSubscriptionCallback {
            override fun onMatch(trackName: String): Boolean {
                if (trackName == localVideoTrackName || trackName == localAudioTrackName) {
                    Log.d(tag, "Ignoring own track: $trackName")
                    return false
                }
                
                Log.i(tag, "Track Discovered: $trackName")
                val trackKey = TrackUtil.generateTrackKeyFromFullName(trackName)

                if (trackName.contains("audio")) {
                    Log.i(tag, "Starting native audio playback for $trackName")
                    nativeAudioLib.startPlayback(trackKey)
                }

                moqTransport.trackCallbacks[trackKey] = object : MoqObjectCallback {
                    override fun onObject(trackName: String, groupId: Long, objectId: Long, payload: ByteBuffer) {
                        if (trackName.contains("video")) {
                            val listener = videoFrameListeners[trackKey]
                            if (listener != null) {
                                val bytes = ByteArray(payload.remaining())
                                payload.get(bytes)
                                listener(bytes, System.nanoTime() / 1000)
                            } else {
                                if (objectId % 100 == 0L) {
                                    Log.w(tag, "No video listener for track $trackKey (trackName=$trackName)")
                                }
                            }
                            addRemoteParticipant(trackKey)
                        } else if (trackName.contains("audio")) {
                            nativeAudioLib.feedDecoder(trackKey, payload, payload.remaining())
                        }
                    }
                }
                
                return true
            }
        })


        // Publish our tracks
        Log.i(tag, "Publishing tracks: $localAudioTrackName, $localVideoTrackName")
        moqTransport.publish(localVideoTrackName)
        videoFramer = MoqMediaFramer(moqTransport, localVideoTrackName)

        moqTransport.publish(localAudioTrackName)
        audioFramer = MoqAudioFramer(moqTransport, localAudioTrackName)

        setupEncoder()
        setupCamera(lifecycleOwner)
        startNativeAudio()
    }

    private fun startNativeAudio() {
        nativeAudioLib.startCapture(object : NativeAudioLib.NativeAudioCallback {
            override fun onAudioEncoded(payload: ByteBuffer, size: Int, presentationTimeUs: Long) {
                if (isMicEnabled) {
                    val info = MediaCodec.BufferInfo()
                    info.set(0, size, presentationTimeUs, 0)
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

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(width, height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()

        val encoderPreview = Preview.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(resolutionSelector)
            .build().apply {
                setSurfaceProvider { request ->
                    inputSurface?.let { 
                        request.provideSurface(it, ContextCompat.getMainExecutor(context)) {} 
                    }
                }
            }

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
            if (localPreviewSurface != null) {
                provider.bindToLifecycle(owner, cameraSelector, encoderPreview, localUiPreview)
            } else {
                provider.bindToLifecycle(owner, cameraSelector, encoderPreview)
            }
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

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(videoMimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            // Prepend SPS/PPS to keyframes for easier decoding by late-joiners
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
        }

        try {
            encoder = MediaCodec.createEncoderByType(videoMimeType).apply {
                setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        try {
                            getOutputBuffer(index)?.let { buffer ->
                                if (info.size > 0) {
                                    videoFramer?.processFrame(buffer, info)
                                }
                            }
                            releaseOutputBuffer(index, false)
                        } catch (e: IllegalStateException) {}
                    }
                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { Log.e(tag, "Encoder Error", e) }
                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        encoderOutputFormat = format
                        formatLatch.countDown()
                    }
                }, encoderHandler)
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                this@VideoSessionManager.inputSurface = createInputSurface()
                start()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to setup video encoder", e)
        }
    }

    fun enableVideo(enabled: Boolean, owner: LifecycleOwner) {
        if (enabled) bindCameraUseCases(owner) else cameraProvider?.unbindAll()
    }
    
    fun enableAudio(enabled: Boolean) { isMicEnabled = enabled }

    @Synchronized
    fun stopSession() {
        //Unpublish self tracks
        moqTransport.unpublishTrack(localVideoTrackName)
        moqTransport.unpublishTrack(localAudioTrackName)
        //Unsubscribe namespace
        moqTransport.unsubscribeNamespace(localPrefix)



        cameraProvider?.unbindAll()
        
        encoderHandler.removeCallbacksAndMessages(null)
        val currentEncoder = encoder
        encoder = null
        try {
            currentEncoder?.stop()
            currentEncoder?.release()
        } catch (e: Exception) {}
        
        nativeAudioLib.stopCapture()
        
        inputSurface?.release()
        inputSurface = null
        _remoteParticipants.value = emptyList()
        encoderOutputFormat = null
        
        formatLatch.countDown()
        formatLatch = CountDownLatch(1)
        videoFramer = null
        audioFramer = null
        videoFrameListeners.clear()
    }
}

data class ParticipantStream(val id: String, val aspectRatio: Float = 16f / 9f)
