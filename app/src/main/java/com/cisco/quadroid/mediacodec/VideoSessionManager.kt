package com.cisco.quadroid.mediacodec

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
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
    private val iFrameInterval = 1 

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    
    private val encoderThread = HandlerThread("EncoderThread").apply { start() }
    private val encoderHandler = Handler(encoderThread.looper)
    
    private val decoderThreads = mutableMapOf<String, HandlerThread>()
    private val decoders = mutableMapOf<String, MediaCodec>()

    private val _remoteParticipants = MutableStateFlow<List<ParticipantStream>>(emptyList())
    val remoteParticipants: StateFlow<List<ParticipantStream>> = _remoteParticipants.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var encoderPreview: Preview? = null
    private var localUiPreview: Preview? = null
    private var currentLifecycleOwner: LifecycleOwner? = null
    private var localUiSurface: Surface? = null

    fun startSession(lifecycleOwner: LifecycleOwner) {
        currentLifecycleOwner = lifecycleOwner
        setupEncoder()
        setupCamera(lifecycleOwner)
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            try {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            } catch (e: Exception) {}
        }

        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoderName = codecList.findEncoderForFormat(format)
        
        encoder = MediaCodec.createByCodecName(encoderName).apply {
            setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    getOutputBuffer(index)?.let { buffer ->
                        if (info.size > 0) broadcastToDecoders(buffer, info)
                    }
                    releaseOutputBuffer(index, false)
                }
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(tag, "Encoder Error", e)
                }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
            }, encoderHandler)
            
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }
    }

    private fun setupCamera(lifecycleOwner: LifecycleOwner) {
        Log.d(tag, "setupCamera called")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            Log.d(tag, "Camera provider future completed")
            cameraProvider = cameraProviderFuture.get()
            Log.d(tag, "Camera provider obtained: $cameraProvider")

            encoderPreview = Preview.Builder()
                .setTargetResolution(android.util.Size(width, height))
                .build()
            encoderPreview?.setSurfaceProvider { request ->
                inputSurface?.let { request.provideSurface(it, ContextCompat.getMainExecutor(context)) { } }
                Log.d(tag, "Encoder preview surface provider set")
            }

            localUiPreview = Preview.Builder().build()
            Log.d(tag, "Local UI preview created")

            // Connect local UI surface if already available
            val shouldBindCamera = localUiSurface?.let { surface ->
                Log.d(tag, "Local UI surface already available, setting surface provider")
                localUiPreview?.setSurfaceProvider { request ->
                    request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { }
                }
                true
            } ?: false

            if (!shouldBindCamera) {
                Log.d(tag, "No local UI surface available yet - will bind camera when surface is ready")
            }

            // Only bind camera if we have the local UI surface ready
            if (shouldBindCamera) {
                try {
                    cameraProvider?.unbindAll()
                    val camera = cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        encoderPreview,
                        localUiPreview
                    )
                    Log.d(tag, "Camera bound in setupCamera: $camera")
                } catch (e: Exception) {
                    Log.e(tag, "Camera Binding Failed in setupCamera", e)
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun setLocalPreviewSurface(surface: Surface) {
        Log.d(tag, "setLocalPreviewSurface called")
        Log.d(tag, "  encoder=${encoder != null}")
        Log.d(tag, "  cameraProvider=${cameraProvider != null}")
        Log.d(tag, "  currentLifecycleOwner=${currentLifecycleOwner != null}")
        Log.d(tag, "  encoderPreview=${encoderPreview != null}")
        Log.d(tag, "  localUiPreview=${localUiPreview != null}")

        localUiSurface = surface

        // Connect the surface to the preview - CRITICAL: Do this before checking if camera is bound
        localUiPreview?.setSurfaceProvider { request ->
            request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { }
            Log.d(tag, "Local UI surface provided to preview")
        }

        // If camera is already bound, we need to rebind it to pick up the new surface
        // This handles the case where setupCamera completed before the surface was ready
        if (encoder != null && cameraProvider != null && currentLifecycleOwner != null && encoderPreview != null && localUiPreview != null) {
            Log.d(tag, "All components ready - rebinding camera to pick up new surface")
            try {
                cameraProvider?.unbindAll()
                val camera = cameraProvider?.bindToLifecycle(
                    currentLifecycleOwner!!,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    encoderPreview,
                    localUiPreview
                )
                Log.d(tag, "Camera rebound successfully with new surface - camera=$camera")
            } catch (e: Exception) {
                Log.e(tag, "Camera Rebinding Failed in setLocalPreviewSurface", e)
                e.printStackTrace()
            }
        } else {
            Log.w(tag, "Cannot bind camera yet - waiting for components:")
            if (encoder == null) Log.w(tag, "  - encoder is null")
            if (cameraProvider == null) Log.w(tag, "  - cameraProvider is null")
            if (currentLifecycleOwner == null) Log.w(tag, "  - currentLifecycleOwner is null")
            if (encoderPreview == null) Log.w(tag, "  - encoderPreview is null")
            if (localUiPreview == null) Log.w(tag, "  - localUiPreview is null")
        }
    }

    fun clearLocalPreviewSurface() {
        Log.d(tag, "clearLocalPreviewSurface called")
        localUiSurface = null
    }

    fun addSimulatedParticipant() {
        if (_remoteParticipants.value.size >= 3) return
        val stream = ParticipantStream(UUID.randomUUID().toString())
        _remoteParticipants.value = _remoteParticipants.value + stream
    }

    fun onSurfaceReady(participantId: String, surface: Surface) {
        setupDecoder(participantId, surface)
    }

    private fun setupDecoder(id: String, surface: Surface) {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            try { setInteger("low-latency", 1) } catch (e: Exception) {}
        }
        val thread = HandlerThread("DecoderThread_$id").apply { start() }
        decoderThreads[id] = thread
        
        val decoder = MediaCodec.createDecoderByType(mimeType).apply {
            setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    releaseOutputBuffer(index, true)
                }
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {}
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
            }, Handler(thread.looper))
            configure(format, surface, null, 0)
            start()
        }
        decoders[id] = decoder
    }

    private fun broadcastToDecoders(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        decoders.values.forEach { decoder ->
            try {
                val index = decoder.dequeueInputBuffer(0)
                if (index >= 0) {
                    decoder.getInputBuffer(index)?.apply {
                        clear()
                        put(buffer)
                        decoder.queueInputBuffer(index, 0, info.size, info.presentationTimeUs, info.flags)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun enableVideo(enabled: Boolean) {
        if (enabled) {
            Log.d(tag, "enableVideo(true) - starting, recreating everything for reliability")
            currentLifecycleOwner?.let { lifecycleOwner ->
                // Always do full recreation for reliability
                // Clean up old resources
                cameraProvider?.unbindAll()
                encoder?.apply {
                    stop()
                    release()
                }
                encoder = null
                inputSurface?.release()
                inputSurface = null

                // Setup new encoder with new input surface
                setupEncoder()

                // Setup camera (will create previews and bind camera when surface is ready)
                setupCamera(lifecycleOwner)

                Log.d(tag, "enableVideo(true) - Full setup completed")
            }
        } else {
            Log.d(tag, "enableVideo(false) - disabling video")
            // Unbind camera and clean up
            cameraProvider?.unbindAll()
            encoder?.apply {
                stop()
                release()
            }
            encoder = null
            inputSurface?.release()
            inputSurface = null
            localUiSurface = null
        }
    }

    fun enableAudio(enabled: Boolean) {
        // Implementation for audio mute/unmute
    }

    fun stopSession() {
        cameraProvider?.unbindAll()
        encoder?.apply { stop(); release() }
        encoder = null
        decoders.forEach { (id, dec) -> 
            dec.stop(); dec.release()
            decoderThreads[id]?.quitSafely()
        }
        decoders.clear()
        decoderThreads.clear()
        inputSurface?.release()
        inputSurface = null
        _remoteParticipants.value = emptyList()
    }
}

data class ParticipantStream(val id: String)
