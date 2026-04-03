package com.cisco.quadroid.mediacodec

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
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
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

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
    private val bitRate = 2000000 
    private val frameRate = 30
    private val iFrameInterval = 5

    // Audio Config
    private val audioMimeType = MediaFormat.MIMETYPE_AUDIO_AAC
    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRate = 44100
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val audioBitRate = 64000
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)

    private var audioRecord: AudioRecord? = null
    private var isMicEnabled = true
    private var audioThread: Thread? = null

    private var audioEncoder: MediaCodec? = null
    private val audioEncoderThread = HandlerThread("VideoSessionManager_AudioEncoder").apply { start() }
    private val audioEncoderHandler = Handler(audioEncoderThread.looper)
    private var audioFramer: MoqAudioFramer? = null

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
    @Volatile
    private var audioEncoderOutputFormat: MediaFormat? = null
    private var formatLatch = CountDownLatch(1)
    private var audioFormatLatch = CountDownLatch(1)

    private val decoders = ConcurrentHashMap<String, MediaCodec>()
    private val decoderThreads = ConcurrentHashMap<String, HandlerThread>()
    private val decoderHandlers = ConcurrentHashMap<String, Handler>()

    private val audioDecoders = ConcurrentHashMap<String, MediaCodec>()
    private val audioTracks = ConcurrentHashMap<String, AudioTrack>()
    private val audioDecoderThreads = ConcurrentHashMap<String, HandlerThread>()
    private val audioDecoderHandlers = ConcurrentHashMap<String, Handler>()

    private val _remoteParticipants = MutableStateFlow<List<ParticipantStream>>(emptyList())
    val remoteParticipants: StateFlow<List<ParticipantStream>> = _remoteParticipants.asStateFlow()
    
    private val _videoAspectRatio = MutableStateFlow(height.toFloat() / width.toFloat())
    val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

    private var localPreviewSurface: Surface? = null
    
    private var videoFramer: MoqMediaFramer? = null
    private val localVideoTrackName = "webex.com/meeting123/alice/video"
    private val localAudioTrackName = "webex.com/meeting123/alice/audio"
    private val namespace: String = localVideoTrackName.substringBeforeLast("/")

    private val otherVideoTrackName = "webex.com/meeting123/bob/video"
    private val otherAudioTrackName = "webex.com/meeting123/bob/audio"

    val connectionStatus: StateFlow<MoqConnectionStatus> = moqTransport.connectionStatus

    fun connectToRelay(url: String) {
        val deviceId = DeviceIdentifier.get(context)
        moqTransport.connect(url, deviceId)
    }

    fun disconnectFromRelay() {
        moqTransport.disconnect()
    }

    fun startSession(lifecycleOwner: LifecycleOwner, rotation: Int, relayUrl: String) {
        stopSession()

        this.lifecycleOwner = lifecycleOwner
        this.rotation = rotation
        formatLatch = CountDownLatch(1)
        audioFormatLatch = CountDownLatch(1)
        cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        _isFrontCamera.value = true
        
        if (moqTransport.connectionStatus.value != MoqConnectionStatus.CONNECTED) {
            connectToRelay(relayUrl)
        }

        moqTransport.subscribeNamespace(namespace, object : NamespaceSubscriptionCallback {
            override fun onMatch(trackName: String): Boolean {
                if (trackName == otherAudioTrackName || trackName == otherVideoTrackName) return false
                
                Log.i(tag, "Namespace Match Found: $trackName")
                val trackKey = TrackUtil.generateTrackKeyFromFullName(trackName)
                
                moqTransport.trackCallbacks[trackKey] = object : MoqObjectCallback {
                    override fun onObject(trackName: String, groupId: Long, objectId: Long, payload: ByteBuffer) {
                        if (trackName.contains("video")) {
                            decoders[trackKey]?.let { decoder ->
                                try {
                                    val index = decoder.dequeueInputBuffer(0)
                                    if (index >= 0) {
                                        val inputBuffer = decoder.getInputBuffer(index)
                                        if (inputBuffer != null) {
                                            inputBuffer.clear()
                                            val size = payload.remaining() // Capture size BEFORE put()
                                            inputBuffer.put(payload)
                                            val flags = if (objectId == 0L) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                                            decoder.queueInputBuffer(index, 0, size, 0, flags)
                                            if (objectId % 100 == 0L) Log.d(tag, "Fed $size bytes to video decoder $trackKey")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(tag, "Error feeding video decoder for $trackKey", e)
                                }
                            }
                            // Fallback discovery in case onMatch is slow or skipped
                            addRemoteParticipant(trackKey)
                        } else if (trackName.contains("audio")) {
                            audioDecoders[trackKey]?.let { decoder ->
                                try {
                                    val index = decoder.dequeueInputBuffer(0)
                                    if (index >= 0) {
                                        val inputBuffer = decoder.getInputBuffer(index)
                                        if (inputBuffer != null) {
                                            inputBuffer.clear()
                                            val size = payload.remaining()
                                            inputBuffer.put(payload)
                                            decoder.queueInputBuffer(index, 0, size, 0, 0)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(tag, "Error feeding audio decoder for $trackKey", e)
                                }
                            } ?: run {
                                audioEncoderHandler.post {
                                    try {
                                        if (audioFormatLatch.await(2, TimeUnit.SECONDS)) {
                                            setupAudioDecoder(trackKey)
                                        }
                                    } catch (e: Exception) {}
                                }
                            }

                        }
                    }
                }
                
                return true
            }
        })

        setupEncoder()
        setupAudioEncoder()
        setupCamera(lifecycleOwner)
        startAudioCapture()
    }

    private fun addRemoteParticipant(trackKey: String) {
        if (_remoteParticipants.value.any { it.id == trackKey }) {
            return
        }
        
        // Ensure state update happens on Main Thread for Compose visibility
        Handler(context.mainLooper).post {
            if (!_remoteParticipants.value.any { it.id == trackKey }) {
                val stream = ParticipantStream(trackKey)
                _remoteParticipants.value = _remoteParticipants.value + stream
                Log.i(tag, "SUCCESS: Added remote participant to UI state: $trackKey. List size: ${_remoteParticipants.value.size}")
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
                        Log.i(tag, "Encoder format ready")
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

    private fun setupAudioEncoder() {
        val format = MediaFormat.createAudioFormat(audioMimeType, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, audioBitRate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }

        try {
            audioEncoder = MediaCodec.createEncoderByType(audioMimeType).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            audioEncoderHandler.post(object : Runnable {
                override fun run() {
                    val encoder = audioEncoder ?: return
                    val info = MediaCodec.BufferInfo()
                    try {
                        val index = encoder.dequeueOutputBuffer(info, 1000)
                        if (index >= 0) {
                            encoder.getOutputBuffer(index)?.let { buffer ->
                                if (info.size > 0) {
                                    audioFramer?.processFrame(buffer, info)
                                }
                            }
                            encoder.releaseOutputBuffer(index, false)
                        } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            audioEncoderOutputFormat = encoder.outputFormat
                            audioFormatLatch.countDown()
                        }
                        audioEncoderHandler.post(this)
                    } catch (e: IllegalStateException) {}
                    catch (e: Exception) { Log.e(tag, "Audio encoder output error", e) }
                }
            })
        } catch (e: Exception) {
            Log.e(tag, "Failed to setup audio encoder", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        try {
            audioRecord = AudioRecord(audioSource, sampleRate, channelConfigIn, audioFormat, bufferSize)
            audioRecord?.startRecording()
            audioThread = Thread {
                val byteBuffer = ByteBuffer.allocateDirect(bufferSize)
                while (!Thread.interrupted()) {
                    byteBuffer.clear()
                    val read = audioRecord?.read(byteBuffer, bufferSize) ?: 0
                    if (read > 0) {
                        if (!isMicEnabled) {
                            for (i in 0 until read) byteBuffer.put(i, 0)
                        }
                        feedAudioEncoder(byteBuffer, read)
                    }
                }
            }.apply { start() }
        } catch (e: Exception) {
            Log.e(tag, "Audio capture failed", e)
        }
    }

    private fun feedAudioEncoder(buffer: ByteBuffer, size: Int) {
        val encoder = audioEncoder ?: return
        try {
            val index = encoder.dequeueInputBuffer(0)
            if (index >= 0) {
                val inputBuffer = encoder.getInputBuffer(index)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    buffer.position(0)
                    buffer.limit(size)
                    val toCopy = min(size, inputBuffer.remaining())
                    inputBuffer.put(buffer)
                    encoder.queueInputBuffer(index, 0, toCopy, System.nanoTime() / 1000, 0)
                }
            }
        } catch (e: Exception) {}
    }

    fun onRemoteSurfaceReady(trackKey: String, surface: Surface) {
        Log.i(tag, "onRemoteSurfaceReady for trackKey: $trackKey")
        decoders[trackKey]?.setOutputSurface(surface) ?: run {
            val thread = HandlerThread("DecoderThread_$trackKey").apply { start() }
            val handler = Handler(thread.looper)
            decoderThreads[trackKey] = thread
            decoderHandlers[trackKey] = handler

            handler.post {
                try {
                    Log.d(tag, "Waiting for formatLatch for $trackKey...")
                    if (formatLatch.await(5, TimeUnit.SECONDS)) {
                        setupDecoder(trackKey, surface)
                    } else {
                        Log.e(tag, "Timeout waiting for encoder format! Using default.")
                        setupDecoder(trackKey, surface)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }
    
    fun onRemoteSurfaceDestroyed(trackKey: String) {
        Log.i(tag, "onRemoteSurfaceDestroyed for trackKey: $trackKey")
        synchronized(this) {
            decoderHandlers.remove(trackKey)?.removeCallbacksAndMessages(null)
            decoders.remove(trackKey)?.apply {
                try { stop(); release() } catch (e: Exception) { }
            }
            decoderThreads.remove(trackKey)?.quitSafely()
            
            audioDecoderHandlers.remove(trackKey)?.removeCallbacksAndMessages(null)
            audioDecoders.remove(trackKey)?.apply {
                try { stop(); release() } catch (e: Exception) { }
            }
            audioTracks.remove(trackKey)?.apply {
                try { stop(); release() } catch (e: Exception) { }
            }
            audioDecoderThreads.remove(trackKey)?.quitSafely()
        }
    }

    private fun setupDecoder(id: String, surface: Surface) {
        Log.i(tag, "Setting up video decoder for $id")
        
        // When decoding to a surface, we should NOT use the encoder's input format directly
        // because it contains COLOR_FormatSurface which is for ENCODER input only.
        val format = encoderOutputFormat?.let {
            val newFormat = MediaFormat.createVideoFormat(videoMimeType, width, height)
            if (it.containsKey("csd-0")) newFormat.setByteBuffer("csd-0", it.getByteBuffer("csd-0"))
            if (it.containsKey("csd-1")) newFormat.setByteBuffer("csd-1", it.getByteBuffer("csd-1"))
            newFormat
        } ?: MediaFormat.createVideoFormat(videoMimeType, width, height)
        
        val handler = decoderHandlers[id] ?: return

        try {
            val decoder = MediaCodec.createDecoderByType(videoMimeType)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            decoders[id] = decoder
            
            handler.post(object : Runnable {
                override fun run() {
                    if (decoders.containsKey(id)) {
                        val bufferInfo = MediaCodec.BufferInfo()
                        try {
                            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                            if (outIndex >= 0) {
                                // Render true makes it go to the surface
                                decoder.releaseOutputBuffer(outIndex, true)
                            }
                            handler.post(this)
                        } catch (e: Exception) { }
                    }
                }
            })
            Log.i(tag, "Decoder for $id started successfully")
        } catch (e: Exception) {
            Log.e(tag, "Failed to setup video decoder for $id", e)
        }
    }

    private fun setupAudioDecoder(id: String) {
        val format = audioEncoderOutputFormat ?: return
        val thread = HandlerThread("AudioDecoderThread_$id").apply { start() }
        val handler = Handler(thread.looper)
        audioDecoderThreads[id] = thread
        audioDecoderHandlers[id] = handler

        try {
            val decoder = MediaCodec.createDecoderByType(audioMimeType).apply {
                configure(format, null, null, 0)
                start()
            }
            audioDecoders[id] = decoder

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfigOut)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack.play()
            audioTracks[id] = audioTrack
            
            handler.post(object : Runnable {
                override fun run() {
                    if (audioDecoders.containsKey(id)) {
                        val bufferInfo = MediaCodec.BufferInfo()
                        try {
                            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                            if (outIndex >= 0) {
                                decoder.getOutputBuffer(outIndex)?.let { buffer ->
                                    audioTrack.write(buffer, bufferInfo.size, AudioTrack.WRITE_BLOCKING)
                                }
                                decoder.releaseOutputBuffer(outIndex, false)
                            }
                            handler.post(this)
                        } catch (e: Exception) { }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(tag, "Failed to setup audio decoder for $id", e)
        }
    }

    fun enableVideo(enabled: Boolean, owner: LifecycleOwner) {
        if (enabled) bindCameraUseCases(owner) else cameraProvider?.unbindAll()
    }
    
    fun enableAudio(enabled: Boolean) { isMicEnabled = enabled }

    @Synchronized
    fun stopSession() {
        cameraProvider?.unbindAll()
        
        encoderHandler.removeCallbacksAndMessages(null)
        val currentEncoder = encoder
        encoder = null
        try {
            currentEncoder?.stop()
            currentEncoder?.release()
        } catch (e: Exception) {}
        
        audioEncoderHandler.removeCallbacksAndMessages(null)
        val currentAudioEncoder = audioEncoder
        audioEncoder = null
        try {
            currentAudioEncoder?.stop()
            currentAudioEncoder?.release()
        } catch (e: Exception) {}

        audioThread?.interrupt()
        audioThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        decoderHandlers.forEach { (_, h) -> h.removeCallbacksAndMessages(null) }
        decoderHandlers.clear()
        decoders.forEach { (_, d) -> try { d.stop(); d.release() } catch(e: Exception) {} }
        decoders.clear()
        decoderThreads.forEach { (_, t) -> t.quitSafely() }
        decoderThreads.clear()
        
        audioDecoderHandlers.forEach { (_, h) -> h.removeCallbacksAndMessages(null) }
        audioDecoderHandlers.clear()
        audioDecoders.forEach { (_, d) -> try { d.stop(); d.release() } catch(e: Exception) {} }
        audioDecoders.clear()
        audioTracks.forEach { (_, t) -> try { t.stop(); t.release() } catch(e: Exception) {} }
        audioTracks.clear()
        audioDecoderThreads.forEach { (_, t) -> t.quitSafely() }
        audioDecoderThreads.clear()
        
        inputSurface?.release()
        inputSurface = null
        _remoteParticipants.value = emptyList()
        encoderOutputFormat = null
        audioEncoderOutputFormat = null
        
        formatLatch.countDown()
        audioFormatLatch.countDown()

        formatLatch = CountDownLatch(1)
        audioFormatLatch = CountDownLatch(1)
        videoFramer = null
        audioFramer = null
    }
}

data class ParticipantStream(val id: String)
