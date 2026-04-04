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
 * Maintains aspect ratio of the incoming feed via Modifier.aspectRatio.
 * Corrects the 90-degree clockwise rotation in raw video frames.
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
    // Default to portrait 9:16 (720/1280) if not specified
    val videoAspectRatio = aspectRatio ?: (720f / 1280f)

    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        currentOnSurfaceCreated.value(Surface(st))
                        applyTransform(this@apply, width, height, -90f, mirrorHorizontal)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        applyTransform(this@apply, width, height, -90f, mirrorHorizontal)
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
            applyTransform(view, view.width, view.height, -90f, mirrorHorizontal)
            view.invalidateOutline()
        },
        modifier = modifier.aspectRatio(videoAspectRatio)
    )
}

/**
 * PreviewNativeVideoRenderer for local preview (PIP/Solo).
 * Maintains aspect ratio via Modifier.aspectRatio.
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
    val videoAspectRatio = aspectRatio ?: (720f / 1280f)

    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        currentOnSurfaceCreated.value(Surface(st))
                        applyTransform(this@apply, width, height, 0f, mirrorHorizontal)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        applyTransform(this@apply, width, height, 0f, mirrorHorizontal)
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
            applyTransform(view, view.width, view.height, 0f, mirrorHorizontal)
            view.invalidateOutline()
        },
        modifier = modifier.aspectRatio(videoAspectRatio)
    )
}

/**
 * Universal transform logic for TextureView.
 * Handles rotation and mirroring. Compensation scaling is applied to handle the aspect ratio swap
 * when rotating in a non-square view.
 */
private fun applyTransform(
    view: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    rotation: Float,
    mirror: Boolean
) {
    if (viewWidth <= 0 || viewHeight <= 0) return

    val matrix = Matrix()
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f
    
    // 1. Apply rotation
    if (rotation != 0f) {
        matrix.postRotate(rotation, centerX, centerY)
        
        // After rotating -90, the X and Y axes are swapped. 
        // TextureView defaultly stretches the buffer to fill viewWidth x viewHeight.
        // We must compensate for this stretch to maintain the buffer's original proportions.
        matrix.postScale(viewHeight.toFloat() / viewWidth.toFloat(), viewWidth.toFloat() / viewHeight.toFloat(), centerX, centerY)
    }

    // 2. Apply horizontal mirroring
    if (mirror) {
        matrix.postScale(-1f, 1f, centerX, centerY)
    }

    view.setTransform(matrix)
}
