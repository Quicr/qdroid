package com.cisco.quadroid.ui.components

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.Composable
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
    AndroidView(
        factory = { context ->
            AspectSafeSurfaceView(context).apply {
                if (zOrderMediaOverlay) {
                    setZOrderOnTop(true)
                }
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceCreated(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurfaceDestroyed()
                    }
                })
            }
        },
        update = { view ->
            aspectRatio?.let {
                // Assuming width/height aspect ratio
                // If it's height/width, we need to be careful.
                // VideoSessionManager says width=720, height=1280, ratio = 720/1280 = 0.5625
                // AspectSafeSurfaceView.setAspectRatio takes (width, height)
                view.setAspectRatio(720, 1280)
            }
        },
        modifier = modifier.graphicsLayer(scaleX = if (mirrorHorizontal) -1f else 1f)
    )
}
