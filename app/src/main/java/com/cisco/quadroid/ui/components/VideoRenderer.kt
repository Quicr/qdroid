package com.cisco.quadroid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun VideoRenderer(
    videoTrack: VideoTrack,
    modifier: Modifier = Modifier,
    eglBaseContext: org.webrtc.EglBase.Context
) {
    val trackState = rememberUpdatedState(videoTrack)
    
    AndroidView(
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                try {
                    trackState.value.addSink(this)
                } catch (e: Exception) {
                    // Ignore native errors
                }
            }
        },
        modifier = modifier,
        update = { view ->
            // Re-bind if track changes, but InCallScreen handles nulls
            // We can just ensure it's added
            try {
                trackState.value.addSink(view)
            } catch (e: Exception) { }
        },
        onRelease = { view ->
            // Safety: Just release the view. removeSink can crash if track is disposed.
            try {
                view.release()
            } catch (e: Exception) { }
        }
    )
}
