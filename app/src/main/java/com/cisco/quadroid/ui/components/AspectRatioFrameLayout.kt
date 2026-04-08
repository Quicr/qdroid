package com.cisco.quadroid.ui.components

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.widget.FrameLayout

/**
 * A FrameLayout that resizes itself to maintain a specific aspect ratio.
 * Used for displaying video content with correct proportions.
 *
 * Based on the pattern used by ExoPlayer and other production video players.
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "AspectRatioFrameLayout"
    }

    enum class ScaleType {
        FIT,  // Letterbox/Pillarbox - entire video visible
        FILL  // Crop to fill - video fills entire view, may be cropped
    }

    private var videoAspectRatio: Float = 0f
    var scaleType: ScaleType = ScaleType.FIT
        set(value) {
            if (field != value) {
                Log.d(TAG, "setScaleType: $value (previous: $field)")
                field = value
                requestLayout()
            }
        }

    /**
     * Set the aspect ratio (width / height) of the video content.
     * @param ratio The aspect ratio, or 0 to use the full available space
     */
    fun setAspectRatio(ratio: Float) {
        if (videoAspectRatio != ratio) {
            Log.d(TAG, "setAspectRatio: $ratio (previous: $videoAspectRatio)")
            videoAspectRatio = ratio
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        if (videoAspectRatio <= 0) {
            // No aspect ratio set, use the measured dimensions
            Log.d(TAG, "onMeasure: No aspect ratio set, using default: ${measuredWidth}x${measuredHeight}")
            return
        }

        val width = measuredWidth
        val height = measuredHeight

        val viewAspectRatio = width.toFloat() / height.toFloat()

        Log.d(TAG, "onMeasure: availableSize=${width}x${height}, viewAspect=$viewAspectRatio, videoAspect=$videoAspectRatio, scaleType=$scaleType")

        val newWidth: Int
        val newHeight: Int

        when (scaleType) {
            ScaleType.FIT -> {
                // Fit entire video inside view (letterbox/pillarbox)
                if (viewAspectRatio > videoAspectRatio) {
                    // View is wider than video - fit by height (add pillarbox)
                    newHeight = height
                    newWidth = (height * videoAspectRatio).toInt()
                    Log.d(TAG, "onMeasure: FIT by height (pillarbox) -> ${newWidth}x${newHeight}")
                } else {
                    // View is taller than video - fit by width (add letterbox)
                    newWidth = width
                    newHeight = (width / videoAspectRatio).toInt()
                    Log.d(TAG, "onMeasure: FIT by width (letterbox) -> ${newWidth}x${newHeight}")
                }
            }
            ScaleType.FILL -> {
                // Fill entire view with video (crop as needed)
                if (viewAspectRatio > videoAspectRatio) {
                    // View is wider than video - fill by width (crop top/bottom)
                    newWidth = width
                    newHeight = (width / videoAspectRatio).toInt()
                    Log.d(TAG, "onMeasure: FILL by width (crop top/bottom) -> ${newWidth}x${newHeight}")
                } else {
                    // View is taller than video - fill by height (crop left/right)
                    newHeight = height
                    newWidth = (height * videoAspectRatio).toInt()
                    Log.d(TAG, "onMeasure: FILL by height (crop left/right) -> ${newWidth}x${newHeight}")
                }
            }
        }

        setMeasuredDimension(newWidth, newHeight)
    }
}
