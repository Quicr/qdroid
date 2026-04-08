package com.cisco.quadroid.ui.components

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.cisco.quadroid.mediacodec.VideoDecoder
import com.cisco.quadroid.util.NALUParser

/**
 * A customized TextureView that handles video decoding and rendering.
 * It supports rotation and mirroring using a transformation matrix to compensate for
 * aspect ratio changes in non-square layouts.
 */
class VideoSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "VideoSurfaceView"
    }

    interface Callback {
        fun onSurfaceCreated(surface: Surface)
        fun onSurfaceDestroyed()
        fun onVideoSizeChanged(width: Int, height: Int, rotation: Float)
    }

    private var videoWidth = 0
    private var videoHeight = 0
    private var decoder: VideoDecoder? = null
    var callback: Callback? = null

    // Properties for transformation
    var rotationAngle: Float = 0f
        set(value) {
            if (field != value) {
                Log.d(TAG, "Setting rotationAngle: $value")
                field = value
                applyTransform()
                updateTextureSize()
                notifyVideoSizeChanged()
            }
        }

    var mirrorHorizontal: Boolean = false
        set(value) {
            if (field != value) {
                Log.d(TAG, "Setting mirrorHorizontal: $value")
                field = value
                applyTransform()
            }
        }

    init {
        surfaceTextureListener = this
    }

    fun setVideoSize(width: Int, height: Int) {
        if (videoWidth != width || videoHeight != height) {
            Log.d(TAG, "setVideoSize - videoWidth: $width, videoHeight: $height")
            videoWidth = width
            videoHeight = height
            post {
                applyTransform()
                updateTextureSize()
                notifyVideoSizeChanged()
                requestLayout()
            }
        }
    }

    private fun notifyVideoSizeChanged() {
        if (videoWidth > 0 && videoHeight > 0) {
            callback?.onVideoSizeChanged(videoWidth, videoHeight, rotationAngle)
        }
    }

    private fun updateTextureSize() {
        val st = surfaceTexture ?: return
        if (videoWidth <= 0 || videoHeight <= 0) return

        val displayRotation = display?.rotation ?: Surface.ROTATION_0
        
        // Configure the buffer size based on screen rotation to ensure correct scaling.
        // As per https://developer.android.com/media/camera/camera2/camera-preview
        if (displayRotation == Surface.ROTATION_0 || displayRotation == Surface.ROTATION_180) {
            // Portrait display - use swapped dimensions for the buffer hint if content is landscape
            st.setDefaultBufferSize(videoHeight, videoWidth)
        } else {
            // Landscape display - use natural dimensions
            st.setDefaultBufferSize(videoWidth, videoHeight)
        }
        Log.d(TAG, "updateTextureSize - video: ${videoWidth}x${videoHeight}, displayRotation: $displayRotation")
    }

    fun feedFrame(data: ByteArray, pts: Long) {
        val decoder = decoder ?: return
        if (NALUParser.isAnnexB(data)) {
            decoder.decodeAnnexBFrame(data, pts)
        } else {
            decoder.decodeAVCCFrame(data, pts)
        }
    }

    /**
     * Applies rotation and mirroring using a transformation matrix.
     */
    private fun applyTransform() {
        val viewWidth = width
        val viewHeight = height

        if (viewWidth <= 0 || viewHeight <= 0) return

        val matrix = Matrix()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        // 1. Apply rotation and compensation scaling
        if (rotationAngle != 0f) {
            matrix.postRotate(rotationAngle, centerX, centerY)

            // After rotating (e.g. -90), the X and Y axes are swapped. 
            // TextureView defaultly stretches the buffer to fill viewWidth x viewHeight.
            // We must compensate for this stretch to maintain the buffer's original proportions.
            // Corrected formula: scale by (viewWidth/viewHeight) horizontally and (viewHeight/viewWidth) vertically.
            matrix.postScale(viewWidth.toFloat() / viewHeight.toFloat(), viewHeight.toFloat() / viewWidth.toFloat(), centerX, centerY)
        }

        // 2. Apply horizontal mirroring
        if (mirrorHorizontal) {
            matrix.postScale(-1f, 1f, centerX, centerY)
        }

        setTransform(matrix)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Use the dimensions provided by the parent (AspectRatioFrameLayout)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            applyTransform()
            updateTextureSize()
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        Log.i(TAG, "SurfaceTexture available - width: $width, height: $height")
        val surface = Surface(surfaceTexture)
        decoder = VideoDecoder(surface) { w, h ->
            setVideoSize(w, h)
        }
        callback?.onSurfaceCreated(surface)
        applyTransform()
        updateTextureSize()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        Log.i(TAG, "SurfaceTexture size changed: ${width}x${height}")
        applyTransform()
        updateTextureSize()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        Log.i(TAG, "SurfaceTexture destroyed")
        decoder?.release()
        decoder = null
        callback?.onSurfaceDestroyed()
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
    }
}
