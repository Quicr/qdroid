// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid.mediacodec.encoder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.cisco.quadroid.mediacodec.model.EncoderState
import com.cisco.quadroid.mediacodec.model.VideoEncoderConfig
import com.cisco.quadroid.transport.MoqNative
import com.cisco.quadroid.transport.MoqTransport

class VideoEncoderManager(
    private val moqTransport: MoqTransport,
    private val context: Context
) {
    private val tag = "VideoEncoderManager"

    private val videoMimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    private val iFrameInterval = 2

    private val encoders = mutableListOf<EncoderState>()
    private var activeEncoderIndex = 0
    private var consecutiveGoodFrames = 0
    private var lastEncoderError = 0L

    private val encoderThread = HandlerThread("VideoEncoderManager").apply { start() }
    private val encoderHandler = Handler(encoderThread.looper)

    var onQualityChanged: ((VideoEncoderConfig) -> Unit)? = null

    val activeConfig: VideoEncoderConfig?
        get() = encoders.getOrNull(activeEncoderIndex)?.config

    val activeInputSurface: Surface?
        get() = encoders.getOrNull(activeEncoderIndex)?.inputSurface

    fun setupEncoders(configs: List<VideoEncoderConfig>) {
        // Create one encoder per video quality level
        configs.forEach { config ->
            try {
                val format = MediaFormat.createVideoFormat(
                    videoMimeType,
                    config.width,
                    config.height
                ).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, config.framerate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
                    // Prepend SPS/PPS to keyframes for easier decoding by late-joiners
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                    }
                }

                // Create native framer for this track
                val nativeTransport = moqTransport as MoqNative
                nativeTransport.createVideoFramer(config.trackNameUrl)

                val encoder = MediaCodec.createEncoderByType(videoMimeType).apply {
                    setCallback(object : MediaCodec.Callback() {
                        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                        override fun onOutputBufferAvailable(
                            codec: MediaCodec,
                            index: Int,
                            info: MediaCodec.BufferInfo
                        ) {
                            try {
                                getOutputBuffer(index)?.let { buffer ->
                                    if (info.size > 0) {
                                        // Process frame with native framer
                                        val isKeyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                        nativeTransport.processVideoFrame(
                                            config.trackNameUrl,
                                            buffer,
                                            info.size,
                                            isKeyframe,
                                            info.presentationTimeUs
                                        )

                                        // Track encoding success for adaptive quality (only for active encoder)
                                        val encoderIndex = encoders.indexOfFirst {
                                            it.config.trackNameUrl == config.trackNameUrl
                                        }
                                        if (encoderIndex == activeEncoderIndex) {
                                            consecutiveGoodFrames++

                                            // Upgrade after 5 consecutive good frames (same rule as remote)
                                            if (consecutiveGoodFrames >= 5) {
                                                tryUpgradeQuality()
                                            }
                                        }
                                    }
                                }
                                releaseOutputBuffer(index, false)
                            } catch (e: IllegalStateException) {
                                Log.e(tag, "Encoder output error for ${config.trackNameUrl}", e)

                                // Track error and trigger downgrade if this is the active encoder
                                val encoderIndex = encoders.indexOfFirst {
                                    it.config.trackNameUrl == config.trackNameUrl
                                }
                                if (encoderIndex == activeEncoderIndex) {
                                    lastEncoderError = System.currentTimeMillis()
                                    consecutiveGoodFrames = 0
                                    downgradeQuality()
                                }
                            }
                        }

                        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                            Log.e(tag, "Encoder Error for ${config.trackNameUrl}", e)

                            // Downgrade on encoder error if this is the active encoder
                            val encoderIndex = encoders.indexOfFirst {
                                it.config.trackNameUrl == config.trackNameUrl
                            }
                            if (encoderIndex == activeEncoderIndex) {
                                lastEncoderError = System.currentTimeMillis()
                                consecutiveGoodFrames = 0
                                downgradeQuality()
                            }
                        }

                        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                            Log.d(tag, "Encoder format changed for ${config.trackNameUrl}: $format")
                        }
                    }, encoderHandler)
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }

                val inputSurface = encoder.createInputSurface()
                encoder.start()

                val encoderState = EncoderState(
                    encoder = encoder,
                    inputSurface = inputSurface,
                    config = config
                )

                encoders.add(encoderState)
                Log.i(
                    tag,
                    "Created encoder for ${config.trackNameUrl}: ${config.width}x${config.height}, ${config.bitrate}bps"
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to setup video encoder for ${config.trackNameUrl}", e)
            }
        }

        Log.i(tag, "Setup ${encoders.size} video encoders")

        // Initialize with highest quality encoder (index 0)
        if (encoders.isNotEmpty()) {
            activeEncoderIndex = 0
            encoders[0].isActive = true
            val highestQualityConfig = encoders[0].config

            Log.i(
                tag,
                "Initialized with highest quality: ${highestQualityConfig.width}x${highestQualityConfig.height}, priority=${highestQualityConfig.priority}"
            )

            // Notify initial quality
            onQualityChanged?.invoke(highestQualityConfig)
        }
    }

    fun switchQuality(newIndex: Int) {
        if (newIndex < 0 || newIndex >= encoders.size) {
            Log.w(tag, "Invalid encoder index: $newIndex")
            return
        }

        if (newIndex == activeEncoderIndex) {
            return // Already at this quality
        }

        val oldIndex = activeEncoderIndex
        val oldConfig = encoders[oldIndex].config
        val newConfig = encoders[newIndex].config

        activeEncoderIndex = newIndex
        consecutiveGoodFrames = 0

        // Mark encoder as active
        encoders.forEach { it.isActive = false }
        encoders[newIndex].isActive = true

        Log.i(
            tag,
            "Quality switch: ${oldConfig.priority} (${oldConfig.width}x${oldConfig.height}) -> " +
                    "${newConfig.priority} (${newConfig.width}x${newConfig.height})"
        )

        // Notify quality change
        onQualityChanged?.invoke(newConfig)
    }

    private fun tryUpgradeQuality() {
        // Find next higher quality (lower priority number)
        val currentPriority = encoders[activeEncoderIndex].config.priority
        val higherQualityIndex = encoders.indexOfFirst { it.config.priority < currentPriority }

        if (higherQualityIndex >= 0) {
            Log.d(tag, "Upgrading quality after $consecutiveGoodFrames consecutive good frames")
            switchQuality(higherQualityIndex)
        }
    }

    private fun downgradeQuality() {
        // Find next lower quality (higher priority number)
        val currentPriority = encoders[activeEncoderIndex].config.priority
        val lowerQualityIndex = encoders.indexOfFirst { it.config.priority > currentPriority }

        if (lowerQualityIndex >= 0) {
            Log.w(tag, "Downgrading quality due to encoding issues")
            switchQuality(lowerQualityIndex)
        } else {
            Log.w(tag, "Already at lowest quality, cannot downgrade further")
        }
    }

    fun cleanup() {
        encoderHandler.removeCallbacksAndMessages(null)

        // Stop and release all encoders
        val nativeTransport = moqTransport as? MoqNative
        encoders.forEach { encoderState ->
            try {
                encoderState.encoder.stop()
                encoderState.encoder.release()
                encoderState.inputSurface.release()

                // Destroy native framer
                nativeTransport?.destroyVideoFramer(encoderState.config.trackNameUrl)

                Log.d(tag, "Released encoder for ${encoderState.config.trackNameUrl}")
            } catch (e: Exception) {
                Log.e(tag, "Error releasing encoder for ${encoderState.config.trackNameUrl}", e)
            }
        }
        encoders.clear()

        // Reset encoder quality tracking
        activeEncoderIndex = 0
        consecutiveGoodFrames = 0
        lastEncoderError = 0L

        Log.i(tag, "Encoder cleanup complete")
    }

    fun shutdown() {
        cleanup()
        encoderThread.quitSafely()
    }
}
