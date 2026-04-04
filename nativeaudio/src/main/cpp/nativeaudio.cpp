#include <jni.h>
#include <string>
#include <vector>
#include <map>
#include <mutex>
#include <memory>
#include <thread>
#include <android/log.h>
#include <oboe/Oboe.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#define TAG "NativeAudio"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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
        JNIEnv* env = getEnv();
        if (env) env->DeleteGlobalRef(callback);
        if (encoder) {
            AMediaCodec_stop(encoder);
            AMediaCodec_delete(encoder);
        }
    }

    bool start() {
        // Setup Encoder
        encoder = AMediaCodec_createEncoderByType("audio/mp4a-latm");
        AMediaFormat* format = AMediaFormat_new();
        AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, 48000);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, 1);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, 64000);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_AAC_PROFILE, 2); // AAC-LC
        AMediaCodec_configure(encoder, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        AMediaFormat_delete(format);
        AMediaCodec_start(encoder);

        // Setup Oboe Stream
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
            return true;
        }
        return false;
    }

    DataCallbackResult onAudioReady(AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        if (!encoder) return DataCallbackResult::Stop;

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
        while (outIdx >= 0) {
            uint8_t* outBuf = AMediaCodec_getOutputBuffer(encoder, outIdx, nullptr);
            if (outBuf && info.size > 0) {
                JNIEnv* env = getEnv();
                if (env) {
                    jobject byteBuffer = env->NewDirectByteBuffer(outBuf + info.offset, info.size);
                    env->CallVoidMethod(callback, onAudioEncodedId, byteBuffer, info.size, info.presentationTimeUs);
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
    jobject callback;
    jmethodID onAudioEncodedId;
    std::shared_ptr<AudioStream> stream;
    AMediaCodec* encoder = nullptr;

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
        if (decoder) {
            AMediaCodec_stop(decoder);
            AMediaCodec_delete(decoder);
        }
        if (stream) {
            stream->stop();
            stream->close();
        }
    }

    bool start() {
        // Setup Decoder
        decoder = AMediaCodec_createDecoderByType("audio/mp4a-latm");
        AMediaFormat* format = AMediaFormat_new();
        AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, 48000);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, 1);
        AMediaCodec_configure(decoder, format, nullptr, nullptr, 0);
        AMediaFormat_delete(format);
        AMediaCodec_start(decoder);

        // Setup Oboe Stream
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
            return true;
        }
        return false;
    }

    void feed(uint8_t* data, size_t size) {
        if (!decoder) return;
        ssize_t bufIdx = AMediaCodec_dequeueInputBuffer(decoder, 2000);
        if (bufIdx >= 0) {
            size_t bufSize;
            uint8_t* buf = AMediaCodec_getInputBuffer(decoder, bufIdx, &bufSize);
            if (size <= bufSize) {
                memcpy(buf, data, size);
                AMediaCodec_queueInputBuffer(decoder, bufIdx, 0, size, 0, 0);
            }
        }
    }

    DataCallbackResult onAudioReady(AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        memset(audioData, 0, numFrames * sizeof(int16_t));
        if (!decoder) return DataCallbackResult::Continue;

        AMediaCodecBufferInfo info;
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(decoder, &info, 0);
        if (outIdx >= 0) {
            uint8_t* outBuf = AMediaCodec_getOutputBuffer(decoder, outIdx, nullptr);
            if (outBuf && info.size > 0) {
                int32_t bytesToCopy = std::min((int32_t)info.size, (int32_t)(numFrames * sizeof(int16_t)));
                memcpy(audioData, outBuf + info.offset, bytesToCopy);
            }
            AMediaCodec_releaseOutputBuffer(decoder, outIdx, false);
        } else if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            // Log format change if needed
        }

        return DataCallbackResult::Continue;
    }

private:
    std::string trackKey;
    std::shared_ptr<AudioStream> stream;
    AMediaCodec* decoder = nullptr;
};

std::unique_ptr<AudioCapture> gCapture;
std::map<std::string, std::unique_ptr<AudioPlayback>> gPlaybacks;
std::mutex gPlaybackMutex;

extern "C" JNIEXPORT jstring JNICALL
Java_com_cisco_nativeaudio_NativeAudioLib_stringFromJNI(JNIEnv* env, jobject) {
    return env->NewStringUTF("Oboe Native Audio Ready");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cisco_nativeaudio_NativeAudioLib_startCapture(JNIEnv* env, jobject, jobject callback) {
    if (gCapture) return JNI_FALSE;
    gCapture = std::make_unique<AudioCapture>(env, callback);
    return gCapture->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_cisco_nativeaudio_NativeAudioLib_stopCapture(JNIEnv*, jobject) {
    gCapture.reset();
}

extern "C" JNIEXPORT jboolean JNICALL
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

extern "C" JNIEXPORT void JNICALL
Java_com_cisco_nativeaudio_NativeAudioLib_stopPlayback(JNIEnv* env, jobject, jstring trackKey) {
    const char* keyChars = env->GetStringUTFChars(trackKey, nullptr);
    std::string key(keyChars);
    env->ReleaseStringUTFChars(trackKey, keyChars);

    std::lock_guard<std::mutex> lock(gPlaybackMutex);
    gPlaybacks.erase(key);
}

extern "C" JNIEXPORT void JNICALL
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
