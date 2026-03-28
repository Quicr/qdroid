package com.cisco.quadroid.mediacodec

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
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
    private val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    private val width = 1280
    private val height = 720
    private val bitRate = 2000000 
    private val frameRate = 30
    private val iFrameInterval = 2

    private var cameraProvider: ProcessCameraProvider? = null
    private var lifecycleOwner: LifecycleOwner? = null

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

    private var localPreviewSurface: Surface? = null

    fun startSession(lifecycleOwner: LifecycleOwner) {
        this.lifecycleOwner = lifecycleOwner
        formatLatch = CountDownLatch(1)
        setupEncoder()
        setupCamera(lifecycleOwner)
    }

    private fun setupCamera(owner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(owner)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(owner: LifecycleOwner) {
        if (cameraProvider == null) return
        
        cameraProvider?.unbindAll()

        val encoderPreview = Preview.Builder().build().apply {
            setSurfaceProvider { request ->
                inputSurface?.let { request.provideSurface(it, ContextCompat.getMainExecutor(context)) {} }
            }
        }

        val localUiPreview = Preview.Builder().build().apply {
             localPreviewSurface?.let { surface ->
                setSurfaceProvider { request ->
                    request.provideSurface(surface, ContextCompat.getMainExecutor(context)) {}
                }
            }
        }
        
        cameraProvider?.bindToLifecycle(
            owner,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            encoderPreview,
            localUiPreview
        )
    }

    fun setLocalPreviewSurface(surface: Surface) {
        localPreviewSurface = surface
        lifecycleOwner?.let {
            if (cameraProvider != null) {
                bindCameraUseCases(it)
            }
        }
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
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
                    encoderOutputFormat = format
                    formatLatch.countDown()
                }
            }, encoderHandler)
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
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
        decoderHandlers[participantId]?.removeCallbacksAndMessages(null)
        decoderThreads[participantId]?.quitSafely()
        decoders[participantId]?.stop()
        decoders[participantId]?.release()
        decoders.remove(participantId)
        decoderThreads.remove(participantId)
        decoderHandlers.remove(participantId)
    }

    private fun setupDecoder(id: String, surface: Surface) {
        val format = encoderOutputFormat ?: return
        
        val thread = HandlerThread("DecoderThread_$id").apply { start() }
        val handler = Handler(thread.looper)
        decoderThreads[id] = thread
        decoderHandlers[id] = handler

        val decoder = MediaCodec.createDecoderByType(mimeType).apply {
            configure(format, surface, null, 0)
            start()
        }
        decoders[id] = decoder
        
        handler.post(object : Runnable {
            override fun run() {
                decoders[id]?.let {
                    val bufferInfo = MediaCodec.BufferInfo()
                    try {
                        val outIndex = it.dequeueOutputBuffer(bufferInfo, 0)
                        if (outIndex >= 0) {
                            it.releaseOutputBuffer(outIndex, true)
                        }
                        handler.post(this)
                    } catch (e: Exception) {
                        // Decoder was likely released, stop the loop
                    }
                }
            }
        })
        
        encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    private fun broadcastToDecoders(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        decoders.forEach { (id, decoder) ->
            try {
                val index = decoder.dequeueInputBuffer(10000)
                if (index >= 0) {
                    val inputBuffer = decoder.getInputBuffer(index)
                    inputBuffer?.clear()
                    inputBuffer?.put(buffer.duplicate())
                    decoder.queueInputBuffer(index, 0, info.size, info.presentationTimeUs, info.flags)
                }
            } catch (e: Exception) {
                // This can happen if the decoder is released, it's safe to ignore
            }
        }
    }
    
    fun enableVideo(enabled: Boolean, owner: LifecycleOwner) {
        if (enabled) {
            bindCameraUseCases(owner)
        } else {
            cameraProvider?.unbindAll()
        }
    }
    
    fun enableAudio(enabled: Boolean) {}

    @Synchronized
    fun stopSession() {
        cameraProvider?.unbindAll()
        encoder?.stop()
        encoder?.release()
        encoder = null

        decoderHandlers.forEach { (_, handler) -> handler.removeCallbacksAndMessages(null) }
        decoderHandlers.clear()

        decoders.forEach { (_, dec) -> dec.stop(); dec.release() }
        decoders.clear()

        decoderThreads.forEach { (_, thread) -> thread.quitSafely() }
        decoderThreads.clear()

        inputSurface?.release()
        inputSurface = null
        _remoteParticipants.value = emptyList()
        encoderOutputFormat = null
        formatLatch = CountDownLatch(1)
    }
}

data class ParticipantStream(val id: String)
