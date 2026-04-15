package com.cisco.quadroid.mediacodec.camera

import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraManager(
    private val context: Context
) {
    private val tag = "CameraManager"

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    var localPreviewSurface: Surface? = null
        private set

    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentEncoderSurface: Surface? = null
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var currentRotation: Int = 0

    fun setupCamera(
        lifecycleOwner: LifecycleOwner,
        encoderSurface: Surface,
        width: Int,
        height: Int,
        rotation: Int
    ) {
        currentLifecycleOwner = lifecycleOwner
        currentEncoderSurface = encoderSurface
        currentWidth = width
        currentHeight = height
        currentRotation = rotation

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(tag, "Failed to get camera provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val lifecycleOwner = currentLifecycleOwner ?: return
        val encoderSurface = currentEncoderSurface ?: return

        provider.unbindAll()

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(currentWidth, currentHeight),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        // Create preview use case for the encoder
        val encoderPreview = Preview.Builder()
            .setTargetRotation(currentRotation)
            .setResolutionSelector(resolutionSelector)
            .build().apply {
                setSurfaceProvider { request ->
                    request.provideSurface(
                        encoderSurface,
                        ContextCompat.getMainExecutor(context)
                    ) {}
                }
            }

        // Create local UI preview
        val localUiPreview = Preview.Builder()
            .setTargetRotation(currentRotation)
            .setResolutionSelector(resolutionSelector)
            .build()

        localPreviewSurface?.let { surface ->
            localUiPreview.setSurfaceProvider { request ->
                request.provideSurface(surface, ContextCompat.getMainExecutor(context)) {}
            }
        }

        try {
            // Bind encoder + UI preview (max 2 use cases)
            val useCases = mutableListOf(encoderPreview)
            if (localPreviewSurface != null) {
                useCases.add(localUiPreview)
            }

            provider.bindToLifecycle(lifecycleOwner, cameraSelector, *useCases.toTypedArray())
            Log.i(
                tag,
                "Bound camera with resolution ${currentWidth}x${currentHeight}"
            )
        } catch (exc: Exception) {
            Log.e(tag, "Use case binding failed", exc)
        }
    }

    fun switchCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            _isFrontCamera.value = false
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            _isFrontCamera.value = true
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        bindCameraUseCases()
        Log.i(tag, "Switched to ${if (_isFrontCamera.value) "front" else "back"} camera")
    }

    fun setLocalPreviewSurface(surface: Surface) {
        localPreviewSurface = surface
        bindCameraUseCases()
        Log.i(tag, "Set local preview surface")
    }

    fun updateEncoderSurface(
        encoderSurface: Surface,
        width: Int,
        height: Int
    ) {
        currentEncoderSurface = encoderSurface
        currentWidth = width
        currentHeight = height
        bindCameraUseCases()
        Log.i(tag, "Updated encoder surface: ${width}x${height}")
    }

    fun unbindAll() {
        cameraProvider?.unbindAll()
        Log.d(tag, "Unbound all camera use cases")
    }

    fun cleanup() {
        cameraProvider?.unbindAll()
        localPreviewSurface?.release()
        localPreviewSurface = null
        currentLifecycleOwner = null
        currentEncoderSurface = null
        Log.d(tag, "Camera cleanup complete")
    }
}
