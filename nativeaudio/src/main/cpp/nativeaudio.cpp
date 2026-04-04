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
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#define TAG "NativeAudio"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

using namespace oboe;

class AudioCapture : public AudioStreamCallback {
public:
    AudioCapture(JNIEnv* env, jobject callback) {
        env->GetJavaVM(&jvm);
        this->callback = env->NewGlobalRef(callback);
        jclass clazz = env->GetObjectClass(callback);
        onAudioEncodedId = env->GetMethodID(clazz, "onAudioEncoded", "(Ljava/nio/ByteBuffer;IJ)V");
    }

    ~AudioCapture() {
        isRunning = false;
        // Stop the stream first to ensure onAudioReady is no longer called
        if (stream) {
            stream->stop();
            stream->close();
        }

        if (encoder) {
            AMediaCodec_stop(encoder);
            AMediaCodec_delete(encoder);
        }

        JNIEnv* env = getEnv();
        if (env && callback) {
            env->DeleteGlobalRef(callback);
            callback = nullptr;
        }
    }

    bool start() {
        encoder = AMediaCodec_createEncoderByType("audio/mp4a-latm");
        AMediaFormat* format = AMediaFormat_new();
        AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, 48000);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, 1);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, 64000);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_AAC_PROFILE, 2);
        AMediaCodec_configure(encoder, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        AMediaFormat_delete(format);
        AMediaCodec_start(encoder);

        AudioStreamBuilder builder;
        builder.setDirection(Direction::Input)
               ->setPerformanceMode(PerformanceMode::LowLatency)
               ->setSharingMode(SharingMode::Exclusive)
               ->setFormat(AudioFormat::I16)
               ->setChannelCount(1)
               ->setSampleRate(48000)
               ->setCallback(this)
               ->openStream(stream);

        if (stream) {
            stream->requestStart();
            isRunning = true;
            return true;
        }
        return false;
    }

    DataCallbackResult onAudioReady(AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        if (!isRunning || !encoder) return DataCallbackResult::Stop;

        // Feed Encoder
        ssize_t bufIdx = AMediaCodec_dequeueInputBuffer(encoder, 0);
        if (bufIdx >= 0) {
            size_t bufSize;
            uint8_t* buf = AMediaCodec_getInputBuffer(encoder, bufIdx, &bufSize);
            int32_t bytesToCopy = numFrames * sizeof(int16_t);
            if (bytesToCopy <= (int32_t)bufSize) {
                memcpy(buf, audioData, bytesToCopy);
                AMediaCodec_queueInputBuffer(encoder, bufIdx, 0, bytesToCopy,
                                            std::chrono::duration_cast<std::chrono::microseconds>(
                                            std::chrono::system_clock::now().time_since_epoch()).count(), 0);
            }
        }

        // Drain Encoder
        AMediaCodecBufferInfo info;
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(encoder, &info, 0);
        while (isRunning && outIdx >= 0) {
            uint8_t* outBuf = AMediaCodec_getOutputBuffer(encoder, outIdx, nullptr);
            if (outBuf && info.size > 0 && callback) {
                JNIEnv* env = getEnv();
                if (env) {
                    jobject byteBuffer = env->NewDirectByteBuffer(outBuf + info.offset, info.size);
                    env->CallVoidMethod(callback, onAudioEncodedId, byteBuffer, (jint)info.size, (jlong)info.presentationTimeUs);
                    env->DeleteLocalRef(byteBuffer);
                }
            }
            AMediaCodec_releaseOutputBuffer(encoder, outIdx, false);
            outIdx = AMediaCodec_dequeueOutputBuffer(encoder, &info, 0);
        }
        return DataCallbackResult::Continue;
    }

private:
    JavaVM* jvm;
    jobject callback = nullptr;
    jmethodID onAudioEncodedId;
    std::shared_ptr<AudioStream> stream;
    AMediaCodec* encoder = nullptr;
    std::atomic<bool> isRunning{false};

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

        if (drainThread.joinable()) drainThread.join();

        if (decoder) {
            AMediaCodec_stop(decoder);
            AMediaCodec_delete(decoder);
        }
    }

    bool start() {
        decoder = AMediaCodec_createDecoderByType("audio/mp4a-latm");
        AMediaFormat* format = AMediaFormat_new();
        AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, 48000);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, 1);
        AMediaCodec_configure(decoder, format, nullptr, nullptr, 0);
        AMediaFormat_delete(format);
        AMediaCodec_start(decoder);

        AudioStreamBuilder builder;
        builder.setDirection(Direction::Output)
               ->setPerformanceMode(PerformanceMode::LowLatency)
               ->setSharingMode(SharingMode::Exclusive)
               ->setFormat(AudioFormat::I16)
               ->setChannelCount(1)
               ->setSampleRate(48000)
               ->setCallback(this)
               ->openStream(stream);

        if (stream) {
            stream->requestStart();
            isRunning = true;
            drainThread = std::thread(&AudioPlayback::drainLoop, this);
            return true;
        }
        return false;
    }

    void feed(uint8_t* data, size_t size) {
        if (!decoder || !isRunning) return;
        ssize_t bufIdx = AMediaCodec_dequeueInputBuffer(decoder, 5000);
        if (bufIdx >= 0) {
            size_t bufSize;
            uint8_t* buf = AMediaCodec_getInputBuffer(decoder, bufIdx, &bufSize);
            if (size <= bufSize) {
                memcpy(buf, data, size);
                AMediaCodec_queueInputBuffer(decoder, bufIdx, 0, size, 0, 0);
            }
        } else {
            LOGW("Audio playback input overflow for %s", trackKey.c_str());
        }
    }

    DataCallbackResult onAudioReady(AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        if (!isRunning) return DataCallbackResult::Stop;

        size_t bytesNeeded = numFrames * sizeof(int16_t);
        std::lock_guard<std::mutex> lock(bufferMutex);
        if (pcmBuffer.size() >= bytesNeeded) {
            memcpy(audioData, pcmBuffer.data(), bytesNeeded);
            pcmBuffer.erase(pcmBuffer.begin(), pcmBuffer.begin() + bytesNeeded);
        } else {
            size_t available = pcmBuffer.size();
            if (available > 0) {
                memcpy(audioData, pcmBuffer.data(), available);
                pcmBuffer.clear();
            }
            memset((uint8_t*)audioData + available, 0, bytesNeeded - available);
        }
        return DataCallbackResult::Continue;
    }

private:
    std::string trackKey;
    std::shared_ptr<AudioStream> stream;
    AMediaCodec* decoder = nullptr;
    std::vector<uint8_t> pcmBuffer;
    std::mutex bufferMutex;
    std::thread drainThread;
    std::atomic<bool> isRunning{false};

    void drainLoop() {
        while (isRunning) {
            if (!decoder) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }
            AMediaCodecBufferInfo info;
            ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(decoder, &info, 1000);
            if (outIdx >= 0) {
                uint8_t* outBuf = AMediaCodec_getOutputBuffer(decoder, outIdx, nullptr);
                if (outBuf && info.size > 0) {
                    std::lock_guard<std::mutex> lock(bufferMutex);
                    if (pcmBuffer.size() < 48000 * sizeof(int16_t)) { // Cap at 1s
                        pcmBuffer.insert(pcmBuffer.end(), outBuf + info.offset, outBuf + info.offset + info.size);
                    }
                }
                AMediaCodec_releaseOutputBuffer(decoder, outIdx, false);
            } else if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
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
        return env->NewStringUTF("Oboe Native Audio Ready");
    }

    JNIEXPORT jboolean JNICALL
    Java_com_cisco_nativeaudio_NativeAudioLib_startCapture(JNIEnv* env, jobject, jobject callback) {
        std::lock_guard<std::mutex> lock(gPlaybackMutex);
        if (gCapture) return JNI_TRUE;
        gCapture = std::make_unique<AudioCapture>(env, callback);
        return gCapture->start() ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT void JNICALL
    Java_com_cisco_nativeaudio_NativeAudioLib_stopCapture(JNIEnv*, jobject) {
        std::lock_guard<std::mutex> lock(gPlaybackMutex);
        gCapture.reset();
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
