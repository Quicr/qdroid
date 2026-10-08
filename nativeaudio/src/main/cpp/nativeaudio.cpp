// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#include <jni.h>
#include <string>
#include <vector>
#include <map>
#include <mutex>
#include <memory>
#include <thread>
#include <atomic>
#include <android/log.h>
#include <oboe/Oboe.h>
#include <opus.h>
#include "lockfree_queue.h"
#include "vad_gate.h"
// libfvad's fvad.h has no extern "C" guard of its own, so wrap it to keep the
// C symbols unmangled when consumed from this C++ translation unit.
extern "C" {
#include "fvad.h"
}

#define TAG "NativeAudio"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

using namespace oboe;

// Opus configuration matching ptt audio_engine.cpp
constexpr int SAMPLE_RATE = 48000;
constexpr int CHANNELS = 1;
constexpr int FRAME_SIZE = 960; //320; // 20ms at 16kHz
constexpr int MAX_PACKET_SIZE = 4000;
constexpr int BITRATE = 32000; // 32 kbps for good VoIP quality (was 16000)
constexpr int PCM_BUFFER_SIZE = FRAME_SIZE * CHANNELS * sizeof(int16_t);

// Lockless queue configuration
constexpr uint32_t QUEUE_CAPACITY = 256; // Must be power of 2

// Voice Activity Detection (libfvad) configuration.
// Replaces the former Kotlin android-vad path: VAD now runs natively on raw PCM
// right before Opus encoding, so silent frames are never encoded or sent across
// the JNI boundary. One 48 kHz / 960-sample frame is exactly 20 ms, which is a
// valid libfvad frame length, so no resampling or re-framing is needed.
constexpr int VAD_MODE = 3;              // 3 = "very aggressive" (matches prior VERY_AGGRESSIVE)
constexpr int VAD_FRAME_MS = (FRAME_SIZE * 1000) / SAMPLE_RATE; // 20 ms per frame
constexpr int VAD_SPEECH_TRIGGER_MS = 50;   // min continuous speech to open the gate
constexpr int VAD_SILENCE_HANGOVER_MS = 300; // min continuous silence to close the gate

// Thin wrapper over libfvad that adds the same speech/silence hysteresis the
// android-vad "continuous speech" mode provided. All state is owned by the
// single encode thread, so no locking is required here.
class NativeVad {
public:
    NativeVad() {
        vad_ = fvad_new();
        if (vad_) {
            fvad_set_sample_rate(vad_, SAMPLE_RATE);
            fvad_set_mode(vad_, VAD_MODE);
        } else {
            LOGE("NativeVad - fvad_new() failed; VAD will fail open (transmit all)");
        }
    }

    ~NativeVad() {
        if (vad_) {
            fvad_free(vad_);
            vad_ = nullptr;
        }
    }

    // Returns true if this frame should be transmitted (speech active, including
    // the trailing hangover). Fails open (true) if the detector is unavailable or
    // the frame is rejected, so audio is never silently lost on error.
    bool shouldTransmit(const int16_t* frame, size_t length) {
        if (!vad_) return true;

        int raw = fvad_process(vad_, frame, length);
        if (raw < 0) return true; // invalid frame length — shouldn't happen at 960

        // The speech/silence hysteresis lives in VadGate (see vad_gate.h) so it can
        // be unit-tested on the host independently of libfvad.
        return gate_.update(raw == 1);
    }

    // Clear hysteresis/history so a freshly re-enabled gate starts clean.
    void reset() {
        if (vad_) fvad_reset(vad_);
        gate_.reset();
    }

private:
    Fvad* vad_ = nullptr;
    VadGate gate_{VAD_FRAME_MS, VAD_SPEECH_TRIGGER_MS, VAD_SILENCE_HANGOVER_MS};
};

class AudioCapture : public AudioStreamCallback {
public:
    AudioCapture(JNIEnv* env, jobject callback, bool initialVadEnabled) {
        env->GetJavaVM(&jvm);
        this->callback = env->NewGlobalRef(callback);
        jclass clazz = env->GetObjectClass(callback);
        onAudioEncodedId = env->GetMethodID(clazz, "onAudioEncoded", "(Ljava/nio/ByteBuffer;IJI)V");
        vadEnabled.store(initialVadEnabled);
    }

    // Toggle VAD at runtime (called from the JNI thread). The encode thread
    // observes the change and resets the detector on a disabled->enabled edge.
    void setVadEnabled(bool enabled) {
        vadEnabled.store(enabled);
    }

    ~AudioCapture() {
        isRunning = false;
        // Stop the stream first to ensure onAudioReady is no longer called
        if (stream) {
            stream->stop();
            stream->close();
        }

        if (encodeThread.joinable()) {
            encodeThread.join();
        }

        // Safe to read stats here: the encode thread (sole writer) has joined.
        if (framesProcessed > 0) {
            double dropRate = (framesDropped * 100.0) / framesProcessed;
            LOGI("VAD session stats: dropped=%lld/%lld (%.1f%%)",
                 (long long)framesDropped, (long long)framesProcessed, dropRate);
        }

        if (encoder) {
            opus_encoder_destroy(encoder);
            encoder = nullptr;
        }

        JNIEnv* env = getEnv();
        if (env && callback) {
            env->DeleteGlobalRef(callback);
            callback = nullptr;
        }
    }

    bool start() {
        LOGD("AudioCapture::start() - Creating Opus encoder");

        // Create Opus encoder
        int error;
        encoder = opus_encoder_create(SAMPLE_RATE, CHANNELS, OPUS_APPLICATION_VOIP, &error);
        if (error != OPUS_OK || !encoder) {
            LOGE("AudioCapture::start() - Failed to create Opus encoder, error=%d", error);
            return false;
        }

        // Configure Opus encoder
        opus_encoder_ctl(encoder, OPUS_SET_BITRATE(BITRATE));
        opus_encoder_ctl(encoder, OPUS_SET_VBR(1)); // Enable variable bitrate
        opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(10)); // Max quality

        LOGD("AudioCapture::start() - Opus encoder created successfully");

        // Create Oboe audio stream
        AudioStreamBuilder builder;
        Result result = builder.setDirection(Direction::Input)
                ->setUsage(oboe::Usage::Media)
                ->setContentType(oboe::ContentType::Speech)
               ->setPerformanceMode(PerformanceMode::LowLatency)
               ->setSharingMode(SharingMode::Shared)  // Allow device switching (e.g., USB-C headset)
               ->setFormat(AudioFormat::I16)
               ->setChannelCount(CHANNELS)
               ->setSampleRate(SAMPLE_RATE)
               ->setFramesPerDataCallback(FRAME_SIZE)
               ->setCallback(this)
               ->openStream(stream);

        if (result != Result::OK) {
            LOGE("AudioCapture::start() - Failed to open Oboe stream, result=%d", (int)result);
            return false;
        }

        LOGD("AudioCapture::start() - Oboe stream opened successfully, requesting start");
        result = stream->requestStart();
        if (result != Result::OK) {
            LOGE("AudioCapture::start() - Failed to start Oboe stream, result=%d", (int)result);
            return false;
        }

        isRunning = true;
        encodeThread = std::thread(&AudioCapture::encodeLoop, this);

        LOGD("AudioCapture::start() - Oboe stream started successfully");
        return true;
    }

    DataCallbackResult onAudioReady(AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        if (!isRunning || !encoder) return DataCallbackResult::Stop;

        // Push PCM data to encoding queue
        std::vector<int16_t> pcmData(static_cast<int16_t*>(audioData),
                                     static_cast<int16_t*>(audioData) + numFrames);

        if (!pcmQueue.push(pcmData)) {
            LOGW("AudioCapture::onAudioReady() - PCM queue full, dropping frame");
        }

        return DataCallbackResult::Continue;
    }

private:
    JavaVM* jvm;
    jobject callback = nullptr;
    jmethodID onAudioEncodedId;
    std::shared_ptr<AudioStream> stream;
    OpusEncoder* encoder = nullptr;
    std::atomic<bool> isRunning{false};
    std::atomic<bool> vadEnabled{true};
    std::thread encodeThread;

    // VAD state and stats — owned exclusively by the encode thread.
    NativeVad vad;
    int64_t framesProcessed = 0;
    int64_t framesDropped = 0;

    LockFreeQueue<std::vector<int16_t>, QUEUE_CAPACITY> pcmQueue;

    void encodeLoop() {
        std::vector<int16_t> pcmData;
        std::vector<uint8_t> opusData(MAX_PACKET_SIZE);
        int frameCount = 0;
        bool prevVadEnabled = vadEnabled.load(std::memory_order_relaxed);

        while (isRunning) {
            if (pcmQueue.pop(pcmData)) {
                if (pcmData.size() != FRAME_SIZE) {
                    LOGW("AudioCapture::encodeLoop() - Unexpected frame size: %zu", pcmData.size());
                    continue;
                }

                framesProcessed++;

                // Voice Activity Detection on raw PCM, before Opus encoding.
                // Silent frames are dropped here, so they are never encoded nor
                // pushed across JNI — removing the former Kotlin-on-encoded-bytes
                // hop and the encode cost of silence.
                bool curVadEnabled = vadEnabled.load(std::memory_order_relaxed);
                if (curVadEnabled && !prevVadEnabled) {
                    vad.reset(); // re-enabled: start the hysteresis clean
                }
                prevVadEnabled = curVadEnabled;

                if (curVadEnabled && !vad.shouldTransmit(pcmData.data(), FRAME_SIZE)) {
                    framesDropped++;
                    if (framesProcessed % 100 == 0) {
                        double dropRate = (framesDropped * 100.0) / framesProcessed;
                        LOGD("VAD stats: dropped=%lld/%lld (%.1f%%)",
                             (long long)framesDropped, (long long)framesProcessed, dropRate);
                    }
                    continue; // silence — skip encode + callback
                }

                // Encode with Opus
                int encodedBytes = opus_encode(encoder, pcmData.data(), FRAME_SIZE,
                                              opusData.data(), MAX_PACKET_SIZE);

                if (encodedBytes < 0) {
                    LOGE("AudioCapture::encodeLoop() - Opus encode error: %d", encodedBytes);
                    continue;
                }

                if (++frameCount % 100 == 0) {
                    LOGD("AudioCapture::encodeLoop() - Encoded frame %d, size=%d bytes",
                         frameCount, encodedBytes);
                }

                // Send to Java callback
                JNIEnv* env = getEnv();
                if (env && callback) {
                    jobject byteBuffer = env->NewDirectByteBuffer(opusData.data(), encodedBytes);
                    auto timestamp = std::chrono::duration_cast<std::chrono::microseconds>(
                        std::chrono::system_clock::now().time_since_epoch()).count();
                    env->CallVoidMethod(callback, onAudioEncodedId, byteBuffer,
                                      (jint)encodedBytes, (jlong)timestamp, (jint)0);
                    env->DeleteLocalRef(byteBuffer);
                }
            } else {
                // Queue empty, sleep briefly
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
        }
    }

    JNIEnv* getEnv() {
        JNIEnv* env;
        if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            jvm->AttachCurrentThread(&env, nullptr);
        }
        return env;
    }
};

class AudioPlayback : public AudioStreamCallback {
public:
    AudioPlayback(std::string trackKey) : trackKey(trackKey) {}

    ~AudioPlayback() {
        isRunning = false;
        // Stop the stream first to ensure onAudioReady is no longer called
        if (stream) {
            stream->stop();
            stream->close();
        }

        if (decodeThread.joinable()) {
            decodeThread.join();
        }

        if (decoder) {
            opus_decoder_destroy(decoder);
            decoder = nullptr;
        }
    }

    bool start() {
        LOGD("AudioPlayback::start() - Creating Opus decoder for %s", trackKey.c_str());

        // Create Opus decoder
        int error;
        decoder = opus_decoder_create(SAMPLE_RATE, CHANNELS, &error);
        if (error != OPUS_OK || !decoder) {
            LOGE("AudioPlayback::start() - Failed to create Opus decoder, error=%d", error);
            return false;
        }

        LOGD("AudioPlayback::start() - Opus decoder created successfully");

        // Create Oboe audio stream
        AudioStreamBuilder builder;
        Result result = builder.setDirection(Direction::Output)
                ->setUsage(oboe::Usage::Media)
                ->setContentType(oboe::ContentType::Speech)
               ->setPerformanceMode(PerformanceMode::LowLatency)
               ->setSharingMode(SharingMode::Shared)  // Allow device switching (e.g., USB-C headset)
               ->setFormat(AudioFormat::I16)
               ->setChannelCount(CHANNELS)
               ->setSampleRate(SAMPLE_RATE)
               ->setCallback(this)
               ->openStream(stream);

        if (result != Result::OK || !stream) {
            LOGE("AudioPlayback::start() - Failed to open Oboe stream");
            return false;
        }

        result = stream->requestStart();
        if (result != Result::OK) {
            LOGE("AudioPlayback::start() - Failed to start Oboe stream");
            return false;
        }

        isRunning = true;
        decodeThread = std::thread(&AudioPlayback::decodeLoop, this);

        LOGD("AudioPlayback::start() - Started playback for %s", trackKey.c_str());
        return true;
    }

    void feed(uint8_t* data, size_t size) {
        if (!decoder || !isRunning) return;

        std::vector<uint8_t> opusData(data, data + size);
        if (!opusQueue.push(opusData)) {
            LOGW("AudioPlayback::feed() - Opus queue full for %s", trackKey.c_str());
        }
    }

    DataCallbackResult onAudioReady(AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        if (!isRunning) return DataCallbackResult::Stop;

        int16_t* output = static_cast<int16_t*>(audioData);
        size_t bytesNeeded = numFrames * sizeof(int16_t);

        std::lock_guard<std::mutex> lock(bufferMutex);
        if (pcmBuffer.size() >= bytesNeeded) {
            memcpy(output, pcmBuffer.data(), bytesNeeded);
            pcmBuffer.erase(pcmBuffer.begin(), pcmBuffer.begin() + bytesNeeded);
        } else {
            // Underrun - fill with available data + silence
            size_t available = pcmBuffer.size();
            if (available > 0) {
                memcpy(output, pcmBuffer.data(), available);
                pcmBuffer.clear();
            }
            memset((uint8_t*)output + available, 0, bytesNeeded - available);
        }

        return DataCallbackResult::Continue;
    }

private:
    std::string trackKey;
    std::shared_ptr<AudioStream> stream;
    OpusDecoder* decoder = nullptr;
    std::vector<uint8_t> pcmBuffer;
    std::mutex bufferMutex;
    std::thread decodeThread;
    std::atomic<bool> isRunning{false};

    LockFreeQueue<std::vector<uint8_t>, QUEUE_CAPACITY> opusQueue;

    void decodeLoop() {
        std::vector<uint8_t> opusData;
        std::vector<int16_t> pcmData(FRAME_SIZE);

        while (isRunning) {
            if (opusQueue.pop(opusData)) {
                // Decode with Opus
                int decodedSamples = opus_decode(decoder, opusData.data(), opusData.size(),
                                                pcmData.data(), FRAME_SIZE, 0);

                if (decodedSamples < 0) {
                    LOGE("AudioPlayback::decodeLoop() - Opus decode error: %d", decodedSamples);
                    continue;
                }

                // Add decoded PCM to playback buffer
                std::lock_guard<std::mutex> lock(bufferMutex);
                size_t bytesToAdd = decodedSamples * sizeof(int16_t);
                if (pcmBuffer.size() < SAMPLE_RATE * sizeof(int16_t)) { // Cap at 1s
                    pcmBuffer.insert(pcmBuffer.end(),
                                   (uint8_t*)pcmData.data(),
                                   (uint8_t*)pcmData.data() + bytesToAdd);
                }
            } else {
                // Queue empty, sleep briefly
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
        }
    }
};

std::unique_ptr<AudioCapture> gCapture;
std::map<std::string, std::unique_ptr<AudioPlayback>> gPlaybacks;
std::mutex gPlaybackMutex;

extern "C" {
    JNIEXPORT jstring JNICALL
    Java_com_cisco_nativeaudio_NativeAudioLib_stringFromJNI(JNIEnv* env, jobject) {
        return env->NewStringUTF("Oboe Native Audio with Opus Ready");
    }

    JNIEXPORT jboolean JNICALL
    Java_com_cisco_nativeaudio_NativeAudioLib_startCapture(JNIEnv* env, jobject, jobject callback, jboolean vadEnabled) {
        std::lock_guard<std::mutex> lock(gPlaybackMutex);
        if (gCapture) return JNI_TRUE;
        gCapture = std::make_unique<AudioCapture>(env, callback, vadEnabled == JNI_TRUE);
        return gCapture->start() ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT void JNICALL
    Java_com_cisco_nativeaudio_NativeAudioLib_stopCapture(JNIEnv*, jobject) {
        std::lock_guard<std::mutex> lock(gPlaybackMutex);
        gCapture.reset();
    }

    JNIEXPORT void JNICALL
    Java_com_cisco_nativeaudio_NativeAudioLib_setVadEnabled(JNIEnv*, jobject, jboolean enabled) {
        std::lock_guard<std::mutex> lock(gPlaybackMutex);
        if (gCapture) {
            gCapture->setVadEnabled(enabled == JNI_TRUE);
        }
    }

    JNIEXPORT jboolean JNICALL
    Java_com_cisco_nativeaudio_NativeAudioLib_startPlayback(JNIEnv* env, jobject, jstring trackKey) {
        const char* keyChars = env->GetStringUTFChars(trackKey, nullptr);
        std::string key(keyChars);
        env->ReleaseStringUTFChars(trackKey, keyChars);

        std::lock_guard<std::mutex> lock(gPlaybackMutex);
        if (gPlaybacks.count(key)) return JNI_TRUE;
        auto playback = std::make_unique<AudioPlayback>(key);
        if (playback->start()) {
            gPlaybacks[key] = std::move(playback);
            return JNI_TRUE;
        }
        return JNI_FALSE;
    }

    JNIEXPORT void JNICALL
    Java_com_cisco_nativeaudio_NativeAudioLib_stopPlayback(JNIEnv* env, jobject, jstring trackKey) {
        const char* keyChars = env->GetStringUTFChars(trackKey, nullptr);
        std::string key(keyChars);
        env->ReleaseStringUTFChars(trackKey, keyChars);

        std::lock_guard<std::mutex> lock(gPlaybackMutex);
        gPlaybacks.erase(key);
    }

    JNIEXPORT void JNICALL
    Java_com_cisco_nativeaudio_NativeAudioLib_feedDecoder(JNIEnv* env, jobject, jstring trackKey, jobject payload, jint size) {
        const char* keyChars = env->GetStringUTFChars(trackKey, nullptr);
        std::string key(keyChars);
        env->ReleaseStringUTFChars(trackKey, keyChars);

        uint8_t* data = (uint8_t*)env->GetDirectBufferAddress(payload);
        if (!data) return;

        std::lock_guard<std::mutex> lock(gPlaybackMutex);
        if (gPlaybacks.count(key)) {
            gPlaybacks[key]->feed(data, size);
        }
    }
}
