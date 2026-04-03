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
            aspectRatio?.let {
                // Assuming width=1280, height=720 for the video source
                // VideoSessionManager config: width=1280, height=720
                view.setAspectRatio(1280, 720)
            }
        },
        modifier = modifier.graphicsLayer(scaleX = if (mirrorHorizontal) -1f else 1f)
    )
}
