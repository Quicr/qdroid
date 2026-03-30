#include <jni.h>
#include <string>
#include <memory>
#include <vector>
#include <android/log.h>
#include <quicr/client.h>

#define LOG_TAG "MoqJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global JVM reference to call back into Kotlin
JavaVM* g_jvm = nullptr;

struct MoqContext {
    std::unique_ptr<quicr::Client> client;
    jobject object_callback_ref = nullptr;
    jobject namespace_callback_ref = nullptr;

    ~MoqContext() {
        JNIEnv* env = nullptr;
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            if (object_callback_ref) env->DeleteGlobalRef(object_callback_ref);
            if (namespace_callback_ref) env->DeleteGlobalRef(namespace_callback_ref);
        }
    }
};

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeConnect(JNIEnv *env, jobject thiz, jstring url) {
    const char *native_url = env->GetStringUTFChars(url, nullptr);
    LOGI("Connecting to MoQ relay: %s", native_url);

    auto context = new MoqContext();

    // libquicr boring2 branch uses ClientConfig and Client
    quicr::ClientConfig config;
    context->client = std::make_unique<quicr::Client>(config);

    env->ReleaseStringUTFChars(url, native_url);
    return reinterpret_cast<jlong>(context);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeDisconnect(JNIEnv *env, jobject thiz, jlong ptr) {
    auto context = reinterpret_cast<MoqContext *>(ptr);
    delete context;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativePublish(JNIEnv *env, jobject thiz, jlong ptr,
                                                        jstring track_name, jint priority,
                                                        jboolean use_datagram) {
    auto context = reinterpret_cast<MoqContext *>(ptr);
    const char *name = env->GetStringUTFChars(track_name, nullptr);

    // Update with correct boring2 branch API if needed
    // context->client->publish(name);

    env->ReleaseStringUTFChars(track_name, name);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeSendObject(JNIEnv *env, jobject thiz, jlong ptr,
                                                           jstring track_name, jlong group_id,
                                                           jlong object_id, jobject payload,
                                                           jint payload_size, jint priority,
                                                           jlong delivery_timeout_ms,
                                                           jboolean use_datagram) {
    auto context = reinterpret_cast<MoqContext *>(ptr);
    const char *name = env->GetStringUTFChars(track_name, nullptr);

    uint8_t* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(payload));
    if (!data) {
        LOGE("Failed to get direct buffer address for zero-copy");
        env->ReleaseStringUTFChars(track_name, name);
        return;
    }

    // Zero-copy transmission: pass raw pointer directly to libquicr
    // context->client->send_object(name, group_id, object_id, data, payload_size, priority, delivery_timeout_ms, use_datagram);

    env->ReleaseStringUTFChars(track_name, name);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeSubscribe(JNIEnv *env, jobject thiz, jlong ptr,
                                                          jstring track_name, jobject callback) {
    auto context = reinterpret_cast<MoqContext *>(ptr);
    const char *name = env->GetStringUTFChars(track_name, nullptr);

    if (context->object_callback_ref) env->DeleteGlobalRef(context->object_callback_ref);
    context->object_callback_ref = env->NewGlobalRef(callback);

    // context->client->subscribe(name);

    env->ReleaseStringUTFChars(track_name, name);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeSubscribeNamespace(JNIEnv *env, jobject thiz,
                                                                   jlong ptr,
                                                                   jstring namespace_prefix,
                                                                   jobject callback) {
    auto context = reinterpret_cast<MoqContext *>(ptr);
    const char *prefix = env->GetStringUTFChars(namespace_prefix, nullptr);

    if (context->namespace_callback_ref) env->DeleteGlobalRef(context->namespace_callback_ref);
    context->namespace_callback_ref = env->NewGlobalRef(callback);

    // context->client->subscribe_namespace(prefix);

    env->ReleaseStringUTFChars(namespace_prefix, prefix);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeSetNamespaceDefaultBehavior(JNIEnv *env,
                                                                            jobject thiz, jlong ptr,
                                                                            jboolean accept_all) {
    auto context = reinterpret_cast<MoqContext *>(ptr);
    // context->client->set_namespace_default_behavior(accept_all);
}
