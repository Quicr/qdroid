// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

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

    // These fields are written from a background thread (startSession runs off-main) and
    // read on the main thread inside the CameraX provider callbacks, so mark them volatile.
    @Volatile
    var localPreviewSurface: Surface? = null
        private set

    @Volatile
    private var currentLifecycleOwner: LifecycleOwner? = null
    @Volatile
    private var currentEncoderSurface: Surface? = null
    @Volatile
    private var currentWidth: Int = 0
    @Volatile
    private var currentHeight: Int = 0
    @Volatile
    private var currentRotation: Int = 0

    // The encoder and the local UI preview are kept as two independently-managed use
    // cases. The encoder feed (the outgoing stream) must never be torn down just because
    // the transient local-preview UI surface comes and goes, so we bind/unbind each use
    // case on its own instead of calling unbindAll() and rebinding everything together.
    @Volatile
    private var encoderUseCase: Preview? = null
    @Volatile
    private var localPreviewUseCase: Preview? = null

    /** CameraX bind/unbind are main-thread only; route provider calls here. */
    private fun runOnMain(action: () -> Unit) {
        ContextCompat.getMainExecutor(context).execute(action)
    }

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
                bindEncoderUseCase()
                bindLocalPreviewUseCase()
            } catch (e: Exception) {
                Log.e(tag, "Failed to get camera provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun resolutionSelector(): ResolutionSelector =
        ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(currentWidth, currentHeight),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

    /**
     * Binds (or rebinds) the encoder use case. This is the outgoing video feed and is
     * kept alive independently of the local-preview UI surface.
     */
    private fun bindEncoderUseCase() {
        val provider = cameraProvider ?: return
        val lifecycleOwner = currentLifecycleOwner ?: return
        val encoderSurface = currentEncoderSurface ?: return

        // Replace only the encoder use case, leaving the local preview untouched.
        encoderUseCase?.let { provider.unbind(it) }

        val preview = Preview.Builder()
            .setTargetRotation(currentRotation)
            .setResolutionSelector(resolutionSelector())
            .build().apply {
                setSurfaceProvider { request ->
                    request.provideSurface(
                        encoderSurface,
                        ContextCompat.getMainExecutor(context)
                    ) {}
                }
            }
        encoderUseCase = preview

        try {
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
            Log.i(tag, "Bound encoder use case with resolution ${currentWidth}x${currentHeight}")
        } catch (exc: Exception) {
            Log.e(tag, "Encoder use case binding failed", exc)
        }
    }

    /**
     * Binds (or rebinds) the local-preview UI use case. No-op if no preview surface is
     * currently set; the encoder keeps running regardless.
     */
    private fun bindLocalPreviewUseCase() {
        val provider = cameraProvider ?: return
        val lifecycleOwner = currentLifecycleOwner ?: return
        val surface = localPreviewSurface ?: return

        // Replace only the local preview use case, leaving the encoder untouched.
        localPreviewUseCase?.let { provider.unbind(it) }

        val preview = Preview.Builder()
            .setTargetRotation(currentRotation)
            .setResolutionSelector(resolutionSelector())
            .build().apply {
                setSurfaceProvider { request ->
                    request.provideSurface(
                        surface,
                        ContextCompat.getMainExecutor(context)
                    ) {}
                }
            }
        localPreviewUseCase = preview

        try {
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
            Log.i(tag, "Bound local preview use case")
        } catch (exc: Exception) {
            Log.e(tag, "Local preview use case binding failed", exc)
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
        bindEncoderUseCase()
        bindLocalPreviewUseCase()
        Log.i(tag, "Switched to ${if (_isFrontCamera.value) "front" else "back"} camera")
    }

    fun setLocalPreviewSurface(surface: Surface) {
        localPreviewSurface = surface
        bindLocalPreviewUseCase()
        Log.i(tag, "Set local preview surface")
    }

    /**
     * Called when the local-preview UI surface is destroyed (e.g. the PIP is hidden or
     * disposed). Unbinds only the preview use case so the encoder — and therefore the
     * outgoing video — keeps streaming. No stale surface is left attached to the camera.
     */
    fun removeLocalPreviewSurface() {
        cameraProvider?.let { provider ->
            localPreviewUseCase?.let { provider.unbind(it) }
        }
        localPreviewUseCase = null
        localPreviewSurface = null
        Log.i(tag, "Removed local preview use case; encoder remains bound")
    }

    fun updateEncoderSurface(
        encoderSurface: Surface,
        width: Int,
        height: Int
    ) {
        currentEncoderSurface = encoderSurface
        currentWidth = width
        currentHeight = height
        bindEncoderUseCase()
        Log.i(tag, "Updated encoder surface: ${width}x${height}")
    }

    fun unbindAll() {
        val provider = cameraProvider
        runOnMain { provider?.unbindAll() }
        encoderUseCase = null
        localPreviewUseCase = null
        Log.d(tag, "Unbound all camera use cases")
    }

    fun cleanup() {
        // Only the CameraX unbind must hop to the main thread. Field resets stay on the
        // calling thread so they happen-before the subsequent setupCamera() writes in
        // startSession (same background thread) and can't be clobbered by an async callback.
        val provider = cameraProvider
        runOnMain { provider?.unbindAll() }
        encoderUseCase = null
        localPreviewUseCase = null
        localPreviewSurface?.release()
        localPreviewSurface = null
        currentLifecycleOwner = null
        currentEncoderSurface = null
        Log.d(tag, "Camera cleanup complete")
    }
}
