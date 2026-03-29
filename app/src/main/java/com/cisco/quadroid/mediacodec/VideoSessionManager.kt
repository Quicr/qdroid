package com.cisco.quadroid.mediacodec

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
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
    @ApplicationContext private val context: Context
) {
    private val tag = "VideoSessionManager"

    // Video Config: Use landscape for capture stability, rotate in metadata
    private val videoMimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    private val width = 1280
    private val height = 720
    private val bitRate = 2000000 
    private val frameRate = 30
    private val iFrameInterval = 2

    // Audio Config
    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private var audioRecord: AudioRecord? = null
    private var isMicEnabled = true
    private var audioThread: Thread? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var rotation: Int = 0

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val encoderThread = HandlerThread("VideoSessionManager_Encoder").apply { start() }
    private val encoderHandler = Handler(encoderThread.looper)

    @Volatile
    private var encoderOutputFormat: MediaFormat? = null
    private var formatLatch = CountDownLatch(1)

    private val decoders = ConcurrentHashMap<String, MediaCodec>()
    private val decoderThreads = ConcurrentHashMap<String, HandlerThread>()
    private val decoderHandlers = ConcurrentHashMap<String, Handler>()

    private val _remoteParticipants = MutableStateFlow<List<ParticipantStream>>(emptyList())
    val remoteParticipants: StateFlow<List<ParticipantStream>> = _remoteParticipants.asStateFlow()
    
    // Default to portrait ratio (720/1280) for the UI
    private val _videoAspectRatio = MutableStateFlow(height.toFloat() / width.toFloat())
    val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

    private var localPreviewSurface: Surface? = null

    fun startSession(lifecycleOwner: LifecycleOwner, rotation: Int) {
        this.lifecycleOwner = lifecycleOwner
        this.rotation = rotation
        formatLatch = CountDownLatch(1)
        setupEncoder()
        setupCamera(lifecycleOwner)
        startAudioCapture()
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
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        getOutputBuffer(index)?.let { buffer ->
                            if (info.size > 0) broadcastToDecoders(buffer, info)
                        }
                    }
                    releaseOutputBuffer(index, false)
                }
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { Log.e(tag, "Encoder Error", e) }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    val rotatedFormat = MediaFormat().apply {
                        setInteger(MediaFormat.KEY_WIDTH, format.getInteger(MediaFormat.KEY_WIDTH))
                        setInteger(MediaFormat.KEY_HEIGHT, format.getInteger(MediaFormat.KEY_HEIGHT))
                        setString(MediaFormat.KEY_MIME, videoMimeType)
                        if (format.containsKey("csd-0")) setByteBuffer("csd-0", format.getByteBuffer("csd-0"))
                        if (format.containsKey("csd-1")) setByteBuffer("csd-1", format.getByteBuffer("csd-1"))
                        
                        setInteger("rotation-degrees", 270)
                    }
                    encoderOutputFormat = rotatedFormat
                    formatLatch.countDown()
                }
            }, encoderHandler)
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        try {
            audioRecord = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)
            audioRecord?.startRecording()
            audioThread = Thread {
                val audioBuffer = ShortArray(bufferSize)
                while (!Thread.interrupted()) {
                    val read = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                    if (read > 0 && !isMicEnabled) {
                        for (i in 0 until read) audioBuffer[i] = 0
                    }
                }
            }.apply { start() }
        } catch (e: Exception) {
            Log.e(tag, "Audio capture failed", e)
        }
    }

    fun addSimulatedParticipant() {
        if (_remoteParticipants.value.size >= 3) return
        val stream = ParticipantStream(UUID.randomUUID().toString())
        _remoteParticipants.value = _remoteParticipants.value + stream
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
        
        encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    private fun broadcastToDecoders(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        decoders.forEach { (_, decoder) ->
            try {
                val index = decoder.dequeueInputBuffer(10000)
                if (index >= 0) {
                    val inputBuffer = decoder.getInputBuffer(index)
                    inputBuffer?.clear()
                    inputBuffer?.put(buffer.duplicate())
                    decoder.queueInputBuffer(index, 0, info.size, info.presentationTimeUs, info.flags)
                }
            } catch (e: Exception) { }
        }
    }
    
    fun enableVideo(enabled: Boolean, owner: LifecycleOwner) {
        if (enabled) bindCameraUseCases(owner) else cameraProvider?.unbindAll()
    }
    
    fun enableAudio(enabled: Boolean) { isMicEnabled = enabled }

    @Synchronized
    fun stopSession() {
        cameraProvider?.unbindAll()
        encoder?.stop()
        encoder?.release()
        encoder = null
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
        inputSurface?.release()
        inputSurface = null
        _remoteParticipants.value = emptyList()
        encoderOutputFormat = null
        formatLatch = CountDownLatch(1)
    }
}

data class ParticipantStream(val id: String)
