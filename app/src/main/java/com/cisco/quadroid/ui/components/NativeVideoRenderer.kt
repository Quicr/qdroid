package com.cisco.quadroid.ui.components

import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.ViewOutlineProvider
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * NativeVideoRenderer for remote participants.
 * It applies a -90 degree rotation to offset the 90-degree clockwise shift
 * commonly found in incoming raw video frames.
 */
@Composable
fun NativeVideoRenderer(
    modifier: Modifier = Modifier,
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    mirrorHorizontal: Boolean = false,
    aspectRatio: Float? = null
) {
    val currentOnSurfaceCreated = rememberUpdatedState(onSurfaceCreated)
    val currentOnSurfaceDestroyed = rememberUpdatedState(onSurfaceDestroyed)
    val targetAspectRatio = aspectRatio ?: (720f / 1280f)

    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        currentOnSurfaceCreated.value(Surface(st))
                        applyRemoteTransform(this@apply, width, height, mirrorHorizontal)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        applyRemoteTransform(this@apply, width, height, mirrorHorizontal)
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        currentOnSurfaceDestroyed.value()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }

                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 48f)
                    }
                }
            }
        },
        update = { view ->
            applyRemoteTransform(view, view.width, view.height, mirrorHorizontal)
            view.invalidateOutline()
        },
        modifier = modifier.aspectRatio(targetAspectRatio)
    )
}

/**
 * PreviewNativeVideoRenderer for local preview (PIP/Solo).
 * Does not apply any rotation transformations, relying on the source (e.g. CameraX)
 * to provide a correctly oriented buffer for the surface.
 */
@Composable
fun PreviewNativeVideoRenderer(
    modifier: Modifier = Modifier,
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    mirrorHorizontal: Boolean = false,
    aspectRatio: Float? = null
) {
    val currentOnSurfaceCreated = rememberUpdatedState(onSurfaceCreated)
    val currentOnSurfaceDestroyed = rememberUpdatedState(onSurfaceDestroyed)
    val targetAspectRatio = aspectRatio ?: (720f / 1280f)

    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        currentOnSurfaceCreated.value(Surface(st))
                        applyPreviewTransform(this@apply, width, height, mirrorHorizontal)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        applyPreviewTransform(this@apply, width, height, mirrorHorizontal)
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        currentOnSurfaceDestroyed.value()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }

                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 48f)
                    }
                }
            }
        },
        update = { view ->
            applyPreviewTransform(view, view.width, view.height, mirrorHorizontal)
            view.invalidateOutline()
        },
        modifier = modifier.aspectRatio(targetAspectRatio)
    )
}

/**
 * Transform for remote video: corrects 90deg rotation and optionally mirrors.
 */
private fun applyRemoteTransform(view: TextureView, viewWidth: Int, viewHeight: Int, mirror: Boolean) {
    if (viewWidth == 0 || viewHeight == 0) return
    val matrix = Matrix()
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f
    
    // Rotate -90 to offset 90 clockwise shift
    matrix.postRotate(-90f, centerX, centerY)
    
    // Compensate for aspect ratio swap after rotation
    val scaleX = viewHeight.toFloat() / viewWidth.toFloat()
    val scaleY = viewWidth.toFloat() / viewHeight.toFloat()
    matrix.postScale(scaleX, scaleY, centerX, centerY)
    
    if (mirror) {
        matrix.postScale(-1f, 1f, centerX, centerY)
    }
    view.setTransform(matrix)
}

/**
 * Transform for local preview: only applies mirroring, no rotation.
 */
private fun applyPreviewTransform(view: TextureView, viewWidth: Int, viewHeight: Int, mirror: Boolean) {
    if (viewWidth == 0 || viewHeight == 0) return
    val matrix = Matrix()
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f
    
    if (mirror) {
        matrix.postScale(-1f, 1f, centerX, centerY)
    }
    view.setTransform(matrix)
}
