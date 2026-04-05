package com.cisco.quadroid.ui.components

import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.cisco.quadroid.MainViewModel

/**
 * NativeVideoRenderer for remote participants using VideoSurfaceView.
 * It corrects the 90-degree clockwise rotation common in remote feeds.
 */
@Composable
fun NativeVideoRenderer(
    trackKey: String,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    mirrorHorizontal: Boolean = false,
    aspectRatio: Float? = 0.0f
) {

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                Log.d("NativeVideoRenderer", "Creating AspectRatioFrameLayout + VideoSurfaceView for trackKey: $trackKey")

                val aspectRatioLayout = AspectRatioFrameLayout(context).apply {
                    // Match the default scaling of PreviewNativeVideoRenderer
                    scaleType = AspectRatioFrameLayout.ScaleType.FIT
                }

                val videoSurfaceView = VideoSurfaceView(context).apply {
                    callback = object : VideoSurfaceView.Callback {
                        override fun onSurfaceCreated(surface: Surface) {
                            Log.d("NativeVideoRenderer", "Surface created for trackKey: $trackKey")
                        }

                        override fun onSurfaceDestroyed() {
                            Log.d("NativeVideoRenderer", "Surface destroyed for trackKey: $trackKey")
                        }

                        override fun onVideoSizeChanged(width: Int, height: Int, rotation: Float) {
                            val ratio = if (rotation == -90f || rotation == 90f) {
                                height.toFloat() / width.toFloat()
                            } else {
                                width.toFloat() / height.toFloat()
                            }
                            aspectRatioLayout.setAspectRatio(ratio)
                        }
                    }
                }

                aspectRatioLayout.addView(
                    videoSurfaceView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                )

                aspectRatioLayout
            },
            update = { aspectRatioLayout ->
                val videoSurfaceView = aspectRatioLayout.getChildAt(0) as? VideoSurfaceView
                videoSurfaceView?.let { view ->
                    view.rotationAngle = -90f
                    view.mirrorHorizontal = mirrorHorizontal

                    viewModel.addVideoFrameListener(trackKey) { data, pts ->
                        view.feedFrame(data, pts)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = {
                viewModel.removeVideoFrameListener(trackKey)
            }
        )
    }
}
//GEMINI: DO NOT MODIFY - START
/**
 * PreviewNativeVideoRenderer for local preview using VideoSurfaceView.
 */
@Composable
fun PreviewNativeVideoRenderer(
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
    mirrorHorizontal: Boolean = false,
    aspectRatio: Float? = null
) {
    val currentOnSurfaceCreated = rememberUpdatedState(onSurfaceCreated)
    val currentOnSurfaceDestroyed = rememberUpdatedState(onSurfaceDestroyed)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                val aspectRatioLayout = AspectRatioFrameLayout(context)
                aspectRatio?.let { aspectRatioLayout.setAspectRatio(it) }

                val videoSurfaceView = VideoSurfaceView(context).apply {
                    callback = object : VideoSurfaceView.Callback {
                        override fun onSurfaceCreated(surface: Surface) {
                            currentOnSurfaceCreated.value(surface)
                        }
                        override fun onSurfaceDestroyed() {
                            currentOnSurfaceDestroyed.value()
                        }
                        override fun onVideoSizeChanged(width: Int, height: Int, rotation: Float) {
                            val ratio = width.toFloat() / height.toFloat()
                            aspectRatioLayout.setAspectRatio(ratio)
                        }
                    }
                }

                aspectRatioLayout.addView(
                    videoSurfaceView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                )

                aspectRatioLayout
            },
            update = { aspectRatioLayout ->
                val videoSurfaceView = aspectRatioLayout.getChildAt(0) as? VideoSurfaceView
                videoSurfaceView?.let { view ->
                    view.rotationAngle = 0f
                    view.mirrorHorizontal = mirrorHorizontal
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
    //GEMINI: DO NOT MODIFY -END
}
