package com.cisco.quadroid.mediacodec.audio

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import android.util.Log

/**
 * Voice Activity Detector wrapper for android-vad WebRTC VAD library
 *
 * Provides low-latency, real-time voice activity detection for audio streams.
 * Uses WebRTC's GMM-based VAD algorithm optimized for streaming applications.
 */
class VoiceActivityDetector(
    private val sampleRate: Int = 48000,
    private val frameSize: Int = 960
) : AutoCloseable {

    private val TAG = "VoiceActivityDetector"

    private val vad: VadWebRTC

    // Configuration
    private val vadSampleRate: SampleRate
    private val vadFrameSize: FrameSize

    // Continuous speech detection parameters
    private val speechDurationMs = 50    // Min speech to trigger detection
    private val silenceDurationMs = 300  // Min silence to stop detection

    init {
        // Map audio config to VAD config
        vadSampleRate = when (sampleRate) {
            8000 -> SampleRate.SAMPLE_RATE_8K
            16000 -> SampleRate.SAMPLE_RATE_16K
            32000 -> SampleRate.SAMPLE_RATE_32K
            48000 -> SampleRate.SAMPLE_RATE_48K
            else -> {
                Log.w(TAG, "Unsupported sample rate $sampleRate, using 16K")
                SampleRate.SAMPLE_RATE_16K
            }
        }

        vadFrameSize = when {
            sampleRate == 8000 && frameSize == 80 -> FrameSize.FRAME_SIZE_80
            sampleRate == 8000 && frameSize == 160 -> FrameSize.FRAME_SIZE_160
            sampleRate == 8000 && frameSize == 240 -> FrameSize.FRAME_SIZE_240
            sampleRate == 16000 && frameSize == 160 -> FrameSize.FRAME_SIZE_160
            sampleRate == 16000 && frameSize == 320 -> FrameSize.FRAME_SIZE_320
            sampleRate == 16000 && frameSize == 480 -> FrameSize.FRAME_SIZE_480
            sampleRate == 32000 && frameSize == 320 -> FrameSize.FRAME_SIZE_320
            sampleRate == 32000 && frameSize == 640 -> FrameSize.FRAME_SIZE_640
            sampleRate == 32000 && frameSize == 960 -> FrameSize.FRAME_SIZE_960
            sampleRate == 48000 && frameSize == 480 -> FrameSize.FRAME_SIZE_480
            sampleRate == 48000 && frameSize == 960 -> FrameSize.FRAME_SIZE_960
            sampleRate == 48000 && frameSize == 1440 -> FrameSize.FRAME_SIZE_1440
            else -> {
                Log.w(TAG, "Unsupported frame size $frameSize for rate $sampleRate, using 960")
                FrameSize.FRAME_SIZE_960
            }
        }

        // Initialize WebRTC VAD with aggressive mode for maximum silence filtering
        vad = VadWebRTC(
            sampleRate = vadSampleRate,
            frameSize = vadFrameSize,
            mode = Mode.VERY_AGGRESSIVE,
            speechDurationMs = speechDurationMs,
            silenceDurationMs = silenceDurationMs
        )

        Log.i(TAG, "VAD initialized: rate=$vadSampleRate, frame=$vadFrameSize, mode=VERY_AGGRESSIVE")
    }

    /**
     * Check if audio frame contains speech
     * @param audioData PCM 16-bit audio data
     * @return true if speech detected, false if silence
     */
    fun isSpeech(audioData: ShortArray): Boolean {
        return try {
            vad.isSpeech(audioData)
        } catch (e: Exception) {
            Log.e(TAG, "VAD processing error", e)
            true // On error, assume speech to avoid dropping frames
        }
    }

    /**
     * Check if audio frame contains speech (ByteArray overload)
     * @param audioData PCM 16-bit audio data as bytes
     * @return true if speech detected, false if silence
     */
    fun isSpeech(audioData: ByteArray): Boolean {
        return try {
            vad.isSpeech(audioData)
        } catch (e: Exception) {
            Log.e(TAG, "VAD processing error", e)
            true // On error, assume speech to avoid dropping frames
        }
    }

    override fun close() {
        vad.close()
        Log.i(TAG, "VAD closed")
    }
}
