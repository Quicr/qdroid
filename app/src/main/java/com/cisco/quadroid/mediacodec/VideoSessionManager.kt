package com.cisco.quadroid.mediacodec

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.cisco.quadroid.transport.MoqAudioFramer
import com.cisco.quadroid.transport.MoqMediaFramer
import com.cisco.quadroid.transport.MoqObjectCallback
import com.cisco.quadroid.transport.MoqTransport
import com.cisco.quadroid.transport.NamespaceSubscriptionCallback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
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
    private val localVideoTrackName = "quadroid/video/${UUID.randomUUID()}"
    private val localAudioTrackName = "quadroid/audio/${UUID.randomUUID()}"

    // Tracks the first real remote participant to duplicate for "addParticipant"
    private var primaryRemoteVideoTrack: String? = null
    private var primaryRemoteAudioTrack: String? = null

    fun startSession(lifecycleOwner: LifecycleOwner, rotation: Int, relayUrl: String) {
        this.lifecycleOwner = lifecycleOwner
        this.rotation = rotation
        formatLatch = CountDownLatch(1)
        audioFormatLatch = CountDownLatch(1)
        primaryRemoteVideoTrack = null
        primaryRemoteAudioTrack = null
        
        moqTransport.connect(relayUrl)
        
        moqTransport.publish(localVideoTrackName)
        videoFramer = MoqMediaFramer(moqTransport, localVideoTrackName)
        
        moqTransport.publish(localAudioTrackName)
        audioFramer = MoqAudioFramer(moqTransport, localAudioTrackName)
        
        setupEncoder()
        setupAudioEncoder()
        setupCamera(lifecycleOwner)
        startAudioCapture()
        
        moqTransport.subscribeNamespace("quadroid/video/", object : NamespaceSubscriptionCallback {
            override fun onMatch(trackName: String): Boolean {
                return if (trackName != localVideoTrackName) {
                    if (primaryRemoteVideoTrack == null) primaryRemoteVideoTrack = trackName
                    addRemoteVideoParticipant(trackName)
                    true
                } else {
                    false
                }
            }
        })

        moqTransport.subscribeNamespace("quadroid/audio/", object : NamespaceSubscriptionCallback {
            override fun onMatch(trackName: String): Boolean {
                return if (trackName != localAudioTrackName) {
                    if (primaryRemoteAudioTrack == null) primaryRemoteAudioTrack = trackName
                    addRemoteAudioParticipant(trackName)
                    true
                } else {
                    false
                }
            }
        })
    }

    fun addParticipant() {
        if (_remoteParticipants.value.size >= 3) return
        val trackToDuplicate = primaryRemoteVideoTrack ?: return // Need at least one real participant
        
        // Create a virtual participant that subscribes to the same track
        val virtualId = "virtual_${UUID.randomUUID()}"
        val stream = ParticipantStream(virtualId)
        _remoteParticipants.value = _remoteParticipants.value + stream
        
        // Subscribe the same video track to a new decoder for this virtual slot
        subscribeVideoToDecoder(trackToDuplicate, virtualId)
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

        val encoderPreview = Preview.Builder()
            .setTargetRotation(rotation)
            .build().apply {
                setSurfaceProvider { request ->
                    inputSurface?.let { request.provideSurface(it, ContextCompat.getMainExecutor(context)) {} }
                }
            }

        val localUiPreview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            
        localPreviewSurface?.let { surface ->
            localUiPreview.setSurfaceProvider { request ->
                request.provideSurface(surface, ContextCompat.getMainExecutor(context)) {}
            }
        }
        
        try {
            if (localPreviewSurface != null) {
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, encoderPreview, localUiPreview)
            } else {
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, encoderPreview)
            }
        } catch (exc: Exception) {
            Log.e(tag, "Use case binding failed", exc)
        }
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

        val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        
        encoder = MediaCodec.createByCodecName(encoderName).apply {
            setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    getOutputBuffer(index)?.let { buffer ->
                        if (info.size > 0) {
                            videoFramer?.processFrame(buffer, info)
                        }
                    }
                    releaseOutputBuffer(index, false)
                }
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { Log.e(tag, "Encoder Error", e) }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    encoderOutputFormat = format
                    formatLatch.countDown()
                }
            }, encoderHandler)
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }
    }

    private fun setupAudioEncoder() {
        val format = MediaFormat.createAudioFormat(audioMimeType, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, audioBitRate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }

        audioEncoder = MediaCodec.createEncoderByType(audioMimeType).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        audioEncoderHandler.post(object : Runnable {
            override fun run() {
                val encoder = audioEncoder ?: return
                val info = MediaCodec.BufferInfo()
                try {
                    val index = encoder.dequeueOutputBuffer(info, 0)
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
                } catch (e: Exception) {
                    Log.e(tag, "Audio encoder output error", e)
                }
            }
        })
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
        audioEncoder?.let { encoder ->
            try {
                val index = encoder.dequeueInputBuffer(10000)
                if (index >= 0) {
                    val inputBuffer = encoder.getInputBuffer(index)
                    inputBuffer?.clear()
                    inputBuffer?.put(buffer)
                    encoder.queueInputBuffer(index, 0, size, System.nanoTime() / 1000, 0)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error feeding audio encoder", e)
            }
        }
    }

    private fun addRemoteVideoParticipant(trackName: String) {
        val participantId = trackName.substringAfterLast("/")
        if (_remoteParticipants.value.any { it.id == participantId }) return
        
        val stream = ParticipantStream(participantId)
        _remoteParticipants.value = _remoteParticipants.value + stream
        
        subscribeVideoToDecoder(trackName, participantId)
    }

    private fun subscribeVideoToDecoder(trackName: String, participantId: String) {
        moqTransport.subscribe(trackName, object : MoqObjectCallback {
            override fun onObject(trackName: String, groupId: Long, objectId: Long, payload: ByteBuffer) {
                decoders[participantId]?.let { decoder ->
                    try {
                        val index = decoder.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val inputBuffer = decoder.getInputBuffer(index)
                            inputBuffer?.clear()
                            inputBuffer?.put(payload)
                            val flags = if (objectId == 0L) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            decoder.queueInputBuffer(index, 0, payload.remaining(), 0, flags)
                        }
                    } catch (e: Exception) {}
                }
            }
        })
    }

    private fun addRemoteAudioParticipant(trackName: String) {
        val participantId = trackName.substringAfterLast("/")
        
        moqTransport.subscribe(trackName, object : MoqObjectCallback {
            override fun onObject(trackName: String, groupId: Long, objectId: Long, payload: ByteBuffer) {
                audioDecoders[participantId]?.let { decoder ->
                    try {
                        val index = decoder.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val inputBuffer = decoder.getInputBuffer(index)
                            inputBuffer?.clear()
                            inputBuffer?.put(payload)
                            decoder.queueInputBuffer(index, 0, payload.remaining(), 0, 0)
                        }
                    } catch (e: Exception) {}
                } ?: run {
                    Handler(audioEncoderThread.looper).post {
                        try {
                            audioFormatLatch.await()
                            setupAudioDecoder(participantId)
                        } catch (e: Exception) {}
                    }
                }
            }
        })
    }

    private fun setupAudioDecoder(id: String) {
        val format = audioEncoderOutputFormat ?: return
        val thread = HandlerThread("AudioDecoderThread_$id").apply { start() }
        val handler = Handler(thread.looper)
        audioDecoderThreads[id] = thread
        audioDecoderHandlers[id] = handler

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
    }

    fun onRemoteSurfaceReady(participantId: String, surface: Surface) {
        decoders[participantId]?.setOutputSurface(surface) ?: run {
            val thread = HandlerThread("SetupDecoderThread_$participantId").apply { start() }
            Handler(thread.looper).post {
                try {
                    formatLatch.await()
                    setupDecoder(participantId, surface)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }
    
    fun onRemoteSurfaceDestroyed(participantId: String) {
        synchronized(this) {
            decoderHandlers.remove(participantId)?.removeCallbacksAndMessages(null)
            decoders.remove(participantId)?.apply {
                try { stop(); release() } catch (e: Exception) { }
            }
            decoderThreads.remove(participantId)?.quitSafely()
            
            audioDecoderHandlers.remove(participantId)?.removeCallbacksAndMessages(null)
            audioDecoders.remove(participantId)?.apply {
                try { stop(); release() } catch (e: Exception) { }
            }
            audioTracks.remove(participantId)?.apply {
                try { stop(); release() } catch (e: Exception) { }
            }
            audioDecoderThreads.remove(participantId)?.quitSafely()
        }
    }

    private fun setupDecoder(id: String, surface: Surface) {
        val format = encoderOutputFormat ?: return
        val thread = HandlerThread("DecoderThread_$id").apply { start() }
        val handler = Handler(thread.looper)
        decoderThreads[id] = thread
        decoderHandlers[id] = handler

        val decoder = MediaCodec.createDecoderByType(videoMimeType).apply {
            configure(format, surface, null, 0)
            start()
        }
        decoders[id] = decoder
        
        handler.post(object : Runnable {
            override fun run() {
                if (decoders.containsKey(id)) {
                    val bufferInfo = MediaCodec.BufferInfo()
                    try {
                        val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                        if (outIndex >= 0) {
                            decoder.releaseOutputBuffer(outIndex, true)
                        }
                        handler.post(this)
                    } catch (e: Exception) { }
                }
            }
        })
    }

    fun enableVideo(enabled: Boolean, owner: LifecycleOwner) {
        if (enabled) bindCameraUseCases(owner) else cameraProvider?.unbindAll()
    }
    
    fun enableAudio(enabled: Boolean) { isMicEnabled = enabled }

    @Synchronized
    fun stopSession() {
        moqTransport.disconnect()
        cameraProvider?.unbindAll()
        
        encoderHandler.removeCallbacksAndMessages(null)
        encoder?.stop()
        encoder?.release()
        encoder = null
        
        audioEncoderHandler.removeCallbacksAndMessages(null)
        audioEncoder?.stop()
        audioEncoder?.release()
        audioEncoder = null

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
        formatLatch = CountDownLatch(1)
        audioFormatLatch = CountDownLatch(1)
        videoFramer = null
        audioFramer = null
        primaryRemoteVideoTrack = null
        primaryRemoteAudioTrack = null
    }
}

data class ParticipantStream(val id: String)
