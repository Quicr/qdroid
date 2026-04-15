package com.cisco.quadroid.mediacodec.jitter

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cisco.quadroid.transport.MoqNative
import com.cisco.quadroid.transport.MoqTransport
import com.cisco.quadroid.transport.VideoFrame
import com.cisco.quadroid.transport.VideoJitterBufferCallback
import java.util.concurrent.ConcurrentHashMap

class JitterBufferMonitor(
    private val moqTransport: MoqTransport
) {
    private val tag = "JitterBufferMonitor"

    private val trackKeyToFullName = mutableMapOf<String, String>()
    private val videoFrameListeners = ConcurrentHashMap<String, (ByteArray, Long) -> Unit>()

    private val monitoringHandler = Handler(Looper.getMainLooper())
    private var isMonitoring = false

    // Monitoring runnable for periodic jitter buffer statistics
    private val monitoringRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring) return

            val moqNative = moqTransport as? MoqNative
            if (moqNative != null) {
                // Monitor video jitter buffers
                videoFrameListeners.keys.forEach { trackKey ->
                    val fullTrackName = trackKeyToFullName[trackKey]
                    if (fullTrackName != null) {
                        val stats = moqNative.nativeGetVideoJitterBufferStats(fullTrackName)
                        if (stats != null) {
                            // Log warning if frame drop rate is significant
                            if (stats.framesDropped > 0) {
                                Log.w(tag, "Video Buffer [$trackKey]: recv=${stats.framesReceived}, " +
                                    "out=${stats.framesOutput}, drop=${stats.framesDropped} " +
                                    "(${String.format("%.1f", stats.dropRatePercent)}%), " +
                                    "avgLat=${String.format("%.1f", stats.avgLatencyMs)}ms, " +
                                    "maxLat=${String.format("%.1f", stats.maxLatencyMs)}ms")
                            }
                            // Log info periodically even if no drops
                            else if (stats.framesOutput > 0) {
                                Log.i(tag, "Video Buffer [$trackKey]: recv=${stats.framesReceived}, " +
                                    "out=${stats.framesOutput}, " +
                                    "avgLat=${String.format("%.1f", stats.avgLatencyMs)}ms")
                            }
                        }
                    }
                }

                // Monitor audio jitter buffers
                trackKeyToFullName.forEach { (trackKey, fullTrackName) ->
                    if (fullTrackName.contains("audio")) {
                        val stats = moqNative.nativeGetAudioJitterBufferStats(fullTrackName)
                        if (stats != null) {
                            // Log warning if packet drop rate is significant
                            if (stats.packetsDropped > 0) {
                                Log.w(tag, "Audio Buffer [$trackKey]: recv=${stats.packetsReceived}, " +
                                    "out=${stats.packetsOutput}, drop=${stats.packetsDropped} " +
                                    "(${String.format("%.1f", stats.dropRatePercent)}%), " +
                                    "avgLat=${String.format("%.1f", stats.avgLatencyMs)}ms, " +
                                    "maxLat=${String.format("%.1f", stats.maxLatencyMs)}ms")
                            }
                            // Log info periodically even if no drops
                            else if (stats.packetsOutput > 0) {
                                Log.i(tag, "Audio Buffer [$trackKey]: recv=${stats.packetsReceived}, " +
                                    "out=${stats.packetsOutput}, " +
                                    "avgLat=${String.format("%.1f", stats.avgLatencyMs)}ms")
                            }
                        }
                    }
                }
            }

            // Schedule next check in 5 seconds
            if (isMonitoring) {
                monitoringHandler.postDelayed(this, 5000)
            }
        }
    }

    fun registerTrack(trackKey: String, fullTrackName: String) {
        trackKeyToFullName[trackKey] = fullTrackName
    }

    fun addVideoFrameListener(trackKey: String, listener: (ByteArray, Long) -> Unit) {
        Log.d(tag, "addVideoFrameListener for $trackKey")
        videoFrameListeners[trackKey] = listener

        // Create native jitter buffer for this track using full track name
        val moqNative = moqTransport as? MoqNative
        if (moqNative != null) {
            // Get full track name for jitter buffer lookup in C++
            val fullTrackName = trackKeyToFullName[trackKey]
            if (fullTrackName == null) {
                Log.w(tag, "No full track name found for trackKey $trackKey, jitter buffer not created")
                return
            }

            val callback = object : VideoJitterBufferCallback {
                override fun onFramesReady(trackName: String, frames: Array<VideoFrame>) {
                    val frameListener = videoFrameListeners[trackKey] ?: return

                    for (frame in frames) {
                        // Pass frames to decoder
                        // shouldRender flag is informational - we pass all frames to maintain decoder state
                        frameListener(frame.data, frame.ptsUs)
                    }

                    // Log periodically for monitoring
                    if (frames.isNotEmpty() && frames[0].objectId % 100 == 0L) {
                        Log.v(tag, "[$trackKey] Delivered ${frames.size} frames from jitter buffer")
                    }
                }
            }

            // Use full track name (not hashed key) for C++ jitter buffer lookup
            moqNative.nativeCreateVideoJitterBuffer(fullTrackName, callback)
            Log.i(tag, "Created jitter buffer for track $trackKey (fullName: $fullTrackName)")
        } else {
            Log.w(tag, "MoqTransport is not MoqNative, jitter buffer not available")
        }
    }

    fun removeVideoFrameListener(trackKey: String) {
        Log.d(tag, "removeVideoFrameListener for $trackKey")

        // Destroy native jitter buffer using full track name
        val moqNative = moqTransport as? MoqNative
        val fullTrackName = trackKeyToFullName[trackKey]
        if (fullTrackName != null) {
            moqNative?.nativeDestroyVideoJitterBuffer(fullTrackName)
            // Don't remove mapping - keep it for when renderer is recreated
            // The mapping will be cleared when the session ends in clear()
        }

        videoFrameListeners.remove(trackKey)
    }

    fun startMonitoring() {
        if (!isMonitoring) {
            isMonitoring = true
            monitoringHandler.postDelayed(monitoringRunnable, 5000) // Start after 5 seconds
            Log.d(tag, "Started jitter buffer monitoring")
        }
    }

    fun stopMonitoring() {
        if (isMonitoring) {
            isMonitoring = false
            monitoringHandler.removeCallbacks(monitoringRunnable)
            Log.d(tag, "Stopped jitter buffer monitoring")
        }
    }

    fun clear() {
        // Destroy all jitter buffers (video and audio)
        val moqNative = moqTransport as? MoqNative
        if (moqNative != null) {
            // Destroy video jitter buffers
            videoFrameListeners.keys.forEach { trackKey ->
                val fullTrackName = trackKeyToFullName[trackKey]
                if (fullTrackName != null) {
                    moqNative.nativeDestroyVideoJitterBuffer(fullTrackName)
                    Log.d(tag, "Destroyed video jitter buffer for track $trackKey (fullName: $fullTrackName)")
                }
            }

            // Destroy audio jitter buffers
            trackKeyToFullName.forEach { (trackKey, fullTrackName) ->
                if (fullTrackName.contains("audio")) {
                    moqNative.nativeDestroyAudioJitterBuffer(fullTrackName)
                    Log.d(tag, "Destroyed audio jitter buffer for track $trackKey (fullName: $fullTrackName)")
                }
            }
        }

        videoFrameListeners.clear()
        trackKeyToFullName.clear()
    }
}
