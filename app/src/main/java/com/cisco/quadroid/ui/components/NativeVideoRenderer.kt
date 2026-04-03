package com.cisco.quadroid.ui.components

import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun NativeVideoRenderer(
    modifier: Modifier = Modifier,
    onSurfaceCreated: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    mirrorHorizontal: Boolean = false,
    aspectRatio: Float? = null,
    zOrderMediaOverlay: Boolean = false
) {
    // Use rememberUpdatedState to ensure the latest callbacks are used in the factory closure
    val currentOnSurfaceCreated = rememberUpdatedState(onSurfaceCreated)
    val currentOnSurfaceDestroyed = rememberUpdatedState(onSurfaceDestroyed)

    AndroidView(
        factory = { context ->
            Log.d("NativeVideoRenderer", "Creating AspectSafeSurfaceView")
            AspectSafeSurfaceView(context).apply {
                if (zOrderMediaOverlay) {
                    setZOrderOnTop(true)
                }
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        Log.d("NativeVideoRenderer", "Surface Created: ${holder.surface}")
                        currentOnSurfaceCreated.value(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        Log.d("NativeVideoRenderer", "Surface Changed: ${width}x${height}")
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        Log.d("NativeVideoRenderer", "Surface Destroyed")
                        currentOnSurfaceDestroyed.value()
                    }
                })
            }
        },
        update = { view ->
            // Use the provided aspectRatio if available, otherwise default to portrait 9:16
            val ratio = aspectRatio ?: (720f / 1280f)
            
            // AspectSafeSurfaceView.setAspectRatio expects (width, height)
            // If ratio < 1, it's portrait. If ratio > 1, it's landscape.
            if (ratio < 1f) {
                view.setAspectRatio(720, 1280)
            } else {
                view.setAspectRatio(1280, 720)
            }
        },
        modifier = modifier.graphicsLayer(scaleX = if (mirrorHorizontal) -1f else 1f)
    )
}
