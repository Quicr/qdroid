package com.cisco.quadroid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun VideoRenderer(
    videoTrack: VideoTrack,
    eglBaseContext: EglBase.Context,
    modifier: Modifier = Modifier,
    zOrderMediaOverlay: Boolean = false
) {
    val context = LocalContext.current
    val videoView = remember {
        SurfaceViewRenderer(context).apply {
            init(eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            setZOrderMediaOverlay(zOrderMediaOverlay)
        }
    }

    DisposableEffect(videoTrack) {
        try {
            videoTrack.addSink(videoView)
        } catch (e: Exception) {
            // Track might be natively disposed
        }
        onDispose {
            try {
                videoTrack.removeSink(videoView)
            } catch (e: Exception) {
                // Ignore errors if track already destroyed
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            videoView.release()
        }
    }

    AndroidView(
        factory = { videoView },
        modifier = modifier
    )
}
